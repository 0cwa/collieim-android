package im.molly.unifiedpush.health

import org.signal.core.util.eventbus.EventBus
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.events.PushServiceEvent
import org.thoughtcrime.securesms.jobs.UnifiedPushHealthJob
import org.thoughtcrime.securesms.keyvalue.SettingsValues.ForceWebsocketMode
import org.thoughtcrime.securesms.keyvalue.SettingsValues.NotificationDeliveryMethod
import org.thoughtcrime.securesms.keyvalue.SignalStore

/** Applies [PushHealth] decisions to the app: the WebSocket fallback and the pings that check a miss. */
object PushHealthActions {

  private val TAG = Log.tag(PushHealthActions::class.java)
  private val lock = Any()

  /** Restarts the network layer so the message connection follows the new mode. A test replaces it. */
  @androidx.annotation.VisibleForTesting
  internal var resetNetwork: () -> Unit = { AppDependencies.resetNetwork(true) }

  /** Called for every envelope the websocket delivers. Cheap; only an urgent, late one with no push after it counts. */
  @JvmStatic
  fun onEnvelope(urgent: Boolean, serverTimestampMs: Long, deliveredMs: Long) {
    val values = SignalStore.unifiedpush
    if (SignalStore.settings.preferredNotificationMethod != NotificationDeliveryMethod.UNIFIEDPUSH || !values.enabled) return
    if (!PushHealth.isMiss(urgent, serverTimestampMs, deliveredMs, values.lastReceivedTime)) return

    val outcome = synchronized(lock) { PushHealth.recordMiss(values.health, System.currentTimeMillis()) } ?: return
    Log.w(TAG, "An urgent message reached the app ${(deliveredMs - serverTimestampMs) / 1000} s after the server had it and no push came. Failures: ${outcome.failures}")
    apply(outcome)
    // One late message could be a phone that was offline: a ping settles it before it counts for good.
    if (!outcome.enteredFallback && values.controlKey != null) UnifiedPushHealthJob.enqueue(manual = false)
  }

  /** Records the verdict of a ping and applies it. */
  @JvmStatic
  fun onPing(hop: PushHealth.Hop) {
    val outcome = synchronized(lock) { PushHealth.recordPing(SignalStore.unifiedpush.health, hop, System.currentTimeMillis() / 1000) }
    Log.i(TAG, "Push test: ${hop.code}. Failures: ${outcome.failures}")
    apply(outcome)
  }

  /** An accepted push is proof that push works, so earlier failures stop counting. */
  @JvmStatic
  fun onPushAccepted() {
    val health = SignalStore.unifiedpush.health
    if (health.failures != 0 && !health.fallback) synchronized(lock) { health.failures = 0 }
  }

  private fun apply(outcome: PushHealth.Outcome) {
    val settings = SignalStore.settings
    if (outcome.enteredFallback) {
      if (settings.forceWebsocketMode != ForceWebsocketMode.ENABLED_BY_USER) settings.forceWebsocketMode = ForceWebsocketMode.ENABLED_AUTOMATICALLY
      resetNetwork()
    } else if (outcome.leftFallback) {
      if (settings.forceWebsocketMode == ForceWebsocketMode.ENABLED_AUTOMATICALLY) settings.forceWebsocketMode = ForceWebsocketMode.DISABLED_AUTOMATICALLY
      resetNetwork()
    }
    EventBus.default.post(PushServiceEvent)
  }
}
