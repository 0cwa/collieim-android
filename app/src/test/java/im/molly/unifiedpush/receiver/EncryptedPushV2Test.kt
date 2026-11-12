package im.molly.unifiedpush.receiver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Plain JVM tests (no libsignal) of event version 2 against the pinned CollieSocket v2 fixture. */
class EncryptedPushV2Test {

  private val status = javaClass.getResourceAsStream("/colliesocket-contract-v2/fixtures/status-updated.json")!!.readBytes()
  private val now = 1800000010L
  private fun text(vararg replace: Pair<String, String>) =
    replace.fold(String(status)) { acc, (from, to) -> acc.replace(from, to) }.toByteArray()

  @Test
  fun `the fixture is a valid version 2 status event`() {
    val event = EncryptedPush.parse(status, now, setOf(1, 2))!!
    assertEquals(2, event.version)
    assertEquals(EncryptedPush.Kind.STATUS, event.kind)
    assertEquals(EncryptedPush.CODE_SUBSCRIPTION_UPDATED, event.code)
    assertEquals(2L, event.epoch)
  }

  @Test
  fun `version 2 is refused unless asked for, and version 1 cannot carry the new code`() {
    assertNull(EncryptedPush.parse(status, now))
    assertNull(EncryptedPush.parse(status, now, setOf(1)))
    assertNull(EncryptedPush.parse(text("\"version\": 2" to "\"version\": 1"), now, setOf(1, 2)))
    assertNotNull(EncryptedPush.parse(text("\"subscription_updated\"" to "\"connection_conflict\"", "\"version\": 2" to "\"version\": 1"), now, setOf(1)))
  }

  @Test
  fun `the new code needs a status event and nothing else about the event changes`() {
    assertNull(EncryptedPush.parse(text("\"status\"" to "\"wake\""), now, setOf(1, 2)))
    assertNull(EncryptedPush.parse(text("\"version\": 2" to "\"version\": 3"), now, setOf(1, 2)))
    assertNull(EncryptedPush.parse(text("\"subscription_updated\"" to "\"other\""), now, setOf(1, 2)))
  }

  private fun decide(epochs: Set<Long>, versionFor: (Long) -> Int) = EncryptedPush.decide(
    content = status, decrypted = true, now = now, locked = { false }, activeInstance = "fixture-instance",
    acceptedEpochs = epochs, state = EncryptedPush.ReplayState(now - 1, emptyList()), versionForEpoch = versionFor,
  )

  @Test
  fun `a keyed epoch accepts version 2 and an unkeyed epoch refuses it`() {
    assertEquals(EncryptedPush.Decision.ACCEPT_STATUS, decide(setOf(2L)) { 2 }.decision)
    assertEquals(EncryptedPush.Decision.DROP_INVALID, decide(setOf(2L)) { 1 }.decision)
    assertEquals(EncryptedPush.Decision.DROP_ENROLLMENT_MISMATCH, decide(setOf(3L)) { 2 }.decision)
  }

  @Test
  fun `a keyed epoch refuses a version 1 event`() {
    val v1 = text("\"subscription_updated\"" to "\"connection_conflict\"", "\"version\": 2" to "\"version\": 1")
    val outcome = EncryptedPush.decide(v1, true, now, { false }, "fixture-instance", setOf(2L),
      EncryptedPush.ReplayState(now - 1, emptyList()), versionForEpoch = { 2 })
    assertEquals(EncryptedPush.Decision.DROP_INVALID, outcome.decision)
  }

  @Test
  fun `a version that only equals 1 or 2 after truncation to an int is refused`() {
    // 2^32 + 2 truncates to 2 as an Int
    assertNull(EncryptedPush.parse(text("\"version\": 2" to "\"version\": 4294967298"), now, setOf(1, 2)))
    assertNull(EncryptedPush.parse(text("\"version\": 2" to "\"version\": 4294967297"), now, setOf(1, 2)))
    assertNotNull(EncryptedPush.parse(status, now, setOf(1, 2)))
  }
}
