package im.molly.unifiedpush.receiver

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import im.molly.unifiedpush.UnifiedPushDistributor
import im.molly.unifiedpush.UnifiedPushNotificationBuilder
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.unmockkConstructor
import io.mockk.unmockkStatic
import io.mockk.verify
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
import im.molly.unifiedpush.model.RegistrationStatus
import im.molly.unifiedpush.control.ControlUpdate
import im.molly.unifiedpush.model.MollySocketDevice
import io.mockk.spyk
import org.signal.storage.keyvalue.KeyValueDataSet
import org.signal.storage.keyvalue.KeyValueStore
import org.signal.storage.keyvalue.MockKeyValuePersistentStorage
import im.molly.unifiedpush.control.ControlState
import im.molly.unifiedpush.control.Target
import org.thoughtcrime.securesms.jobs.UnifiedPushControlJob
import im.molly.unifiedpush.components.settings.app.notifications.updateMollySocket

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

  @Before
  fun setUp() {
    Log.initialize(SystemOutLogger())
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
  }

  @After
  fun tearDown() {
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
    val receiver = UnifiedPushReceiver()
    val endpoint = mockk<PushEndpoint>()
    every { endpoint.url } returns "https://example.org/push"
    every { endpoint.pubKeySet } returns null
    every { values.endpoint } returns endpoint.url
    receiver.onRegistrationFailed(context, FailedReason.NETWORK, "instance")
    receiver.onNewEndpoint(context, endpoint, "instance")
    org.junit.Assert.assertFalse(failed)
    org.junit.Assert.assertFalse(retryScheduled)
    receiver.onRegistrationFailed(context, FailedReason.NETWORK, "instance")

    // Two recovery jobs and one endpoint refresh, even though the endpoint did not change.
    verify(exactly = 3) { AppDependencies.jobManager.add(any<UnifiedPushRefreshJob>()) }
  }

  @Test
  fun `new endpoint stores the connector keys and instance for enrollment export`() {
    val receiver = UnifiedPushReceiver()
    every { values.endpoint } returns null
    every { values.endpoint = any() } returns Unit
    every { values.airGapped } returns false
    val endpoint = PushEndpoint("https://example.org/push", PublicKeySet("pub-key", "auth-secret"))

    receiver.onNewEndpoint(context, endpoint, "default")

    verify { values.endpointP256dh = "pub-key" }
    verify { values.endpointAuth = "auth-secret" }
    verify { values.endpointInstance = "default" }
  }

  @Test
  fun `unregistering clears the stored connector keys`() {
    mockkStatic(UnifiedPush::class)
    try {
      every { UnifiedPush.forceRemoveDistributor(any()) } returns Unit
      every { values.endpoint } returns "https://example.org/push"
      every { values.endpoint = any() } returns Unit

      UnifiedPushReceiver().onUnregistered(context, "default")

      verify { values.endpointP256dh = null }
      verify { values.endpointAuth = null }
      verify { values.endpointInstance = null }
    } finally {
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
    values.mutateControl { state(ControlState(key = key, activeEpoch = active, pendingEpoch = pending)) }
    values.replayState = EncryptedPush.ReplayState(System.currentTimeMillis() / 1000 - 1, emptyList()).serialize()
    every { values.endpointInstance } returns "default"
    every { values.lastReceivedTime = any() } returns Unit
    every { values.enabled } returns true
    every { values.registrationStatus } returns RegistrationStatus.REGISTERED
    every { SignalStore.account.isRegistered } returns true
    mockkStatic(PushFetchHandler::class)
    every { PushFetchHandler.handleReceivedNotification(any(), any()) } returns Unit
    clearMocks(values, answers = false, recordedCalls = true)
    block()
  }

  @After
  fun unmockFetch() = unmockkStatic(PushFetchHandler::class)

  private fun urgent(decrypted: Boolean = true) = PushMessage("""{"urgent":true}""".toByteArray(), decrypted)

  private fun fetches(count: Int) {
    Thread.sleep(400)
    verify(exactly = count) { PushFetchHandler.handleReceivedNotification(any(), any()) }
  }

  @Test
  fun `active enrollment fetches on a decrypted valid wake and refuses its replay`() {
    enrollment(active = 1, pending = 0) {
      val receiver = UnifiedPushReceiver()
      receiver.onMessage(context, PushMessage(wake(), true), "default")
      verify(timeout = 3000, exactly = 1) { PushFetchHandler.handleReceivedNotification(any(), true) }
      receiver.onMessage(context, PushMessage(wake(), true), "default")
      fetches(1)
      verify(exactly = 1) { values.mutateControl(any(), any()) }
    }
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
      verify(timeout = 3000, exactly = 1) { PushFetchHandler.handleReceivedNotification(any(), true) }
    }
  }

  @Test
  fun `an export the server never imported leaves a stock enrollment on plaintext wakes`() {
    enrollment(active = 0, pending = 1) {
      val receiver = UnifiedPushReceiver()
      receiver.onMessage(context, urgent(), "default")
      verify(timeout = 3000, exactly = 1) { PushFetchHandler.handleReceivedNotification(any(), true) }
      receiver.onMessage(context, PushMessage("""{"test":true}""".toByteArray(), true), "default")
      org.junit.Assert.assertEquals(0L, active)
      org.junit.Assert.assertEquals(1L, pending)
    }
  }

  @Test
  fun `an event at the pending epoch promotes it, commits first and ends plaintext wakes`() {
    enrollment(active = 0, pending = 1) {
      val receiver = UnifiedPushReceiver()
      receiver.onMessage(context, PushMessage(wake(), true), "default")
      verify(timeout = 3000, exactly = 1) { PushFetchHandler.handleReceivedNotification(any(), true) }
      org.junit.Assert.assertEquals(1L, active)
      org.junit.Assert.assertEquals(0L, pending)
      org.junit.Assert.assertTrue(LockedPushState.isEncryptedEnrollment(context))

      receiver.onMessage(context, urgent(), "default")
      fetches(1)
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
      verify(timeout = 3000, exactly = 1) { PushFetchHandler.handleReceivedNotification(any(), true) }
      verify(exactly = 1) { values.mutateControl(any(), any()) }
      org.junit.Assert.assertEquals(1L, active)
      receiver.onMessage(context, urgent(), "default")
      fetches(1)
      receiver.onMessage(context, PushMessage(wake(epoch = 2, id = "22222222-2222-4222-8222-222222222222"), true), "default")
      verify(timeout = 3000, exactly = 2) { PushFetchHandler.handleReceivedNotification(any(), true) }
      org.junit.Assert.assertEquals(2L, active)
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

  private val fixtureKey = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"
  private val fixtureP256dh = "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4"
  private val fixtureAuth = "MDEyMzQ1Njc4OTo7PD0-Pw"

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
      UnifiedPushReceiver().onMessage(context, PushMessage(statusEvent(2, "subscription_updated"), true), "default")
      org.junit.Assert.assertEquals("the whole path worked, so earlier failures stop counting", 0, healthStore.failures)
      org.junit.Assert.assertNull(values.controlState.update)
      org.junit.Assert.assertEquals(2L, active)
      org.junit.Assert.assertEquals("https://push.example.invalid/new", values.controlState.serverTarget!!.endpoint)
      verify(exactly = 0) { values.statusCode = any() }
      verify(exactly = 1) { values.mutateControl(any(), any()) }
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
      verify(timeout = 3000, exactly = 1) { PushFetchHandler.handleReceivedNotification(any(), true) }
    }
  }

  @Test
  fun `an unkeyed enrollment accepts only version 1 events`() {
    enrollment(active = 1, pending = 0) {
      UnifiedPushReceiver().onMessage(context, PushMessage(wake(version = 2), true), "default")
      fetches(0)
      UnifiedPushReceiver().onMessage(context, PushMessage(wake(), true), "default")
      verify(timeout = 3000, exactly = 1) { PushFetchHandler.handleReceivedNotification(any(), true) }
    }
  }

  private fun fakeAccount() {
    val aci = mockk<org.signal.core.models.ServiceId.ACI>()
    every { aci.toString() } returns "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    every { SignalStore.account.aci } returns aci
    values.device = MollySocketDevice(2, "SYNTHETIC-NOT-A-SIGNAL-CREDENTIAL")
    every { values.airGapped } returns false
  }

  private fun controlJobAdds(n: Int) = verify(exactly = n) { AppDependencies.jobManager.add(match { it is UnifiedPushControlJob }) }

  private val oldTarget get() = Target("https://push.example.invalid/old", fixtureP256dh, fixtureAuth)

  @Test
  fun `a new endpoint on an imported keyed enrollment stores an update and queues the send`() {
    fakeAccount()
    values.endpoint = "https://push.example.invalid/old"
    enrollment(active = 3, pending = 0, key = fixtureKey, state = { it.copy(highestEpoch = 3, serverTarget = oldTarget) }) {
      UnifiedPushReceiver().onNewEndpoint(context, PushEndpoint("https://push.example.invalid/new", PublicKeySet(fixtureP256dh, fixtureAuth)), "default")

      val update = values.controlState.update!!
      org.junit.Assert.assertEquals(4L, update.epoch)
      org.junit.Assert.assertEquals("https://push.example.invalid/new", update.endpoint)
      org.junit.Assert.assertEquals(4L, values.controlState.highestEpoch)
      controlJobAdds(1)
      verify(exactly = 1) { AppDependencies.jobManager.cancelAllInQueue(UnifiedPushControlJob.KEY) }
    }
  }

  @Test
  fun `a new endpoint without a control key or before the server proved the enrollment sends nothing`() {
    fakeAccount()
    values.endpoint = "https://push.example.invalid/old"
    val endpoint = PushEndpoint("https://push.example.invalid/new", PublicKeySet(fixtureP256dh, fixtureAuth))
    enrollment(active = 3, pending = 0, key = null) {
      UnifiedPushReceiver().onNewEndpoint(context, endpoint, "default")
      controlJobAdds(0)
    }
    values.endpoint = "https://push.example.invalid/old"
    enrollment(active = 0, pending = 1, key = null, state = { it.copy(pendingKey = fixtureKey) }) {
      UnifiedPushReceiver().onNewEndpoint(context, endpoint, "default")
      controlJobAdds(0)
    }
  }

  @Test
  fun `an endpoint that changed after the export is sent after the promotion, under the new key`() {
    fakeAccount()
    values.endpoint = "https://push.example.invalid/rotated"
    values.endpointP256dh = fixtureP256dh
    values.endpointAuth = fixtureAuth
    enrollment(active = 0, pending = 2, state = { it.copy(pendingKey = fixtureKey, pendingTarget = oldTarget) }) {
      UnifiedPushReceiver().onMessage(context, PushMessage(wake(epoch = 2, version = 2), true), "default")
      org.junit.Assert.assertEquals(2L, active)
      org.junit.Assert.assertEquals(fixtureKey, values.controlState.key)
      val update = values.controlState.update!!
      org.junit.Assert.assertEquals(3L, update.epoch)
      org.junit.Assert.assertEquals("https://push.example.invalid/rotated", update.endpoint)
      controlJobAdds(1)
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
