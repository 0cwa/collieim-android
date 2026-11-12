package im.molly.unifiedpush.health

import android.app.Application
import im.molly.unifiedpush.control.ControlState
import im.molly.unifiedpush.control.LegacyPending
import im.molly.unifiedpush.components.settings.app.notifications.updateMollySocket
import im.molly.unifiedpush.model.MollySocket
import im.molly.unifiedpush.model.MollySocketDevice
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.util.logging.Log
import org.signal.storage.keyvalue.KeyValueDataSet
import org.signal.storage.keyvalue.KeyValueStore
import org.signal.storage.keyvalue.MockKeyValuePersistentStorage
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.jobs.UnifiedPushHealthJob
import org.thoughtcrime.securesms.keyvalue.SettingsValues.ForceWebsocketMode
import org.thoughtcrime.securesms.keyvalue.SettingsValues.NotificationDeliveryMethod
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.keyvalue.UnifiedPushValues
import org.thoughtcrime.securesms.testutil.MockAppDependenciesRule
import org.thoughtcrime.securesms.testutil.MockSignalStoreRule
import org.thoughtcrime.securesms.testutil.SystemOutLogger
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class PushHealthActionsTest {
  @get:Rule val dependencies = MockAppDependenciesRule()
  @get:Rule val signalStore = MockSignalStoreRule()

  private val values = spyk(UnifiedPushValues(KeyValueStore(MockKeyValuePersistentStorage.withDataSet(KeyValueDataSet()))))
  private var mode = ForceWebsocketMode.DISABLED_AUTOMATICALLY
  private var lockedPush = 0L
  private var resets = 0
  private val queued = mutableListOf<Runnable>()

  /** Runs what the actions post, in the order posted, when the test says so (or at once, see [inline]). */
  private var inline = true

  @Before
  fun setUp() {
    Log.initialize(SystemOutLogger())
    every { SignalStore.unifiedpush } returns values
    every { SignalStore.settings.preferredNotificationMethod } returns NotificationDeliveryMethod.UNIFIEDPUSH
    every { SignalStore.settings.forceWebsocketMode } answers { mode }
    every { SignalStore.settings.forceWebsocketMode = any() } answers { mode = firstArg() }
    values.enabled = true
    keyed()
    PushHealthActions.resetNetwork = { resets++ }
    PushHealthActions.lockedPushArrived = { lockedPush }
    PushHealthActions.executor = Executor { if (inline) it.run() else queued += it }
    every { AppDependencies.jobManager.add(any<UnifiedPushHealthJob>()) } returns Unit
  }

  @After
  fun tearDown() {
    UnifiedPushHealthJob.distributorActive = im.molly.unifiedpush.UnifiedPushDistributor::checkIfActive
  }

  private val health get() = values.health
  private val controlKey = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"

  private fun keyed(pendingEpoch: Long = 0) {
    values.mutateControl { ControlState(key = controlKey, activeEpoch = 2, highestEpoch = 2, legacyPending = pendingEpoch.takeIf { it > 0 }?.let { LegacyPending(null, it, null) }) }
  }

  private val minute = 60_000L
  private fun lateUrgent(): Long { val now = System.currentTimeMillis(); PushHealthActions.onEnvelope(true, now - 5 * minute, now); return now }
  private fun pingJobs(n: Int) = verify(exactly = n) { AppDependencies.jobManager.add(match { it is UnifiedPushHealthJob }) }

  @Test
  fun `a first late urgent message counts one failure and asks for a ping instead of switching`() {
    lateUrgent()
    assertEquals(1, health.failures)
    assertFalse(health.fallback)
    assertEquals(ForceWebsocketMode.DISABLED_AUTOMATICALLY, mode)
    pingJobs(1)
  }

  @Test
  fun `a failed test after a miss switches to WebSocket mode and restarts the network`() {
    lateUrgent()
    PushHealthActions.onPing(PushHealth.Hop.COLLIESOCKET_OR_PROVIDER)
    assertTrue(health.fallback)
    assertEquals(ForceWebsocketMode.ENABLED_AUTOMATICALLY, mode)
    assertEquals(1, resets)
    assertEquals("colliesocket_or_provider", health.lastResult)
  }

  @Test
  fun `a passing test returns to push and a user's own WebSocket choice is never touched`() {
    health.failures = 1
    PushHealthActions.onPing(PushHealth.Hop.SIGNAL_SEND)
    assertEquals(ForceWebsocketMode.ENABLED_AUTOMATICALLY, mode)
    PushHealthActions.onPing(PushHealth.Hop.NONE)
    assertFalse(health.fallback)
    assertEquals(ForceWebsocketMode.DISABLED_AUTOMATICALLY, mode)

    mode = ForceWebsocketMode.ENABLED_BY_USER
    health.failures = 1
    PushHealthActions.onPing(PushHealth.Hop.SIGNAL_SEND)
    assertEquals(ForceWebsocketMode.ENABLED_BY_USER, mode)
    PushHealthActions.onPing(PushHealth.Hop.NONE)
    assertEquals(ForceWebsocketMode.ENABLED_BY_USER, mode)
  }

  @Test
  fun `young messages, non urgent ones and ones after a push do not count, nor does another delivery method`() {
    val now = System.currentTimeMillis()
    PushHealthActions.onEnvelope(true, now - minute, now)
    PushHealthActions.onEnvelope(false, now - 5 * minute, now)
    values.lastReceivedTime = now - minute
    PushHealthActions.onEnvelope(true, now - 5 * minute, now)
    assertEquals(0, health.failures)
    values.lastReceivedTime = 0
    every { SignalStore.settings.preferredNotificationMethod } returns NotificationDeliveryMethod.WEBSOCKET
    PushHealthActions.onEnvelope(true, now - 5 * minute, now)
    assertEquals(0, health.failures)
  }

  @Test
  fun `an accepted push clears earlier failures unless the fallback is active`() {
    health.failures = 1
    PushHealthActions.onPushAccepted()
    assertEquals(0, health.failures)
    health.failures = 2
    health.fallback = true
    PushHealthActions.onPushAccepted()
    assertEquals(2, health.failures)
  }

  // --- B1: a stock server cannot be pinged, and that is not a failure ---

  @Test
  fun `a stock MollySocket user who taps Test my push twice is not forced into WebSocket mode`() {
    // No control key: a stock enrollment. The distributor is fine.
    values.mutateControl { ControlState() }
    values.device = MollySocketDevice(2, "SYNTHETIC-NOT-A-SIGNAL-CREDENTIAL")
    values.endpoint = "https://push.example.invalid/x"
    val aci = mockk<org.signal.core.models.ServiceId.ACI>()
    every { aci.toString() } returns "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    every { SignalStore.account.aci } returns aci
    UnifiedPushHealthJob.distributorActive = { true }
    fun tap() = UnifiedPushHealthJob.Factory().create(
      org.thoughtcrime.securesms.jobmanager.Job.Parameters.Builder().build(),
      org.thoughtcrime.securesms.jobmanager.JsonJobData.Builder().putBoolean("manual", true).serialize()
    ).onRun()

    tap()
    tap()
    tap()
    assertEquals(PushHealth.Hop.UNAVAILABLE.code, health.lastResult)
    assertEquals("shown, never counted", 0, health.failures)
    assertFalse(health.fallback)
    assertEquals(ForceWebsocketMode.DISABLED_AUTOMATICALLY, mode)
    assertEquals(0, resets)
  }

  // --- B5: the screen is told on every way out of the job ---

  private val fixtureP256dh = "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4"
  private val fixtureAuth = "MDEyMzQ1Njc4OTo7PD0-Pw"

  @Test
  fun `a test that ends without a verdict still tells the screen`() {
    val aci = mockk<org.signal.core.models.ServiceId.ACI>()
    every { aci.toString() } returns "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    every { SignalStore.account.aci } returns aci
    values.device = MollySocketDevice(2, "SYNTHETIC-NOT-A-SIGNAL-CREDENTIAL")
    values.endpoint = "https://push.example.invalid/first"
    values.endpointP256dh = fixtureP256dh
    values.endpointAuth = fixtureAuth
    values.mutateControl { ControlState(key = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8", activeEpoch = 2, highestEpoch = 2,
      serverTarget = im.molly.unifiedpush.control.Target("https://push.example.invalid/first", fixtureP256dh, fixtureAuth)) }
    UnifiedPushHealthJob.distributorActive = { true }
    UnifiedPushHealthJob.timeoutMs = 20_000
    var events = 0
    val subscription = org.signal.core.util.eventbus.EventBus.default.subscribe<org.thoughtcrime.securesms.events.PushServiceEvent> { events++ }
    try {
      val job = UnifiedPushHealthJob.Factory().create(
        org.thoughtcrime.securesms.jobmanager.Job.Parameters.Builder().build(),
        org.thoughtcrime.securesms.jobmanager.JsonJobData.Builder().putBoolean("manual", true).serialize()
      )
      val done = java.util.concurrent.CountDownLatch(1)
      Thread { job.onRun(); done.countDown() }.start()
      // wait for the ping to be pending, then a newer endpoint replaces it: the job ends without a verdict
      val deadline = System.currentTimeMillis() + 10_000
      while (values.controlState.update == null && System.currentTimeMillis() < deadline) Thread.sleep(20)
      assertTrue(values.controlState.update!!.ping)
      im.molly.unifiedpush.control.EndpointUpdates.reconcile(
        im.molly.unifiedpush.control.UnifiedPushControlStore(values), "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", 2,
        im.molly.unifiedpush.control.ControlFrame.Subscription("https://push.example.invalid/second", fixtureP256dh, fixtureAuth), 1
      )
      assertTrue(done.await(15, java.util.concurrent.TimeUnit.SECONDS))
      assertEquals("no verdict was recorded", 0L, health.lastResultAt)
      assertEquals(0, health.failures)
      assertTrue("the screen was told", events >= 1)
    } finally {
      subscription.unsubscribe()
      UnifiedPushHealthJob.timeoutMs = PushHealth.PING_TIMEOUT_MS
    }
  }

  // --- B2: pushes dropped while locked are still pushes ---

  @Test
  fun `a message delivered after an unlock is not a miss when a push arrived while the app was locked`() {
    val now = System.currentTimeMillis()
    lockedPush = now - 4 * minute // dropped while locked, after the message reached the server
    PushHealthActions.onEnvelope(true, now - 5 * minute, now)
    assertEquals(0, health.failures)
    lockedPush = now - 6 * minute // before the message: it should have woken the app
    PushHealthActions.onEnvelope(true, now - 5 * minute, now)
    assertEquals(1, health.failures)
  }

  @Test
  fun `only an active keyed enrollment is watched for misses`() {
    values.mutateControl { ControlState() }
    lateUrgent()
    values.mutateControl { ControlState(key = controlKey, legacyPending = LegacyPending(null, 1, null)) } // exported, not yet proven
    lateUrgent()
    assertEquals(0, health.failures)
    pingJobs(0)
    keyed()
    lateUrgent()
    assertEquals(1, health.failures)
  }

  // --- B3: the network restart is not done on the calling thread ---

  @Test
  fun `entering the fallback restarts the network on the executor, not on the message thread`() {
    inline = false
    health.failures = 1
    lateUrgent()
    assertEquals("the miss is counted at once", 2, health.failures)
    assertTrue(health.fallback)
    assertEquals(ForceWebsocketMode.DISABLED_AUTOMATICALLY, mode)
    assertEquals(0, resets)
    assertEquals(1, queued.size)
    queued.forEach { it.run() }
    assertEquals(ForceWebsocketMode.ENABLED_AUTOMATICALLY, mode)
    assertEquals(1, resets)
  }

  // --- B7: no automatic ping while an export awaits import ---

  @Test
  fun `a miss while an export awaits import counts but sends no automatic ping`() {
    // active enrollment with an unimported export waiting
    values.mutateControl { ControlState(key = controlKey, activeEpoch = 2, highestEpoch = 3, legacyPending = LegacyPending(null, 3, null)) }
    lateUrgent()
    assertEquals(1, health.failures)
    pingJobs(0)
    keyed()
    health.failures = 0
    health.lastMissAt = 0
    lateUrgent()
    pingJobs(1)
  }

  // --- B8: a different server forgets the verdicts and the automatic WebSocket mode ---

  @Test
  fun `changing the MollySocket server resets the health state and an automatic WebSocket mode`() {
    health.failures = 2
    health.fallback = true
    health.lastResult = "signal_send"
    health.lastResultAt = 5
    health.lastMissAt = 7
    mode = ForceWebsocketMode.ENABLED_AUTOMATICALLY
    values.vapidPublicKey = "old"
    values.updateMollySocket(MollySocket.AirGapped("new"))
    assertEquals(0, health.failures)
    assertFalse(health.fallback)
    assertEquals(null, health.lastResult)
    assertEquals(0L, health.lastMissAt)
    assertEquals(ForceWebsocketMode.DISABLED_AUTOMATICALLY, mode)
  }

  @Test
  fun `changing the server leaves a WebSocket mode the user chose`() {
    health.fallback = true
    mode = ForceWebsocketMode.ENABLED_BY_USER
    values.vapidPublicKey = "old"
    values.updateMollySocket(MollySocket.AirGapped("new"))
    assertFalse(health.fallback)
    assertEquals(ForceWebsocketMode.ENABLED_BY_USER, mode)
  }

  @Test
  fun `the same server keeps its verdicts`() {
    health.failures = 1
    values.vapidPublicKey = "same"
    values.updateMollySocket(MollySocket.AirGapped("same"))
    assertEquals(1, health.failures)
  }
}
