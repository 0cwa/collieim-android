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
import im.molly.unifiedpush.components.settings.app.notifications.updateMollySocket

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class UnifiedPushReceiverTest {
  @get:Rule val dependencies = MockAppDependenciesRule()
  @get:Rule val signalStore = MockSignalStoreRule()

  private val values = mockk<UnifiedPushValues>(relaxUnitFun = true)
  private val context: Application get() = ApplicationProvider.getApplicationContext()
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

  private fun wake(epoch: Long = 1, id: String = "11111111-1111-4111-8111-111111111111"): ByteArray {
    val now = System.currentTimeMillis() / 1000
    return ("""{"version":1,"event_id":"$id","instance":"default","subscription_epoch":$epoch,"kind":"wake",""" +
      """"issued_at":$now,"expires_at":${now + 300}}""").toByteArray()
  }

  private var active = 0L
  private var pending = 0L
  private var replay: String? = null

  /** Sets up the stored enrollment state (epochs, replay state) and the fetch handler, then runs [block]. */
  private fun enrollment(active: Long, pending: Long, block: () -> Unit) {
    this.active = active
    this.pending = pending
    replay = EncryptedPush.ReplayState(System.currentTimeMillis() / 1000 - 1, emptyList()).serialize()
    every { values.enrollmentEpoch } answers { this@UnifiedPushReceiverTest.active }
    every { values.pendingEnrollmentEpoch } answers { this@UnifiedPushReceiverTest.pending }
    every { values.endpointInstance } returns "default"
    every { values.replayState } answers { replay }
    every { values.commitReplayState(any()) } answers { replay = firstArg() }
    every { values.commitReceived(any(), any()) } answers {
      replay = firstArg(); this@UnifiedPushReceiverTest.active = secondArg(); this@UnifiedPushReceiverTest.pending = 0
    }
    every { values.lastReceivedTime = any() } returns Unit
    every { values.enabled } returns true
    every { values.registrationStatus } returns RegistrationStatus.REGISTERED
    every { SignalStore.account.isRegistered } returns true
    mockkStatic(PushFetchHandler::class)
    every { PushFetchHandler.handleReceivedNotification(any(), any()) } returns Unit
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
      verify(exactly = 1) { values.commitReplayState(any()) }
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
      verify(exactly = 0) { values.commitReplayState(any()) }
      verify(exactly = 0) { values.commitReceived(any(), any()) }
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
      verify(exactly = 0) { values.commitReplayState(any()) }
      verify(exactly = 0) { values.enrollmentEpoch }
    }
    LockedPushState.setEncryptedEnrollment(context, false)
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
      verify(exactly = 0) { values.commitReceived(any(), any()) }
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
      verify(exactly = 1) { values.commitReceived(any(), 1) }
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
      verify(exactly = 1) { values.commitReplayState(any()) }
      org.junit.Assert.assertEquals(1L, active)
      receiver.onMessage(context, urgent(), "default")
      fetches(1)
      receiver.onMessage(context, PushMessage(wake(epoch = 2, id = "22222222-2222-4222-8222-222222222222"), true), "default")
      verify(timeout = 3000, exactly = 2) { PushFetchHandler.handleReceivedNotification(any(), true) }
      verify(exactly = 1) { values.commitReceived(any(), 2) }
      org.junit.Assert.assertEquals(2L, active)
      receiver.onMessage(context, PushMessage(wake(epoch = 1, id = "33333333-3333-4333-8333-333333333333"), true), "default")
      fetches(2)
    }
  }

  @Test
  fun `stock settings change clears the encrypted enrollment but keeps the replay state`() {
    enrollment(active = 1, pending = 2) {
      LockedPushState.setEncryptedEnrollment(context, true)
      every { values.vapidPublicKey } returns "old"
      every { values.vapidPublicKey = any() } returns Unit
      every { values.airGapped = any() } returns Unit
      every { values.mollySocketUrl = any() } returns Unit
      every { values.vapidKeySynced = any() } returns Unit
      every { values.endpoint = any() } returns Unit
      every { values.enrollmentEpoch = any() } answers { active = firstArg() }
      every { values.pendingEnrollmentEpoch = any() } answers { pending = firstArg() }
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
}
