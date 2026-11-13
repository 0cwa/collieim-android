package im.molly.unifiedpush.receiver

import com.code_intelligence.jazzer.junit.FuzzTest
import com.code_intelligence.jazzer.mutation.annotation.NotNull
import com.code_intelligence.jazzer.mutation.annotation.WithSize
import im.molly.unifiedpush.control.ControlFrame
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.stream.Stream

class EncryptedPushFuzzTest {
  @Test
  fun `pinned v3 event fixtures parse through production code`() {
    val paths = listOf(
      "event-enrollment-imported-v3.json",
      "event-first-v3-adoption-v3.json",
      "event-independent-account-b-v3.json",
      "event-subscription-updated-v3.json",
      "event-test-v3.json",
      "event-wake-v3.json",
    )
    for (path in paths) {
      val bytes = javaClass.getResourceAsStream("/colliesocket-contract-v3/fixtures/$path")!!.use { it.readBytes() }
      assertNotNull(EncryptedPush.parse(bytes, FIXED_NOW, setOf(3)), path)
      assertNull(EncryptedPush.parse(bytes, FIXED_NOW, setOf(1, 2)), path)
    }
  }

  @Test
  fun `valid v3 parser result is stable across repeated invocation`() {
    val bytes = javaClass.getResourceAsStream(
      "/colliesocket-contract-v3/fixtures/event-enrollment-imported-v3.json",
    )!!.use { it.readBytes() }
    assertEquals(EncryptedPush.parse(bytes, FIXED_NOW, setOf(3)), EncryptedPush.parse(bytes, FIXED_NOW, setOf(3)))
  }

  @Test
  fun `production decision gates undecrypted input and accepts a valid synthetic event`() {
    val bytes = javaClass.getResourceAsStream(
      "/colliesocket-contract-v3/fixtures/event-enrollment-imported-v3.json",
    )!!.use { it.readBytes() }
    val state = EncryptedPush.ReplayState(0, emptyList())
    val notDecrypted = EncryptedPush.decide(
      bytes, false, FIXED_NOW, { false }, "fixture-instance", setOf(1, 2, 3, 5, 6, 9), state,
      versionsForEpoch = { setOf(1, 2, 3) },
    )
    assertEquals(EncryptedPush.Decision.DROP_NOT_DECRYPTED, notDecrypted.decision)
    assertEquals(state, notDecrypted.state)

    val parsed = EncryptedPush.parse(bytes, FIXED_NOW, setOf(1, 2, 3))!!
    val accepted = EncryptedPush.decide(
      bytes, true, FIXED_NOW, { false }, parsed.instance, setOf(parsed.epoch), state,
      versionsForEpoch = { setOf(1, 2, 3) },
    )
    assertEquals(EncryptedPush.Decision.ACCEPT_STATUS, accepted.decision)
    assertEquals(parsed.eventId, accepted.event?.eventId)
    assertEquals(FIXED_NOW, accepted.state.clockHighWater)
    assertEquals(1, accepted.state.seen.size)
  }

  @Test
  fun `production control frame builds deterministic authenticated bytes for a fixed nonce`() {
    val subscription = ControlFrame.Subscription(
      "https://push.example.invalid/synthetic-endpoint",
      "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4",
      "BTBZMqHH6r4Tts7J_aSIgg",
    )
    val key = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"
    val nonce = ByteArray(ControlFrame.NONCE_SIZE) { it.toByte() }
    val first = ControlFrame.build(key, ACCOUNT, 2, 1, subscription, nonce)
    val second = ControlFrame.build(key, ACCOUNT, 2, 1, subscription, nonce)
    assertArrayEquals(first, second)
    assertTrue(ControlFrame.matchesIdentity(first, ACCOUNT, 2, 1))
    assertTrue(first.size in (ControlFrame.HEADER_SIZE + ControlFrame.NONCE_SIZE + 16)..ControlFrame.MAX_FRAME)
  }

  @MethodSource("v3EventSeeds")
  @FuzzTest(maxDuration = "60s")
  fun parseArbitraryBoundedBytes(input: @NotNull @WithSize(max = MAX_FUZZ_INPUT) ByteArray) {
    // This is the actual incoming event parser. Invalid inputs are expected to return null; uncaught failures fail fuzzing.
    val parsed = EncryptedPush.parse(input, FIXED_NOW, setOf(1, 2, 3))
    val initialState = EncryptedPush.ReplayState(0, emptyList())
    val first = EncryptedPush.decide(
      input,
      decrypted = true,
      now = FIXED_NOW,
      locked = { false },
      activeInstance = parsed?.instance ?: "fixture-instance",
      acceptedEpochs = parsed?.let { setOf(it.epoch) } ?: emptySet(),
      state = initialState,
      versionsForEpoch = { setOf(1, 2, 3) },
    )
    if (parsed == null) {
      assertEquals(EncryptedPush.Decision.DROP_INVALID, first.decision)
      assertEquals(initialState, first.state)
    } else {
      val expected = when (parsed.kind) {
        EncryptedPush.Kind.WAKE -> EncryptedPush.Decision.ACCEPT_WAKE
        EncryptedPush.Kind.TEST -> EncryptedPush.Decision.ACCEPT_TEST
        EncryptedPush.Kind.STATUS -> EncryptedPush.Decision.ACCEPT_STATUS
      }
      assertEquals(expected, first.decision)
      val replay = EncryptedPush.decide(
        input, true, FIXED_NOW, { false }, parsed.instance, setOf(parsed.epoch), first.state,
        versionsForEpoch = { setOf(1, 2, 3) },
      )
      assertEquals(EncryptedPush.Decision.DROP_DUPLICATE, replay.decision)
      assertEquals(first.state, replay.state)
    }
  }

  private companion object {
    const val FIXED_NOW = 1_800_000_000L
    const val MAX_FUZZ_INPUT = 4_096
    const val ACCOUNT = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"

    @JvmStatic
    fun v3EventSeeds(): Stream<Arguments> = listOf(
      "event-enrollment-imported-v3.json",
      "event-first-v3-adoption-v3.json",
      "event-independent-account-b-v3.json",
      "event-subscription-updated-v3.json",
      "event-test-v3.json",
      "event-wake-v3.json",
    ).stream().map { path ->
      val bytes = EncryptedPushFuzzTest::class.java
        .getResourceAsStream("/colliesocket-contract-v3/fixtures/$path")!!.use { it.readBytes() }
      Arguments.of(bytes)
    }
  }
}
