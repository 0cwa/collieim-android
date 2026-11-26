package im.molly.unifiedpush.receiver

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import im.molly.unifiedpush.components.settings.app.notifications.PairingV1Coordinator
import im.molly.unifiedpush.components.settings.app.notifications.PairingReceiptV4State
import im.molly.unifiedpush.components.settings.app.notifications.matchingV4ReceiptState
import im.molly.unifiedpush.components.settings.app.notifications.ownsCurrentV4PairingRoute
import im.molly.unifiedpush.components.settings.app.notifications.hasLivePendingV4PairingConfirmation
import im.molly.unifiedpush.UnifiedPushDistributor
import im.molly.unifiedpush.UnifiedPushNotificationBuilder
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.mockkObject
import io.mockk.unmockkConstructor
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.jobs.UnifiedPushRefreshJob
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.keyvalue.UnifiedPushValues
import org.thoughtcrime.securesms.notifications.NotificationChannels
import org.thoughtcrime.securesms.service.KeyCachingService
import org.thoughtcrime.securesms.testutil.MockAppDependenciesRule
import org.thoughtcrime.securesms.testutil.MockSignalStoreRule
import org.thoughtcrime.securesms.testutil.SystemOutLogger
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.UnifiedPush
import org.unifiedpush.android.connector.data.PublicKeySet
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage
import org.thoughtcrime.securesms.gcm.PushFetchHandler
import org.thoughtcrime.securesms.jobmanager.Job
import im.molly.unifiedpush.model.RegistrationStatus
import im.molly.unifiedpush.control.ControlUpdate
import im.molly.unifiedpush.control.ControlFrame
import im.molly.unifiedpush.control.ControlFrameV4
import im.molly.unifiedpush.control.EndpointReconciler
import im.molly.unifiedpush.model.MollySocketDevice
import im.molly.unifiedpush.lifecycle.UnifiedPushLifecycle
import im.molly.unifiedpush.lifecycle.UnifiedPushAccountTransition
import io.mockk.spyk
import org.signal.storage.keyvalue.KeyValueDataSet
import org.signal.storage.keyvalue.KeyValueStore
import org.signal.storage.keyvalue.KeyValuePersistenceException
import org.signal.storage.keyvalue.PlainTextKeyValueStore
import org.signal.storage.keyvalue.MockKeyValuePersistentStorage
import org.thoughtcrime.securesms.testutil.SnapshotKeyValuePersistentStorage
import im.molly.unifiedpush.control.ControlState
import im.molly.unifiedpush.control.ControlStateRead
import im.molly.unifiedpush.control.EndpointUpdates
import im.molly.unifiedpush.control.ImportIntent
import im.molly.unifiedpush.control.LegacyPending
import im.molly.unifiedpush.control.Target
import im.molly.unifiedpush.control.ReceiverControlState
import im.molly.unifiedpush.enrollment.ControlKeyId
import im.molly.unifiedpush.enrollment.EnrollmentExporter
import im.molly.unifiedpush.enrollment.ReceiverEnrollmentV4
import im.molly.unifiedpush.enrollment.EnrollmentFile
import im.molly.unifiedpush.enrollment.ExportMode
import im.molly.unifiedpush.enrollment.PairingDistributorRegistration
import im.molly.unifiedpush.enrollment.PairingRegistrationGate
import im.molly.unifiedpush.enrollment.PairingTransport
import im.molly.unifiedpush.enrollment.PairingV1
import im.molly.unifiedpush.enrollment.RelayDisclosure
import org.thoughtcrime.securesms.jobs.UnifiedPushControlJob
import im.molly.unifiedpush.components.settings.app.notifications.updateMollySocket
import org.signal.core.models.ServiceId
import kotlinx.coroutines.runBlocking
import java.util.concurrent.Executor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import com.fasterxml.jackson.databind.ObjectMapper

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class UnifiedPushReceiverTest {
  @get:Rule val dependencies = MockAppDependenciesRule()
  @get:Rule val signalStore = MockSignalStoreRule()

  // A real values object over an in-memory store, so the control record is the real one; a spy lets tests stub and verify.
  private val values = spyk(UnifiedPushValues(KeyValueStore(MockKeyValuePersistentStorage.withDataSet(KeyValueDataSet()))))
  private val context: Application get() = ApplicationProvider.getApplicationContext()
  private val healthStore = object : im.molly.unifiedpush.health.PushHealth.HealthStore {
    override var failures = 0
    override var fallback = false
    override var lastResult: String? = null
    override var lastResultAt = 0L
    override var lastMissAt = 0L
  }
  private var failed = false
  private var retryScheduled = false
  private var accountIdentityId: String? = null
  private var lockedPushStateMocked = false

  @Before
  fun setUp() {
    Log.initialize(SystemOutLogger())
    UnifiedPushLifecycle.resetForTests()
    mockkStatic(KeyCachingService::class)
    mockkStatic(NotificationChannels::class)
    every { NotificationChannels.supported() } returns false
    every { KeyCachingService.isLocked() } returns false
    every { SignalStore.unifiedpush } returns values
    every { values.registrationFailed } answers { failed }
    every { values.registrationFailed = any() } answers { failed = firstArg() }
    every { values.registrationRetryScheduled } answers { retryScheduled }
    every { values.registrationRetryScheduled = any() } answers { retryScheduled = firstArg() }
    every { values.health } returns healthStore
    every { AppDependencies.jobManager.add(any<UnifiedPushRefreshJob>()) } returns Unit
    mockkConstructor(UnifiedPushNotificationBuilder::class)
    every { anyConstructed<UnifiedPushNotificationBuilder>().setNotificationRegistrationFailed() } returns Unit
    every { anyConstructed<UnifiedPushNotificationBuilder>().clearRegistrationFailed() } returns Unit
    context.getSystemService(NotificationManager::class.java).cancelAll()
  }

  @After
  fun tearDown() {
    accountIdentityId = null
    if (lockedPushStateMocked) {
      unmockkObject(LockedPushState)
      unmockkStatic(LockedPushState::class)
      lockedPushStateMocked = false
    }
    unmockkStatic(UnifiedPush::class)
    unmockkConstructor(UnifiedPushNotificationBuilder::class)
    unmockkStatic(KeyCachingService::class)
    unmockkStatic(NotificationChannels::class)
  }

  @Test
  fun `repeated failures schedule only one recovery job and remain visible`() {
    val receiver = UnifiedPushReceiver()
    receiver.onRegistrationFailed(context, FailedReason.NETWORK, "instance")
    receiver.onRegistrationFailed(context, FailedReason.ACTION_REQUIRED, "instance")

    org.junit.Assert.assertTrue(failed)
    org.junit.Assert.assertTrue(retryScheduled)
    verify(exactly = 1) { AppDependencies.jobManager.add(any<UnifiedPushRefreshJob>()) }
    verify(exactly = 2) { anyConstructed<UnifiedPushNotificationBuilder>().setNotificationRegistrationFailed() }
  }

  @Test
  fun `successful unchanged endpoint resets failure and permits a later retry`() {
    fakeAccount()
    mockPendingEndpointStorage()
    val receiver = UnifiedPushReceiver()
    val endpoint = mockk<PushEndpoint>()
    every { endpoint.url } returns "https://example.org/push"
    every { endpoint.pubKeySet } returns null
    every { values.endpoint } returns endpoint.url
    receiver.onRegistrationFailed(context, FailedReason.NETWORK, "instance")
    receiver.onNewEndpoint(context, endpoint, "instance")
    awaitLifecycleReady()
    org.junit.Assert.assertFalse(failed)
    org.junit.Assert.assertFalse(retryScheduled)
    receiver.onRegistrationFailed(context, FailedReason.NETWORK, "instance")

    // Two recovery jobs and one endpoint refresh, even though the endpoint did not change.
    verify(timeout = 3000, exactly = 3) { AppDependencies.jobManager.add(any<UnifiedPushRefreshJob>()) }
  }

  @Test
  fun `new endpoint stores one coherent connector tuple for enrollment export`() {
    fakeAccount()
    mockPendingEndpointStorage()
    val receiver = UnifiedPushReceiver()
    every { values.endpoint } returns null
    every { values.endpoint = any() } returns Unit
    every { values.airGapped } returns false
    val endpoint = PushEndpoint("https://example.org/push", PublicKeySet("pub-key", "auth-secret"))

    receiver.onNewEndpoint(context, endpoint, "default")

    verify(timeout = 3000) { values.setEndpointRegistration("https://example.org/push", "pub-key", "auth-secret", "default") }
    awaitLifecycleReady()
  }

  @Test
  fun `first endpoint resumes MollySocket setup without a control key`() {
    fakeAccount()
    values.device = null
    mockPendingEndpointStorage()
    val endpoint = PushEndpoint("https://push.example.invalid/first", null)

    UnifiedPushReceiver().onNewEndpoint(context, endpoint, "default")

    verify(timeout = 3000, exactly = 1) {
      AppDependencies.jobManager.add(match<UnifiedPushRefreshJob> {
        val data = org.thoughtcrime.securesms.jobmanager.JsonJobData.deserialize(it.serialize())
        !data.getBoolean("ping") && data.getBoolean("fromNewEndpoint")
      })
    }
    assertEquals(endpoint.url, values.endpoint)
    assertNull(values.controlState.key)
    controlJobAdds(0)
    awaitLifecycleReady()
  }

  @Test
  fun `rotated legacy endpoint schedules one refresh after registration recovery`() {
    fakeAccount()
    values.endpoint = "https://push.example.invalid/old"
    failed = true
    retryScheduled = true
    mockPendingEndpointStorage()
    val endpoint = PushEndpoint("https://push.example.invalid/new", null)

    UnifiedPushReceiver().onNewEndpoint(context, endpoint, "default")

    verify(timeout = 3000, exactly = 1) { AppDependencies.jobManager.add(any<UnifiedPushRefreshJob>()) }
    assertEquals(endpoint.url, values.endpoint)
    assertFalse(failed)
    assertFalse(retryScheduled)
    controlJobAdds(0)
    awaitLifecycleReady()
  }

  @Test
  fun `unchanged endpoint keeps in-flight push work authorized without registering again`() {
    fakeAccount()
    every { values.enabled } returns true
    values.setEndpointRegistration("https://push.example.invalid/current", "public-key", "auth-key", "default")
    mockPendingEndpointStorage()
    val lease = checkNotNull(UnifiedPushAccountTransition.acquireLease())

    try {
      UnifiedPushReceiver().onNewEndpoint(context, PushEndpoint(values.endpoint!!, PublicKeySet("public-key", "auth-key")), "default")

      assertTrue(UnifiedPushLifecycle.isCurrentGeneration(lease.generation))
      assertNotNull(UnifiedPushAccountTransition.acquireFetchLease(UnifiedPushAccountTransition.fetchAuthorization(lease))?.also { it.close() })
      verify(exactly = 0) { LockedPushState.savePending(any(), any()) }
      verify(exactly = 0) { AppDependencies.jobManager.add(any<UnifiedPushRefreshJob>()) }
    } finally {
      lease.close()
    }
  }

  @Test
  fun `legacy default instance callback does not revoke an existing lease`() {
    fakeAccount()
    values.setEndpointRegistration("https://push.example.invalid/current", null, null, null)
    mockPendingEndpointStorage()
    val lease = checkNotNull(UnifiedPushAccountTransition.acquireLease())

    try {
      UnifiedPushReceiver().onNewEndpoint(context, PushEndpoint(values.endpoint!!, null), "default")

      assertTrue(UnifiedPushLifecycle.isCurrentGeneration(lease.generation))
      assertEquals("default", values.endpointInstance)
      verify(exactly = 0) { LockedPushState.savePending(any(), any()) }
      verify(exactly = 0) { AppDependencies.jobManager.add(any<UnifiedPushRefreshJob>()) }
    } finally {
      lease.close()
    }
  }

  @Test
  fun `unchanged callback still completes pending endpoint work`() {
    fakeAccount()
    val endpoint = LockedPushState.Endpoint("https://push.example.invalid/current", "public-key", "auth-key", "default")
    values.setEndpointRegistration(endpoint.url, endpoint.p256dh, endpoint.auth, endpoint.instance)
    val pending = mockPendingEndpointStorage(LockedPushState.Endpoint(null, null, null, "default"))
    val lease = checkNotNull(UnifiedPushAccountTransition.acquireLease())

    try {
      UnifiedPushReceiver().onNewEndpoint(context, PushEndpoint(endpoint.url!!, PublicKeySet(endpoint.p256dh!!, endpoint.auth!!)), endpoint.instance)

      assertFalse("pending work must not be bypassed just because the stored tuple matches", UnifiedPushLifecycle.isCurrentGeneration(lease.generation))
      assertEquals("the latest callback replaces the pending tuple", endpoint, pending.get())
    } finally {
      lease.close()
    }
    awaitLifecycleReady()
    assertNull(pending.get())
    assertEquals(endpoint.url, values.endpoint)
  }

  @Test
  fun `changed URL revokes old work before applying the endpoint`() {
    assertEndpointChangeRevokesWork(LockedPushState.Endpoint("https://push.example.invalid/new", "public-key", "auth-key", "default"))
  }

  @Test
  fun `changed encryption key revokes old work even when the URL is unchanged`() {
    assertEndpointChangeRevokesWork(LockedPushState.Endpoint("https://push.example.invalid/current", "new-public-key", "auth-key", "default"))
  }

  @Test
  fun `changed authentication key revokes old work even when the URL is unchanged`() {
    assertEndpointChangeRevokesWork(LockedPushState.Endpoint("https://push.example.invalid/current", "public-key", "new-auth-key", "default"))
  }

  @Test
  fun `changed instance revokes old work even when the URL is unchanged`() {
    assertEndpointChangeRevokesWork(LockedPushState.Endpoint("https://push.example.invalid/current", "public-key", "auth-key", "new-instance"))
  }

  @Test
  fun `same URL key rotation resets air gapped delivery and publishes effects once`() {
    fakeAccount()
    val url = "https://push.example.invalid/current"
    values.setEndpointRegistration(url, fixtureP256dh, fixtureAuth, "default")
    every { values.airGapped } returns true
    values.lastReceivedTime = 1234L
    val rotated = LockedPushState.Endpoint(url, fixtureP256dh, "rotated-auth", "default")
    mockPendingEndpointStorage(rotated)
    every { anyConstructed<UnifiedPushNotificationBuilder>().setNotificationEndpointChangedAirGapped() } returns Unit

    val committed = checkNotNull(UnifiedPushReceiver.commitPendingEndpoint(context))
    UnifiedPushReceiver.publishPendingEndpointEffects(context, committed)
    UnifiedPushReceiver().onNewEndpoint(context, PushEndpoint(url, PublicKeySet(fixtureP256dh, "rotated-auth")), "default")

    assertEquals(0L, values.lastReceivedTime)
    assertEquals("rotated-auth", values.endpointAuth)
    verify(exactly = 1) { anyConstructed<UnifiedPushNotificationBuilder>().setNotificationEndpointChangedAirGapped() }
    verify(exactly = 1) { AppDependencies.jobManager.add(any<UnifiedPushRefreshJob>()) }
  }

  @Test
  fun `active keyed air gapped enrollment skips the stock MollySocket manual update warning`() {
    fakeAccount()
    val url = "https://push.example.invalid/current"
    values.setEndpointRegistration(url, fixtureP256dh, fixtureAuth, "default")
    every { values.airGapped } returns true
    values.mutateControl { ControlState(key = fixtureKey, activeEpoch = 1, highestEpoch = 1) }
    val rotated = LockedPushState.Endpoint(url, fixtureP256dh, "rotated-auth", "default")
    mockPendingEndpointStorage(rotated)
    every { anyConstructed<UnifiedPushNotificationBuilder>().setNotificationEndpointChangedAirGapped() } returns Unit

    val committed = checkNotNull(UnifiedPushReceiver.commitPendingEndpoint(context))
    assertNull(checkNotNull(committed.value).warning)
    assertEquals("rotated-auth", values.endpointAuth)
    UnifiedPushReceiver.publishPendingEndpointEffects(context, committed)

    verify(exactly = 0) { anyConstructed<UnifiedPushNotificationBuilder>().setNotificationEndpointChangedAirGapped() }
  }

  @Test
  fun `a confirmed route rejects a replacement with an invalid origin at commit`() {
    fakeAccount()
    val account = checkNotNull(accountIdentityId)
    val owner = "org.example.distributor"
    val oldUrl = "https://push.example.test/current"
    val binding = UnifiedPushValues.PairingEndpointBinding(
      "default", owner, "vapid", null, "https://push.example.test", "session", Long.MAX_VALUE, account, 1, "Relay",
    )
    values.setPairingEndpointRegistration(oldUrl, fixtureP256dh, fixtureAuth, binding)
    values.mutateControl {
      ControlState(
        key = fixtureKey,
        activeKeyId = ControlKeyId.derive(fixtureKey, account, 2, "default"),
        activeImportId = "22222222-2222-4222-8222-222222222222",
        activeImportSeq = 1,
        activeAccountId = account,
        activeDeviceId = 2,
        activeInstance = "default",
        activeEpoch = 1,
        highestEpoch = 1,
        importSequence = 1,
      )
    }
    val valid = LockedPushState.Endpoint("https://push.example.test/rotated", fixtureP256dh, fixtureAuth, "default")
    val invalid = valid.copy(url = "https://user@push.example.test/rotated")
    val pending = mockPendingEndpointStorage(valid)
    every { LockedPushState.setProtectedPairingRoute(context, any(), any()) } returns Unit
    every { LockedPushState.peekPending(context) } answers {
      pending.get()?.let { if (UnifiedPushLifecycle.isReady()) it else invalid }
    }
    mockkStatic(UnifiedPush::class)
    try {
      every { UnifiedPush.getSavedDistributor(context) } returns owner
      every { UnifiedPush.getAckDistributor(context) } returns owner
      assertTrue(UnifiedPushReceiver().pairingIdentityConfirmed(binding))

      assertNull(checkNotNull(UnifiedPushReceiver.commitPendingEndpoint(context)).value)
      assertEquals(binding, values.pairingEndpointBinding)
      assertEquals(oldUrl, values.endpoint)
      assertNull(pending.get())
      assertTrue(UnifiedPushLifecycle.isReady())
    } finally {
      unmockkStatic(UnifiedPush::class)
    }
  }

  private fun assertEndpointChangeRevokesWork(endpoint: LockedPushState.Endpoint) {
    fakeAccount()
    values.setEndpointRegistration("https://push.example.invalid/current", "public-key", "auth-key", "default")
    val pending = mockPendingEndpointStorage()
    val lease = checkNotNull(UnifiedPushAccountTransition.acquireLease())

    try {
      UnifiedPushReceiver().onNewEndpoint(context, PushEndpoint(endpoint.url!!, PublicKeySet(endpoint.p256dh!!, endpoint.auth!!)), endpoint.instance)

      assertFalse(UnifiedPushLifecycle.isCurrentGeneration(lease.generation))
      assertNull(UnifiedPushAccountTransition.acquireFetchLease(UnifiedPushAccountTransition.fetchAuthorization(lease)))
      assertEquals(endpoint, pending.get())
      assertEquals("https://push.example.invalid/current", values.endpoint)
      assertEquals("public-key", values.endpointP256dh)
      assertEquals("auth-key", values.endpointAuth)
      assertEquals("default", values.endpointInstance)
    } finally {
      lease.close()
    }
    awaitLifecycleReady()
    assertEquals(endpoint.url, values.endpoint)
    assertEquals(endpoint.p256dh, values.endpointP256dh)
    assertEquals(endpoint.auth, values.endpointAuth)
    assertEquals(endpoint.instance, values.endpointInstance)
  }

  @Test
  fun `unregistering clears the stored connector keys`() {
    fakeAccount()
    mockPendingEndpointStorage()
    mockkStatic(UnifiedPush::class)
    try {
      every { UnifiedPush.forceRemoveDistributor(any()) } returns Unit
      every { UnifiedPush.getSavedDistributor(context) } returns "org.example.distributor"
      every { UnifiedPush.getAckDistributor(context) } returns "org.example.distributor"
      values.setEndpointRegistration("https://example.org/push", "public-key", "auth-key", "default")

      UnifiedPushReceiver().onUnregistered(context, "default")

      verify(timeout = 3000) { values.setEndpointRegistration(null, null, null, null) }
      verify(timeout = 3000) { UnifiedPush.forceRemoveDistributor(context) }
      verifyOrder {
        values.setEndpointRegistration(null, null, null, null)
        UnifiedPush.forceRemoveDistributor(context)
      }
      verify(exactly = 0) { AppDependencies.jobManager.add(any<UnifiedPushRefreshJob>()) }
      awaitLifecycleReady()
      assertNull(values.endpoint)
    } finally {
      unmockkStatic(UnifiedPush::class)
    }
  }

  @Test
  fun `pending endpoint effects are dropped after a replacement account transition`() {
    fakeAccount()
    values.endpoint = "https://push.example.invalid/old"
    values.airGapped = true
    every { values.airGapped } returns true
    values.lastReceivedTime = 1234L
    mockPendingEndpointStorage(
      LockedPushState.Endpoint("https://push.example.invalid/new", fixtureP256dh, fixtureAuth, "default"),
    )
    val receiver = UnifiedPushReceiver()
    receiver.onRegistrationFailed(context, FailedReason.NETWORK, "default")

    val committed = checkNotNull(UnifiedPushReceiver.commitPendingEndpoint(context))
    val effects = checkNotNull(committed.value)
    assertTrue(effects.failed)
    assertEquals("local timestamp reset is committed with the endpoint tuple", 0L, values.lastReceivedTime)

    fakeAccount("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")
    runBlocking {
      UnifiedPushLifecycle.withTransition(
        persistIncompleteMarker = {},
        readyAfter = { true },
      ) { Unit }
    }
    UnifiedPushReceiver().onRegistrationFailed(context, FailedReason.NETWORK, "replacement")

    UnifiedPushReceiver.publishPendingEndpointEffects(context, committed)

    assertTrue("the replacement account's failure remains visible", failed)
    verify(exactly = 0) { anyConstructed<UnifiedPushNotificationBuilder>().clearRegistrationFailed() }
  }

  @Test
  fun `only the air gapped warning survives a credential only transition`() {
    fakeAccount()
    values.endpoint = "https://push.example.invalid/old"
    every { values.airGapped } returns true
    mockPendingEndpointStorage(LockedPushState.Endpoint("https://push.example.invalid/new", fixtureP256dh, fixtureAuth, "default"))
    UnifiedPushReceiver().onRegistrationFailed(context, FailedReason.NETWORK, "default")
    every { anyConstructed<UnifiedPushNotificationBuilder>().setNotificationEndpointChangedAirGapped() } returns Unit
    val committed = checkNotNull(UnifiedPushReceiver.commitPendingEndpoint(context))
    val effects = checkNotNull(committed.value)
    assertTrue(effects.failed)
    assertNotNull(effects.warning)

    synchronized(UnifiedPushLifecycle.monitor) { UnifiedPushLifecycle.invalidateLocked(credentialOnly = true) }
    val invalidation = UnifiedPushLifecycle.currentGeneration()
    UnifiedPushReceiver.publishPendingEndpointEffects(context, committed)
    verify(exactly = 0) { anyConstructed<UnifiedPushNotificationBuilder>().setNotificationEndpointChangedAirGapped() }

    runBlocking {
      UnifiedPushLifecycle.withTransitionAndGeneration(
        persistIncompleteMarker = {},
        readyAfter = { true },
        invalidationGenerationToComplete = invalidation,
        credentialOnly = true,
      ) { values.device = null }
    }
    UnifiedPushReceiver.applyPendingEndpoint(context)

    verify(timeout = 3000, exactly = 1) { anyConstructed<UnifiedPushNotificationBuilder>().setNotificationEndpointChangedAirGapped() }
    verify(exactly = 0) { anyConstructed<UnifiedPushNotificationBuilder>().clearRegistrationFailed() }
  }

  @Test
  fun `a credential only transition cannot preserve a warning for different route keys`() {
    fakeAccount()
    values.endpoint = "https://push.example.invalid/old"
    every { values.airGapped } returns true
    mockPendingEndpointStorage(LockedPushState.Endpoint("https://push.example.invalid/new", fixtureP256dh, fixtureAuth, "default"))
    every { anyConstructed<UnifiedPushNotificationBuilder>().setNotificationEndpointChangedAirGapped() } returns Unit
    val committed = checkNotNull(UnifiedPushReceiver.commitPendingEndpoint(context))
    assertNotNull(committed.value?.warning)

    runBlocking {
      UnifiedPushLifecycle.withTransitionAndGeneration(
        persistIncompleteMarker = {},
        readyAfter = { true },
        credentialOnly = true,
      ) { values.device = null }
    }
    values.endpointAuth = "different-route-auth"
    UnifiedPushReceiver.publishPendingEndpointEffects(context, committed)

    verify(exactly = 0) { anyConstructed<UnifiedPushNotificationBuilder>().setNotificationEndpointChangedAirGapped() }
  }

  @Test
  fun `push admission and endpoint application defer across account transition`() {
    fakeAccount()
    values.endpoint = "https://example.org/old"
    mockkStatic(UnifiedPush::class)
    every { UnifiedPush.getSavedDistributor(context) } returns "org.example.distributor"
    every { UnifiedPush.getAckDistributor(context) } returns "org.example.distributor"
    every { UnifiedPush.forceRemoveDistributor(context) } returns Unit
    mockkStatic(PushFetchHandler::class)
    every { PushFetchHandler.handleReceivedNotification(any(), any(), any()) } returns Unit
    val pending = mockPendingEndpointStorage()
    val transitionStarted = CountDownLatch(1)
    val finishTransition = CountDownLatch(1)
    val transitionFinished = CountDownLatch(1)
    val transitionFailure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
    Thread {
      try {
        runBlocking {
          UnifiedPushLifecycle.withTransition(
            persistIncompleteMarker = {},
            readyAfter = { true },
          ) {
            transitionStarted.countDown()
            check(finishTransition.await(5, TimeUnit.SECONDS))
          }
        }
      } catch (t: Throwable) {
        transitionFailure.set(t)
      } finally {
        transitionFinished.countDown()
      }
    }.start()

    try {
      assertTrue(transitionStarted.await(5, TimeUnit.SECONDS))
      UnifiedPushReceiver().onMessage(context, urgent(), "default")
      verify(exactly = 0) { PushFetchHandler.handleReceivedNotification(any(), any(), any()) }

      val endpoint = PushEndpoint("https://example.org/new", PublicKeySet("new-pub", "new-auth"))
      UnifiedPushReceiver().onNewEndpoint(context, endpoint, "default")
      UnifiedPushReceiver().onUnregistered(context, "default")
      assertEquals("https://example.org/old", values.endpoint)

      finishTransition.countDown()
      assertTrue(transitionFinished.await(5, TimeUnit.SECONDS))
      assertEquals(null, transitionFailure.get())
      UnifiedPushReceiver.applyPendingEndpoint(context)

      verify(timeout = 3000) { values.setEndpointRegistration(null, null, null, null) }
      awaitLifecycleReady()
      assertEquals(null, values.endpoint)
      assertEquals(null, values.endpointP256dh)
      assertEquals(null, values.endpointAuth)
      assertEquals(null, values.endpointInstance)
    } finally {
      finishTransition.countDown()
      unmockkStatic(UnifiedPush::class)
      unmockkStatic(PushFetchHandler::class)
    }
  }

  @Test
  fun `completed coherent account transition hands off an invalidated endpoint`() {
    fakeAccount()
    values.endpoint = "https://push.example.invalid/old"
    val endpoint = "https://push.example.invalid/replacement"
    val pending = mockPendingEndpointStorage(LockedPushState.Endpoint(endpoint, "p", "a", "default"))
    synchronized(UnifiedPushLifecycle.monitor) {
      UnifiedPushLifecycle.invalidateLocked()
    }

    runBlocking { UnifiedPushAccountTransition.withTransition {} }

    verify(timeout = 3000) { values.setEndpointRegistration(endpoint, "p", "a", "default") }
    awaitLifecycleReady()
    assertNull(pending.get())
    assertEquals(endpoint, values.endpoint)
  }

  @Test
  fun `queued endpoint application preserves pending tuple when the app locks before it runs`() {
    fakeAccount()
    val pending = LockedPushState.Endpoint("https://push.example.invalid/pending", "p", "a", "default")
    val lockChecked = CountDownLatch(1)
    mockPendingEndpointStorage(pending)
    mockkStatic(KeyCachingService::class)
    every { KeyCachingService.isLocked() } answers { lockChecked.countDown(); true }

    try {
      UnifiedPushReceiver.applyPendingEndpoint(context)

      assertTrue("the queued callback must inspect lock state", lockChecked.await(5, TimeUnit.SECONDS))
      verify(exactly = 0) { LockedPushState.peekPending(context) }
      verify(exactly = 0) { LockedPushState.clearPending(context) }
      verify(exactly = 0) { values.setEndpointRegistration(any(), any(), any(), any()) }
    } finally {
    }
  }

  @Test
  fun `unregister invalidates accepted work and waits for its lease before clearing the route`() {
    fakeAccount()
    values.endpoint = "https://push.example.invalid/current"
    val pending = mockPendingEndpointStorage()
    mockkStatic(UnifiedPush::class)
    every { UnifiedPush.getSavedDistributor(context) } returns "org.example.distributor"
    every { UnifiedPush.getAckDistributor(context) } returns "org.example.distributor"
    every { UnifiedPush.forceRemoveDistributor(context) } returns Unit
    val lease = UnifiedPushAccountTransition.acquireLease()!!
    val authorization = UnifiedPushAccountTransition.fetchAuthorization(lease)

    try {
      UnifiedPushReceiver().onUnregistered(context, "default")

      assertFalse(UnifiedPushLifecycle.isCurrentGeneration(lease.generation))
      assertNull(UnifiedPushAccountTransition.acquireFetchLease(authorization))
      verify(exactly = 0) { values.setEndpointRegistration(null, null, null, null) }

      lease.close()
      verify(timeout = 3000) { values.setEndpointRegistration(null, null, null, null) }
      awaitLifecycleReady()
      assertEquals(null, pending.get())
    } finally {
      lease.close()
      unmockkStatic(UnifiedPush::class)
    }
  }

  @Test
  fun `locked failure does not access encrypted state or schedule a job`() {
    every { KeyCachingService.isLocked() } returns true
    UnifiedPushReceiver().onRegistrationFailed(context, FailedReason.NETWORK, "instance")

    verify(exactly = 0) { SignalStore.unifiedpush }
    verify(exactly = 0) { AppDependencies.jobManager.add(any<UnifiedPushRefreshJob>()) }
  }

  @Test
  fun `endpoint recovery clears only the registration failure notification`() {
    fakeAccount()
    mockPendingEndpointStorage()
    unmockkConstructor(UnifiedPushNotificationBuilder::class)
    val manager = context.getSystemService(NotificationManager::class.java)
    val receiver = UnifiedPushReceiver()
    val endpoint = mockk<PushEndpoint>()
    every { endpoint.url } returns "https://example.org/push"
    every { endpoint.pubKeySet } returns null
    every { values.endpoint } returns endpoint.url

    receiver.onRegistrationFailed(context, FailedReason.NETWORK, "instance")
    UnifiedPushNotificationBuilder(context).setNotificationMollySocketForbiddenUuid()
    receiver.onNewEndpoint(context, endpoint, "instance")
    awaitLifecycleReady()
    awaitCondition("only the unrelated notification should remain") {
      val current = shadowOf(manager).allNotifications
      current.size == 1 && current.single().extras.getCharSequence(Notification.EXTRA_TEXT).toString() ==
        context.getString(R.string.UnifiedPushNotificationBuilder__mollysocket_forbidden_uuid)
    }

    val notifications = shadowOf(manager).allNotifications
    org.junit.Assert.assertEquals(1, notifications.size)
    org.junit.Assert.assertEquals(
      context.getString(R.string.UnifiedPushNotificationBuilder__mollysocket_forbidden_uuid),
      notifications.single().extras.getCharSequence(Notification.EXTRA_TEXT).toString()
    )
  }

  @Test
  fun `selecting another distributor permits its own automatic recovery`() {
    mockkStatic(UnifiedPush::class)
    try {
      every { UnifiedPush.saveDistributor(context, "distributor.b") } returns Unit
      val receiver = UnifiedPushReceiver()
      receiver.onRegistrationFailed(context, FailedReason.NETWORK, "instance")
      UnifiedPushDistributor.saveDistributor(context, "distributor.b")
      receiver.onRegistrationFailed(context, FailedReason.NETWORK, "instance")

      verify(exactly = 1) { UnifiedPush.saveDistributor(context, "distributor.b") }
      verify(exactly = 2) { AppDependencies.jobManager.add(any<UnifiedPushRefreshJob>()) }
    } finally {
      unmockkStatic(UnifiedPush::class)
    }
  }

  @Test
  fun `cancelled pairing migration leaves the previous distributor and endpoint active`() {
    assertPairingMigrationRollback { binding ->
      PairingV1Coordinator(context).cancelOfferAttempt(binding.sessionId)
    }
  }

  @Test
  fun `linked device rejects pair bridge before registration or credential provisioning`() {
    fakeAccount()
    every { SignalStore.account.isLinkedDevice } returns true
    values.device = null
    val offerNode = ObjectMapper().readTree(
      checkNotNull(javaClass.getResourceAsStream("/colliesocket-pairing-v1/fixtures/sealed-vectors.json")).use { it.readBytes() },
    )[0]["offer"].deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>().put("intent", "pair_bridge")
    val offerJson = """{"collie_pairing_transport":1,"bridge_device_id":2,"offer":$offerNode}"""
    val coordinator = PairingV1Coordinator(context)

    org.junit.Assert.assertThrows(EnrollmentFile.Invalid::class.java) { coordinator.parseScan(offerJson) }
    assertTrue(coordinator.evaluateOffer(PairingV1.parseOffer(offerNode.toString(), 0)).let { it is PairingV1Coordinator.OfferPlan.Failed })
    assertNull(values.device)
    assertNull(PairingRegistrationGate.current())
  }

  @Test
  fun `cancelling a pending v4 replacement preserves the retained active receiver`() {
    fakeAccount()
    val fixture = ObjectMapper().readTree(
      checkNotNull(javaClass.getResourceAsStream("/colliesocket-encrypted-push-v4/fixtures/receiver.json")).use { it.readBytes() },
    )
    val subscription = fixture["subscription"]
    val receiver = ReceiverControlState(
      receiverId = fixture["receiver_id"].asText(), importId = fixture["request_id"].asText(),
      accountId = fixture["account_id"].asText(), bridgeDeviceId = fixture["device_id"].intValue(),
      instance = subscription["instance"].asText(), hostLabel = fixture["label"].asText(),
      controlKey = fixture["control_key"].asText(), epoch = subscription["subscription_epoch"].longValue(),
      target = Target(subscription["endpoint"].asText(), subscription["p256dh"].asText(), subscription["auth"].asText()),
    )
    val active = receiver.copy(importId = ReceiverEnrollmentV4.newUuid(), confirmed = true)
    val candidate = receiver.copy(receiverId = ReceiverEnrollmentV4.newUuid(), importId = ReceiverEnrollmentV4.newUuid())
    val sessionId = candidate.importId
    val confirmation = LockedPushState.PairingConfirmation(
      sessionId = sessionId, instance = receiver.instance, distributor = "org.example.distributor", vapid = "vapid-key",
      providerOrigin = "https://push.example.test", expiresAt = System.currentTimeMillis() / 1000 + 600,
      hostLabel = candidate.hostLabel, importId = candidate.importId, keyId = "", epoch = candidate.epoch,
      scopeDigest = "fixture-scope", receiverId = candidate.receiverId,
    )
    mockkObject(LockedPushState)
    lockedPushStateMocked = true
    every { LockedPushState.peekPairingConfirmation(context) } returns confirmation
    every { LockedPushState.clearPairingConfirmation(context, sessionId) } returns Unit

    enrollment(active = 0, pending = 0) {
      values.v4ReceiverState = active.serialize()
      values.v4PendingReceiverState = candidate.serialize()
      PairingV1Coordinator(context).cancelOfferAttempt(sessionId)

      assertEquals(active.serialize(), values.v4ReceiverState)
      assertNull(values.v4PendingReceiverState)
      unmockkObject(LockedPushState)
      lockedPushStateMocked = false
      assertTrue("the retained v4 receiver remains protected", LockedPushState.isEncryptedEnrollment(context))
      assertEquals(0L, values.controlState.activeEpoch)
      LockedPushState.setEncryptedEnrollment(context, false)
    }
  }

  @Test
  fun `pending v4 firewall is limited to isolated locked route and preserves reused legacy route`() {
    val isolated = LockedPushState.PairingConfirmation(
      sessionId = "55555555-5555-4555-8555-555555555555", instance = "pairing-v1-isolated",
      distributor = "org.example.distributor", vapid = "fixture-vapid", providerOrigin = "https://push.example.invalid",
      expiresAt = System.currentTimeMillis() / 1000 + 600, hostLabel = "Fixture bridge",
      importId = "44444444-4444-4444-8444-444444444444", keyId = "", epoch = 1,
      scopeDigest = "fixture-scope", receiverId = "33333333-3333-4333-8333-333333333333",
    )
    var confirmation = isolated
    var encryptedEnrollment = false
    mockkObject(LockedPushState)
    mockkStatic(LockedPushState::class, org.thoughtcrime.securesms.gcm.FcmFetchManager::class, PlainTextKeyValueStore::class)
    lockedPushStateMocked = true
    every { LockedPushState.recordPushArrived(context, any()) } returns Unit
    every { LockedPushState.peekPairingConfirmation(context) } answers { confirmation }
    every { LockedPushState.isEncryptedEnrollment(context) } answers { encryptedEnrollment }
    every { LockedPushState.setEncryptedEnrollment(context, any()) } answers { encryptedEnrollment = secondArg() }
    every { org.thoughtcrime.securesms.gcm.FcmFetchManager.postMayHaveMessagesNotification(context) } returns Unit
    every { PlainTextKeyValueStore.lockNotificationsEnabled } returns true
    every { KeyCachingService.isLocked() } returns true
    try {
      val receiver = UnifiedPushReceiver()
      val urgentPlaintext = PushMessage("""{"urgent":true}""".toByteArray(), false)
      receiver.onMessage(context, urgentPlaintext, isolated.instance)
      verify(exactly = 0) { org.thoughtcrime.securesms.gcm.FcmFetchManager.postMayHaveMessagesNotification(context) }

      confirmation = isolated.copy(instance = "default")
      receiver.onMessage(context, urgentPlaintext, "default")
      verify(exactly = 1) { org.thoughtcrime.securesms.gcm.FcmFetchManager.postMayHaveMessagesNotification(context) }

      // An already active encrypted policy remains global and continues to reject plaintext.
      encryptedEnrollment = true
      receiver.onMessage(context, urgentPlaintext, "default")
      verify(exactly = 1) { org.thoughtcrime.securesms.gcm.FcmFetchManager.postMayHaveMessagesNotification(context) }
    } finally {
      unmockkStatic(org.thoughtcrime.securesms.gcm.FcmFetchManager::class)
      unmockkStatic(PlainTextKeyValueStore::class)
    }
  }

  @Test
  fun `v4 pairing receipt matches exact pending or already confirmed attempt only`() {
    val target = Target("https://push.example.invalid/receipt", fixtureP256dh, fixtureAuth)
    val expected = ReceiverControlState(
      receiverId = "33333333-3333-4333-8333-333333333333",
      importId = "44444444-4444-4444-8444-444444444444",
      accountId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
      bridgeDeviceId = 2,
      instance = "pairing-instance",
      hostLabel = "Fixture bridge",
      controlKey = fixtureKey,
      epoch = 1,
      target = target,
    )

    assertEquals(PairingReceiptV4State.PENDING, matchingV4ReceiptState(
      expected, expected, null, expected.accountId, expected.instance, target,
    ))
    assertEquals(PairingReceiptV4State.CONFIRMED, matchingV4ReceiptState(
      expected, null, expected.copy(confirmed = true), expected.accountId, expected.instance, target,
    ))
    assertNull(matchingV4ReceiptState(
      expected, expected.copy(importId = "55555555-5555-4555-8555-555555555555"), null,
      expected.accountId, expected.instance, target,
    ))
    assertNull(matchingV4ReceiptState(expected, expected, null, "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", expected.instance, target))
    assertNull(matchingV4ReceiptState(expected, expected, null, expected.accountId, "other-instance", target))
    assertNull(matchingV4ReceiptState(
      expected, expected, null, expected.accountId, expected.instance,
      Target(target.endpoint, target.p256dh, fixtureP256dh),
    ))
  }

  @Test
  fun `abandoned v4 pairing owns a route only when its durable binding and connector tuple still match`() {
    val pending = ReceiverControlState(
      receiverId = "33333333-3333-4333-8333-333333333333",
      importId = "44444444-4444-4444-8444-444444444444",
      accountId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
      bridgeDeviceId = 2,
      instance = "default",
      hostLabel = "Fixture bridge",
      controlKey = fixtureKey,
      epoch = 1,
      target = Target("https://push.example.invalid/attempt", fixtureP256dh, fixtureAuth),
    )
    val confirmation = LockedPushState.PairingConfirmation(
      sessionId = "55555555-5555-4555-8555-555555555555", instance = pending.instance,
      distributor = "org.example.distributor", vapid = "fixture-vapid", providerOrigin = "https://push.example.invalid",
      expiresAt = Long.MAX_VALUE, hostLabel = "Fixture bridge", importId = pending.importId, keyId = "", epoch = pending.epoch,
      scopeDigest = "fixture-scope", receiverId = pending.receiverId,
    )
    val binding = UnifiedPushValues.PairingEndpointBinding(
      pending.instance, confirmation.distributor, confirmation.vapid, confirmation.providerOrigin,
      "https://push.example.invalid", confirmation.sessionId, confirmation.expiresAt, pending.accountId, 1,
      "Fixture bridge", pending.importId,
    )

    assertTrue(ownsCurrentV4PairingRoute(binding, confirmation, pending, 1,
      pending.target.endpoint, pending.target.p256dh, pending.target.auth, "default"))
    assertFalse("a rotated endpoint on the reused default route is not the abandoned attempt",
      ownsCurrentV4PairingRoute(binding, confirmation, pending, 1,
        "https://push.example.invalid/rotated", fixtureP256dh, fixtureAuth, "default"))
    assertFalse("the instance alone cannot transfer ownership",
      ownsCurrentV4PairingRoute(null, confirmation, pending, 1,
        "https://push.example.invalid/rotated", fixtureP256dh, fixtureAuth, "default"))
  }

  @Test
  fun `retained v4 approval remains live after process restart and cannot be overwritten`() {
    fakeAccount()
    val owner = "org.example.distributor"
    val endpoint = "https://push.example.test/retained-v4"
    val instance = "default"
    val receiver = ReceiverControlState(
      receiverId = "33333333-3333-4333-8333-333333333333",
      importId = "44444444-4444-4444-8444-444444444444",
      accountId = checkNotNull(accountIdentityId),
      bridgeDeviceId = 2,
      instance = instance,
      hostLabel = "Fixture bridge",
      controlKey = fixtureKey,
      epoch = 1,
      target = Target(endpoint, fixtureP256dh, fixtureAuth),
    )
    values.setEndpointRegistration(endpoint, fixtureP256dh, fixtureAuth, instance)
    values.vapidPublicKey = "fixture-vapid"
    values.v4PendingReceiverState = receiver.serialize()
    mockkStatic(UnifiedPush::class)
    every { UnifiedPush.getDistributors(context) } returns listOf(owner)
    every { UnifiedPush.getSavedDistributor(context) } returns owner
    every { UnifiedPush.getAckDistributor(context) } returns owner
    val scope = checkNotNull(RelayDisclosure.capture(context))
    val confirmation = LockedPushState.PairingConfirmation(
      sessionId = "55555555-5555-4555-8555-555555555555", instance = instance, distributor = owner,
      vapid = "fixture-vapid", providerOrigin = "https://push.example.test",
      expiresAt = System.currentTimeMillis() / 1000 + 600, hostLabel = receiver.hostLabel,
      importId = receiver.importId, keyId = "", epoch = receiver.epoch,
      scopeDigest = RelayDisclosure.pairingScopeDigest(scope), receiverId = receiver.receiverId,
    )
    mockkObject(LockedPushState)
    mockkStatic(LockedPushState::class)
    lockedPushStateMocked = true
    every { LockedPushState.peekPairingConfirmation(context) } returns confirmation
    every { LockedPushState.clearPairingConfirmation(context, confirmation.sessionId) } returns Unit
    try {
      assertNull("no in-memory registration survives process death", PairingRegistrationGate.current())
      assertTrue(synchronized(UnifiedPushLifecycle.monitor) { hasLivePendingV4PairingConfirmation(context) })
      val secondOffer = freshPairingOffer().copy(
        sessionId = ReceiverEnrollmentV4.newUuid(), vapidPublicKey = "fixture-vapid", intent = "add_receiver",
      )
      mockkObject(PairingV1)
      every { PairingV1.seal(secondOffer, any(), endpoint) } returns
        PairingV1.SealedRequest("{}", ByteArray(32), byteArrayOf(), byteArrayOf(), byteArrayOf())
      val coordinator = PairingV1Coordinator(context)
      val consent = checkNotNull(coordinator.consentDetails(secondOffer))
      val secondResult = coordinator.sealAfterConsent(secondOffer, scope, consent, null, false, bridgeDeviceId = 2)
      assertTrue("a second approval is refused while the retained v4 approval remains live",
        secondResult is PairingV1Coordinator.SealResult.Failed)
      verify(exactly = 0) { PairingV1.seal(secondOffer, any(), endpoint) }
      assertEquals("the retained pending identity is still the approved one", receiver.serialize(), values.v4PendingReceiverState)
      verify(exactly = 0) { LockedPushState.savePairingConfirmation(context, match { it.sessionId == secondOffer.sessionId }) }
      verify(exactly = 0) { LockedPushState.clearPairingConfirmation(context, confirmation.sessionId) }
      coordinator.clear()
      unmockkObject(PairingV1)
    } finally {
      unmockkStatic(UnifiedPush::class)
    }
  }

  @Test
  fun `interrupted v4 promotion repairs connector selection only while prior selection remains live`() {
    fakeAccount()
    val previousDistributor = "org.example.old-distributor"
    val distributor = "org.example.new-distributor"
    val instance = "pairing-v1-promotion"
    val endpoint = "https://push.example.invalid/promoted"
    val importId = "44444444-4444-4444-8444-444444444444"
    val sessionId = "55555555-5555-4555-8555-555555555555"
    val receiverId = "33333333-3333-4333-8333-333333333333"
    val now = System.currentTimeMillis() / 1000
    val target = Target(endpoint, fixtureP256dh, fixtureAuth)
    values.setPairingEndpointRegistration(
      endpoint, fixtureP256dh, fixtureAuth,
      UnifiedPushValues.PairingEndpointBinding(
        instance, distributor, "fixture-vapid", "https://push.example.invalid", "https://push.example.invalid",
        sessionId, now + 600, checkNotNull(accountIdentityId), 1, "Fixture bridge", importId,
      ),
    )
    values.enabled = true
    values.v4ReceiverState = ReceiverControlState(
      receiverId, importId, checkNotNull(accountIdentityId), 2, instance, "Fixture bridge", fixtureKey, 1,
      target, confirmed = true,
    ).serialize()
    val confirmation = AtomicReference<LockedPushState.PairingConfirmation?>(LockedPushState.PairingConfirmation(
      sessionId, instance, distributor, "fixture-vapid", "https://push.example.invalid", now + 600,
      "Fixture bridge", importId, "", 1, "committed-consent", receiverId = receiverId,
      previousDistributor = previousDistributor, previousInstance = "default",
      previousDeliveryMethod = org.thoughtcrime.securesms.keyvalue.SettingsValues.NotificationDeliveryMethod.UNIFIEDPUSH.name,
    ))
    val connector = context.getSharedPreferences("unifiedpush.connector", Context.MODE_PRIVATE)
    fun selected(id: String) {
      connector.edit().putString("unifiedpush.distributor", id).putBoolean("unifiedpush.distributor_ack", true).commit()
    }
    selected(previousDistributor)
    mockkStatic(UnifiedPush::class)
    mockkObject(LockedPushState)
    mockkStatic(LockedPushState::class)
    lockedPushStateMocked = true
    every { UnifiedPush.getDistributors(context) } returns listOf(distributor)
    every { UnifiedPush.getSavedDistributor(context) } answers { connector.getString("unifiedpush.distributor", null) }
    every { UnifiedPush.getAckDistributor(context) } answers {
      connector.getString("unifiedpush.distributor", null).takeIf { connector.getBoolean("unifiedpush.distributor_ack", false) }
    }
    every { LockedPushState.peekPairingConfirmation(context) } answers { confirmation.get() }
    every { LockedPushState.clearPairingConfirmation(context, sessionId) } answers { confirmation.set(null) }
    every { LockedPushState.setEncryptedEnrollment(context, any()) } returns Unit
    every { LockedPushState.setProtectedPairingRoute(context, any(), any()) } returns Unit
    try {
      UnifiedPushAccountTransition.initializeFromSignalStore()
      assertTrue("a confirmed v4 route with retained consent completes the connector half",
        UnifiedPushReceiver.recoverInterruptedV4Promotion(context))
      assertEquals(distributor, connector.getString("unifiedpush.distributor", null))
      assertTrue(connector.getBoolean("unifiedpush.distributor_ack", false))
      assertNull(confirmation.get())

      confirmation.set(LockedPushState.PairingConfirmation(
        sessionId, instance, distributor, "fixture-vapid", "https://push.example.invalid", now + 600,
        "Fixture bridge", importId, "", 1, "committed-consent", receiverId = receiverId,
        previousDistributor = previousDistributor, previousInstance = "default",
        previousDeliveryMethod = org.thoughtcrime.securesms.keyvalue.SettingsValues.NotificationDeliveryMethod.UNIFIEDPUSH.name,
      ))
      val revokedDistributor = "org.example.user-selected"
      selected(revokedDistributor)
      assertFalse("a user-selected replacement is never overwritten by recovery",
        UnifiedPushReceiver.recoverInterruptedV4Promotion(context))
      assertEquals(revokedDistributor, connector.getString("unifiedpush.distributor", null))
      assertNotNull(confirmation.get())
    } finally {
      unmockkStatic(UnifiedPush::class)
    }
  }

  @Test
  fun `failed pairing migration leaves the previous distributor and endpoint active`() {
    assertPairingMigrationRollback { binding ->
      UnifiedPushReceiver().onRegistrationFailed(context, FailedReason.NETWORK, binding.instance)
    }
  }

  @Test
  fun `padded connector keys stay valid from consent through sealing and submission`() {
    fakeAccount()
    every { SignalStore.account.isUnauthorizedReceived } returns false
    every { SignalStore.settings.preferredNotificationMethod } returns
      org.thoughtcrime.securesms.keyvalue.SettingsValues.NotificationDeliveryMethod.UNIFIEDPUSH
    every { SignalStore.settings.preferredNotificationMethod = any() } returns Unit
    val offer = freshPairingOffer()
    val endpoint = "https://push.example.test/send/token"
    values.vapidPublicKey = offer.vapidPublicKey
    values.setEndpointRegistration(endpoint, "$fixtureP256dh=", "$fixtureAuth==", "default")
    mockPendingEndpointStorage()
    mockkStatic(UnifiedPush::class)
    mockkObject(PairingV1)
    val coordinator = PairingV1Coordinator(context)
    try {
      every { UnifiedPush.getSavedDistributor(any()) } returns "org.example.distributor"
      every { UnifiedPush.getAckDistributor(any()) } returns "org.example.distributor"
      every { PairingV1.seal(offer, any(), endpoint, any()) } returns
        PairingV1.SealedRequest("{}", ByteArray(32), byteArrayOf(), byteArrayOf(), byteArrayOf())
      every { LockedPushState.savePairingConfirmation(context, any()) } returns true

      val consent = checkNotNull(coordinator.consentDetails(offer))
      assertEquals(fixtureP256dh, consent.p256dh)
      assertEquals(fixtureAuth, consent.auth)
      val result = coordinator.sealAfterConsent(offer, RelayDisclosure.capture(context), consent, null, false)
      assertTrue("padded subscription should seal, got $result", result is PairingV1Coordinator.SealResult.Ready)
      val session = (result as PairingV1Coordinator.SealResult.Ready).session
      assertEquals(Target(endpoint, fixtureP256dh, fixtureAuth), values.controlState.intents.single().target)
      verify(exactly = 1) { LockedPushState.savePairingConfirmation(context, match { it.instance == "default" && it.importId == session.importRequestId }) }
      assertEquals(PairingTransport.Result.Offline, coordinator.submit(session, null))

      values.endpointAuth = "malformed"
      assertNull(coordinator.consentDetails(offer))
      assertEquals(PairingTransport.Result.Stale, coordinator.submit(session, null))

      val offCurve = ByteArray(65).also { it[0] = 4 }
      values.setEndpointRegistration(endpoint, java.util.Base64.getUrlEncoder().encodeToString(offCurve), fixtureAuth, "default")
      assertNull(coordinator.consentDetails(offer))
    } finally {
      coordinator.clear()
      unmockkObject(PairingV1)
      unmockkStatic(UnifiedPush::class)
    }
  }

  @Test
  fun `receipt admission uses the claimed route when a fresh v4 candidate has no active endpoint`() {
    assertReceiptAdmissionOnCandidate(replacingEndpoint = false)
  }

  @Test
  fun `receipt admission uses the claimed route while replacement retains the previous distributor route`() {
    assertReceiptAdmissionOnCandidate(replacingEndpoint = true)
  }

  @Test
  fun `receipt admission rejects a candidate route that changed after sealing`() {
    assertReceiptAdmissionOnCandidate(replacingEndpoint = false, mutateCandidateAfterSealing = true)
  }

  @Test
  fun `receipt after candidate confirmation is admitted only after promotion`() {
    assertReceiptAdmissionOnCandidate(replacingEndpoint = false, confirmationFirst = true)
  }

  @Test
  fun `authenticated receipt survives candidate promotion while lifecycle drains a lease`() {
    assertReceiptAdmissionDuringTransitionDrain()
  }

  @Test
  fun `failed candidate receiver commit rebinds only the retained exact approval`() {
    fakeAccount()
    every { SignalStore.account.isUnauthorizedReceived } returns false
    val owner = "org.example.distributor"
    val offer = freshPairingOffer().copy(intent = "add_receiver")
    val previousEndpoint = "https://push.example.invalid/retained"
    val endpoint = "https://push.example.test/retry-candidate"
    values.vapidPublicKey = offer.vapidPublicKey
    values.setEndpointRegistration(previousEndpoint, fixtureP256dh, fixtureAuth, "default")
    mockPendingEndpointStorage()
    mockkStatic(UnifiedPush::class)
    every { UnifiedPush.getDistributors(context) } returns listOf(owner)
    every { UnifiedPush.getSavedDistributor(context) } returns owner
    every { UnifiedPush.getAckDistributor(context) } returns owner
    val scope = checkNotNull(RelayDisclosure.capture(context))
    val registration = checkNotNull(PairingRegistrationGate.begin(
      owner, offer, checkNotNull(accountIdentityId), 1, lifecycleGeneration = scope.generation,
      previousEndpoint = previousEndpoint, previousP256dh = fixtureP256dh, previousAuth = fixtureAuth,
      previousInstance = "default", previousDistributorId = owner, disclosure = scope,
    ) { })
    val claimed = PairingRegistrationGate.Endpoint(endpoint, fixtureP256dh, fixtureAuth, registration.instance)
    assertNotNull(PairingRegistrationGate.claim(registration.instance, owner, owner,
      checkNotNull(accountIdentityId), 1, claimed))
    val receiverId = "33333333-3333-4333-8333-333333333333"
    val importId = "44444444-4444-4444-8444-444444444444"
    val receiverState = ReceiverControlState(
      receiverId, importId, checkNotNull(accountIdentityId), 2, registration.instance,
      hostLabel = offer.hostLabel, controlKey = fixtureKey, epoch = 1,
      target = Target(endpoint, fixtureP256dh, fixtureAuth),
    )
    val identity = PairingRegistrationGate.ImportBinding(
      importId, "", 1, endpoint, fixtureP256dh, fixtureAuth, checkNotNull(accountIdentityId), 1,
      registration.instance, receiverId,
    )
    assertTrue(PairingRegistrationGate.bindImport(registration.instance, identity))
    values.commitV4ReceiverAttempt(
      receiverState.serialize(), EncryptedPush.ReplayState(System.currentTimeMillis() / 1000, emptyList()).serialize(),
    )
    val confirmation = LockedPushState.PairingConfirmation(
      offer.sessionId, registration.instance, owner, offer.vapidPublicKey, offer.providerOrigin,
      offer.expiresAt, offer.hostLabel, importId, "", 1, RelayDisclosure.pairingScopeDigest(scope), receiverId = receiverId,
    )
    mockkObject(LockedPushState)
    mockkStatic(LockedPushState::class)
    lockedPushStateMocked = true
    every { LockedPushState.peekPairingConfirmation(context) } returns confirmation
    every { LockedPushState.clearPairingConfirmation(context, offer.sessionId) } returns Unit
    every { LockedPushState.setEncryptedEnrollment(context, any()) } returns Unit
    every { LockedPushState.setProtectedPairingRoute(context, any(), any()) } returns Unit
    assertTrue("the exact live candidate is retained before the failed transition",
      synchronized(UnifiedPushLifecycle.monitor) { hasLivePendingV4PairingConfirmation(context) })
    var failCommit = true
    every { values.mutateControlWithReceiverAndReplay(any(), any(), any(), any()) } answers {
      if (failCommit) {
        failCommit = false
        throw KeyValuePersistenceException(IllegalStateException("simulated pre-commit persistence failure"))
      }
      callOriginal()
    }

    val eventNode = ObjectMapper().readTree(
      checkNotNull(javaClass.getResourceAsStream("/colliesocket-encrypted-push-v4/fixtures/imported.json")).use { it.readBytes() },
    ) as com.fasterxml.jackson.databind.node.ObjectNode
    val now = System.currentTimeMillis() / 1000
    eventNode.put("instance", registration.instance).put("import_id", importId).put("receiver_id", receiverId)
      .put("issued_at", now).put("expires_at", now + 300)
    val event = eventNode.toString().toByteArray()
    val generationBefore = UnifiedPushLifecycle.currentGeneration()
    UnifiedPushReceiver().onMessage(context, PushMessage(event, true), registration.instance)

    assertFalse("the simulated receiver state write failed", failCommit)
    assertTrue("the lifecycle transition advanced before failing", UnifiedPushLifecycle.currentGeneration() > generationBefore)
    assertEquals("the pending receiver remains uncommitted", receiverState.serialize(), values.v4PendingReceiverState)
    val rebound = checkNotNull(PairingRegistrationGate.current())
    assertEquals("the failed transition did not replace the candidate", registration.instance, rebound.binding.instance)
    assertEquals("the exact candidate gate follows the new lifecycle generation",
      UnifiedPushLifecycle.currentGeneration(), rebound.binding.lifecycleGeneration)
    assertEquals("durable consent remains available for retry", confirmation, LockedPushState.peekPairingConfirmation(context))
    assertTrue(PairingRegistrationGate.allowsCandidateConfirmation(
      registration.instance, owner, owner, checkNotNull(accountIdentityId), 1,
    ))
    PairingRegistrationGate.cancel(registration.instance)
    unmockkStatic(UnifiedPush::class)
  }

  private fun assertReceiptAdmissionDuringTransitionDrain() {
    fakeAccount()
    val owner = "org.example.distributor"
    val offer = freshPairingOffer().copy(intent = "add_receiver")
    val candidateEndpoint = "https://push.example.test/candidate-during-transition"
    values.vapidPublicKey = offer.vapidPublicKey
    values.setEndpointRegistration("https://push.example.invalid/retained", fixtureP256dh, fixtureAuth, "default")
    mockPendingEndpointStorage()
    mockkStatic(UnifiedPush::class)
    mockkObject(PairingV1, PairingTransport)
    val transitionFailure = AtomicReference<Throwable?>()
    var lease: UnifiedPushLifecycle.Lease<UnifiedPushAccountTransition.AccountContext>? = null
    try {
      every { UnifiedPush.getDistributors(context) } returns listOf(owner)
      every { UnifiedPush.getSavedDistributor(context) } returns owner
      every { UnifiedPush.getAckDistributor(context) } returns owner
      every { PairingV1.seal(offer, any(), candidateEndpoint, any()) } returns
        PairingV1.SealedRequest("{}", ByteArray(32), byteArrayOf(), byteArrayOf(), byteArrayOf())
      every { PairingV1.openReceipt(offer, any(), any()) } returns "authenticated receipt"
      every { LockedPushState.savePairingConfirmation(context, any()) } returns true
      every { LockedPushState.clearPairingConfirmation(context, any()) } returns Unit
      every { LockedPushState.setEncryptedEnrollment(context, any()) } returns Unit
      every { PairingTransport.submit(any(), any(), any(), any()) } answers {
        if (arg<(String) -> Boolean>(3)("{}")) PairingTransport.Result.Accepted
        else PairingTransport.Result.InvalidResponse
      }

      val scope = checkNotNull(RelayDisclosure.capture(context))
      val binding = checkNotNull(PairingRegistrationGate.begin(
        owner, offer, checkNotNull(accountIdentityId), 1, lifecycleGeneration = scope.generation,
        previousEndpoint = values.endpoint, previousP256dh = values.endpointP256dh,
        previousAuth = values.endpointAuth, previousInstance = values.endpointInstance ?: "default",
        previousDistributorId = owner, disclosure = scope,
      ) { })
      val endpoint = PairingRegistrationGate.Endpoint(candidateEndpoint, fixtureP256dh, fixtureAuth, binding.instance)
      assertNotNull(PairingRegistrationGate.claim(binding.instance, owner, owner, checkNotNull(accountIdentityId), 1, endpoint))
      val coordinator = PairingV1Coordinator(context)
      val consent = checkNotNull(coordinator.consentDetails(offer))
      val sealed = coordinator.sealAfterConsent(offer, scope, consent, null, false, bridgeDeviceId = 3)
      assertTrue("candidate consent should seal a v4 receiver request: $sealed", sealed is PairingV1Coordinator.SealResult.Ready)
      val session = (sealed as PairingV1Coordinator.SealResult.Ready).session
      val pending = checkNotNull(ReceiverControlState.parse(values.v4PendingReceiverState))
      lease = checkNotNull(UnifiedPushAccountTransition.acquireLease())

      val transitionFinished = CountDownLatch(1)
      Thread {
        try {
          runBlocking {
            UnifiedPushLifecycle.withTransition(
              expectedGeneration = scope.generation,
              persistIncompleteMarker = {},
              readyAfter = { true },
            ) {
              synchronized(UnifiedPushLifecycle.monitor) {
                val current = checkNotNull(ReceiverControlState.parse(values.v4PendingReceiverState))
                check(current.receiverId == pending.receiverId && current.importId == pending.importId)
                val origin = checkNotNull(PairingV1.canonicalOrigin(candidateEndpoint))
                values.setPairingEndpointRegistration(candidateEndpoint, fixtureP256dh, fixtureAuth,
                  UnifiedPushValues.PairingEndpointBinding(
                    binding.instance, owner, offer.vapidPublicKey, offer.providerOrigin, origin, offer.sessionId,
                    offer.expiresAt, checkNotNull(accountIdentityId), 1, offer.hostLabel, current.importId,
                  ),
                )
                values.commitV4ReceiverStates(current.copy(confirmed = true).serialize(), null)
                PairingRegistrationGate.promote(binding.instance)
              }
            }
          }
        } catch (failure: Throwable) {
          transitionFailure.set(failure)
        } finally {
          transitionFinished.countDown()
        }
      }.start()

      awaitCondition("pairing promotion did not start draining the held lease") {
        synchronized(UnifiedPushLifecycle.monitor) { UnifiedPushLifecycle.isTransitionInProgressLocked() }
      }
      assertEquals(scope.account, synchronized(UnifiedPushLifecycle.monitor) { UnifiedPushAccountTransition.captureContextLocked() })
      assertFalse(synchronized(UnifiedPushLifecycle.monitor) { UnifiedPushLifecycle.hasPendingInvalidationLocked() })
      assertTrue("the pre-consent route remains in scope during the drain", RelayDisclosure.matchesDuringTransition(scope))
      val receiptResult = coordinator.submit(session, PairingTransport.parseSubmitUrl("https://push.example.test/submit"))
      verify(exactly = 1) { PairingV1.openReceipt(offer, any(), any()) }
      assertEquals("the authenticated receipt remains pending while the exact candidate transition drains",
        PairingTransport.Result.AwaitingPush, receiptResult)
      assertEquals("receipt admission leaves the pending identity intact", pending.serialize(), values.v4PendingReceiverState)
      lease.close()
      lease = null
      assertTrue("candidate promotion did not finish", transitionFinished.await(5, TimeUnit.SECONDS))
      transitionFailure.get()?.let { throw AssertionError("candidate promotion failed", it) }

      val active = checkNotNull(ReceiverControlState.parse(values.v4ReceiverState))
      assertTrue("the matching promotion confirms the same receiver", active.confirmed)
      assertEquals(pending.receiverId, active.receiverId)
      assertNull(values.v4PendingReceiverState)
      assertNull(PairingRegistrationGate.current())
      assertEquals(candidateEndpoint, values.endpoint)
    } finally {
      lease?.close()
      PairingRegistrationGate.current()?.binding?.let { PairingRegistrationGate.cancel(it.instance) }
      unmockkObject(PairingV1, PairingTransport)
      unmockkStatic(UnifiedPush::class)
    }
  }

  private fun assertReceiptAdmissionOnCandidate(
    replacingEndpoint: Boolean,
    mutateCandidateAfterSealing: Boolean = false,
    confirmationFirst: Boolean = false,
  ) {
    fakeAccount()
    val owner = "org.example.distributor"
    val offer = freshPairingOffer().copy(intent = "add_receiver")
    val candidateEndpoint = "https://push.example.test/candidate-${if (replacingEndpoint) "replacement" else "fresh"}"
    values.vapidPublicKey = offer.vapidPublicKey
    if (replacingEndpoint) {
      values.setEndpointRegistration("https://push.example.invalid/retained", fixtureP256dh, fixtureAuth, "default")
    } else {
      values.setEndpointRegistration(null, null, null, null)
    }
    mockPendingEndpointStorage()
    mockkStatic(UnifiedPush::class)
    mockkObject(PairingV1, PairingTransport)
    try {
      every { UnifiedPush.getDistributors(context) } returns listOf(owner)
      every { UnifiedPush.getSavedDistributor(context) } returns owner
      every { UnifiedPush.getAckDistributor(context) } returns owner
      every { PairingV1.seal(offer, any(), candidateEndpoint, any()) } returns
        PairingV1.SealedRequest("{}", ByteArray(32), byteArrayOf(), byteArrayOf(), byteArrayOf())
      every { PairingV1.openReceipt(offer, any(), any()) } returns "authenticated receipt"
      every { LockedPushState.savePairingConfirmation(context, any()) } returns true
      every { LockedPushState.clearPairingConfirmation(context, any()) } returns Unit
      every { LockedPushState.setEncryptedEnrollment(context, any()) } returns Unit
      every { PairingTransport.submit(any(), any(), any(), any()) } answers {
        if (arg<(String) -> Boolean>(3)("{}")) PairingTransport.Result.Accepted
        else PairingTransport.Result.InvalidResponse
      }

      val scope = checkNotNull(RelayDisclosure.capture(context))
      val binding = checkNotNull(PairingRegistrationGate.begin(
        owner, offer, checkNotNull(accountIdentityId), 1, lifecycleGeneration = scope.generation,
        previousEndpoint = values.endpoint,
        previousP256dh = values.endpointP256dh, previousAuth = values.endpointAuth,
        previousInstance = values.endpointInstance ?: "default", previousDistributorId = owner, disclosure = scope,
      ) { })
      val endpoint = PairingRegistrationGate.Endpoint(candidateEndpoint, fixtureP256dh, fixtureAuth, binding.instance)
      assertNotNull(PairingRegistrationGate.claim(binding.instance, owner, owner, checkNotNull(accountIdentityId), 1, endpoint))

      val coordinator = PairingV1Coordinator(context)
      val consent = checkNotNull(coordinator.consentDetails(offer))
      val sealed = coordinator.sealAfterConsent(offer, scope, consent, null, false, bridgeDeviceId = 3)
      assertTrue("candidate consent should seal a v4 receiver request: $sealed", sealed is PairingV1Coordinator.SealResult.Ready)
      val session = (sealed as PairingV1Coordinator.SealResult.Ready).session
      if (confirmationFirst) {
        val pending = checkNotNull(ReceiverControlState.parse(values.v4PendingReceiverState))
        val origin = checkNotNull(PairingV1.canonicalOrigin(candidateEndpoint))
        values.setPairingEndpointRegistration(candidateEndpoint, fixtureP256dh, fixtureAuth,
          UnifiedPushValues.PairingEndpointBinding(
            binding.instance, owner, offer.vapidPublicKey, offer.providerOrigin, origin, offer.sessionId,
            offer.expiresAt, checkNotNull(accountIdentityId), 1, offer.hostLabel, pending.importId,
          ),
        )
        values.commitV4ReceiverStates(pending.copy(confirmed = true).serialize(), null)
        PairingRegistrationGate.promote(binding.instance)
      }
      if (mutateCandidateAfterSealing) {
        assertNotNull(PairingRegistrationGate.claim(
          binding.instance, owner, owner, checkNotNull(accountIdentityId), 1,
          endpoint.copy(url = "https://push.example.test/wrong-candidate"),
        ))
      }

      assertEquals(
        if (mutateCandidateAfterSealing) "a changed candidate cannot admit the receipt"
        else if (confirmationFirst) "a receipt after promotion is admitted against the confirmed active route"
        else "an authenticated receipt is admitted for the isolated candidate while its push confirmation is pending",
        when {
          mutateCandidateAfterSealing -> PairingTransport.Result.Stale
          confirmationFirst -> PairingTransport.Result.Accepted
          else -> PairingTransport.Result.AwaitingPush
        },
        coordinator.submit(session, PairingTransport.parseSubmitUrl("https://push.example.test/submit")),
      )
    } finally {
      PairingRegistrationGate.current()?.binding?.let { PairingRegistrationGate.cancel(it.instance) }
      unmockkObject(PairingV1, PairingTransport)
      unmockkStatic(UnifiedPush::class)
    }
  }

  @Test
  fun `unlock resumes a scoped retry when the app locked before the worker ran`() {
    fakeAccount()
    val owner = "org.example.distributor"
    val instance = "pairing-v1-established"
    val binding = UnifiedPushValues.PairingEndpointBinding(
      instance, owner, "vapid-key", null, "https://push.example.test",
      "session", Long.MAX_VALUE, checkNotNull(accountIdentityId), 1, "Relay",
    )
    values.setPairingEndpointRegistration("https://push.example.test/send/token", fixtureP256dh, fixtureAuth, binding)
    val locked = AtomicBoolean()
    val checkedWhileLocked = CountDownLatch(1)
    every { KeyCachingService.isLocked() } answers {
      locked.get().also { if (it) checkedWhileLocked.countDown() }
    }
    mockkStatic(UnifiedPush::class)
    try {
      every { UnifiedPush.getSavedDistributor(context) } returns owner
      every { UnifiedPush.getAckDistributor(context) } returns owner
      every { UnifiedPush.registerApp(context, instance, "CollieSocket pairing", binding.vapidPublicKey) } returns Unit

      synchronized(UnifiedPushLifecycle.monitor) {
        UnifiedPushReceiver().onRegistrationFailed(context, FailedReason.NETWORK, instance)
        assertTrue(retryScheduled)
        locked.set(true)
      }
      assertTrue(checkedWhileLocked.await(5, TimeUnit.SECONDS))
      synchronized(UnifiedPushLifecycle.monitor) {
        verify(exactly = 0) { UnifiedPush.registerApp(any(), any(), any(), any()) }
        assertTrue(retryScheduled)
        locked.set(false)
      }

      UnifiedPushReceiver.resumeScopedRegistration(context)

      verify(exactly = 1) { UnifiedPush.registerApp(context, instance, "CollieSocket pairing", binding.vapidPublicKey) }
    } finally {
      unmockkStatic(UnifiedPush::class)
    }
  }

  @Test
  fun `cancelling a candidate requeues the parked control update for the retained route`() {
    fakeAccount()
    val accountId = checkNotNull(accountIdentityId)
    val owner = "org.example.distributor"
    val endpoint = "https://push.example.test/send/retained"
    values.setEndpointRegistration(endpoint, fixtureP256dh, fixtureAuth, "default")
    val subscription = ControlFrame.Subscription(endpoint, fixtureP256dh, fixtureAuth)
    val frame = ControlFrame.build(fixtureKey, accountId, 2, 2, subscription)
    val now = System.currentTimeMillis() / 1000
    val update = ControlUpdate(2, endpoint, fixtureP256dh, fixtureAuth, frame, now, attempts = 1, lastAttemptAt = now)
    values.mutateControl {
      ControlState(key = fixtureKey, activeEpoch = 1, highestEpoch = 2, serverTarget = oldTarget, update = update)
    }
    val offer = freshPairingOffer()
    val candidate = checkNotNull(PairingRegistrationGate.begin(owner, offer, accountId, 1) { })
    mockkStatic(UnifiedPush::class)
    try {
      every { UnifiedPush.getSavedDistributor(context) } returns owner
      every { UnifiedPush.unregisterApp(any(), candidate.instance) } returns Unit

      val job = UnifiedPushControlJob.Factory().create(Job.Parameters.Builder().build(), null)
      val deferred = org.junit.Assert.assertThrows(java.io.IOException::class.java) { job.onRun() }
      assertEquals(TimeUnit.SECONDS.toMillis(30), job.getNextRunAttemptBackoff(6, deferred))
      verify(exactly = 0) { AppDependencies.signalServiceMessageSender.sendControlMessage(any(), any(), any(), any()) }
      controlJobAdds(0)
      assertEquals(update.serialize(), values.controlState.update?.serialize())

      PairingV1Coordinator(context).cancelOfferAttempt(offer.sessionId)

      verify(timeout = 3000, exactly = 1) { AppDependencies.jobManager.add(any<UnifiedPushControlJob>()) }
      assertNull(PairingRegistrationGate.current())
      assertEquals(endpoint, values.endpoint)
      assertEquals(update.serialize(), values.controlState.update?.serialize())
    } finally {
      PairingRegistrationGate.cancel(candidate.instance)
      unmockkStatic(UnifiedPush::class)
    }
  }

  @Test
  fun `exact locked confirmation restores a pairing candidate without its original process state`() {
    assertLockedConfirmationReplay(reusingEndpoint = false)
  }

  @Test
  fun `a locked confirmation completes pairing for a reused connector instance`() {
    assertLockedConfirmationReplay(reusingEndpoint = true)
  }

  private fun assertLockedConfirmationReplay(reusingEndpoint: Boolean) {
    fakeAccount()
    every { SignalStore.account.isUnauthorizedReceived } returns false
    val owner = "org.example.distributor"
    val instance = if (reusingEndpoint) "default" else "pairing-v1-restarted"
    val newEndpoint = "https://push.example.invalid/new"
    values.vapidPublicKey = "vapid-key"
    values.setEndpointRegistration(
      if (reusingEndpoint) newEndpoint else "https://push.example.invalid/old", fixtureP256dh, fixtureAuth, "default",
    )
    every { SignalStore.settings.preferredNotificationMethod } returns
      org.thoughtcrime.securesms.keyvalue.SettingsValues.NotificationDeliveryMethod.UNIFIEDPUSH
    every { SignalStore.settings.preferredNotificationMethod = any() } returns Unit
    mockPendingEndpointStorage()
    mockkStatic(UnifiedPush::class, org.thoughtcrime.securesms.ApplicationContext::class)
    try {
      every { UnifiedPush.getDistributors(context) } returns listOf(owner)
      every { UnifiedPush.getSavedDistributor(context) } returns owner
      every { UnifiedPush.getAckDistributor(context) } returns owner
      val application = mockk<org.thoughtcrime.securesms.ApplicationContext>(relaxed = true)
      every { org.thoughtcrime.securesms.ApplicationContext.getInstance(context) } returns application
      val target = Target(newEndpoint, fixtureP256dh, fixtureAuth)
      val intent = EnrollmentExporter.exportForPairing(
        context, { true }, target = target, instance = instance,
      ) { _, _, _ -> }
      val scope = checkNotNull(RelayDisclosure.capture(context))
      val confirmation = AtomicReference<LockedPushState.PairingConfirmation?>(LockedPushState.PairingConfirmation(
        "paired-session", instance, owner, "vapid-key", "https://push.example.invalid",
        System.currentTimeMillis() / 1000 + 600, "Relay", intent.requestId, intent.keyId,
        intent.epoch, RelayDisclosure.pairingScopeDigest(scope),
      ))
      every { LockedPushState.peekPairingConfirmation(context) } answers { confirmation.get() }
      every { LockedPushState.savePairingConfirmation(context, any()) } answers {
        confirmation.set(secondArg())
        true
      }
      every { LockedPushState.clearPairingConfirmation(context, "paired-session") } answers { confirmation.set(null) }
      every { LockedPushState.setEncryptedEnrollment(context, true) } returns Unit
      every { LockedPushState.setProtectedPairingRoute(context, any(), any()) } returns Unit

      val event = v3Event(intent, "11111111-1111-4111-8111-111111111111", "status")
        .decodeToString().dropLast(1).plus(""","code":"enrollment_imported"}""").toByteArray()
      val receiver = UnifiedPushReceiver()
      every { KeyCachingService.isLocked() } returns true
      receiver.onMessage(context, PushMessage(event, false), instance)
      assertNull(confirmation.get()?.message)
      val wrongImport = event.decodeToString().replace(intent.requestId, "22222222-2222-4222-8222-222222222222").toByteArray()
      receiver.onMessage(context, PushMessage(wrongImport, true), instance)
      assertNull(confirmation.get()?.message)
      val expiry = checkNotNull(EncryptedPush.parse(event, System.currentTimeMillis() / 1000, setOf(3))).expiresAt
      val shorterEvent = event.decodeToString().replace("\"expires_at\":$expiry", "\"expires_at\":${expiry - 60}").toByteArray()
      receiver.onMessage(context, PushMessage(shorterEvent, true), instance)
      assertEquals(java.util.Base64.getEncoder().encodeToString(shorterEvent), confirmation.get()?.message)
      receiver.onMessage(context, PushMessage(event, true), instance)
      assertEquals(java.util.Base64.getEncoder().encodeToString(event), confirmation.get()?.message)
      receiver.onMessage(context, PushMessage(shorterEvent, true), instance)
      assertEquals(java.util.Base64.getEncoder().encodeToString(event), confirmation.get()?.message)
      assertNull(PairingRegistrationGate.current())
      assertEquals("default", values.endpointInstance)

      every { KeyCachingService.isLocked() } returns false
      val generationBeforeRejectedCandidate = UnifiedPushLifecycle.currentGeneration()
      val rejectedCandidate = event.decodeToString().replace("\"code\":\"enrollment_imported\"", "\"code\":\"subscription_updated\"").toByteArray()
      receiver.onMessage(context, PushMessage(rejectedCandidate, true), instance)
      assertEquals("a rejected candidate does not start a lifecycle transition", generationBeforeRejectedCandidate, UnifiedPushLifecycle.currentGeneration())
      val restoredScope = checkNotNull(RelayDisclosure.capture(context))
      assertEquals(RelayDisclosure.pairingScopeDigest(scope), RelayDisclosure.pairingScopeDigest(restoredScope))
      assertEquals(owner, restoredScope.configuration.distributor)
      assertTrue(restoredScope.intents.any { it.requestId == intent.requestId && it.keyId == intent.keyId && it.epoch == intent.epoch && it.instance == instance })
      assertEquals("vapid-key", values.vapidPublicKey)
      assertTrue(checkNotNull(confirmation.get()).matches(checkNotNull(EncryptedPush.parse(event, System.currentTimeMillis() / 1000, setOf(3)))))
      val unlock = FutureTask { UnifiedPushReceiver.applyPendingEndpoint(context) }
      Thread(unlock).start()
      unlock.get(5, TimeUnit.SECONDS)

      assertEquals("the sealed confirmation is consumed after restart", null, confirmation.get())
      assertEquals(intent.requestId, values.controlState.activeImportId)
      assertEquals(instance, values.pairingEndpointBinding?.instance)
      assertEquals(target.endpoint, values.endpoint)
      assertNull(PairingRegistrationGate.current())
      assertNull(confirmation.get())
      verify(timeout = 3000, exactly = 1) { application.activatePushNotificationServicesAfterPairing() }
    } finally {
      PairingRegistrationGate.current()?.binding?.let { PairingRegistrationGate.cancel(it.instance) }
      unmockkStatic(UnifiedPush::class, org.thoughtcrime.securesms.ApplicationContext::class)
    }
  }

  private fun assertPairingMigrationRollback(cancel: (PairingRegistrationGate.Binding) -> Unit) {
    val oldDistributor = "org.example.old"
    val newDistributor = "org.example.new"
    val oldEndpoint = "https://old.example.test/push"
    val oldInstance = "pairing-v1-old"
    val oldBinding = UnifiedPushValues.PairingEndpointBinding(
      oldInstance, oldDistributor, "old-vapid", null, "https://old.example.test",
      "old-session", Long.MAX_VALUE, "account-aci", 1, "Old relay",
    )
    values.setPairingEndpointRegistration(oldEndpoint, "old-key", "old-auth", oldBinding)
    val offer = freshPairingOffer()
    val binding = checkNotNull(PairingRegistrationGate.begin(
      newDistributor, offer, "account-aci", 1,
      previousEndpoint = oldEndpoint, previousP256dh = "old-key", previousAuth = "old-auth",
      previousInstance = oldInstance, previousDistributorId = oldDistributor,
    ) { })

    val registerAction = "org.unifiedpush.android.distributor.REGISTER"
    val unregisterAction = "org.unifiedpush.android.distributor.UNREGISTER"
    mockkStatic(UnifiedPush::class)
    try {
      every { UnifiedPush.getSavedDistributor(context) } returns oldDistributor
      every { UnifiedPush.getAckDistributor(context) } returns oldDistributor
      every { UnifiedPush.registerApp(any(), binding.instance, any(), binding.vapidPublicKey) } answers {
        firstArg<Context>().sendBroadcast(Intent(registerAction).setPackage(oldDistributor))
      }
      every { UnifiedPush.unregisterApp(any(), binding.instance) } answers {
        firstArg<Context>().sendBroadcast(Intent(unregisterAction).setPackage(oldDistributor))
      }

      PairingDistributorRegistration.register(context, binding)
      verify(exactly = 0) { UnifiedPush.unregisterApp(any(), any()) }
      val candidate = PairingRegistrationGate.Endpoint(
        "https://push.example.test/send/candidate", "candidate-key", "candidate-auth", binding.instance,
      )
      assertNotNull(PairingRegistrationGate.claim(
        binding.instance, oldDistributor, oldDistributor, binding.accountId, binding.deviceId, candidate,
      ))
      assertTrue(PairingRegistrationGate.allowsMessageCallback(oldInstance, oldInstance, registrationPending = true))

      cancel(binding)

      assertEquals(oldBinding, values.pairingEndpointBinding)
      assertEquals(oldEndpoint, values.endpoint)
      assertEquals("old-key", values.endpointP256dh)
      assertEquals("old-auth", values.endpointAuth)
      assertNull(PairingRegistrationGate.current())
      verify(exactly = 0) { UnifiedPush.saveDistributor(any(), any()) }
      verify(exactly = 0) { UnifiedPush.unregisterApp(any(), oldInstance) }
      assertEquals(
        listOf(registerAction to newDistributor, unregisterAction to newDistributor),
        shadowOf(context).broadcastIntents.filter { it.action == registerAction || it.action == unregisterAction }
          .map { it.action to it.`package` },
      )
    } finally {
      PairingRegistrationGate.cancel(binding.instance)
      unmockkStatic(UnifiedPush::class)
    }
  }

  private fun freshPairingOffer(): PairingV1.Offer {
    val fixture = checkNotNull(javaClass.getResourceAsStream("/colliesocket-pairing-v1/fixtures/sealed-vectors.json"))
    val offerJson = fixture.use { com.fasterxml.jackson.databind.ObjectMapper().readTree(it)[0]["offer"] }
    return PairingV1.parseOffer(offerJson.toString(), 1_799_999_999L).copy(expiresAt = System.currentTimeMillis() / 1000 + 600)
  }

  @Test
  fun `clearing recovered relay alerts retains distributor failure`() {
    unmockkConstructor(UnifiedPushNotificationBuilder::class)
    val manager = context.getSystemService(NotificationManager::class.java)
    val builder = UnifiedPushNotificationBuilder(context)
    builder.setNotificationRegistrationFailed()
    builder.setNotificationMollySocketForbiddenUuid()

    builder.clearAlerts(clearRegistrationFailure = false)

    val notifications = shadowOf(manager).allNotifications
    org.junit.Assert.assertEquals(1, notifications.size)
    org.junit.Assert.assertEquals(
      context.getString(R.string.UnifiedPushNotificationBuilder__registration_failed),
      notifications.single().extras.getCharSequence(Notification.EXTRA_TEXT).toString()
    )
  }

  private fun wake(epoch: Long = 1, id: String = "11111111-1111-4111-8111-111111111111", version: Int = 1): ByteArray {
    val now = System.currentTimeMillis() / 1000
    return ("""{"version":$version,"event_id":"$id","instance":"default","subscription_epoch":$epoch,"kind":"wake",""" +
      """"issued_at":$now,"expires_at":${now + 300}}""").toByteArray()
  }

  private val active get() = values.enrollmentEpoch
  private val pending get() = values.pendingEnrollmentEpoch
  private val replay get() = values.replayState

  /**
   * Sets up the stored enrollment state (epochs, an optional control key, replay state) and the fetch handler, then
   * runs [block] with the recorded calls cleared.
   */
  private fun enrollment(active: Long, pending: Long, key: String? = null, state: (ControlState) -> ControlState = { it }, block: () -> Unit) {
    if (accountIdentityId == null) fakeAccount()
    values.mutateControl {
      state(ControlState(
        key = key,
        activeEpoch = active,
        highestEpoch = active,
        legacyPending = pending.takeIf { it > 0 }?.let { LegacyPending(null, it, null) },
      ))
    }
    values.replayState = EncryptedPush.ReplayState(System.currentTimeMillis() / 1000 - 1, emptyList()).serialize()
    every { values.endpointInstance } returns "default"
    every { values.lastReceivedTime = any() } returns Unit
    every { SignalStore.account.isLinkedDevice } returns false
    every { values.enabled } returns true
    every { values.registrationStatus } returns RegistrationStatus.REGISTERED
    every { SignalStore.account.isRegistered } returns true
    mockkStatic(PushFetchHandler::class)
    every { PushFetchHandler.handleReceivedNotification(any(), any(), any()) } returns Unit
    clearMocks(values, answers = false, recordedCalls = true)
    block()
  }

  @After
  fun unmockFetch() {
    EnrollmentExporter.removeFiles(context)
    unmockkStatic(PushFetchHandler::class)
  }

  private fun urgent(decrypted: Boolean = true) = PushMessage("""{"urgent":true}""".toByteArray(), decrypted)

  private fun fetches(count: Int) {
    if (count == 0) {
      verify(exactly = 0) { PushFetchHandler.handleReceivedNotification(any(), any(), any()) }
    } else {
      verify(timeout = 3000, exactly = count) { PushFetchHandler.handleReceivedNotification(any(), any(), any()) }
    }
  }

  /** Tests the endpoint handoff independently of Android Keystore availability in Robolectric. */
  private fun mockPendingEndpointStorage(initial: LockedPushState.Endpoint? = null): AtomicReference<LockedPushState.Endpoint?> {
    if (!lockedPushStateMocked) {
      mockkStatic(LockedPushState::class)
      mockkObject(LockedPushState)
      lockedPushStateMocked = true
    }
    return AtomicReference(initial).also { pending ->
      every { LockedPushState.savePending(any(), any()) } answers { pending.set(secondArg()) }
      every { LockedPushState.peekPending(any()) } answers { pending.get() }
      every { LockedPushState.clearPending(any()) } answers { pending.set(null) }
    }
  }

  private fun awaitLifecycleReady() {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
    while (!UnifiedPushLifecycle.isReady() && System.nanoTime() < deadline) Thread.yield()
    assertTrue("pending endpoint transition did not finish", UnifiedPushLifecycle.isReady())
  }

  private fun awaitCondition(description: String, condition: () -> Boolean) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
    while (!condition() && System.nanoTime() < deadline) Thread.yield()
    assertTrue(description, condition())
  }

  @Test
  fun `active enrollment fetches on a decrypted valid wake and refuses its replay`() {
    enrollment(active = 1, pending = 0) {
      val receiver = UnifiedPushReceiver()
      receiver.onMessage(context, PushMessage(wake(), true), "default")
      verify(timeout = 3000, exactly = 1) { PushFetchHandler.handleReceivedNotification(any(), true, any()) }
      receiver.onMessage(context, PushMessage(wake(), true), "default")
      fetches(1)
      verify(exactly = 1) { values.mutateControl(any(), any()) }
    }
  }

  @Test
  fun `v4 receiver rejects plaintext until import then accepts status wake and test events`() {
    val accountId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    fakeAccount(accountId)
    val receiverFixture = ObjectMapper().readTree(
      checkNotNull(javaClass.getResourceAsStream("/colliesocket-encrypted-push-v4/fixtures/receiver.json")).use { it.readBytes() },
    )
    val subscription = receiverFixture["subscription"]
    val endpoint = subscription["endpoint"].asText()
    val p256dh = subscription["p256dh"].asText()
    val auth = subscription["auth"].asText()
    val instance = subscription["instance"].asText()
    val receiverState = ReceiverControlState(
      receiverId = receiverFixture["receiver_id"].asText(),
      importId = receiverFixture["request_id"].asText(),
      accountId = accountId,
      bridgeDeviceId = receiverFixture["device_id"].intValue(),
      instance = instance,
      hostLabel = receiverFixture["label"].asText(),
      controlKey = receiverFixture["control_key"].asText(),
      epoch = subscription["subscription_epoch"].longValue(),
      target = Target(endpoint, p256dh, auth),
    )

    enrollment(active = 0, pending = 0) {
      every { SignalStore.account.isLinkedDevice } returns true
      every { SignalStore.account.deviceId } returns 3
      values.setEndpointRegistration(endpoint, p256dh, auth, instance)
      every { values.endpointInstance } returns instance
      values.replayState = null
      values.commitV4ReceiverAttempt(
        receiverState.serialize(),
        EncryptedPush.ReplayState(System.currentTimeMillis() / 1000, emptyList()).serialize(),
      )
      assertNotNull("the pending enrollment write initializes replay protection", values.replayState)
      assertEquals(
        "an authenticated HTTP receipt before enrollment_imported leaves the attempt pending",
        PairingReceiptV4State.PENDING,
        matchingV4ReceiptState(
          receiverState, ReceiverControlState.parse(values.v4PendingReceiverState), null,
          accountId, instance, receiverState.target,
        ),
      )
      mockkObject(LockedPushState)
      lockedPushStateMocked = true
      mockkStatic(UnifiedPush::class)
      every { UnifiedPush.getDistributors(context) } returns listOf("org.example.distributor")
      every { UnifiedPush.getSavedDistributor(context) } returns "org.example.distributor"
      every { UnifiedPush.getAckDistributor(context) } returns "org.example.distributor"
      val confirmation = LockedPushState.PairingConfirmation(
        sessionId = receiverState.importId, instance = receiverState.instance, distributor = "org.example.distributor",
        vapid = "vapid-key", providerOrigin = "https://push.example.test", expiresAt = System.currentTimeMillis() / 1000 + 600,
        hostLabel = receiverState.hostLabel, importId = receiverState.importId, keyId = "", epoch = receiverState.epoch,
        scopeDigest = RelayDisclosure.pairingScopeDigest(checkNotNull(RelayDisclosure.capture(context))),
        receiverId = receiverState.receiverId,
      )
      var pendingConfirmation: LockedPushState.PairingConfirmation? = confirmation
      every { LockedPushState.peekPairingConfirmation(context) } answers { pendingConfirmation }
      every { LockedPushState.clearPairingConfirmation(context, confirmation.sessionId) } answers { pendingConfirmation = null }
      val receiver = UnifiedPushReceiver()
      receiver.onMessage(context, PushMessage("urgent".toByteArray(), true), instance)
      fetches(0)

      every { anyConstructed<UnifiedPushNotificationBuilder>().setNotificationTest() } returns Unit
      fetches(0)

      fun event(name: String, kind: String? = null, epoch: Long? = null, eventId: String = "77777777-7777-4777-8777-777777777777"): ByteArray {
        val node = ObjectMapper().readTree(
          checkNotNull(javaClass.getResourceAsStream("/colliesocket-encrypted-push-v4/fixtures/$name.json")).use { it.readBytes() },
        ) as com.fasterxml.jackson.databind.node.ObjectNode
        val now = System.currentTimeMillis() / 1000
        node.put("issued_at", now).put("expires_at", now + 300)
        if (kind != null) node.put("kind", kind).put("event_id", eventId)
        if (epoch != null) node.put("subscription_epoch", epoch)
        return node.toString().toByteArray()
      }

      receiver.onMessage(context, PushMessage(event("wake"), true), instance)
      fetches(0)
      val importedBytes = event("imported")
      val importedEvent = checkNotNull(EncryptedPush.parse(importedBytes, System.currentTimeMillis() / 1000, setOf(4)))
      assertTrue(receiverState.identityMatches(importedEvent, accountId))
      // A route tuple that happens to remain identical cannot revive consent after the live relay scope changes.
      every { values.airGapped } returns true
      receiver.onMessage(context, PushMessage(importedBytes, true), instance)
      assertFalse(checkNotNull(ReceiverControlState.parse(values.v4PendingReceiverState)).confirmed)
      every { values.airGapped } returns false
      val live = synchronized(UnifiedPushLifecycle.monitor) { UnifiedPushAccountTransition.captureContextLocked() }
      assertEquals(accountId, live?.accountId)
      assertEquals(instance, live?.instance)
      val replayState = checkNotNull(EncryptedPush.ReplayState.parse(values.replayState))
      assertEquals(
        EncryptedPush.Decision.ACCEPT_STATUS,
        EncryptedPush.decide(
          importedBytes, true, System.currentTimeMillis() / 1000, { false }, instance, setOf(receiverState.epoch),
          replayState, versionsForEpoch = { setOf(4) }, identityMatches = { receiverState.identityMatches(it, live!!.accountId) },
        ).decision,
      )
      receiver.onMessage(context, PushMessage(importedBytes, true), instance)
      assertTrue(checkNotNull(ReceiverControlState.parse(values.v4ReceiverState)).confirmed)
      assertEquals("the confirmed receiver is attached to its paired connector route", receiverState.importId, values.pairingEndpointBinding?.importId)
      assertEquals(
        "a receipt arriving after enrollment_imported matches only the confirmed active attempt",
        PairingReceiptV4State.CONFIRMED,
        matchingV4ReceiptState(
          receiverState, null, ReceiverControlState.parse(values.v4ReceiverState),
          accountId, instance, receiverState.target,
        ),
      )

      // A linked receiver has no local bridge credential. A connector tuple change must still
      // create a receiver-specific v4 frame, and only its exact confirmation can advance it.
      val updatedEndpoint = "https://push.example.invalid/linked-rotated"
      val updatedP256dh = fixtureP256dh
      val updatedAuth = fixtureAuth
      values.device = null
      values.setEndpointRegistration(updatedEndpoint, updatedP256dh, updatedAuth, instance)
      EndpointReconciler.reconcile(forceReceiver = true)
      var liveReceiver = checkNotNull(ReceiverControlState.parse(values.v4ReceiverState))
      assertEquals(1L, liveReceiver.epoch)
      assertEquals(2L, liveReceiver.pendingEpoch)
      assertEquals(Target(updatedEndpoint, updatedP256dh, updatedAuth), liveReceiver.pendingTarget)
      assertTrue(ControlFrameV4.matchesIdentity(
        checkNotNull(liveReceiver.pendingFrame), accountId, receiverState.bridgeDeviceId, receiverState.receiverId, 2,
      ))

      fun updatedEvent(
        receiverId: String = receiverState.receiverId,
        importId: String = receiverState.importId,
        epoch: Long = 2,
        eventId: String = "77777777-7777-4777-8777-777777777777",
      ): ByteArray {
        val node = ObjectMapper().readTree(
          checkNotNull(javaClass.getResourceAsStream("/colliesocket-encrypted-push-v4/fixtures/updated.json")).use { it.readBytes() },
        ) as com.fasterxml.jackson.databind.node.ObjectNode
        val now = System.currentTimeMillis() / 1000
        node.put("issued_at", now).put("expires_at", now + 300).put("receiver_id", receiverId)
          .put("import_id", importId).put("subscription_epoch", epoch).put("event_id", eventId)
        return node.toString().toByteArray()
      }

      val replayBeforeRejectedConfirmations = values.replayState
      receiver.onMessage(context, PushMessage(updatedEvent(ReceiverEnrollmentV4.newUuid()), true), instance)
      receiver.onMessage(context, PushMessage(updatedEvent(importId = ReceiverEnrollmentV4.newUuid()), true), instance)
      receiver.onMessage(context, PushMessage(updatedEvent(epoch = 3), true), instance)
      assertEquals(replayBeforeRejectedConfirmations, values.replayState)
      liveReceiver = checkNotNull(ReceiverControlState.parse(values.v4ReceiverState))
      assertEquals(1L, liveReceiver.epoch)
      assertEquals(2L, liveReceiver.pendingEpoch)

      val delayedPriorEpoch = updatedEvent(
        epoch = 1, eventId = "99999999-9999-4999-8999-999999999999",
      )
      val replayBeforeDelayedConfirmation = values.replayState
      val delayedParsed = checkNotNull(EncryptedPush.parse(delayedPriorEpoch, System.currentTimeMillis() / 1000, setOf(4)))
      assertTrue("the active receiver identity accepts a delayed active-epoch status", liveReceiver.identityMatches(delayedParsed, accountId))
      assertEquals(
        EncryptedPush.Decision.ACCEPT_STATUS,
        EncryptedPush.decide(
          delayedPriorEpoch, true, System.currentTimeMillis() / 1000, { false }, instance,
          setOfNotNull(liveReceiver.epoch, liveReceiver.pendingEpoch),
          checkNotNull(EncryptedPush.ReplayState.parse(replayBeforeDelayedConfirmation)),
          versionsForEpoch = { setOf(4) }, identityMatches = { liveReceiver.identityMatches(it, accountId) },
        ).decision,
      )
      receiver.onMessage(context, PushMessage(delayedPriorEpoch, true), instance)
      liveReceiver = checkNotNull(ReceiverControlState.parse(values.v4ReceiverState))
      assertEquals("a delayed confirmation cannot advance a newer update", 1L, liveReceiver.epoch)
      assertEquals(2L, liveReceiver.pendingEpoch)
      assertEquals(Target(updatedEndpoint, updatedP256dh, updatedAuth), liveReceiver.pendingTarget)
      assertTrue("the fresh event ID is still recorded for replay protection", replayBeforeDelayedConfirmation != values.replayState)
      assertEquals("the delayed confirmation cannot publish a health success", 0L, healthStore.lastResultAt)

      val updatedConfirmation = updatedEvent()
      receiver.onMessage(context, PushMessage(updatedConfirmation, true), instance)
      liveReceiver = checkNotNull(ReceiverControlState.parse(values.v4ReceiverState))
      assertEquals(2L, liveReceiver.epoch)
      assertNull(liveReceiver.pendingEpoch)
      val confirmedState = liveReceiver.serialize()
      val confirmedReplay = values.replayState
      receiver.onMessage(context, PushMessage(updatedConfirmation, true), instance)
      assertEquals(confirmedState, values.v4ReceiverState)
      assertEquals(confirmedReplay, values.replayState)

      receiver.onMessage(
        context, PushMessage(event("wake", "wake", 2, "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1"), true), instance,
      )
      verify(timeout = 3000, exactly = 1) { PushFetchHandler.handleReceivedNotification(any(), true, any()) }
      receiver.onMessage(context, PushMessage(event("wake", "test", 2, "88888888-8888-4888-8888-888888888888"), true), instance)
      verify(timeout = 3000, exactly = 1) { anyConstructed<UnifiedPushNotificationBuilder>().setNotificationTest() }
    }
  }

  @Config(sdk = [35])
  @Test
  fun `export refuses missing replay history after an accepted wake and redelivery has no effect`() {
    enrollment(active = 1, pending = 0) {
      val aci = mockk<ServiceId.ACI>()
      every { aci.toString() } returns "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
      every { signalStore.account.aci } returns aci
      every { SignalStore.account.aci } returns aci
      values.device = MollySocketDevice(2, "SYNTHETIC-NOT-A-SIGNAL-CREDENTIAL")
      values.endpoint = "https://push.example.invalid/synthetic-endpoint"
      values.endpointP256dh = "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4"
      values.endpointAuth = "BTBZMqHH6r4Tts7J_aSIgg"

      val receiver = UnifiedPushReceiver()
      val accepted = wake()
      receiver.onMessage(context, PushMessage(accepted, true), "default")
      verify(timeout = 3000, exactly = 1) { PushFetchHandler.handleReceivedNotification(any(), true, any()) }
      val controlBefore = values.controlState
      org.junit.Assert.assertNotNull(values.replayState)

      // Model losing only the replay key while the accepted registration remains intact.
      values.replayState = null
      org.junit.Assert.assertEquals(1L, values.controlState.activeEpoch)
      org.junit.Assert.assertNull(values.replayState)
      val exportFailure = runCatching {
        EnrollmentExporter.exportWithEpoch(context, 1, ExportMode.NEW_INTENT) { true }
      }.exceptionOrNull()

      org.junit.Assert.assertEquals("state_unavailable (failure=$exportFailure)", "state_unavailable", (exportFailure as? EnrollmentFile.Invalid)?.code)
      org.junit.Assert.assertEquals(controlBefore, values.controlState)
      org.junit.Assert.assertNull(values.replayState)
      val directory = checkNotNull(context.getExternalFilesDir(null))
      org.junit.Assert.assertFalse(java.io.File(directory, EnrollmentExporter.FILE_NAME).exists())
      org.junit.Assert.assertFalse(java.io.File(directory, "${EnrollmentExporter.FILE_NAME}.tmp").exists())

      receiver.onMessage(context, PushMessage(accepted, true), "default")
      fetches(1)
      org.junit.Assert.assertEquals(controlBefore, values.controlState)
    }
  }

  @Test
  @Config(sdk = [35])
  fun `explicit control reset cannot bootstrap missing replay below an accepted clock high water`() {
    fakeAccount()
    values.endpoint = "https://push.example.invalid/synthetic-endpoint"
    values.endpointP256dh = fixtureP256dh
    values.endpointAuth = fixtureAuth

    enrollment(active = 1, pending = 0) {
      val receiver = UnifiedPushReceiver()
      receiver.onMessage(context, PushMessage(wake(id = "22222222-2222-4222-8222-222222222222"), true), "default")
      verify(timeout = 3000, exactly = 1) { PushFetchHandler.handleReceivedNotification(any(), true, any()) }
      val acceptedReplay = EncryptedPush.ReplayState.parse(values.replayState)!!
      val highWater = acceptedReplay.clockHighWater

      values.clearControl()
      org.junit.Assert.assertEquals("an explicit server reset keeps replay history", acceptedReplay.serialize(), values.replayState)
      org.junit.Assert.assertTrue(values.controlStateRead is ControlStateRead.Current)
      values.replayState = null

      for (mode in listOf(ExportMode.NEW_INTENT, ExportMode.RECOVERY)) {
        val failure = runCatching {
          EnrollmentExporter.exportWithEpochAtTime(
            context = context,
            serverEpoch = 0,
            mode = mode,
            nowSeconds = highWater - 1,
            isDistributorActive = { true },
          )
        }.exceptionOrNull()

        org.junit.Assert.assertEquals("$mode must refuse a lower-clock bootstrap", "state_unavailable", (failure as? EnrollmentFile.Invalid)?.code)
        org.junit.Assert.assertEquals(ControlState(), values.controlState)
        org.junit.Assert.assertNull(values.replayState)
        val directory = checkNotNull(context.getExternalFilesDir(null))
        org.junit.Assert.assertFalse(java.io.File(directory, EnrollmentExporter.FILE_NAME).exists())
        org.junit.Assert.assertFalse(java.io.File(directory, "${EnrollmentExporter.FILE_NAME}.tmp").exists())
      }
    }
  }

  @Test
  @Config(sdk = [35])
  fun `rerender refuses missing replay below accepted clock while candidate remains`() {
    fakeAccount()
    values.endpoint = "https://push.example.invalid/synthetic-endpoint"
    values.endpointP256dh = fixtureP256dh
    values.endpointAuth = fixtureAuth

    enrollment(active = 1, pending = 0) {
      val intent = EnrollmentExporter.exportWithEpochAtTime(
        context = context,
        serverEpoch = 1,
        mode = ExportMode.NEW_INTENT,
        nowSeconds = System.currentTimeMillis() / 1000,
        isDistributorActive = { true },
      )
      val receiver = UnifiedPushReceiver()
      receiver.onMessage(
        context,
        PushMessage(wake(id = "33333333-3333-4333-8333-333333333333"), true),
        "default",
      )
      verify(timeout = 3000, exactly = 1) { PushFetchHandler.handleReceivedNotification(any(), true, any()) }

      val before = values.controlState
      org.junit.Assert.assertTrue(before.intents.single { it.requestId == intent.requestId }.eligible)
      val highWater = EncryptedPush.ReplayState.parse(values.replayState)!!.clockHighWater
      values.replayState = null

      val failure = runCatching {
        EnrollmentExporter.exportWithEpochAtTime(
          context = context,
          serverEpoch = null,
          mode = ExportMode.RERENDER,
          requestId = intent.requestId,
          nowSeconds = highWater - 1,
          isDistributorActive = { true },
        )
      }.exceptionOrNull()

      org.junit.Assert.assertEquals("state_unavailable", (failure as? EnrollmentFile.Invalid)?.code)
      org.junit.Assert.assertEquals(before, values.controlState)
      org.junit.Assert.assertNull(values.replayState)
      val directory = checkNotNull(context.getExternalFilesDir(null))
      org.junit.Assert.assertFalse(java.io.File(directory, EnrollmentExporter.FILE_NAME).exists())
      org.junit.Assert.assertFalse(java.io.File(directory, "${EnrollmentExporter.FILE_NAME}.tmp").exists())
    }
  }

  @Test
  @Config(sdk = [35])
  fun `recovery refuses missing replay below accepted clock after reset`() {
    fakeAccount()
    values.endpoint = "https://push.example.invalid/synthetic-endpoint"
    values.endpointP256dh = fixtureP256dh
    values.endpointAuth = fixtureAuth

    enrollment(active = 1, pending = 0) {
      val receiver = UnifiedPushReceiver()
      receiver.onMessage(
        context,
        PushMessage(wake(id = "44444444-4444-4444-8444-444444444444"), true),
        "default",
      )
      verify(timeout = 3000, exactly = 1) { PushFetchHandler.handleReceivedNotification(any(), true, any()) }
      val acceptedReplay = EncryptedPush.ReplayState.parse(values.replayState)!!
      val highWater = acceptedReplay.clockHighWater

      values.clearControl()
      org.junit.Assert.assertEquals(acceptedReplay.serialize(), values.replayState)
      values.replayState = null
      org.junit.Assert.assertTrue(values.controlStateRead is ControlStateRead.Current)
      val resetState = values.controlState

      val failure = runCatching {
        EnrollmentExporter.exportWithEpochAtTime(
          context = context,
          serverEpoch = 0,
          mode = ExportMode.RECOVERY,
          nowSeconds = highWater - 1,
          isDistributorActive = { true },
        )
      }.exceptionOrNull()

      org.junit.Assert.assertEquals("state_unavailable", (failure as? EnrollmentFile.Invalid)?.code)
      org.junit.Assert.assertEquals(resetState, values.controlState)
      org.junit.Assert.assertNull(values.replayState)
      val directory = checkNotNull(context.getExternalFilesDir(null))
      org.junit.Assert.assertFalse(java.io.File(directory, EnrollmentExporter.FILE_NAME).exists())
      org.junit.Assert.assertFalse(java.io.File(directory, "${EnrollmentExporter.FILE_NAME}.tmp").exists())
    }
  }

  @Test
  fun `failed receiver replay persistence leaves cache unchanged and starts no wake effect`() {
    fakeAccount()
    val persistent = SnapshotKeyValuePersistentStorage()
    val durableValues = UnifiedPushValues(KeyValueStore(persistent, Executor { it.run() }))
    durableValues.mutateControl { it.copy(activeEpoch = 1, highestEpoch = 1) }
    durableValues.replayState = EncryptedPush.ReplayState(System.currentTimeMillis() / 1000 - 1, emptyList()).serialize()
    persistent.nextWriteFailure = IllegalStateException("synthetic replay write failure")
    every { SignalStore.unifiedpush } returns durableValues
    mockkStatic(PushFetchHandler::class)
    every { PushFetchHandler.handleReceivedNotification(any(), any(), any()) } returns Unit

    val controlBefore = durableValues.controlState
    val replayBefore = durableValues.replayState
    UnifiedPushReceiver().onMessage(context, PushMessage(wake(), true), "default")

    org.junit.Assert.assertEquals(controlBefore, durableValues.controlState)
    org.junit.Assert.assertEquals(replayBefore, durableValues.replayState)
    verify(exactly = 0) { PushFetchHandler.handleReceivedNotification(any(), any(), any()) }
  }

  @Test
  fun `active enrollment refuses plaintext wakes, undecrypted pushes and other epochs without any effect`() {
    enrollment(active = 1, pending = 0) {
      val before = replay
      val receiver = UnifiedPushReceiver()
      receiver.onMessage(context, urgent(), "default")
      receiver.onMessage(context, urgent(false), "default")
      receiver.onMessage(context, PushMessage("""{"test":true}""".toByteArray(), true), "default")
      receiver.onMessage(context, PushMessage(wake(), false), "default")
      receiver.onMessage(context, PushMessage(wake(epoch = 2), true), "default")
      fetches(0)
      verify(exactly = 0) { values.mutateControl(any(), any()) }
      org.junit.Assert.assertEquals(before, replay)
    }
  }

  @Test
  fun `locked active enrollment drops pushes without reading stored state`() {
    enrollment(active = 1, pending = 0) {
      LockedPushState.setEncryptedEnrollment(context, true)
      every { KeyCachingService.isLocked() } returns true
      clearMocks(values, answers = false, recordedCalls = true)
      UnifiedPushReceiver().onMessage(context, PushMessage(wake(), true), "default")
      UnifiedPushReceiver().onMessage(context, urgent(), "default")
      fetches(0)
      verify(exactly = 0) { values.mutateControl(any(), any()) }
      verify(exactly = 0) { values.enrollmentEpoch }
    }
    LockedPushState.setEncryptedEnrollment(context, false)
  }

  @Test
  fun `a push dropped while locked is remembered, where it can be read without the database`() {
    enrollment(active = 1, pending = 0, key = fixtureKey) {
      org.junit.Assert.assertEquals(0L, LockedPushState.lastPushArrived(context))
      every { KeyCachingService.isLocked() } returns true
      val before = System.currentTimeMillis()
      UnifiedPushReceiver().onMessage(context, PushMessage(wake(version = 2), true), "default")
      org.junit.Assert.assertTrue(LockedPushState.lastPushArrived(context) >= before)
      fetches(0)
    }
  }

  @Test
  fun `discarding the enrollment forgets the record and clears the flag the receiver reads while locked`() {
    enrollment(active = 2, pending = 3, key = fixtureKey) {
      LockedPushState.setEncryptedEnrollment(context, true)
      im.molly.unifiedpush.control.ControlReset.discard(context)
      org.junit.Assert.assertEquals(ControlState(), values.controlState)
      org.junit.Assert.assertFalse(LockedPushState.isEncryptedEnrollment(context))
    }
  }

  @Test
  fun `stock enrollment still fetches on a plaintext wake`() {
    enrollment(active = 0, pending = 0) {
      UnifiedPushReceiver().onMessage(context, urgent(), "default")
      verify(timeout = 3000, exactly = 1) { PushFetchHandler.handleReceivedNotification(any(), true, any()) }
    }
  }

  @Test
  fun `corrupt control state does not fall back to plaintext push handling`() {
    enrollment(active = 0, pending = 0) {
      every { values.controlStateRead } returns ControlStateRead.Corrupt

      UnifiedPushReceiver().onMessage(context, urgent(), "default")

      fetches(0)
      verify(exactly = 0) { values.mutateControl(any(), any()) }
    }
  }

  @Test
  fun `v3 identity with zero active epoch is corrupt and never dispatches a plaintext wake`() {
    enrollment(active = 0, pending = 0) {
      val account = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
      val valid = ControlState(
        key = fixtureKey,
        activeKeyId = ControlKeyId.derive(fixtureKey, account, 2, "default"),
        activeImportId = "22222222-2222-4222-8222-222222222222",
        activeImportSeq = 1,
        activeAccountId = account,
        activeDeviceId = 2,
        activeInstance = "default",
        activeEpoch = 1,
        highestEpoch = 1,
        importSequence = 1,
      )
      values.store.beginWrite()
        .putString("up.control.state", valid.serialize().replace("\"active_epoch\":1", "\"active_epoch\":0"))
        .commit()
      assertEquals(ControlStateRead.Corrupt, values.controlStateRead)

      UnifiedPushReceiver().onMessage(context, urgent(), "default")

      fetches(0)
      verify(exactly = 0) { PushFetchHandler.handleReceivedNotification(any(), any(), any()) }
    }
  }

  @Test
  fun `an export the server never imported leaves a stock enrollment on plaintext wakes`() {
    enrollment(active = 0, pending = 1) {
      val receiver = UnifiedPushReceiver()
      receiver.onMessage(context, urgent(), "default")
      verify(timeout = 3000, exactly = 1) { PushFetchHandler.handleReceivedNotification(any(), true, any()) }
      receiver.onMessage(context, PushMessage("""{"test":true}""".toByteArray(), true), "default")
      org.junit.Assert.assertEquals(0L, active)
      org.junit.Assert.assertEquals(1L, pending)
    }
  }

  @Test
  fun `only a matching v3 intent event promotes and commits before effects`() {
    fakeAccount()
    val candidate = importIntent("22222222-2222-4222-8222-222222222222", 1, 1, fixtureKey)
    enrollment(active = 0, pending = 0, state = { it.copy(intents = listOf(candidate), importSequence = 1, highestEpoch = 1) }) {
      val receiver = UnifiedPushReceiver()
      receiver.onMessage(context, PushMessage(v3Event(candidate, "11111111-1111-4111-8111-111111111111", "wake"), true), "default")
      verify(timeout = 3000, exactly = 1) { PushFetchHandler.handleReceivedNotification(any(), true, any()) }
      org.junit.Assert.assertEquals(1L, active)
      org.junit.Assert.assertEquals(0L, pending)
      org.junit.Assert.assertEquals(candidate.requestId, values.controlState.activeImportId)
      org.junit.Assert.assertEquals(1L, values.controlState.activeImportSeq)
      org.junit.Assert.assertTrue(LockedPushState.isEncryptedEnrollment(context))

      receiver.onMessage(context, urgent(), "default")
      fetches(1)
    }
    LockedPushState.setEncryptedEnrollment(context, false)
  }

  @Test
  fun `eligible lower recovery intent stays admitted below an ineligible higher epoch`() {
    fakeAccount()
    values.setEndpointRegistration(oldTarget.endpoint, oldTarget.p256dh, oldTarget.auth, "default")
    val recovery = importIntent("22222222-2222-4222-8222-222222222222", 1, 2, fixtureKey).copy(recovery = true)
    val superseded = importIntent("55555555-5555-4555-8555-555555555555", 2, 9, fixtureKey).copy(eligible = false)
    enrollment(active = 0, pending = 0, state = {
      it.copy(intents = listOf(recovery, superseded), importSequence = 2, highestEpoch = 9)
    }) {
      UnifiedPushReceiver().onMessage(
        context, PushMessage(v3Event(recovery, "66666666-6666-4666-8666-666666666666", "wake"), true), "default",
      )
      assertEquals("the exact eligible recovery intent promotes", recovery.requestId, values.controlState.activeImportId)
      assertEquals(2L, values.controlState.activeEpoch)
    }
    LockedPushState.setEncryptedEnrollment(context, false)
  }

  @Test
  fun `v3 candidate from another live account is dropped without changing key state or replay state`() {
    LockedPushState.setEncryptedEnrollment(context, false)
    fakeAccount()
    val candidate = importIntent("22222222-2222-4222-8222-222222222222", 1, 1, fixtureKey)
    enrollment(active = 0, pending = 0, state = { it.copy(intents = listOf(candidate), importSequence = 1, highestEpoch = 1) }) {
      val before = values.controlState
      val replayBefore = values.replayState
      fakeAccount("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")

      UnifiedPushReceiver().onMessage(
        context,
        PushMessage(v3Event(candidate, "11111111-1111-4111-8111-111111111111", "wake"), true),
        "default",
      )

      fetches(0)
      org.junit.Assert.assertEquals(before, values.controlState)
      org.junit.Assert.assertEquals(replayBefore, values.replayState)
      org.junit.Assert.assertFalse(LockedPushState.isEncryptedEnrollment(context))
    }
    LockedPushState.setEncryptedEnrollment(context, false)
  }

  @Test
  fun `v3 candidate for another live device is dropped without changing key state or replay state`() {
    LockedPushState.setEncryptedEnrollment(context, false)
    fakeAccount()
    val candidate = importIntent("22222222-2222-4222-8222-222222222222", 1, 1, fixtureKey)
    enrollment(active = 0, pending = 0, state = { it.copy(intents = listOf(candidate), importSequence = 1, highestEpoch = 1) }) {
      val before = values.controlState
      val replayBefore = values.replayState
      values.device = MollySocketDevice(3, "SYNTHETIC-NOT-A-SIGNAL-CREDENTIAL")

      UnifiedPushReceiver().onMessage(
        context,
        PushMessage(v3Event(candidate, "11111111-1111-4111-8111-111111111111", "wake"), true),
        "default",
      )

      fetches(0)
      org.junit.Assert.assertEquals(before, values.controlState)
      org.junit.Assert.assertEquals(replayBefore, values.replayState)
      org.junit.Assert.assertFalse(LockedPushState.isEncryptedEnrollment(context))
    }
    LockedPushState.setEncryptedEnrollment(context, false)
  }

  @Test
  fun `a rejected or replayed event never falls back to the plaintext path while an export is pending`() {
    enrollment(active = 0, pending = 1) {
      val receiver = UnifiedPushReceiver()
      val expired = ("""{"version":1,"event_id":"11111111-1111-4111-8111-111111111111","instance":"default","subscription_epoch":1,""" +
        """"kind":"wake","issued_at":1,"expires_at":2}""").toByteArray()
      receiver.onMessage(context, PushMessage(expired, true), "default")
      receiver.onMessage(context, PushMessage(wake(epoch = 2), true), "default")
      fetches(0)
    }
  }

  @Test
  fun `an export at a new epoch keeps the active epoch accepted until the new one is confirmed`() {
    enrollment(active = 1, pending = 2) {
      val receiver = UnifiedPushReceiver()
      receiver.onMessage(context, PushMessage(wake(epoch = 1), true), "default")
      verify(timeout = 3000, exactly = 1) { PushFetchHandler.handleReceivedNotification(any(), true, any()) }
      verify(exactly = 1) { values.mutateControl(any(), any()) }
      org.junit.Assert.assertEquals(1L, active)
      receiver.onMessage(context, urgent(), "default")
      fetches(1)
      receiver.onMessage(context, PushMessage(wake(epoch = 2, version = 2, id = "22222222-2222-4222-8222-222222222222"), true), "default")
      fetches(1)
      org.junit.Assert.assertEquals("v2 cannot invent the pending import identity", 1L, active)
      receiver.onMessage(context, PushMessage(wake(epoch = 1, id = "33333333-3333-4333-8333-333333333333"), true), "default")
      fetches(2)
    }
  }

  @Test
  fun `stock settings change clears the encrypted enrollment but keeps the replay state`() {
    enrollment(active = 1, pending = 2) {
      LockedPushState.setEncryptedEnrollment(context, true)
      every { SignalStore.settings.forceWebsocketMode } returns org.thoughtcrime.securesms.keyvalue.SettingsValues.ForceWebsocketMode.DISABLED_AUTOMATICALLY
      every { values.vapidPublicKey } returns "old"
      every { values.vapidPublicKey = any() } returns Unit
      every { values.airGapped = any() } returns Unit
      every { values.mollySocketUrl = any() } returns Unit
      every { values.vapidKeySynced = any() } returns Unit
      every { values.endpoint = any() } returns Unit
      val before = replay
      values.updateMollySocket(im.molly.unifiedpush.model.MollySocket.AirGapped("new"))
      org.junit.Assert.assertEquals(0L, active)
      org.junit.Assert.assertEquals(0L, pending)
      org.junit.Assert.assertFalse(LockedPushState.isEncryptedEnrollment(context))
      org.junit.Assert.assertEquals(before, replay)
    }
  }

  @Test
  fun `endpoint received while locked is not applied to the stored values`() {
    every { KeyCachingService.isLocked() } returns true
    clearMocks(values, answers = false, recordedCalls = true)
    UnifiedPushReceiver().onNewEndpoint(context, PushEndpoint("https://example.org/push", PublicKeySet("pub", "auth")), "default")
    verify(exactly = 0) { SignalStore.unifiedpush }
  }

  @Test
  fun `stale default callback cannot replace a scoped endpoint while locked`() {
    every { KeyCachingService.isLocked() } returns true
    val pending = mockPendingEndpointStorage()
    every { LockedPushState.protectedPairingInstance(any()) } returns "pairing-v1-current"

    UnifiedPushReceiver().onNewEndpoint(
      context,
      PushEndpoint("https://push.example.invalid/stale", PublicKeySet("stale-public", "stale-auth")),
      "default",
    )

    assertNull(pending.get())
    verify(exactly = 0) { LockedPushState.savePending(any(), any()) }
  }

  private val fixtureKey = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"
  private val fixtureP256dh = "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4"
  private val fixtureAuth = "MDEyMzQ1Njc4OTo7PD0-Pw"

  private fun importIntent(requestId: String, sequence: Long, epoch: Long, key: String, eligible: Boolean = true) = ImportIntent(
    requestId = requestId,
    sequence = sequence,
    epoch = epoch,
    controlKey = key,
    keyId = ControlKeyId.derive(key, "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", 2, "default"),
    target = Target("https://push.example.invalid/old", fixtureP256dh, fixtureAuth),
    accountId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
    deviceId = 2,
    password = "SYNTHETIC-NOT-A-SIGNAL-CREDENTIAL",
    instance = "default",
    recovery = false,
    eligible = eligible,
  )

  private fun v3Event(intent: ImportIntent, eventId: String, kind: String): ByteArray {
    val now = System.currentTimeMillis() / 1000
    return """{"version":3,"event_id":"$eventId","instance":"${intent.instance}","subscription_epoch":${intent.epoch},"kind":"$kind","issued_at":$now,"expires_at":${now + 300},"key_id":"${intent.keyId}","import_id":"${intent.requestId}"}""".toByteArray()
  }

  private fun statusEvent(epoch: Long, code: String, version: Int = 2, id: String = "33333333-3333-4333-8333-333333333333"): ByteArray {
    val now = System.currentTimeMillis() / 1000
    return ("""{"version":$version,"event_id":"$id","instance":"default","subscription_epoch":$epoch,"kind":"status",""" +
      """"issued_at":$now,"expires_at":${now + 300},"code":"$code"}""").toByteArray()
  }

  private fun withPendingUpdate(epoch: Long, block: () -> Unit) {
    val update = ControlUpdate(epoch, "https://push.example.invalid/new", fixtureP256dh, fixtureAuth, ByteArray(80), 1)
    every { values.statusCode = any() } returns Unit
    val aci = mockk<org.signal.core.models.ServiceId.ACI>()
    every { aci.toString() } returns "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    every { SignalStore.account.aci } returns aci
    enrollment(active = 1, pending = 0, key = fixtureKey, state = {
      it.copy(update = update, highestEpoch = epoch, serverTarget = Target("https://push.example.invalid/old", fixtureP256dh, fixtureAuth))
    }, block = block)
  }

  @Test
  fun `subscription_updated at the pending update's epoch confirms it and is not a server problem`() {
    withPendingUpdate(epoch = 2) {
      healthStore.failures = 1
      val eventBytes = statusEvent(2, "subscription_updated")
      val event = EncryptedPush.parse(eventBytes, System.currentTimeMillis() / 1000, setOf(2))!!
      org.junit.Assert.assertTrue(EndpointUpdates.identityMatches(values.controlState, event))
      UnifiedPushReceiver().onMessage(context, PushMessage(eventBytes, true), "default")
      org.junit.Assert.assertEquals("the whole path worked, so earlier failures stop counting", 0, healthStore.failures)
      org.junit.Assert.assertNull(values.controlState.update)
      org.junit.Assert.assertEquals(2L, active)
      org.junit.Assert.assertEquals("https://push.example.invalid/new", values.controlState.serverTarget!!.endpoint)
      verify(exactly = 0) { values.statusCode = any() }
      verify(exactly = 2) { values.mutateControl(any(), any()) }
      fetches(0)
    }
  }

  @Test
  fun `a confirmation at another epoch, another status, or in event version 1 leaves the update pending`() {
    withPendingUpdate(epoch = 2) {
      val receiver = UnifiedPushReceiver()
      receiver.onMessage(context, PushMessage(statusEvent(1, "subscription_updated"), true), "default")
      receiver.onMessage(context, PushMessage(statusEvent(3, "subscription_updated"), true), "default")
      receiver.onMessage(context, PushMessage(statusEvent(2, "connection_conflict"), true), "default")
      receiver.onMessage(context, PushMessage(statusEvent(2, "subscription_updated", version = 1), true), "default")
      org.junit.Assert.assertEquals(2L, values.controlState.update!!.epoch)
      org.junit.Assert.assertEquals(1L, active)
    }
  }

  @Test
  fun `a keyed enrollment accepts only version 2 events`() {
    enrollment(active = 1, pending = 0, key = fixtureKey) {
      UnifiedPushReceiver().onMessage(context, PushMessage(wake(), true), "default")
      fetches(0)
      UnifiedPushReceiver().onMessage(context, PushMessage(wake(version = 2), true), "default")
      verify(timeout = 3000, exactly = 1) { PushFetchHandler.handleReceivedNotification(any(), true, any()) }
    }
  }

  @Test
  fun `an unkeyed enrollment accepts only version 1 events`() {
    enrollment(active = 1, pending = 0) {
      UnifiedPushReceiver().onMessage(context, PushMessage(wake(version = 2), true), "default")
      fetches(0)
      UnifiedPushReceiver().onMessage(context, PushMessage(wake(), true), "default")
      verify(timeout = 3000, exactly = 1) { PushFetchHandler.handleReceivedNotification(any(), true, any()) }
    }
  }

  private fun fakeAccount(accountId: String = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa") {
    val aci = mockk<org.signal.core.models.ServiceId.ACI>()
    every { aci.toString() } returns accountId
    every { SignalStore.account.aci } returns aci
    every { SignalStore.account.isRegistered } returns true
    every { SignalStore.account.isLinkedDevice } returns false
    every { SignalStore.account.isUnauthorizedReceived } returns false
    every { SignalStore.account.hasAciIdentityKey() } returns true
    every { SignalStore.settings.preferredNotificationMethod } returns
      org.thoughtcrime.securesms.keyvalue.SettingsValues.NotificationDeliveryMethod.UNIFIEDPUSH
    every { SignalStore.settings.preferredNotificationMethod = any() } returns Unit
    every { SignalStore.account.servicePassword } returns "SYNTHETIC-SERVICE-PASSWORD"
    every { SignalStore.account.deviceId } returns 1
    accountIdentityId = accountId
    values.device = MollySocketDevice(2, "SYNTHETIC-NOT-A-SIGNAL-CREDENTIAL")
    every { values.airGapped } returns false
  }

  private fun controlJobAdds(n: Int) = verify(exactly = n) { AppDependencies.jobManager.add(match { it is UnifiedPushControlJob }) }

  private val oldTarget get() = Target("https://push.example.invalid/old", fixtureP256dh, fixtureAuth)

  @Test
  fun `a new endpoint on an imported keyed enrollment stores an update and queues the send`() {
    fakeAccount()
    mockPendingEndpointStorage()
    values.endpoint = "https://push.example.invalid/old"
    enrollment(active = 3, pending = 0, key = fixtureKey, state = { it.copy(highestEpoch = 3, serverTarget = oldTarget) }) {
      UnifiedPushReceiver().onNewEndpoint(context, PushEndpoint("https://push.example.invalid/new", PublicKeySet(fixtureP256dh, fixtureAuth)), "default")
      awaitLifecycleReady()

      awaitCondition("endpoint reconciliation should build its update",) { values.controlState.update != null }
      val update = checkNotNull(values.controlState.update)
      org.junit.Assert.assertEquals(4L, update.epoch)
      org.junit.Assert.assertEquals("https://push.example.invalid/new", update.endpoint)
      org.junit.Assert.assertEquals(4L, values.controlState.highestEpoch)
      verify(timeout = 3000, exactly = 1) { AppDependencies.jobManager.add(match { it is UnifiedPushControlJob }) }
      verify(exactly = 1) { AppDependencies.jobManager.cancelAllInQueue(UnifiedPushControlJob.KEY) }
    }
  }

  @Test
  fun `a new endpoint without a control key or before the server proved the enrollment sends nothing`() {
    fakeAccount()
    mockPendingEndpointStorage()
    values.endpoint = "https://push.example.invalid/old"
    val endpoint = PushEndpoint("https://push.example.invalid/new", PublicKeySet(fixtureP256dh, fixtureAuth))
    enrollment(active = 3, pending = 0, key = null) {
      UnifiedPushReceiver().onNewEndpoint(context, endpoint, "default")
      awaitLifecycleReady()
      controlJobAdds(0)
    }
    values.endpoint = "https://push.example.invalid/old"
    enrollment(active = 0, pending = 1, key = null, state = { it.copy(legacyPending = LegacyPending(fixtureKey, 1, null)) }) {
      UnifiedPushReceiver().onNewEndpoint(context, endpoint, "default")
      awaitLifecycleReady()
      controlJobAdds(0)
    }
  }

  @Test
  fun `an endpoint that changed after the export is sent after the promotion, under the new key`() {
    fakeAccount()
    values.endpoint = "https://push.example.invalid/rotated"
    values.endpointP256dh = fixtureP256dh
    values.endpointAuth = fixtureAuth
    enrollment(active = 0, pending = 2, state = { it.copy(legacyPending = LegacyPending(fixtureKey, 2, oldTarget)) }) {
      UnifiedPushReceiver().onMessage(context, PushMessage(wake(epoch = 2, version = 2), true), "default")
      org.junit.Assert.assertEquals("legacy event cannot promote an unknown import", 0L, active)
      org.junit.Assert.assertNull(values.controlState.key)
      controlJobAdds(0)
    }
    LockedPushState.setEncryptedEnrollment(context, false)
  }

  @Test
  fun `an update lost before it was stored is rebuilt when the app starts or comes to the foreground`() {
    fakeAccount()
    values.endpoint = "https://push.example.invalid/stored-then-killed"
    values.endpointP256dh = fixtureP256dh
    values.endpointAuth = fixtureAuth
    enrollment(active = 3, pending = 0, key = fixtureKey, state = { it.copy(highestEpoch = 3, serverTarget = oldTarget) }) {
      org.junit.Assert.assertNull(values.controlState.update)
      UnifiedPushReceiver.resumeControl()
      org.junit.Assert.assertEquals(4L, values.controlState.update!!.epoch)
      controlJobAdds(1)
    }
  }

  @Test
  fun `coming to the foreground resends an unconfirmed update that has not been tried lately, and not one that has`() {
    fakeAccount()
    val stale = ControlUpdate(4, "https://push.example.invalid/new", fixtureP256dh, fixtureAuth, ByteArray(80), 1, attempts = 5, lastAttemptAt = 10)
    values.endpoint = "https://push.example.invalid/new"
    values.endpointP256dh = fixtureP256dh
    values.endpointAuth = fixtureAuth
    enrollment(active = 3, pending = 0, key = fixtureKey, state = { it.copy(highestEpoch = 4, update = stale, serverTarget = oldTarget) }) {
      UnifiedPushReceiver.resumeControl()
      controlJobAdds(1)
      org.junit.Assert.assertEquals("no new epoch for a resend", 4L, values.controlState.highestEpoch)
      val fresh = stale.afterAttempt(System.currentTimeMillis() / 1000)
      values.mutateControl { it.copy(update = fresh) }
      UnifiedPushReceiver.resumeControl()
      controlJobAdds(1)
    }
  }

  @Test
  fun `a locked app does not resume the control update`() {
    fakeAccount()
    enrollment(active = 3, pending = 0, key = fixtureKey, state = { it.copy(highestEpoch = 3, serverTarget = oldTarget) }) {
      every { KeyCachingService.isLocked() } returns true
      UnifiedPushReceiver.resumeControl()
      controlJobAdds(0)
    }
  }
}
