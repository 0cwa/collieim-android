/*
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.jobs

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import im.molly.unifiedpush.MollySocketRepository
import im.molly.unifiedpush.UnifiedPushDistributor
import im.molly.unifiedpush.UnifiedPushNotificationBuilder
import im.molly.unifiedpush.model.LinkStatus
import im.molly.unifiedpush.control.ControlState
import im.molly.unifiedpush.control.ControlStateRead
import im.molly.unifiedpush.control.ControlFrame
import im.molly.unifiedpush.control.ReceiverControlState
import im.molly.unifiedpush.control.Target
import im.molly.unifiedpush.enrollment.RelayDisclosure
import im.molly.unifiedpush.enrollment.PairingRegistrationGate
import im.molly.unifiedpush.enrollment.PairingV1
import im.molly.unifiedpush.control.ControlUpdate
import im.molly.unifiedpush.control.EndpointReconciler
import im.molly.unifiedpush.model.ConnectionResult
import im.molly.unifiedpush.model.MollySocketDevice
import im.molly.unifiedpush.model.RegistrationStatus
import im.molly.unifiedpush.lifecycle.UnifiedPushLifecycle
import im.molly.unifiedpush.lifecycle.UnifiedPushAccountTransition
import im.molly.unifiedpush.receiver.LockedPushState
import im.molly.unifiedpush.receiver.UnifiedPushReceiver
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkConstructor
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.models.ServiceId
import org.signal.core.util.logging.Log
import org.signal.storage.keyvalue.KeyValueDataSet
import org.signal.storage.keyvalue.KeyValuePersistenceException
import org.signal.storage.keyvalue.KeyValuePersistentStorage
import org.signal.storage.keyvalue.KeyValueStore
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.jobmanager.Job
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.keyvalue.UnifiedPushValues
import org.thoughtcrime.securesms.linkdevice.Device
import org.thoughtcrime.securesms.linkdevice.LinkDeviceRepository
import org.thoughtcrime.securesms.notifications.NotificationChannels
import org.thoughtcrime.securesms.service.KeyCachingService
import org.thoughtcrime.securesms.testutil.MockSignalStoreRule
import org.thoughtcrime.securesms.testutil.SnapshotKeyValuePersistentStorage
import org.thoughtcrime.securesms.testutil.SystemOutLogger
import org.whispersystems.signalservice.api.SignalServiceMessageSender
import org.whispersystems.signalservice.api.messages.SendMessageResult
import org.unifiedpush.android.connector.data.PublicKeySet
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage
import org.unifiedpush.android.connector.UnifiedPush
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class UnifiedPushRefreshJobTest {

  @get:Rule
  val signalStore = MockSignalStoreRule()

  private val unifiedPush = mockk<UnifiedPushValues>(relaxUnitFun = true)
  private val device = MollySocketDevice(deviceId = 4, password = "retained-relay-credential")
  private var storedDevice: MollySocketDevice? = device
  private var storedStatus = RegistrationStatus.UNKNOWN
  private var transitionMarker = false
  private var pendingCleanup: UnifiedPushValues.RelayCleanup? = null
  private val retainedUpdate = ControlUpdate(5, "https://push.example.invalid/old", "p", "a", byteArrayOf(1), 1)
  private var controlState = ControlState(key = "retained-control-key", activeEpoch = 4, highestEpoch = 5, importSequence = 7, update = retainedUpdate)

  @Test
  fun `pending v4 state without a live confirmation does not block primary refresh`() {
    val context = ApplicationProvider.getApplicationContext<Application>()
    val pending = ReceiverControlState(
      receiverId = "33333333-3333-4333-8333-333333333333",
      importId = "44444444-4444-4444-8444-444444444444",
      accountId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
      bridgeDeviceId = 2,
      instance = "second-instance",
      hostLabel = "Secondary phone",
      controlKey = "ICEiIyQlJicoKSorLC0uLzAxMjM0NTY3ODk6Ozw9Pj8",
      epoch = 1,
      target = Target(
        "https://push.example.invalid/pending",
        "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4",
        "EBESExQVFhcYGRobHB0eHw",
      ),
    )
    every { unifiedPush.endpoint } returns null
    every { unifiedPush.endpointInstance } returns null
    every { unifiedPush.pairingEndpointBinding } returns null
    every { unifiedPush.v4PendingReceiverState } returns pending.serialize()

    assertNotNull("the fixture must be a readable pending v4 receiver", ReceiverControlState.parse(unifiedPush.v4PendingReceiverState))
    val refresh = newRefreshJob().apply { setContext(context) }
    assertFalse(synchronized(UnifiedPushLifecycle.monitor) { refresh.legacyRelayBlockedLocked() })
  }

  @Test
  fun `isolated distributor replacement protects candidate until matching confirmation promotes it`() {
    assertIsolatedReplacementProtection()
  }

  @Test
  fun `isolated candidate with a different selected distributor is retired and releases refresh`() {
    assertIsolatedReplacementProtection(wrongDistributor = true)
  }

  @Test
  fun `isolated fresh candidate protects refresh without an active route`() {
    assertIsolatedReplacementProtection(fresh = true)
  }

  @Test
  fun `persisted isolated replacement protects refresh before receiver restoration`() {
    assertIsolatedReplacementProtection(afterRestart = true)
  }

  @Test
  fun `persisted candidate releases refresh after retained distributor changes`() {
    assertIsolatedReplacementProtection(wrongDistributor = true, afterRestart = true)
  }

  @Test
  fun `expired isolated confirmation releases refresh and retires only the candidate`() {
    assertIsolatedReplacementProtection(expired = true)
  }

  private fun assertIsolatedReplacementProtection(
    wrongDistributor: Boolean = false,
    fresh: Boolean = false,
    expired: Boolean = false,
    afterRestart: Boolean = false,
  ) {
    val context = ApplicationProvider.getApplicationContext<Application>()
    val accountId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    val oldDistributor = "org.example.distributor.a"
    val candidateDistributor = "org.example.distributor.b"
    val oldEndpoint = if (fresh) null else "https://push.example.invalid/retained"
    val candidateEndpoint = "https://push.example.invalid/candidate"
    val candidateP256dh = "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4"
    val candidateAuth = "EBESExQVFhcYGRobHB0eHw"
    val offer = PairingV1.Offer(
      "33333333-3333-4333-8333-333333333333", "add_receiver", System.currentTimeMillis() / 1000 + 600,
      "Secondary phone", null, "vapid-key", "server-public-key",
    )
    val account = UnifiedPushAccountTransition.AccountContext(
      accountId, 1, null, oldEndpoint, oldEndpoint?.let { "old-p256dh" }, oldEndpoint?.let { "old-auth" }, "default",
    )
    val scope = RelayDisclosure.Snapshot(
      1, account, RelayDisclosure.Configuration(
        manual = false, url = "https://molly.example.invalid", vapid = "vapid-key",
        deliveryMethod = org.thoughtcrime.securesms.keyvalue.SettingsValues.NotificationDeliveryMethod.UNIFIEDPUSH,
        distributor = oldDistributor,
      ), emptyList(),
    )
    lateinit var receiver: ReceiverControlState
    lateinit var binding: PairingRegistrationGate.Binding
    var savedDistributor = oldDistributor
    var ackDistributor = oldDistributor
    var endpoint = oldEndpoint
    var endpointP256dh: String? = oldEndpoint?.let { "old-p256dh" }
    var endpointAuth: String? = oldEndpoint?.let { "old-auth" }
    var endpointInstance = "default"
    var activeState: String? = null
    var pendingState: String? = null
    var pairingBinding: UnifiedPushValues.PairingEndpointBinding? = null
    every { unifiedPush.endpoint } answers { endpoint }
    every { unifiedPush.endpointP256dh } answers { endpointP256dh }
    every { unifiedPush.endpointAuth } answers { endpointAuth }
    every { unifiedPush.endpointInstance } answers { endpointInstance }
    every { unifiedPush.v4ReceiverState } answers { activeState }
    every { unifiedPush.v4PendingReceiverState } answers { pendingState }
    every { unifiedPush.pairingEndpointBinding } answers { pairingBinding }
    every { unifiedPush.controlStateRead } returns ControlStateRead.Current(controlState)
    every { unifiedPush.commitV4ReceiverStates(any(), any()) } answers {
      activeState = firstArg()
      pendingState = secondArg()
    }
    every { unifiedPush.setPairingEndpointRegistration(any(), any(), any(), any()) } answers {
      endpoint = firstArg()
      endpointP256dh = secondArg()
      endpointAuth = thirdArg()
      pairingBinding = arg(3)
      endpointInstance = checkNotNull(pairingBinding?.instance)
    }

    mockkObject(RelayDisclosure, LockedPushState)
    mockkStatic(UnifiedPush::class, LockedPushState::class)
    try {
      every { RelayDisclosure.capture(context) } returns scope
      every { RelayDisclosure.pairingScopeDigest(scope) } returns "pre-consent-scope"
      every { RelayDisclosure.matchesDuringTransition(scope) } returns true
      every { LockedPushState.peekPairingConfirmation(context) } answers { LockedPushState.PairingConfirmation(
        offer.sessionId, binding.instance, candidateDistributor, offer.vapidPublicKey, offer.providerOrigin,
        if (expired) System.currentTimeMillis() / 1000 - 1 else offer.expiresAt,
        offer.hostLabel, receiver.importId, "", receiver.epoch, "pre-consent-scope", receiverId = receiver.receiverId,
      ) }
      every { LockedPushState.clearPairingConfirmation(context, any()) } returns Unit
      every { LockedPushState.setEncryptedEnrollment(context, any()) } returns Unit
      every { UnifiedPush.getDistributors(context) } returns listOf(oldDistributor, candidateDistributor)
      every { UnifiedPush.getSavedDistributor(context) } answers { savedDistributor }
      every { UnifiedPush.getAckDistributor(context) } answers { ackDistributor }

      binding = checkNotNull(PairingRegistrationGate.begin(
        candidateDistributor, offer, accountId, 1, lifecycleGeneration = scope.generation,
        previousEndpoint = oldEndpoint, previousP256dh = oldEndpoint?.let { "old-p256dh" }, previousAuth = oldEndpoint?.let { "old-auth" },
        previousInstance = "default", previousDistributorId = oldDistributor, disclosure = scope,
      ) { })
      receiver = ReceiverControlState(
        receiverId = "55555555-5555-4555-8555-555555555555",
        importId = "66666666-6666-4666-8666-666666666666",
        accountId = accountId,
        bridgeDeviceId = 2,
        instance = binding.instance,
        hostLabel = "Secondary phone",
        controlKey = "ICEiIyQlJicoKSorLC0uLzAxMjM0NTY3ODk6Ozw9Pj8",
        epoch = 1,
        target = Target(candidateEndpoint, candidateP256dh, candidateAuth),
      )
      pendingState = receiver.serialize()
      val claimed = PairingRegistrationGate.Endpoint(candidateEndpoint, candidateP256dh, candidateAuth, binding.instance)
      assertNotNull(PairingRegistrationGate.claim(binding.instance, oldDistributor, oldDistributor, accountId, 1, claimed))
      assertTrue(PairingRegistrationGate.bindImport(binding.instance, PairingRegistrationGate.ImportBinding(
        receiver.importId, "", receiver.epoch, candidateEndpoint, candidateP256dh, candidateAuth,
        accountId, binding.deviceId, binding.instance, receiver.receiverId,
      )))
      val gateCandidate = checkNotNull(PairingRegistrationGate.current())
      assertEquals(receiver.instance, gateCandidate.binding.instance)
      assertEquals(receiver.target, Target(checkNotNull(gateCandidate.claimedEndpoint).url,
        checkNotNull(gateCandidate.claimedEndpoint).p256dh!!, checkNotNull(gateCandidate.claimedEndpoint).auth!!))
      assertEquals(receiver.importId, gateCandidate.importBinding?.requestId)
      assertEquals(binding.deviceId, gateCandidate.importBinding?.deviceId)
      assertEquals(oldDistributor, gateCandidate.binding.disclosure?.configuration?.distributor)
      val confirmation = checkNotNull(LockedPushState.peekPairingConfirmation(context))
      assertEquals(candidateDistributor, confirmation.distributor)
      assertEquals(offer.sessionId, confirmation.sessionId)
      assertEquals(receiver.importId, confirmation.importId)
      assertEquals(receiver.instance, confirmation.instance)
      assertEquals(receiver.receiverId, confirmation.receiverId)
      assertEquals(receiver.epoch, confirmation.epoch)
      assertEquals(!expired, System.currentTimeMillis() / 1000 < confirmation.expiresAt)
      assertEquals(gateCandidate.binding.distributorId, confirmation.distributor)
      assertEquals(gateCandidate.binding.previousDistributorId, scope.configuration.distributor)
      assertTrue(gateCandidate.binding.matchesDistributorSelection(savedDistributor, ackDistributor))
      assertEquals(gateCandidate.binding.sessionId, confirmation.sessionId)
      assertTrue(RelayDisclosure.matchesDuringTransition(scope))
      assertEquals("pre-consent-scope", RelayDisclosure.pairingScopeDigest(gateCandidate.binding.disclosure!!))
      assertTrue(candidateDistributor in UnifiedPush.getDistributors(context))
      assertEquals(receiver.receiverId, gateCandidate.importBinding?.receiverId)
      assertEquals(receiver.importId, gateCandidate.importBinding?.requestId)
      assertEquals(receiver.epoch, gateCandidate.importBinding?.epoch)
      assertEquals(receiver.instance, gateCandidate.importBinding?.instance)
      assertEquals(accountId, gateCandidate.importBinding?.accountId)
      assertEquals(candidateEndpoint, gateCandidate.importBinding?.endpoint)
      assertEquals(candidateP256dh, gateCandidate.importBinding?.p256dh)
      assertEquals(candidateAuth, gateCandidate.importBinding?.auth)

      val refresh = newRefreshJob()
      if (afterRestart) PairingRegistrationGate.cancel(binding.instance)
      if (wrongDistributor) {
        savedDistributor = "org.example.distributor.wrong"
        ackDistributor = "org.example.distributor.wrong"
      }
      val blocked = synchronized(UnifiedPushLifecycle.monitor) { refresh.legacyRelayBlockedLocked() }
      assertEquals(
        if (wrongDistributor) "a different selected distributor retires the stale candidate and releases refresh"
        else if (expired) "an expired confirmation retires the candidate and releases refresh"
        else "the pending candidate blocks queued legacy refresh while A remains selected",
        !wrongDistributor && !expired,
        blocked,
      )
      if (wrongDistributor || expired) {
        verify(exactly = 1) { unifiedPush.commitV4ReceiverStates(null, null) }
        assertNull(pendingState)
        assertEquals("the previously selected route remains intact", oldEndpoint, endpoint)
        assertNull(PairingRegistrationGate.current())
        return
      }
      assertEquals("the refresh guard preserves the pending import", receiver.serialize(), pendingState)
      assertEquals("the previously selected route remains active before confirmation", oldEndpoint, endpoint)
      assertEquals(oldDistributor, savedDistributor)
      assertEquals(oldDistributor, ackDistributor)
      if (afterRestart) return

      // Model the exact authenticated enrollment_imported commit: install B, confirm this identity, and retire its gate.
      val origin = checkNotNull(im.molly.unifiedpush.enrollment.PairingV1.canonicalOrigin(candidateEndpoint))
      unifiedPush.setPairingEndpointRegistration(candidateEndpoint, candidateP256dh, candidateAuth,
        UnifiedPushValues.PairingEndpointBinding(
          binding.instance, candidateDistributor, offer.vapidPublicKey, offer.providerOrigin, origin, offer.sessionId,
          offer.expiresAt, accountId, 1, offer.hostLabel, receiver.importId,
        ),
      )
      unifiedPush.commitV4ReceiverStates(receiver.copy(confirmed = true).serialize(), null)
      savedDistributor = candidateDistributor
      ackDistributor = candidateDistributor
      assertNotNull(PairingRegistrationGate.promote(binding.instance))

      assertEquals(candidateEndpoint, endpoint)
      assertEquals(candidateDistributor, pairingBinding?.distributorId)
      assertEquals(receiver.receiverId, ReceiverControlState.parse(activeState)?.receiverId)
      assertTrue(checkNotNull(ReceiverControlState.parse(activeState)).confirmed)
      assertNull(pendingState)
      assertNull(PairingRegistrationGate.current())
    } finally {
      PairingRegistrationGate.current()?.binding?.let { PairingRegistrationGate.cancel(it.instance) }
      unmockkStatic(UnifiedPush::class, LockedPushState::class)
      unmockkObject(RelayDisclosure, LockedPushState)
    }
  }

  @Test
  fun `expired pending v4 confirmation retires only the candidate and allows primary refresh`() {
    val context = ApplicationProvider.getApplicationContext<Application>()
    val active = ReceiverControlState(
      receiverId = "33333333-3333-4333-8333-333333333333",
      importId = "44444444-4444-4444-8444-444444444444",
      accountId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
      bridgeDeviceId = 2,
      instance = "default",
      hostLabel = "Primary phone",
      controlKey = "ICEiIyQlJicoKSorLC0uLzAxMjM0NTY3ODk6Ozw9Pj8",
      epoch = 1,
      target = Target("https://push.example.invalid/active", "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4", "EBESExQVFhcYGRobHB0eHw"),
      confirmed = true,
    )
    val pending = active.copy(
      receiverId = "55555555-5555-4555-8555-555555555555",
      importId = "66666666-6666-4666-8666-666666666666",
      instance = "pairing-v1-expired",
      target = Target("https://push.example.invalid/candidate", active.target.p256dh, active.target.auth),
      confirmed = false,
    )
    every { unifiedPush.v4ReceiverState } returns active.serialize()
    every { unifiedPush.v4PendingReceiverState } returns pending.serialize()
    var endpoint = pending.target.endpoint
    var endpointP256dh: String? = pending.target.p256dh
    var endpointAuth: String? = pending.target.auth
    var endpointInstance = pending.instance
    var receiverState: String? = active.serialize()
    var pendingState: String? = pending.serialize()
    var pairingBinding: UnifiedPushValues.PairingEndpointBinding? = UnifiedPushValues.PairingEndpointBinding(
      instance = pending.instance, distributorId = "org.example.distributor", vapidPublicKey = "vapid-key",
      providerOrigin = null, endpointOrigin = "https://push.example.invalid", sessionId = "expired-session",
      expiresAt = System.currentTimeMillis() / 1000 - 1, accountId = pending.accountId, deviceId = 1, hostLabel = pending.hostLabel,
      importId = pending.importId,
    )
    every { unifiedPush.endpoint } answers { endpoint }
    every { unifiedPush.endpointP256dh } answers { endpointP256dh }
    every { unifiedPush.endpointAuth } answers { endpointAuth }
    every { unifiedPush.endpointInstance } answers { endpointInstance }
    every { unifiedPush.v4ReceiverState } answers { receiverState }
    every { unifiedPush.v4PendingReceiverState } answers { pendingState }
    every { unifiedPush.pairingEndpointBinding } answers { pairingBinding }
    every { unifiedPush.commitV4ReceiverStates(any(), any()) } answers {
      receiverState = firstArg()
      pendingState = secondArg()
    }
    every { unifiedPush.setEndpointRegistration(any(), any(), any(), any()) } answers {
      endpoint = firstArg()
      endpointP256dh = secondArg()
      endpointAuth = thirdArg()
      endpointInstance = arg(3)
      pairingBinding = null
    }
    every { unifiedPush.controlStateRead } returns ControlStateRead.Current(controlState)

    val account = UnifiedPushAccountTransition.AccountContext(
      accountId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", localDeviceId = 1, pushDevice = null,
      endpoint = "https://push.example.invalid/active", endpointP256dh = active.target.p256dh,
      endpointAuth = active.target.auth, instance = "default",
    )
    val scope = RelayDisclosure.Snapshot(
      0, account, RelayDisclosure.Configuration(
        manual = false, url = "https://molly.example.invalid", vapid = "vapid-key",
        deliveryMethod = org.thoughtcrime.securesms.keyvalue.SettingsValues.NotificationDeliveryMethod.UNIFIEDPUSH,
        distributor = "org.example.distributor",
      ), emptyList(),
    )
    val confirmation = LockedPushState.PairingConfirmation(
      sessionId = "expired-session", instance = pending.instance, distributor = "org.example.distributor",
      vapid = "vapid-key", providerOrigin = null, expiresAt = System.currentTimeMillis() / 1000 - 1,
      hostLabel = pending.hostLabel, importId = pending.importId, keyId = "", epoch = pending.epoch,
      scopeDigest = "expired-scope", receiverId = pending.receiverId,
    )
    mockkObject(RelayDisclosure, LockedPushState)
    mockkStatic(UnifiedPush::class, LockedPushState::class)
    try {
      every { RelayDisclosure.capture(context) } returns scope
      every { RelayDisclosure.pairingScopeDigest(scope) } answers { callOriginal() }
      every { LockedPushState.peekPairingConfirmation(context) } returns confirmation
      every { LockedPushState.clearPairingConfirmation(context, confirmation.sessionId) } returns Unit
      every { LockedPushState.setEncryptedEnrollment(context, any()) } returns Unit
      every { UnifiedPush.getSavedDistributor(context) } returns "org.example.distributor"
      every { UnifiedPush.getAckDistributor(context) } returns "org.example.distributor"
      every { UnifiedPush.getDistributors(context) } returns listOf("org.example.distributor")

      val refresh = newRefreshJob().apply { setContext(context) }
      assertFalse(synchronized(UnifiedPushLifecycle.monitor) { refresh.legacyRelayBlockedLocked() })

      verify(exactly = 1) { unifiedPush.commitV4ReceiverStates(active.serialize(), null) }
      verify(exactly = 1) {
        unifiedPush.setEndpointRegistration(active.target.endpoint, active.target.p256dh, active.target.auth, active.instance)
      }
      assertEquals(active.serialize(), receiverState)
      assertNull(pendingState)
      assertEquals("the active endpoint is restored", active.target.endpoint, endpoint)
      assertEquals("the pairing instance is retired", active.instance, endpointInstance)
      assertNull(pairingBinding)
      verify(exactly = 1) { LockedPushState.clearPairingConfirmation(context, confirmation.sessionId) }
    } finally {
      unmockkStatic(UnifiedPush::class, LockedPushState::class)
      unmockkObject(RelayDisclosure, LockedPushState)
    }
  }

  @Before
  fun setUp() {
    Log.initialize(SystemOutLogger())
    UnifiedPushLifecycle.resetForTests()
    mockkObject(LinkDeviceRepository)

    every { SignalStore.unifiedpush } returns unifiedPush
    every { signalStore.account.isRegistered } returns true
    every { signalStore.account.isLinkedDevice } returns false
    val aci = mockk<ServiceId.ACI>()
    every { aci.toString() } returns "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    every { signalStore.account.aci } returns aci
    every { signalStore.account.hasAciIdentityKey() } returns true
    every { signalStore.account.servicePassword } returns "SYNTHETIC-SERVICE-PASSWORD"
    every { signalStore.account.deviceId } returns 1
    every { unifiedPush.enabled } returns false
    every { unifiedPush.airGapped } returns false
    every { unifiedPush.registrationStatus } answers { storedStatus }
    every { unifiedPush.registrationStatus = any() } answers { storedStatus = firstArg() }
    every { unifiedPush.endpoint } returns "https://push.example.invalid/endpoint"
    every { unifiedPush.endpointP256dh } returns "SYNTHETIC-P256DH"
    every { unifiedPush.endpointAuth } returns "SYNTHETIC-AUTH"
    every { unifiedPush.endpointInstance } returns "fixture-instance"
    every { unifiedPush.pairingEndpointBinding } returns null
    every { unifiedPush.vapidKeySynced } returns true
    every { unifiedPush.v4ReceiverState } returns null
    every { unifiedPush.v4PendingReceiverState } returns null
    every { unifiedPush.mollySocketUrl } returns "https://molly.example.invalid"
    every { unifiedPush.device } answers { storedDevice }
    every { unifiedPush.device = any() } answers { storedDevice = firstArg() }
    every { unifiedPush.pendingRelayCleanup } answers { pendingCleanup }
    every { unifiedPush.setRelayCleanup(any(), any()) } answers {
      pendingCleanup = if (secondArg<Boolean>()) firstArg() else null
    }
    every { unifiedPush.isProvisioningPending(any()) } returns false
    every { unifiedPush.commitProvisionedDevice(any()) } answers { storedDevice = firstArg() }
    every { unifiedPush.commitClearedDevice() } answers { storedDevice = null }
    every { unifiedPush.controlState } answers { controlState }
    every { unifiedPush.hasIncompleteAccountTransition } answers { transitionMarker }
    every { unifiedPush.setIncompleteAccountTransition(any()) } answers { transitionMarker = firstArg() }
    every { unifiedPush.mutateControl(any(), any()) } answers {
      controlState = secondArg<(ControlState) -> ControlState>().invoke(controlState)
      controlState
    }

    coEvery { LinkDeviceRepository.loadDevices() } returns listOf(
      Device(id = device.deviceId, name = "MollySocket", createdMillis = null, lastSeenMillis = 0, registrationId = 1)
    )
  }

  @After
  fun tearDown() {
    unmockkObject(LinkDeviceRepository)
    unmockkObject(MollySocketRepository, UnifiedPushDistributor, AppDependencies, LockedPushState, EndpointReconciler)
    unmockkStatic(AppDependencies::class, KeyCachingService::class, NotificationChannels::class, LockedPushState::class, EndpointReconciler::class, UnifiedPushDistributor::class)
    unmockkConstructor(UnifiedPushNotificationBuilder::class)
  }

  @Test
  fun `failed unlink keeps relay credentials and reports a retryable failure`() {
    coEvery { LinkDeviceRepository.removeDevice(device.deviceId) } returns false

    val failure = runCleanup()

    assertTrue(failure is IOException)
    assertTrue(UnifiedPushRefreshJob().onShouldRetry(failure as IOException))
    assertSame(device, storedDevice)
    verify(exactly = 0) { unifiedPush.device = any() }
    verify(exactly = 0) { unifiedPush.commitClearedDevice() }
    coVerify(exactly = 1) { LinkDeviceRepository.removeDevice(device.deviceId) }
  }

  @Test
  fun `successful unlink clears relay credentials`() {
    coEvery { LinkDeviceRepository.removeDevice(device.deviceId) } returns true

    assertNull(runCleanup())

    assertNull(storedDevice)
    assertEquals("retained-control-key", controlState.key)
    assertEquals(7L, controlState.importSequence)
    assertNull(controlState.update)
    verify(exactly = 1) { unifiedPush.commitClearedDevice() }
    coVerify(exactly = 1) { LinkDeviceRepository.removeDevice(device.deviceId) }
  }

  @Test
  fun `renamed relay is unlinked before its credentials are cleared`() {
    coEvery { LinkDeviceRepository.loadDevices() } returns listOf(
      Device(id = device.deviceId, name = "Renamed relay", createdMillis = null, lastSeenMillis = 0, registrationId = 1)
    )
    coEvery { LinkDeviceRepository.removeDevice(device.deviceId) } answers {
      assertSame("the credential must remain tracked until the server acknowledges unlink", device, storedDevice)
      assertSame(retainedUpdate, controlState.update)
      true
    }

    assertNull(runCleanup())

    assertNull(storedDevice)
    assertNull(controlState.update)
    assertEquals("retained-control-key", controlState.key)
    assertEquals(7L, controlState.importSequence)
    coVerify(exactly = 1) { LinkDeviceRepository.removeDevice(device.deviceId) }
  }

  @Test
  fun `unreadable relay name cannot bypass unlink and failed removal remains retryable`() {
    // LinkDeviceRepository exposes encoded fallback names when the name cannot be decrypted.
    coEvery { LinkDeviceRepository.loadDevices() } returns listOf(
      Device(id = device.deviceId, name = "AQID", createdMillis = null, lastSeenMillis = 0, registrationId = 1)
    )
    coEvery { LinkDeviceRepository.removeDevice(device.deviceId) } returns false

    val failure = runCleanup()

    assertTrue(failure is IOException)
    assertTrue(UnifiedPushRefreshJob().onShouldRetry(failure as IOException))
    assertSame(device, storedDevice)
    assertSame(retainedUpdate, controlState.update)
    verify(exactly = 0) { unifiedPush.device = any() }
    verify(exactly = 0) { unifiedPush.commitClearedDevice() }
    coVerify(exactly = 1) { LinkDeviceRepository.removeDevice(device.deviceId) }

    coEvery { LinkDeviceRepository.removeDevice(device.deviceId) } returns true

    assertNull(runCleanup())
    assertNull(storedDevice)
    assertNull(controlState.update)
    coVerify(exactly = 2) { LinkDeviceRepository.removeDevice(device.deviceId) }
  }

  @Test
  fun `a different device with the relay name is not revoked`() {
    coEvery { LinkDeviceRepository.loadDevices() } returns listOf(
      Device(id = device.deviceId + 1, name = "MollySocket", createdMillis = null, lastSeenMillis = 0, registrationId = 1)
    )

    assertNull(runCleanup())

    assertNull(storedDevice)
    coVerify(exactly = 0) { LinkDeviceRepository.removeDevice(any()) }
  }

  @Test
  fun `manual unlink clears the matching relay only after server removal succeeds`() = runBlocking {
    val removed = UnifiedPushAccountTransition.removeLinkedDeviceAndClearRelayIfMatching(device.deviceId) { id ->
      assertEquals(device.deviceId, id)
      assertSame(device, storedDevice)
      assertSame(retainedUpdate, controlState.update)
      assertEquals(device, pendingCleanup?.device)
      assertEquals(false, pendingCleanup?.unlinkConfirmed)
      assertFalse(transitionMarker)
      true
    }

    assertTrue(removed)
    assertNull(storedDevice)
    assertNull(pendingCleanup)
    assertTrue(UnifiedPushLifecycle.isReady())
    assertNull(controlState.update)
    assertEquals("retained-control-key", controlState.key)
    assertEquals(7L, controlState.importSequence)
  }

  @Test
  fun `manual unlink failure retains the matching relay and control update`() = runBlocking {
    val removed = UnifiedPushAccountTransition.removeLinkedDeviceAndClearRelayIfMatching(device.deviceId) { id ->
      assertEquals(device.deviceId, id)
      false
    }

    assertFalse(removed)
    assertEquals(device, pendingCleanup?.device)
    assertEquals(false, pendingCleanup?.unlinkConfirmed)
    assertNull(UnifiedPushAccountTransition.acquireLease())
    assertFalse(transitionMarker)
    assertSame(device, storedDevice)
    assertSame(retainedUpdate, controlState.update)
    assertEquals("retained-control-key", controlState.key)
    assertEquals(7L, controlState.importSequence)
  }

  @Test
  fun `manual unlink of an unrelated ID leaves saved relay credentials intact`() = runBlocking {
    val unrelatedDeviceId = device.deviceId + 1
    val removed = UnifiedPushAccountTransition.removeLinkedDeviceAndClearRelayIfMatching(unrelatedDeviceId) { id ->
      assertEquals(unrelatedDeviceId, id)
      true
    }

    assertTrue(removed)
    assertNull(pendingCleanup)
    assertSame(device, storedDevice)
    assertSame(retainedUpdate, controlState.update)
    assertEquals("retained-control-key", controlState.key)
    assertEquals(7L, controlState.importSequence)
  }

  @Test
  fun `manual unlink does not clear a replacement relay with the same ID`() = runBlocking {
    val replacement = MollySocketDevice(deviceId = device.deviceId, password = "replacement-relay-credential")
    val removed = UnifiedPushAccountTransition.removeLinkedDeviceAndClearRelayIfMatching(device.deviceId) {
      storedDevice = replacement
      true
    }

    assertTrue(removed)
    assertSame(replacement, storedDevice)
    assertSame(retainedUpdate, controlState.update)
    assertEquals("retained-control-key", controlState.key)
    assertEquals(7L, controlState.importSequence)
  }

  @Test
  fun `manual unlink rejects a changed account after waiting for transition admission`() = runBlocking {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val transitionEntered = CountDownLatch(1)
    val releaseTransition = CountDownLatch(1)
    val selectionCaptured = CountDownLatch(1)
    val selectedAccount = mockk<ServiceId.ACI>()
    val replacementAccount = mockk<ServiceId.ACI>()
    every { selectedAccount.toString() } answers {
      selectionCaptured.countDown()
      "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    }
    every { replacementAccount.toString() } returns "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
    every { signalStore.account.aci } returns selectedAccount
    var removalCalls = 0

    val transition = scope.launch {
      UnifiedPushAccountTransition.withTransition {
        transitionEntered.countDown()
        check(releaseTransition.await(5, TimeUnit.SECONDS))
        synchronized(UnifiedPushLifecycle.monitor) {
          every { signalStore.account.aci } returns replacementAccount
        }
      }
    }
    val removal = scope.async {
      check(transitionEntered.await(5, TimeUnit.SECONDS))
      UnifiedPushAccountTransition.removeLinkedDeviceAndClearRelayIfMatching(device.deviceId) {
        removalCalls++
        true
      }
    }

    try {
      assertTrue("selection must be captured while transition admission is blocked", selectionCaptured.await(5, TimeUnit.SECONDS))
      releaseTransition.countDown()

      assertFalse(withTimeout(5_000) { removal.await() })
      withTimeout(5_000) { transition.join() }
      assertEquals(0, removalCalls)
      assertSame(device, storedDevice)
      assertSame(retainedUpdate, controlState.update)
      assertFalse(transitionMarker)
      assertTrue(UnifiedPushLifecycle.isReady())
    } finally {
      releaseTransition.countDown()
      scope.cancel()
      withTimeout(5_000) {
        transition.join()
        removal.join()
      }
    }
  }

  @Test
  fun `manual unlink finishes local transition after caller cancellation during lease drain`() = runBlocking {
    val lease = UnifiedPushAccountTransition.acquireLease()!!
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val serverRemovalStarted = CountDownLatch(1)

    val removal = scope.launch {
      UnifiedPushAccountTransition.removeLinkedDeviceAndClearRelayIfMatching(device.deviceId) {
        serverRemovalStarted.countDown()
        true
      }
    }

    try {
      withTimeout(5_000) {
        while (UnifiedPushLifecycle.isReady()) delay(1)
      }
      assertEquals("server removal must wait for the active push lease", 1L, serverRemovalStarted.count)

      removal.cancel()
      lease.close()
      withTimeout(5_000) { removal.join() }

      assertTrue(removal.isCancelled)
      assertEquals(0L, serverRemovalStarted.count)
      assertFalse(transitionMarker)
      assertTrue(UnifiedPushLifecycle.isReady())
      assertNull(storedDevice)
      assertNull(controlState.update)

      val nextLease = UnifiedPushAccountTransition.acquireLease()
      assertNotNull("a later push operation must be able to acquire a lease", nextLease)
      nextLease!!.close()
    } finally {
      lease.close()
      scope.cancel()
      withTimeout(5_000) { removal.join() }
    }
  }

  @Test
  fun `refresh continuation cannot acquire the generation of a competing transition`() {
    val lease = UnifiedPushAccountTransition.acquireLease()!!
    val job = UnifiedPushRefreshJob()
    val clearedGeneration = checkNotNull(job.clearDevice(device, lease))

    val sameGenerationLease = job.acquireLeaseAfterDeviceClear(clearedGeneration)
    assertNotNull("the clear transition's own generation remains usable", sameGenerationLease)
    sameGenerationLease!!.close()

    val replacement = runBlocking {
      UnifiedPushLifecycle.withTransitionAndGeneration(
        persistIncompleteMarker = { transitionMarker = it },
        readyAfter = { true },
      ) {
        storedDevice = MollySocketDevice(deviceId = 8, password = "replacement-relay-credential")
      }
    }

    assertTrue(replacement.generation != clearedGeneration)
    assertNull("a continuation from the old refresh cannot adopt a later generation", job.acquireLeaseAfterDeviceClear(clearedGeneration))
  }

  @Test
  fun `unknown link status keeps relay credentials without attempting unlink`() {
    coEvery { LinkDeviceRepository.loadDevices() } returns null

    assertTrue(runCleanup() is IOException)

    assertSame(device, storedDevice)
    verify(exactly = 0) { unifiedPush.device = any() }
    verify(exactly = 0) { unifiedPush.commitClearedDevice() }
    coVerify(exactly = 0) { LinkDeviceRepository.removeDevice(any()) }
  }

  @Test
  fun `device created for old account is removed when a transition starts before publication`() {
    val created = MollySocketDevice(deviceId = 5, password = "new-relay-credential")
    storedDevice = null
    every { unifiedPush.enabled } returns true
    every { unifiedPush.airGapped } returns true
    every { unifiedPush.endpoint } returns "https://push.example.invalid/endpoint"
    every { unifiedPush.vapidPublicKey } returns "SYNTHETIC-VAPID"
    every { unifiedPush.lastReceivedTime } returns 1L

    mockkObject(MollySocketRepository, UnifiedPushDistributor, AppDependencies)
    mockkStatic(AppDependencies::class)
    every { UnifiedPushDistributor.checkIfActive() } returns true
    every { AppDependencies.resetNetwork(any()) } returns Unit

    val createEntered = CountDownLatch(1)
    val finishCreate = CountDownLatch(1)
    every { MollySocketRepository.createDevice() } answers {
      createEntered.countDown()
      check(finishCreate.await(5, TimeUnit.SECONDS))
      created
    }
    every { MollySocketRepository.removeDevice(created) } returns Unit

    val transitionMarker = CountDownLatch(1)
    val transitionBody = CountDownLatch(1)
    val transitionError = AtomicReference<Throwable?>()
    val jobExecutor = Executors.newSingleThreadExecutor()
    val transitionExecutor = Executors.newSingleThreadExecutor()
    try {
      val job = jobExecutor.submit<Throwable?> {
        val refresh = UnifiedPushRefreshJob(testPing = false, fromNewEndpoint = true)
          .apply { setContext(ApplicationProvider.getApplicationContext()) }
        runCatching { refresh.onRun() }.exceptionOrNull()
      }

      assertTrue(createEntered.await(5, TimeUnit.SECONDS))
      val transition = transitionExecutor.submit {
        try {
          runBlocking {
            UnifiedPushLifecycle.withTransition(
              persistIncompleteMarker = { pending -> if (pending) transitionMarker.countDown() },
              readyAfter = { true },
            ) { transitionBody.countDown() }
          }
        } catch (failure: Throwable) {
          transitionError.set(failure)
        }
      }

      assertTrue(transitionMarker.await(5, TimeUnit.SECONDS))
      assertTrue("transition must wait for the leased create", transitionBody.count == 1L)
      finishCreate.countDown()

      assertNull(job.get(5, TimeUnit.SECONDS))
      transition.get(5, TimeUnit.SECONDS)
      assertNull(transitionError.get())
      assertNull("obsolete device must not be published", storedDevice)
      assertEquals("control key and sequence survive a stale create", "retained-control-key", controlState.key)
      assertEquals(7L, controlState.importSequence)
      verify(exactly = 0) { unifiedPush.device = created }
      verify(exactly = 0) { unifiedPush.commitProvisionedDevice(any()) }
      verify(exactly = 1) { MollySocketRepository.removeDevice(created) }
      assertTrue(transitionBody.count == 0L)
    } finally {
      finishCreate.countDown()
      jobExecutor.shutdownNow()
      transitionExecutor.shutdownNow()
    }
  }

  @Test
  fun `an HTTP relay must not provision a linked device`() {
    configureEndpointCallbacks()
    storedDevice = null
    every { unifiedPush.airGapped } returns false
    every { unifiedPush.mollySocketUrl } returns "http://relay.example.invalid"

    UnifiedPushRefreshJob(testPing = false, fromNewEndpoint = true)
      .apply { setContext(ApplicationProvider.getApplicationContext()) }
      .onRun()

    assertEquals(RegistrationStatus.PENDING, unifiedPush.registrationStatus)
    verify(exactly = 0) { MollySocketRepository.createDevice() }
    verify(exactly = 0) { MollySocketRepository.registerDeviceOnServer(any(), any(), any(), any()) }
  }

  @Test
  fun `a confirmed linked v4 receiver stays registered without creating bridge credentials`() {
    val accountId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    val instance = "fixture-instance"
    val importId = "22222222-2222-4222-8222-222222222222"
    val endpoint = "https://push.example.invalid/linked"
    val p256dh = "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4"
    val auth = "MDEyMzQ1Njc4OTo7PD0-Pw"
    val aci = mockk<ServiceId.ACI>()
    every { aci.toString() } returns accountId
    every { signalStore.account.aci } returns aci
    every { signalStore.account.deviceId } returns 3
    every { signalStore.account.isLinkedDevice } returns true
    every { unifiedPush.enabled } returns true
    every { unifiedPush.endpoint } returns endpoint
    every { unifiedPush.endpointInstance } returns instance
    every { unifiedPush.endpointP256dh } returns p256dh
    every { unifiedPush.endpointAuth } returns auth
    every { unifiedPush.pairingEndpointBinding } returns UnifiedPushValues.PairingEndpointBinding(
      instance, "org.collie.pack", "SYNTHETIC-VAPID", "https://push.example.invalid", "https://push.example.invalid",
      "33333333-3333-4333-8333-333333333333", System.currentTimeMillis() / 1000 + 600, accountId, 3, "Fixture host", importId,
    )
    every { unifiedPush.v4ReceiverState } returns ReceiverControlState(
      receiverId = "44444444-4444-4444-8444-444444444444", importId = importId, accountId = accountId,
      bridgeDeviceId = 2, instance = instance, controlKey = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8",
      hostLabel = "Fixture bridge",
      epoch = 1, target = Target(endpoint, p256dh, auth), confirmed = true,
    ).serialize()
    every { unifiedPush.registrationFailed } returns false
    storedDevice = null
    mockkObject(MollySocketRepository, UnifiedPushDistributor, AppDependencies)
    mockkStatic(AppDependencies::class, NotificationChannels::class)
    every { UnifiedPushDistributor.checkIfActive() } returns true
    every { AppDependencies.application } returns ApplicationProvider.getApplicationContext()
    every { AppDependencies.resetNetwork(any()) } returns Unit
    every { NotificationChannels.supported() } returns false

    newRefreshJob().onRun()

    assertEquals(RegistrationStatus.REGISTERED, unifiedPush.registrationStatus)
    verify(exactly = 0) { MollySocketRepository.createDevice() }
    verify(exactly = 0) { MollySocketRepository.registerDeviceOnServer(any(), any(), any(), any()) }
  }

  @Test
  fun `refresh recreates a validated device instead of registering the removed lease snapshot`() {
    val created = MollySocketDevice(deviceId = 6, password = "new-relay-credential")
    storedDevice = device
    every { unifiedPush.enabled } returns true
    every { unifiedPush.airGapped } returns true
    every { unifiedPush.vapidPublicKey } returns "SYNTHETIC-VAPID"
    every { unifiedPush.vapidKeySynced } returns true
    every { unifiedPush.lastReceivedTime } returns 0L

    mockkObject(MollySocketRepository, UnifiedPushDistributor, AppDependencies)
    mockkStatic(AppDependencies::class)
    every { UnifiedPushDistributor.checkIfActive() } returns true
    every { AppDependencies.resetNetwork(any()) } returns Unit
    every { MollySocketRepository.getDeviceStatus(device) } returns LinkStatus.NOT_LINKED
    every { MollySocketRepository.createDevice() } returns created

    val job = UnifiedPushRefreshJob(testPing = false, fromNewEndpoint = true)
      .apply { setContext(ApplicationProvider.getApplicationContext()) }
    job.onRun()

    assertEquals(created, storedDevice)
    assertEquals(RegistrationStatus.PENDING, unifiedPush.registrationStatus)
    verify(exactly = 1) { MollySocketRepository.createDevice() }
    verify(exactly = 0) { MollySocketRepository.registerDeviceOnServer(any(), any(), any(), any()) }
  }

  @Test
  fun `duplicate endpoint during linked device creation does not revoke the new device`() {
    val pending = configureEndpointCallbacks()
    val context = ApplicationProvider.getApplicationContext<Application>()
    val created = MollySocketDevice(deviceId = 6, password = "new-relay-credential")
    val generation = UnifiedPushLifecycle.currentGeneration()
    storedDevice = null
    every { MollySocketRepository.createDevice() } answers {
      // A distributor can repeat its saved subscription while the HTTP linking request is in flight.
      UnifiedPushReceiver().onNewEndpoint(context, savedEndpoint(), "fixture-instance")
      created
    }
    every { MollySocketRepository.removeDevice(created) } returns Unit
    every { MollySocketRepository.registerDeviceOnServer(any(), created, any(), false) } returns ConnectionResult.OK

    val refresh = UnifiedPushRefreshJob(testPing = false).apply { setContext(context) }
    refresh.onRun()
    awaitEndpointCallbacks(pending)

    assertEquals("a repeated subscription must not retire the linking job's generation", generation, UnifiedPushLifecycle.currentGeneration())
    assertEquals(created, storedDevice)
    assertEquals(RegistrationStatus.REGISTERED, storedStatus)
    verify(exactly = 1) { MollySocketRepository.createDevice() }
    verify(exactly = 1) { MollySocketRepository.registerDeviceOnServer(any(), created, "https://push.example.invalid/endpoint", false) }
    verify(exactly = 0) { MollySocketRepository.removeDevice(created) }
  }

  @Test
  fun `configuration test accepts its ping after the distributor repeats the saved endpoint`() {
    val pending = configureEndpointCallbacks()
    val context = ApplicationProvider.getApplicationContext<Application>()
    val receiver = UnifiedPushReceiver()
    val generation = UnifiedPushLifecycle.currentGeneration()
    every { UnifiedPushDistributor.registerApp(any()) } answers {
      receiver.onNewEndpoint(context, savedEndpoint(), "fixture-instance")
    }
    every { MollySocketRepository.registerDeviceOnServer(any(), device, any(), true) } answers {
      receiver.onMessage(context, PushMessage("""{"test":true}""".toByteArray(), true), "fixture-instance")
      ConnectionResult.OK
    }

    val refresh = UnifiedPushRefreshJob(testPing = true).apply { setContext(context) }
    refresh.onRun()
    awaitEndpointCallbacks(pending)

    assertEquals(generation, UnifiedPushLifecycle.currentGeneration())
    assertEquals(device, storedDevice)
    assertEquals(RegistrationStatus.REGISTERED, storedStatus)
    verify(exactly = 1) { anyConstructed<UnifiedPushNotificationBuilder>().setNotificationTest() }
    verify(exactly = 1) { MollySocketRepository.registerDeviceOnServer(any(), device, "https://push.example.invalid/endpoint", true) }
    verify(exactly = 0) { MollySocketRepository.createDevice() }
  }

  /** Uses the real receiver and lifecycle, with only network, notification and sealed-storage effects replaced. */
  private fun configureEndpointCallbacks(): AtomicReference<LockedPushState.Endpoint?> {
    val context = ApplicationProvider.getApplicationContext<Application>()
    mockkObject(MollySocketRepository, UnifiedPushDistributor, AppDependencies, LockedPushState, EndpointReconciler)
    mockkStatic(AppDependencies::class, KeyCachingService::class, NotificationChannels::class, LockedPushState::class, EndpointReconciler::class, UnifiedPushDistributor::class)
    mockkConstructor(UnifiedPushNotificationBuilder::class)
    every { AppDependencies.application } returns context
    every { AppDependencies.isInitialized } returns false
    every { AppDependencies.jobManager } returns mockk(relaxUnitFun = true)
    every { AppDependencies.resetNetwork(any()) } returns Unit
    every { KeyCachingService.isLocked() } returns false
    every { NotificationChannels.supported() } returns false
    every { EndpointReconciler.reconcile(any()) } returns Unit
    every { LockedPushState.setEncryptedEnrollment(any(), any()) } returns Unit
    every { anyConstructed<UnifiedPushNotificationBuilder>().setNotificationTest() } returns Unit
    every { anyConstructed<UnifiedPushNotificationBuilder>().clearAlerts(any()) } returns Unit
    every { UnifiedPushDistributor.checkIfActive() } returns true
    every { UnifiedPushDistributor.registerApp(any()) } returns Unit
    every { MollySocketRepository.getDeviceStatus(device) } returns LinkStatus.LINKED
    every { unifiedPush.enabled } returns true
    every { unifiedPush.vapidPublicKey } returns "SYNTHETIC-VAPID"
    every { unifiedPush.vapidKeySynced } returns true
    every { unifiedPush.lastReceivedTime } returns 0L
    every { unifiedPush.registrationFailed } returns false
    every { unifiedPush.registrationRetryScheduled } returns false
    controlState = ControlState()
    every { unifiedPush.controlStateRead } answers { ControlStateRead.Current(controlState) }
    return AtomicReference<LockedPushState.Endpoint?>().also { pending ->
      every { LockedPushState.savePending(any(), any()) } answers { pending.set(secondArg()) }
      every { LockedPushState.peekPending(any()) } answers { pending.get() }
      every { LockedPushState.clearPending(any()) } answers { pending.set(null) }
    }
  }

  private fun savedEndpoint() = PushEndpoint(
    "https://push.example.invalid/endpoint",
    PublicKeySet("SYNTHETIC-P256DH", "SYNTHETIC-AUTH"),
  )

  private fun awaitEndpointCallbacks(pending: AtomicReference<LockedPushState.Endpoint?>) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
    while ((pending.get() != null || !UnifiedPushLifecycle.isReady()) && System.nanoTime() < deadline) Thread.yield()
    assertNull("endpoint callback must finish before test teardown", pending.get())
    assertTrue(UnifiedPushLifecycle.isReady())
  }

  @Test
  fun `route removal waits for a selected control send before clearing device and update`() {
    val accountId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    val aci = mockk<ServiceId.ACI>()
    every { aci.toString() } returns accountId
    every { SignalStore.account.isRegistered } returns true
    every { SignalStore.account.aci } returns aci
    every { SignalStore.account.hasAciIdentityKey() } returns true
    every { SignalStore.account.servicePassword } returns "SYNTHETIC-SERVICE-PASSWORD"
    every { SignalStore.account.deviceId } returns 1
    every { unifiedPush.device } answers { storedDevice }
    every { unifiedPush.endpoint } returns "https://push.example.invalid/endpoint"
    val endpointP256dh = "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4"
    val endpointAuth = "MDEyMzQ1Njc4OTo7PD0-Pw"
    every { unifiedPush.endpointP256dh } returns endpointP256dh
    every { unifiedPush.endpointAuth } returns endpointAuth
    every { unifiedPush.endpointInstance } returns "fixture-instance"

    val frame = ControlFrame.header(accountId, device.deviceId, 5) + ByteArray(20)
    val update = ControlUpdate(5, "https://push.example.invalid/endpoint", endpointP256dh, endpointAuth, frame, 1)
    controlState = ControlState(key = "retained-control-key", activeEpoch = 4, highestEpoch = 5, importSequence = 7, update = update)
    every { unifiedPush.controlState } answers { controlState }
    every { unifiedPush.mutateControl(any(), any()) } answers {
      controlState = secondArg<(ControlState) -> ControlState>().invoke(controlState)
      controlState
    }

    mockkObject(AppDependencies, MollySocketRepository)
    mockkStatic(AppDependencies::class)
    val sender = mockk<SignalServiceMessageSender>()
    val sendResult = mockk<SendMessageResult>()
    every { AppDependencies.signalServiceMessageSender } returns sender
    val sendEntered = CountDownLatch(1)
    val finishSend = CountDownLatch(1)
    every { sender.sendControlMessage(accountId, 1, device.deviceId, frame) } answers {
      sendEntered.countDown()
      check(finishSend.await(5, TimeUnit.SECONDS))
      sendResult
    }
    coEvery { LinkDeviceRepository.removeDevice(device.deviceId) } returns true
    every { MollySocketRepository.getDeviceStatus(device) } returns LinkStatus.LINKED
    every { unifiedPush.enabled } returns true
    val removeEntered = CountDownLatch(1)
    val markerSet = CountDownLatch(1)
    every { unifiedPush.setRelayCleanup(any(), true) } answers {
      pendingCleanup = firstArg()
      markerSet.countDown()
    }
    every { MollySocketRepository.removeDevice(device) } answers {
      removeEntered.countDown()
      check(markerSet.count == 0L)
      check(sendEntered.await(5, TimeUnit.SECONDS))
      Unit
    }

    val sendExecutor = Executors.newSingleThreadExecutor()
    val refreshExecutor = Executors.newSingleThreadExecutor()
    try {
      val send = sendExecutor.submit<Throwable?> { runCatching { newControlJob().onRun() }.exceptionOrNull() }
      assertTrue(sendEntered.await(5, TimeUnit.SECONDS))
      every { unifiedPush.enabled } returns false
      val refresh = refreshExecutor.submit<Throwable?> {
        val job = UnifiedPushRefreshJob().apply { setContext(ApplicationProvider.getApplicationContext()) }
        runCatching { job.onRun() }.exceptionOrNull()
      }

      val removed = removeEntered.await(5, TimeUnit.SECONDS)
      assertTrue("refresh outcome before remote unlink: ${if (refresh.isDone) refresh.get(0, TimeUnit.SECONDS) else "still running"}", removed)
      // Remote removal has succeeded, but local authority remains intact until the send lease drains.
      assertEquals(device, storedDevice)
      assertEquals(update, controlState.update)
      finishSend.countDown()
      send.get(5, TimeUnit.SECONDS)
      assertNull(refresh.get(5, TimeUnit.SECONDS))

      assertNull(storedDevice)
      assertNull(controlState.update)
      assertEquals(7L, controlState.importSequence)
    } finally {
      finishSend.countDown()
      sendExecutor.shutdownNow()
      refreshExecutor.shutdownNow()
    }
  }

  @Test
  fun `relay learns a fresh credential only after its delayed write is acknowledged`() {
    val store = provisioningStore()
    store.values.vapidKeySynced = false
    assertFalse(store.rebuilt().vapidKeySynced)
    val created = MollySocketDevice(deviceId = 11, password = "fresh-relay-credential")
    every { MollySocketRepository.createDevice() } returns created
    val gate = CountDownLatch(1)
    store.executor.gate = gate

    val executor = Executors.newSingleThreadExecutor()
    try {
      val run = executor.submit<Throwable?> { runCatching { newRefreshJob().onRun() }.exceptionOrNull() }

      assertTrue(store.executor.held.await(5, TimeUnit.SECONDS))
      verify(exactly = 0) { MollySocketRepository.registerDeviceOnServer(any(), any(), any(), any()) }
      assertNull("nothing durable yet", store.rebuilt().device)

      gate.countDown()
      assertNull(run.get(5, TimeUnit.SECONDS))

      assertEquals(created, store.rebuilt().device)
      assertTrue(store.rebuilt().vapidKeySynced)
      verify(exactly = 1) { MollySocketRepository.registerDeviceOnServer(any(), created, any(), any()) }
      verify(exactly = 0) { MollySocketRepository.removeDevice(any()) }
    } finally {
      gate.countDown()
      executor.shutdownNow()
    }
  }

  @Test
  fun `failed backing write removes the fresh device and never reaches the relay`() {
    val store = provisioningStore()
    val created = MollySocketDevice(deviceId = 12, password = "fresh-relay-credential")
    every { MollySocketRepository.createDevice() } returns created
    every { MollySocketRepository.removeDevice(created) } returns Unit
    store.storage.delegate.nextWriteFailure = IllegalStateException("synthetic backing write failure")

    val failure = runRefresh()

    assertTrue(failure is KeyValuePersistenceException)
    assertTrue(UnifiedPushRefreshJob().onShouldRetry(failure as Exception))
    assertNull(store.values.device)
    assertNull(store.rebuilt().device)
    verify(exactly = 1) { MollySocketRepository.removeDevice(created) }
    verify(exactly = 0) { MollySocketRepository.registerDeviceOnServer(any(), any(), any(), any()) }
  }

  @Test
  fun `rejected backing write removes the fresh device and never reaches the relay`() {
    val store = provisioningStore()
    val created = MollySocketDevice(deviceId = 13, password = "fresh-relay-credential")
    every { MollySocketRepository.createDevice() } returns created
    every { MollySocketRepository.removeDevice(created) } returns Unit
    store.executor.rejectNext = true

    val failure = runRefresh()

    assertTrue(failure is KeyValuePersistenceException)
    assertTrue(failure!!.cause is RejectedExecutionException)
    assertNull(store.rebuilt().device)
    verify(exactly = 1) { MollySocketRepository.removeDevice(created) }
    verify(exactly = 0) { MollySocketRepository.registerDeviceOnServer(any(), any(), any(), any()) }
  }

  @Test
  fun `failed compensation keeps the original write error and does not retry removal`() {
    val store = provisioningStore()
    val created = MollySocketDevice(deviceId = 14, password = "fresh-relay-credential")
    val writeFailure = IllegalStateException("synthetic backing write failure")
    val removalFailure = IOException("synthetic removal failure")
    every { MollySocketRepository.createDevice() } returns created
    every { MollySocketRepository.removeDevice(created) } throws removalFailure
    store.storage.delegate.nextWriteFailure = writeFailure

    val failure = runRefresh()

    assertTrue(failure is KeyValuePersistenceException)
    assertSame(writeFailure, failure!!.cause)
    assertTrue(failure.suppressed.contains(removalFailure))
    assertNull(store.rebuilt().device)
    verify(exactly = 1) { MollySocketRepository.removeDevice(created) }
    verify(exactly = 0) { MollySocketRepository.registerDeviceOnServer(any(), any(), any(), any()) }
  }

  @Test
  fun `acknowledgement failure after the write clears the fresh record only after removal`() {
    val store = provisioningStore()
    val created = MollySocketDevice(deviceId = 15, password = "fresh-relay-credential")
    every { MollySocketRepository.createDevice() } returns created
    every { MollySocketRepository.removeDevice(created) } answers {
      assertEquals("the write took effect even though it was not acknowledged", created, store.rebuilt().device)
      assertTrue("the current process must reject the exact credential during removal", store.values.isProvisioningPending(created))
      assertTrue("a reopened store must also reject it", store.rebuilt().isProvisioningPending(created))
      Unit
    }
    store.storage.failReadAfterNextWrite = true

    val failure = runRefresh()

    assertTrue(failure is KeyValuePersistenceException)
    assertNull(store.values.device)
    assertNull(store.rebuilt().device)
    verify(exactly = 1) { MollySocketRepository.removeDevice(created) }
    verify(exactly = 0) { MollySocketRepository.registerDeviceOnServer(any(), any(), any(), any()) }
  }

  @Test
  fun `failed acknowledgement of guard removal does not unlink an acknowledged credential`() {
    val store = provisioningStore()
    val created = MollySocketDevice(deviceId = 16, password = "fresh-relay-credential")
    every { MollySocketRepository.createDevice() } returns created
    every { MollySocketRepository.getDeviceStatus(created) } returns LinkStatus.LINKED
    store.storage.afterWrite = { dataSet ->
      if (dataSet.containsKey("mollysocket.deviceId")) store.storage.failReadAfterNextWrite = true
    }

    assertTrue(runRefresh() is KeyValuePersistenceException)
    assertEquals(created, store.rebuilt().device)
    assertFalse(store.rebuilt().isProvisioningPending(created))
    verify(exactly = 0) { MollySocketRepository.registerDeviceOnServer(any(), any(), any(), any()) }

    assertNull(runRefresh())

    verify(exactly = 1) { MollySocketRepository.registerDeviceOnServer(any(), created, any(), any()) }
    verify(exactly = 0) { MollySocketRepository.removeDevice(any()) }
  }

  @Test
  fun `acknowledgement failure with failed removal keeps the written record`() {
    val store = provisioningStore()
    val created = MollySocketDevice(deviceId = 16, password = "fresh-relay-credential")
    every { MollySocketRepository.createDevice() } returns created
    every { MollySocketRepository.removeDevice(created) } throws IOException("synthetic removal failure")
    store.storage.failReadAfterNextWrite = true

    assertTrue(runRefresh() is KeyValuePersistenceException)

    assertEquals("an unremoved device stays tracked for the next validation", created, store.rebuilt().device)
    verify(exactly = 1) { MollySocketRepository.removeDevice(created) }
    verify(exactly = 0) { MollySocketRepository.registerDeviceOnServer(any(), any(), any(), any()) }
  }

  @Test
  fun `compensation never clears a replacement record`() {
    val store = provisioningStore()
    val created = MollySocketDevice(deviceId = 17, password = "fresh-relay-credential")
    val replacement = MollySocketDevice(deviceId = 17, password = "replacement-relay-credential")
    every { MollySocketRepository.createDevice() } returns created
    every { MollySocketRepository.removeDevice(created) } answers {
      store.values.device = replacement
      Unit
    }
    store.storage.failReadAfterNextWrite = true

    assertTrue(runRefresh() is KeyValuePersistenceException)

    assertEquals(replacement, store.values.device)
    assertEquals(replacement, store.rebuilt().device)
    verify(exactly = 1) { MollySocketRepository.removeDevice(created) }
  }

  @Test
  fun `compensating cleanup clears the revoked device across a route-only transition`() {
    val store = provisioningStore()
    val created = MollySocketDevice(deviceId = 18, password = "fresh-relay-credential")
    val newEndpoint = "https://push.example.invalid/rotated-endpoint"
    val transitionDraining = CountDownLatch(1)
    val transitionError = AtomicReference<Throwable?>()
    val transitionExecutor = Executors.newSingleThreadExecutor()
    var transitionFuture: java.util.concurrent.Future<*>? = null
    store.storage.afterWrite = { dataSet ->
      if (dataSet.getBoolean(UnifiedPushValues.UNIFIEDPUSH_ACCOUNT_TRANSITION_PENDING, false)) {
        transitionDraining.countDown()
      }
    }
    every { MollySocketRepository.createDevice() } returns created
    every { MollySocketRepository.removeDevice(created) } answers {
      assertEquals(created, store.rebuilt().device)
      synchronized(UnifiedPushLifecycle.monitor) {
        UnifiedPushLifecycle.invalidateLocked()
      }
      transitionFuture = transitionExecutor.submit {
        try {
          runBlocking {
            UnifiedPushAccountTransition.withTransitionAndGeneration(completesInvalidation = true) {
              store.values.setEndpointRegistration(newEndpoint, "NEW-P256DH", "NEW-AUTH", "fixture-instance")
            }
          }
        } catch (t: Throwable) {
          transitionError.set(t)
        }
      }
      check(transitionDraining.await(5, TimeUnit.SECONDS))
      Unit
    }
    store.storage.failReadAfterNextWrite = true

    try {
      val failure = runRefresh()
      transitionFuture?.get(5, TimeUnit.SECONDS)

      assertNull(transitionError.get())
      assertTrue(failure is KeyValuePersistenceException)
      assertEquals(0, failure!!.suppressed.size)
      assertNull(store.values.device)
      assertNull(store.rebuilt().device)
      assertEquals(newEndpoint, store.rebuilt().endpoint)
      assertTrue(UnifiedPushLifecycle.isReady())
      assertFalse(store.rebuilt().hasIncompleteAccountTransition)
      verify(exactly = 1) { MollySocketRepository.removeDevice(created) }
      verify(exactly = 0) { MollySocketRepository.registerDeviceOnServer(any(), any(), any(), any()) }
    } finally {
      transitionExecutor.shutdownNow()
    }
  }

  @Test
  fun `compensating cleanup clears the revoked device without clearing another caller's pending invalidation`() {
    val store = provisioningStore()
    val created = MollySocketDevice(deviceId = 19, password = "fresh-relay-credential")
    every { MollySocketRepository.createDevice() } returns created
    every { MollySocketRepository.removeDevice(created) } answers {
      synchronized(UnifiedPushLifecycle.monitor) {
        UnifiedPushLifecycle.invalidateLocked()
      }
      Unit
    }
    store.storage.failReadAfterNextWrite = true

    val failure = runRefresh()

    assertTrue(failure is KeyValuePersistenceException)
    assertEquals(0, failure!!.suppressed.size)
    assertNull(store.values.device)
    assertNull(store.rebuilt().device)
    assertFalse(UnifiedPushLifecycle.isReady())
    assertTrue(synchronized(UnifiedPushLifecycle.monitor) { UnifiedPushLifecycle.hasPendingInvalidationLocked() })
    runBlocking { UnifiedPushAccountTransition.withTransition(completesInvalidation = true) {} }
    assertTrue(UnifiedPushLifecycle.isReady())
    verify(exactly = 1) { MollySocketRepository.removeDevice(created) }
    verify(exactly = 0) { MollySocketRepository.registerDeviceOnServer(any(), any(), any(), any()) }
  }

  @Test
  fun `compensating cleanup does not complete an invalidation that supersedes its own`() {
    assertCompensationAfterInterveningTransition(replaceInvalidation = true)
  }

  @Test
  fun `compensating cleanup completes its own invalidation after an unrelated transition`() {
    assertCompensationAfterInterveningTransition(replaceInvalidation = false)
  }

  private fun assertCompensationAfterInterveningTransition(replaceInvalidation: Boolean) {
    val store = provisioningStore()
    val created = MollySocketDevice(deviceId = 22, password = "fresh-relay-credential")
    val transitionRan = AtomicBoolean()
    every { MollySocketRepository.createDevice() } returns created
    every { MollySocketRepository.removeDevice(created) } answers {
      every { SignalStore.unifiedpush } answers {
        val cleanupHasInvalidated = !Thread.holdsLock(UnifiedPushLifecycle.monitor) &&
          synchronized(UnifiedPushLifecycle.monitor) { UnifiedPushLifecycle.hasPendingInvalidationLocked() }
        if (cleanupHasInvalidated && transitionRan.compareAndSet(false, true)) {
          runBlocking { UnifiedPushAccountTransition.withTransition(completesInvalidation = replaceInvalidation) {} }
          if (replaceInvalidation) {
            synchronized(UnifiedPushLifecycle.monitor) {
              assertFalse(UnifiedPushLifecycle.isReadyLocked())
              UnifiedPushLifecycle.invalidateLocked()
            }
          }
        }
        store.values
      }
    }
    store.storage.failReadAfterNextWrite = true

    val failure = runRefresh()

    assertTrue(transitionRan.get())
    assertTrue(failure is KeyValuePersistenceException)
    assertEquals(0, failure!!.suppressed.size)
    assertNull(store.rebuilt().device)
    assertEquals(!replaceInvalidation, UnifiedPushLifecycle.isReady())
    assertEquals(replaceInvalidation, synchronized(UnifiedPushLifecycle.monitor) { UnifiedPushLifecycle.hasPendingInvalidationLocked() })
  }

  @Test
  fun `compensating cleanup across a transition never clears a different account's credential`() {
    val store = provisioningStore()
    val created = MollySocketDevice(deviceId = 20, password = "fresh-relay-credential")
    val replacementAccount = mockk<ServiceId.ACI>()
    every { replacementAccount.toString() } returns "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
    val transitionDraining = CountDownLatch(1)
    val transitionError = AtomicReference<Throwable?>()
    val transitionExecutor = Executors.newSingleThreadExecutor()
    var transitionFuture: java.util.concurrent.Future<*>? = null
    store.storage.afterWrite = { dataSet ->
      if (dataSet.getBoolean(UnifiedPushValues.UNIFIEDPUSH_ACCOUNT_TRANSITION_PENDING, false)) {
        transitionDraining.countDown()
      }
    }
    every { MollySocketRepository.createDevice() } returns created
    every { MollySocketRepository.removeDevice(created) } answers {
      transitionFuture = transitionExecutor.submit {
        try {
          runBlocking {
            UnifiedPushAccountTransition.withTransition {
              synchronized(UnifiedPushLifecycle.monitor) {
                every { signalStore.account.aci } returns replacementAccount
              }
            }
          }
        } catch (t: Throwable) {
          transitionError.set(t)
        }
      }
      check(transitionDraining.await(5, TimeUnit.SECONDS))
      Unit
    }
    store.storage.failReadAfterNextWrite = true

    try {
      val failure = runRefresh()
      transitionFuture?.get(5, TimeUnit.SECONDS)

      assertNull(transitionError.get())
      assertTrue(failure is KeyValuePersistenceException)
      assertEquals(0, failure!!.suppressed.size)
      assertEquals(created, store.values.device)
      assertEquals(created, store.rebuilt().device)
      assertTrue(UnifiedPushLifecycle.isReady())
      verify(exactly = 1) { MollySocketRepository.removeDevice(created) }
      verify(exactly = 0) { MollySocketRepository.registerDeviceOnServer(any(), any(), any(), any()) }
    } finally {
      transitionExecutor.shutdownNow()
    }
  }

  @Test
  fun `failed clear backing write surfaces as suppressed alongside the original provisioning error`() {
    val store = provisioningStore()
    val created = MollySocketDevice(deviceId = 21, password = "fresh-relay-credential")
    val clearWriteFailure = IllegalStateException("synthetic clear backing write failure")
    every { MollySocketRepository.createDevice() } returns created
    every { MollySocketRepository.removeDevice(created) } answers {
      store.storage.beforeWrite = { dataSet ->
        if (dataSet.containsKey("mollysocket.deviceId") && dataSet.getInteger("mollysocket.deviceId", -1) == 0) {
          throw clearWriteFailure
        }
      }
      Unit
    }
    store.storage.failReadAfterNextWrite = true

    val failure = runRefresh()

    assertTrue(failure is KeyValuePersistenceException)
    val suppressed = failure!!.suppressed.single()
    assertTrue(suppressed is KeyValuePersistenceException)
    assertSame(clearWriteFailure, suppressed.cause)
    assertEquals(created, store.values.device)
    assertEquals(created, store.rebuilt().device)
    assertFalse(UnifiedPushLifecycle.isReady())
    assertNotNull(store.rebuilt().pendingRelayCleanup)
    assertNull(UnifiedPushAccountTransition.acquireLease())
    store.storage.beforeWrite = null
    store.values.enabled = false
    assertNull(runRefresh())
    assertTrue(UnifiedPushLifecycle.isReady())
    assertNull(store.rebuilt().device)
    assertNull(store.rebuilt().pendingRelayCleanup)
    verify(exactly = 1) { MollySocketRepository.removeDevice(created) }
    verify(exactly = 0) { MollySocketRepository.registerDeviceOnServer(any(), any(), any(), any()) }
  }

  @Test
  fun `failed clear acknowledgement surfaces as suppressed alongside the original provisioning error`() {
    val store = provisioningStore()
    val created = MollySocketDevice(deviceId = 22, password = "fresh-relay-credential")
    every { MollySocketRepository.createDevice() } returns created
    every { MollySocketRepository.removeDevice(created) } answers {
      store.storage.beforeWrite = { dataSet ->
        if (dataSet.containsKey("mollysocket.deviceId") && dataSet.getInteger("mollysocket.deviceId", -1) == 0) {
          store.storage.failReadAfterNextWrite = true
        }
      }
      Unit
    }
    store.storage.failReadAfterNextWrite = true

    val failure = runRefresh()

    assertTrue(failure is KeyValuePersistenceException)
    val suppressed = failure!!.suppressed.single()
    assertTrue(suppressed is KeyValuePersistenceException)
    assertFalse(UnifiedPushLifecycle.isReady())
    assertNotNull(store.rebuilt().pendingRelayCleanup)
    assertNull(UnifiedPushAccountTransition.acquireLease())
    assertNull("the unacknowledged clear actually landed", store.rebuilt().device)
    val restarted = store.rebuilt()
    restarted.enabled = false
    restarted.mutateControl {
      val update = ControlUpdate(5, "https://push.example.invalid/old", "p", "a", ByteArray(ControlFrame.HEADER_SIZE), 1)
      it.copy(key = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", activeEpoch = 4, highestEpoch = 5, importSequence = 7, update = update)
    }
    every { SignalStore.unifiedpush } returns restarted
    UnifiedPushAccountTransition.initializeFromSignalStore()
    assertNull(UnifiedPushAccountTransition.acquireLease())

    assertNull(runRefresh())

    assertNull(restarted.controlState.update)
    assertEquals(7L, restarted.controlState.importSequence)
    assertNull(store.rebuilt().pendingRelayCleanup)
    assertTrue(UnifiedPushLifecycle.isReady())
    verify(exactly = 1) { MollySocketRepository.removeDevice(created) }
    verify(exactly = 0) { MollySocketRepository.registerDeviceOnServer(any(), any(), any(), any()) }
  }

  @Test
  fun `resumed cleanup preserves a replacement credential and an unrelated account marker`() {
    val store = provisioningStore()
    val replacement = MollySocketDevice(device.deviceId, "replacement-relay-credential")
    store.values.device = replacement
    val cleanup = UnifiedPushValues.RelayCleanup("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", 1, device, unlinkConfirmed = false)
    store.values.setRelayCleanup(cleanup, pending = true)
    store.values.setIncompleteAccountTransition(true)
    val restarted = store.rebuilt()
    every { SignalStore.unifiedpush } returns restarted
    UnifiedPushAccountTransition.initializeFromSignalStore()

    assertNull(runRefresh())

    assertEquals(replacement, store.rebuilt().device)
    assertNull(store.rebuilt().pendingRelayCleanup)
    assertTrue(store.rebuilt().hasIncompleteAccountTransition)
    assertNull(UnifiedPushAccountTransition.acquireLease())
    verify(exactly = 0) { MollySocketRepository.removeDevice(any()) }
  }

  @Test
  fun `manual unlink with a lost response recovers from the durable intent after restart`() = runBlocking {
    val store = provisioningStore()
    store.values.enabled = false
    store.values.device = device
    store.values.mutateControl {
      val update = ControlUpdate(5, "https://push.example.invalid/old", "p", "a", ByteArray(ControlFrame.HEADER_SIZE), 1)
      it.copy(key = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", activeEpoch = 4, highestEpoch = 5, importSequence = 7, update = update)
    }

    val removed = UnifiedPushAccountTransition.removeLinkedDeviceAndClearRelayIfMatching(device.deviceId) {
      val durable = store.rebuilt()
      val intent = checkNotNull(durable.pendingRelayCleanup)
      assertEquals("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", intent.accountId)
      assertEquals(1, intent.localDeviceId)
      assertEquals(device, intent.device)
      assertFalse(intent.unlinkConfirmed)
      assertFalse(durable.hasIncompleteAccountTransition)
      assertEquals(device, durable.device)
      assertNull(UnifiedPushAccountTransition.acquireLease())
      throw IOException("synthetic lost unlink response")
    }
    assertFalse(removed)

    val restarted = store.rebuilt()
    every { SignalStore.unifiedpush } returns restarted
    UnifiedPushAccountTransition.initializeFromSignalStore()
    assertNull(UnifiedPushAccountTransition.acquireLease())

    every { MollySocketRepository.getDeviceStatus(device) } returns LinkStatus.UNKNOWN
    assertTrue(runRefresh() is IOException)
    assertEquals(device, store.rebuilt().device)
    assertNotNull(store.rebuilt().controlState.update)
    assertNull(UnifiedPushAccountTransition.acquireLease())

    every { MollySocketRepository.getDeviceStatus(device) } returns LinkStatus.NOT_LINKED
    assertNull(runRefresh())
    assertNull(store.rebuilt().device)
    assertNull(store.rebuilt().pendingRelayCleanup)
    assertNull(store.rebuilt().controlState.update)
    assertEquals("https://molly.example.invalid", store.rebuilt().mollySocketUrl)
    assertTrue(UnifiedPushLifecycle.isReady())
    verify(exactly = 0) { MollySocketRepository.removeDevice(any()) }
  }

  @Test
  fun `manual unlink waits for a durable intent before calling the server`() = runBlocking {
    val store = provisioningStore()
    store.values.device = device
    store.storage.delegate.nextWriteFailure = IllegalStateException("synthetic intent write failure")

    val removed = UnifiedPushAccountTransition.removeLinkedDeviceAndClearRelayIfMatching(device.deviceId) {
      error("server removal cannot start without an acknowledged intent")
    }

    assertFalse(removed)
    assertEquals(device, store.rebuilt().device)
    assertNull(store.rebuilt().pendingRelayCleanup)
    assertNull(UnifiedPushAccountTransition.acquireLease())
  }

  @Test
  fun `restart after remote unlink confirms absence before clearing the saved relay`() {
    val store = provisioningStore()
    store.values.enabled = false
    store.values.device = device
    every { MollySocketRepository.getDeviceStatus(device) } returns LinkStatus.LINKED
    every { MollySocketRepository.removeDevice(device) } answers {
      val durable = store.rebuilt()
      assertEquals(device, durable.device)
      assertEquals(false, durable.pendingRelayCleanup?.unlinkConfirmed)
      assertNull(UnifiedPushAccountTransition.acquireLease())
      // Simulate the server removing the device before this process receives the result.
      throw IOException("synthetic lost unlink response")
    }

    assertTrue(runRefresh() is IOException)

    val restarted = store.rebuilt()
    every { SignalStore.unifiedpush } returns restarted
    UnifiedPushAccountTransition.initializeFromSignalStore()
    assertNull(UnifiedPushAccountTransition.acquireLease())

    every { MollySocketRepository.getDeviceStatus(device) } returns LinkStatus.UNKNOWN
    assertTrue(runRefresh() is IOException)
    assertEquals(device, store.rebuilt().device)
    assertEquals(false, store.rebuilt().pendingRelayCleanup?.unlinkConfirmed)
    assertNull(UnifiedPushAccountTransition.acquireLease())

    every { MollySocketRepository.getDeviceStatus(device) } returns LinkStatus.NOT_LINKED
    assertNull(runRefresh())

    assertNull(store.rebuilt().device)
    assertNull(store.rebuilt().pendingRelayCleanup)
    assertTrue(UnifiedPushLifecycle.isReady())
    verify(exactly = 1) { MollySocketRepository.removeDevice(device) }
  }

  @Test
  fun `failed intent persistence never calls remote unlink`() {
    val store = provisioningStore()
    store.values.enabled = false
    store.values.device = device
    every { MollySocketRepository.getDeviceStatus(device) } returns LinkStatus.LINKED
    store.storage.delegate.nextWriteFailure = IllegalStateException("synthetic intent write failure")

    assertTrue(runRefresh() is KeyValuePersistenceException)

    assertEquals(device, store.rebuilt().device)
    assertNull(store.rebuilt().pendingRelayCleanup)
    assertNull(UnifiedPushAccountTransition.acquireLease())
    verify(exactly = 0) { MollySocketRepository.removeDevice(any()) }
  }

  @Test
  fun `restart retries a linked relay after compensating removal was ambiguous`() {
    val store = provisioningStore()
    val created = MollySocketDevice(deviceId = 23, password = "fresh-relay-credential")
    every { MollySocketRepository.createDevice() } returns created
    every { MollySocketRepository.removeDevice(created) } answers {
      assertEquals(created, store.rebuilt().pendingRelayCleanup?.device)
      assertEquals(false, store.rebuilt().pendingRelayCleanup?.unlinkConfirmed)
      throw IOException("synthetic lost compensation response")
    }
    store.storage.failReadAfterNextWrite = true

    assertTrue(runRefresh() is KeyValuePersistenceException)

    val restarted = store.rebuilt()
    restarted.enabled = false
    every { SignalStore.unifiedpush } returns restarted
    UnifiedPushAccountTransition.initializeFromSignalStore()
    assertNull(UnifiedPushAccountTransition.acquireLease())
    every { MollySocketRepository.getDeviceStatus(created) } returns LinkStatus.LINKED
    every { MollySocketRepository.removeDevice(created) } returns Unit

    assertNull(runRefresh())

    assertNull(store.rebuilt().device)
    assertNull(store.rebuilt().pendingRelayCleanup)
    assertTrue(UnifiedPushLifecycle.isReady())
    verify(exactly = 2) { MollySocketRepository.removeDevice(created) }
  }

  @Test
  fun `restart completes a legacy cleanup without querying or repeating unlink`() {
    val store = provisioningStore()
    store.values.enabled = false
    store.values.device = device
    store.storage.delegate.writeDataSet(KeyValueDataSet().apply {
      putString("mollysocket.cleanup.account", "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
      putInteger("mollysocket.cleanup.localDevice", 1)
      putInteger("mollysocket.cleanup.device", device.deviceId)
      putString("mollysocket.cleanup.password", device.password)
    }, emptyList())
    val restarted = store.rebuilt()
    every { SignalStore.unifiedpush } returns restarted
    UnifiedPushAccountTransition.initializeFromSignalStore()
    assertNull(UnifiedPushAccountTransition.acquireLease())

    assertNull(runRefresh())

    assertNull(store.rebuilt().device)
    assertNull(store.rebuilt().pendingRelayCleanup)
    assertTrue(UnifiedPushLifecycle.isReady())
    verify(exactly = 0) { MollySocketRepository.getDeviceStatus(any()) }
    verify(exactly = 0) { MollySocketRepository.removeDevice(any()) }
  }

  private class ProvisioningStore(val storage: AckFailingStorage, val executor: ControlledExecutor, val values: UnifiedPushValues) {
    fun rebuilt() = UnifiedPushValues(KeyValueStore(storage.delegate, Executor { it.run() }))
  }

  /** A real store for the relay values, ready for the refresh job to create a device and register it with the relay. */
  private fun provisioningStore(): ProvisioningStore {
    val storage = AckFailingStorage(SnapshotKeyValuePersistentStorage(KeyValueDataSet()))
    val executor = ControlledExecutor()
    val values = UnifiedPushValues(KeyValueStore(storage, executor))
    values.enabled = true
    values.airGapped = false
    values.mollySocketUrl = "https://molly.example.invalid"
    values.vapidPublicKey = "SYNTHETIC-VAPID"
    values.setEndpointRegistration("https://push.example.invalid/endpoint", "SYNTHETIC-P256DH", "SYNTHETIC-AUTH", "fixture-instance")
    every { SignalStore.unifiedpush } returns values

    mockkObject(MollySocketRepository, UnifiedPushDistributor, AppDependencies)
    mockkStatic(AppDependencies::class)
    every { UnifiedPushDistributor.checkIfActive() } returns true
    every { AppDependencies.resetNetwork(any()) } returns Unit
    every { MollySocketRepository.registerDeviceOnServer(any(), any(), any(), any()) } returns null
    return ProvisioningStore(storage, executor, values)
  }

  private fun newRefreshJob() = UnifiedPushRefreshJob(testPing = false, fromNewEndpoint = true)
    .apply { setContext(ApplicationProvider.getApplicationContext()) }

  private fun runRefresh(): Throwable? {
    val executor = Executors.newSingleThreadExecutor()
    return try {
      executor.submit<Throwable?> { runCatching { newRefreshJob().onRun() }.exceptionOrNull() }.get(5, TimeUnit.SECONDS)
    } finally {
      executor.shutdownNow()
    }
  }

  /** Runs writes inline unless told to reject the next one or hold it behind [gate] on another thread. */
  private class ControlledExecutor : Executor {
    @Volatile var rejectNext = false

    @Volatile var gate: CountDownLatch? = null
    val held = CountDownLatch(1)

    override fun execute(command: Runnable) {
      if (rejectNext) {
        rejectNext = false
        throw RejectedExecutionException("synthetic executor rejection")
      }
      val waitFor = gate ?: return command.run()
      gate = null
      Thread {
        held.countDown()
        check(waitFor.await(5, TimeUnit.SECONDS))
        command.run()
      }.start()
    }
  }

  /** Lets a write reach storage and then fails the store's re-read, so the store cannot acknowledge it. */
  private class AckFailingStorage(val delegate: SnapshotKeyValuePersistentStorage) : KeyValuePersistentStorage {
    @Volatile var failReadAfterNextWrite = false
    @Volatile var beforeWrite: ((KeyValueDataSet) -> Unit)? = null
    @Volatile var afterWrite: ((KeyValueDataSet) -> Unit)? = null

    @Volatile private var failNextRead = false

    override fun writeDataSet(dataSet: KeyValueDataSet, removes: Collection<String>) {
      beforeWrite?.invoke(dataSet)
      delegate.writeDataSet(dataSet, removes)
      if (failReadAfterNextWrite) {
        failReadAfterNextWrite = false
        failNextRead = true
      }
      afterWrite?.invoke(dataSet)
    }

    override fun getDataSet(): KeyValueDataSet {
      if (failNextRead) {
        failNextRead = false
        throw IllegalStateException("synthetic acknowledgement failure")
      }
      return delegate.getDataSet()
    }
  }

  private fun runCleanup(): Throwable? {
    val executor = Executors.newSingleThreadExecutor()
    return try {
      executor.submit<Throwable?> {
        val job = UnifiedPushRefreshJob().apply { setContext(ApplicationProvider.getApplicationContext()) }
        runCatching { job.onRun() }.exceptionOrNull()
      }.get(5, TimeUnit.SECONDS)
    } finally {
      executor.shutdownNow()
    }
  }

  private fun newControlJob() = UnifiedPushControlJob.Factory().create(Job.Parameters.Builder().build(), null)
}
