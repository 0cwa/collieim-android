package im.molly.unifiedpush.health

import im.molly.unifiedpush.control.ControlUpdate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plain JVM tests (no libsignal) of the push health rules. */
class PushHealthTest {

  private class Memory : PushHealth.HealthStore {
    override var failures = 0
    override var fallback = false
    override var lastResult: String? = null
    override var lastResultAt = 0L
    override var lastMissAt = 0L
  }

  private val minute = 60_000L
  private val t0 = 1_800_000_000_000L

  @Test
  fun `an urgent message over two minutes old with no push after it arrived is a miss`() {
    assertTrue(PushHealth.isMiss(true, t0, t0 + 2 * minute + 1, lastPushMs = t0 - minute))
    assertFalse("exactly two minutes", PushHealth.isMiss(true, t0, t0 + 2 * minute, t0 - minute))
    assertFalse("young message", PushHealth.isMiss(true, t0, t0 + minute, t0 - minute))
    assertFalse("not urgent", PushHealth.isMiss(false, t0, t0 + 10 * minute, t0 - minute))
    assertFalse("a push came after the message arrived", PushHealth.isMiss(true, t0, t0 + 10 * minute, lastPushMs = t0 + 1))
    assertFalse("a push at the same instant", PushHealth.isMiss(true, t0, t0 + 10 * minute, lastPushMs = t0 + 5 * minute))
  }

  @Test
  fun `two failures switch to WebSocket mode once`() {
    val store = Memory()
    val first = PushHealth.recordMiss(store, t0)!!
    assertEquals(1, first.failures)
    assertFalse(first.enteredFallback)
    assertFalse(store.fallback)
    val second = PushHealth.recordPing(store, PushHealth.Hop.COLLIESOCKET_OR_PROVIDER, t0 / 1000 + 100)
    assertEquals(2, second.failures)
    assertTrue(second.enteredFallback)
    assertTrue(store.fallback)
    val third = PushHealth.recordPing(store, PushHealth.Hop.SIGNAL_SEND, t0 / 1000 + 200)
    assertEquals(3, third.failures)
    assertFalse("already in fallback", third.enteredFallback)
  }

  @Test
  fun `a success in between resets the count`() {
    val store = Memory()
    PushHealth.recordMiss(store, t0)
    PushHealth.recordPing(store, PushHealth.Hop.NONE, t0 / 1000 + 10)
    assertEquals(0, store.failures)
    val after = PushHealth.recordPing(store, PushHealth.Hop.SIGNAL_SEND, t0 / 1000 + 20)
    assertEquals(1, after.failures)
    assertFalse(store.fallback)
  }

  @Test
  fun `a backlog of late messages counts once and a later miss counts again`() {
    val store = Memory()
    assertEquals(1, PushHealth.recordMiss(store, t0)!!.failures)
    assertNull(PushHealth.recordMiss(store, t0 + 1_000))
    assertNull(PushHealth.recordMiss(store, t0 + 9 * minute))
    assertEquals(1, store.failures)
    assertEquals(2, PushHealth.recordMiss(store, t0 + 10 * minute)!!.failures)
  }

  @Test
  fun `a passing test leaves the WebSocket fallback`() {
    val store = Memory()
    PushHealth.recordPing(store, PushHealth.Hop.DISTRIBUTOR, 1)
    PushHealth.recordPing(store, PushHealth.Hop.DISTRIBUTOR, 2)
    assertTrue(store.fallback)
    val out = PushHealth.recordPing(store, PushHealth.Hop.NONE, 3)
    assertTrue(out.leftFallback)
    assertFalse(store.fallback)
    assertEquals(0, store.failures)
    assertEquals("ok", store.lastResult)
  }

  @Test
  fun `a miss does not overwrite the name of a hop a ping already found failing`() {
    val store = Memory()
    PushHealth.recordPing(store, PushHealth.Hop.SIGNAL_SEND, 5)
    PushHealth.recordMiss(store, t0)
    assertEquals("signal_send", store.lastResult)
    assertEquals(5, store.lastResultAt)
    val fresh = Memory()
    PushHealth.recordMiss(fresh, t0)
    assertEquals(PushHealth.Hop.COLLIESOCKET_OR_PROVIDER.code, fresh.lastResult)
  }

  private fun update(sentAt: Long) = ControlUpdate(2, "https://p.example.invalid/x", "k", "a", ByteArray(60), 1, sentAt = sentAt, ping = true)

  @Test
  fun `the failing hop is named from what the phone knows`() {
    assertEquals(PushHealth.Hop.DISTRIBUTOR, PushHealth.diagnose(update(10), distributorActive = false, hasEndpoint = true))
    assertEquals(PushHealth.Hop.DISTRIBUTOR, PushHealth.diagnose(update(10), distributorActive = true, hasEndpoint = false))
    assertEquals(PushHealth.Hop.SIGNAL_SEND, PushHealth.diagnose(update(0), true, true))
    assertEquals(PushHealth.Hop.COLLIESOCKET_OR_PROVIDER, PushHealth.diagnose(update(10), true, true))
    assertEquals(PushHealth.Hop.UNAVAILABLE, PushHealth.diagnose(null, true, true))
  }

  @Test
  fun `hop codes round trip and unknown codes are null`() {
    PushHealth.Hop.entries.forEach { assertEquals(it, PushHealth.Hop.fromCode(it.code)) }
    assertNull(PushHealth.Hop.fromCode("nonsense"))
    assertNull(PushHealth.Hop.fromCode(null))
  }

  @Test
  fun `thresholds are the issue's`() {
    assertEquals(2 * minute, PushHealth.MISS_AFTER_MS)
    assertEquals(minute, PushHealth.PING_TIMEOUT_MS)
    assertEquals(2, PushHealth.FAILURES_TO_FALLBACK)
  }

  @Test
  fun `a test that could not run is shown but never counted as a failure`() {
    val store = Memory()
    repeat(5) { assertEquals(0, PushHealth.recordPing(store, PushHealth.Hop.UNAVAILABLE, 10L + it).failures) }
    assertFalse(store.fallback)
    assertEquals("unavailable", store.lastResult)
    assertEquals(14, store.lastResultAt)
    // and it does not erase the failures that were already counted
    PushHealth.recordPing(store, PushHealth.Hop.SIGNAL_SEND, 20)
    assertEquals(1, PushHealth.recordPing(store, PushHealth.Hop.UNAVAILABLE, 21).failures)
    assertEquals(1, store.failures)
  }

  @Test
  fun `reset forgets everything`() {
    val store = Memory()
    PushHealth.recordPing(store, PushHealth.Hop.SIGNAL_SEND, 1)
    PushHealth.recordMiss(store, t0)
    PushHealth.recordPing(store, PushHealth.Hop.SIGNAL_SEND, 2)
    assertTrue(store.fallback)
    PushHealth.reset(store)
    assertEquals(0, store.failures)
    assertFalse(store.fallback)
    assertNull(store.lastResult)
    assertEquals(0L, store.lastResultAt)
    assertEquals(0L, store.lastMissAt)
  }
}
