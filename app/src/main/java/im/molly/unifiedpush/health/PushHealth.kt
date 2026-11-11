package im.molly.unifiedpush.health

import im.molly.unifiedpush.control.ControlUpdate

/**
 * Decides whether the push path works, from two kinds of evidence: an urgent message that reached the app much later
 * than the server received it with no push in between (a miss), and a control ping that was not confirmed in time.
 * Two failures in a row mean push cannot be relied on and the app falls back to WebSocket mode; any success resets
 * the count. Pure rules over a [HealthStore]; the caller applies the [Outcome] (WebSocket mode, network restart).
 */
object PushHealth {

  /** An urgent message older than this when it reaches the app, with no wake after it arrived, counts as a miss. */
  const val MISS_AFTER_MS = 2 * 60 * 1000L

  /** Misses closer together than this are one event: a backlog delivered after being offline is not many failures. */
  const val MISS_WINDOW_MS = 10 * 60 * 1000L

  /** The ping's confirmation must arrive within this long. */
  const val PING_TIMEOUT_MS = 60 * 1000L

  const val FAILURES_TO_FALLBACK = 2

  /** Where the path broke, as far as the phone can tell. [code] is what is stored. */
  enum class Hop(val code: String) {
    NONE("ok"),
    UNAVAILABLE("unavailable"),
    DISTRIBUTOR("distributor"),
    SIGNAL_SEND("signal_send"),
    COLLIESOCKET_OR_PROVIDER("colliesocket_or_provider");

    companion object {
      fun fromCode(code: String?): Hop? = entries.firstOrNull { it.code == code }
    }
  }

  interface HealthStore {
    var failures: Int
    var fallback: Boolean
    var lastResult: String?
    var lastResultAt: Long
    var lastMissAt: Long
  }

  class Outcome(val failures: Int, val enteredFallback: Boolean, val leftFallback: Boolean)

  fun isMiss(urgent: Boolean, serverTimestampMs: Long, deliveredMs: Long, lastPushMs: Long): Boolean =
    urgent && deliveredMs - serverTimestampMs > MISS_AFTER_MS && lastPushMs < serverTimestampMs

  /** Counts a miss unless one was counted within [MISS_WINDOW_MS]. Returns null when it was not counted. */
  fun recordMiss(store: HealthStore, nowMs: Long): Outcome? {
    if (store.lastMissAt != 0L && nowMs - store.lastMissAt < MISS_WINDOW_MS) return null
    store.lastMissAt = nowMs
    return fail(store, Hop.COLLIESOCKET_OR_PROVIDER, nowMs / 1000, keepResult = true)
  }

  /** A ping outcome: [Hop.NONE] is a confirmed round trip, anything else a failure that names the hop. */
  fun recordPing(store: HealthStore, hop: Hop, nowSeconds: Long): Outcome =
    if (hop == Hop.NONE) succeed(store, nowSeconds) else fail(store, hop, nowSeconds, keepResult = false)

  private fun succeed(store: HealthStore, nowSeconds: Long): Outcome {
    val wasFallback = store.fallback
    store.failures = 0
    store.fallback = false
    store.lastResult = Hop.NONE.code
    store.lastResultAt = nowSeconds
    return Outcome(0, enteredFallback = false, leftFallback = wasFallback)
  }

  private fun fail(store: HealthStore, hop: Hop, nowSeconds: Long, keepResult: Boolean): Outcome {
    val failures = store.failures + 1
    val enters = failures >= FAILURES_TO_FALLBACK && !store.fallback
    store.failures = failures
    if (enters) store.fallback = true
    if (!keepResult || store.lastResult == null || store.lastResult == Hop.NONE.code) {
      store.lastResult = hop.code
      store.lastResultAt = nowSeconds
    }
    return Outcome(failures, enteredFallback = enters, leftFallback = false)
  }

  /**
   * Names the failing hop for an update that was not confirmed in time. A missing distributor or endpoint comes
   * first because nothing can reach the app. Then the Signal send: if no send of this blob ever succeeded, the
   * message never left the phone. If it did leave, the confirmation never came back, and the phone cannot tell
   * CollieSocket (it did not apply the update, e.g. a refused endpoint or no Signal connection) from the push
   * provider or distributor that carries the confirmation, so it says both.
   */
  fun diagnose(update: ControlUpdate?, distributorActive: Boolean, hasEndpoint: Boolean): Hop = when {
    !distributorActive || !hasEndpoint -> Hop.DISTRIBUTOR
    update == null -> Hop.UNAVAILABLE
    update.sentAt == 0L -> Hop.SIGNAL_SEND
    else -> Hop.COLLIESOCKET_OR_PROVIDER
  }
}
