package im.molly.unifiedpush.health

import android.app.Application
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
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
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.jobs.UnifiedPushHealthJob
import org.thoughtcrime.securesms.keyvalue.SettingsValues.ForceWebsocketMode
import org.thoughtcrime.securesms.keyvalue.SettingsValues.NotificationDeliveryMethod
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.keyvalue.UnifiedPushValues
import org.thoughtcrime.securesms.testutil.MockAppDependenciesRule
import org.thoughtcrime.securesms.testutil.MockSignalStoreRule
import org.thoughtcrime.securesms.testutil.SystemOutLogger

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class PushHealthActionsTest {
  @get:Rule val dependencies = MockAppDependenciesRule()
  @get:Rule val signalStore = MockSignalStoreRule()

  private val values = mockk<UnifiedPushValues>(relaxUnitFun = true)
  private val health = object : PushHealth.HealthStore {
    override var failures = 0
    override var fallback = false
    override var lastResult: String? = null
    override var lastResultAt = 0L
    override var lastMissAt = 0L
  }
  private var mode = ForceWebsocketMode.DISABLED_AUTOMATICALLY
  private var lastPush = 0L
  private var resets = 0

  @Before
  fun setUp() {
    Log.initialize(SystemOutLogger())
    every { SignalStore.unifiedpush } returns values
    every { values.health } returns health
    every { values.enabled } returns true
    every { values.controlKey } returns "key"
    every { values.lastReceivedTime } answers { lastPush }
    every { SignalStore.settings.preferredNotificationMethod } returns NotificationDeliveryMethod.UNIFIEDPUSH
    every { SignalStore.settings.forceWebsocketMode } answers { mode }
    every { SignalStore.settings.forceWebsocketMode = any() } answers { mode = firstArg() }
    every { AppDependencies.jobManager.add(any<UnifiedPushHealthJob>()) } returns Unit
    resets = 0
    PushHealthActions.resetNetwork = { resets++ }
  }

  private val minute = 60_000L
  private fun lateUrgent(): Long { val now = System.currentTimeMillis(); PushHealthActions.onEnvelope(true, now - 5 * minute, now); return now }

  @Test
  fun `a first late urgent message counts one failure and asks for a ping instead of switching`() {
    lateUrgent()
    assertEquals(1, health.failures)
    assertFalse(health.fallback)
    assertEquals(ForceWebsocketMode.DISABLED_AUTOMATICALLY, mode)
    verify(exactly = 1) { AppDependencies.jobManager.add(match { it is UnifiedPushHealthJob }) }
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
    lastPush = now - minute
    PushHealthActions.onEnvelope(true, now - 5 * minute, now)
    assertEquals(0, health.failures)
    lastPush = 0
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
}
