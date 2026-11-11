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

  /** Runs [block] with an encrypted enrollment (epoch 1, instance "default") and returns the stored replay state. */
  private fun encrypted(block: () -> Unit): () -> String? {
    var replay: String? = EncryptedPush.ReplayState(System.currentTimeMillis() / 1000 - 1, emptyList()).serialize()
    every { values.enrollmentEpoch } returns 1
    every { values.endpointInstance } returns "default"
    every { values.replayState } answers { replay }
    every { values.replayState = any() } answers { replay = firstArg() }
    every { values.lastReceivedTime = any() } returns Unit
    every { values.enabled } returns true
    every { SignalStore.account.isRegistered } returns true
    mockkStatic(PushFetchHandler::class)
    every { PushFetchHandler.handleReceivedNotification(any(), any()) } returns Unit
    block()
    return { replay }
  }

  @After
  fun unmockFetch() = unmockkStatic(PushFetchHandler::class)

  @Test
  fun `encrypted enrollment fetches on a decrypted valid wake and refuses its replay`() {
    encrypted {
      val receiver = UnifiedPushReceiver()
      receiver.onMessage(context, PushMessage(wake(), true), "default")
      verify(timeout = 3000, exactly = 1) { PushFetchHandler.handleReceivedNotification(any(), true) }
      receiver.onMessage(context, PushMessage(wake(), true), "default")
      Thread.sleep(300)
      verify(exactly = 1) { PushFetchHandler.handleReceivedNotification(any(), any()) }
    }
  }

  @Test
  fun `encrypted enrollment refuses plaintext wakes, undecrypted pushes and other epochs without any effect`() {
    val replay = encrypted {
      val receiver = UnifiedPushReceiver()
      receiver.onMessage(context, PushMessage("""{"urgent":true}""".toByteArray(), true), "default")
      receiver.onMessage(context, PushMessage("""{"urgent":true}""".toByteArray(), false), "default")
      receiver.onMessage(context, PushMessage("""{"test":true}""".toByteArray(), true), "default")
      receiver.onMessage(context, PushMessage(wake(), false), "default")
      receiver.onMessage(context, PushMessage(wake(epoch = 2), true), "default")
      Thread.sleep(300)
      verify(exactly = 0) { PushFetchHandler.handleReceivedNotification(any(), any()) }
      verify(exactly = 0) { values.replayState = any() }
    }
    org.junit.Assert.assertNotNull(replay())
  }

  @Test
  fun `locked encrypted enrollment drops pushes without reading stored state`() {
    encrypted {
      LockedPushState.setEncryptedEnrollment(context, true)
      every { KeyCachingService.isLocked() } returns true
      clearMocks(values, answers = false, recordedCalls = true)
      UnifiedPushReceiver().onMessage(context, PushMessage(wake(), true), "default")
      UnifiedPushReceiver().onMessage(context, PushMessage("""{"urgent":true}""".toByteArray(), true), "default")
      Thread.sleep(300)
      verify(exactly = 0) { PushFetchHandler.handleReceivedNotification(any(), any()) }
      verify(exactly = 0) { values.replayState = any() }
      verify(exactly = 0) { values.enrollmentEpoch }
    }
    LockedPushState.setEncryptedEnrollment(context, false)
  }

  @Test
  fun `stock enrollment still fetches on a plaintext wake`() {
    encrypted {
      every { values.enrollmentEpoch } returns 0
      UnifiedPushReceiver().onMessage(context, PushMessage("""{"urgent":true}""".toByteArray(), true), "default")
      verify(timeout = 3000, exactly = 1) { PushFetchHandler.handleReceivedNotification(any(), true) }
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
