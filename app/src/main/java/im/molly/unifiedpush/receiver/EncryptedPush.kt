package im.molly.unifiedpush.receiver

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/**
 * Receiver side of `contracts/encrypted-push/v1` and `v2` (event version 2 adds `subscription_updated`) in https://github.com/0cwa/colliesocket, pinned at
 * [CONTRACT_COMMIT] (`event.schema.json`, `protocol.md`). Pure functions: the caller supplies the clock, the lock
 * state and the stored replay state, and persists the state this returns. Nothing here logs or echoes input.
 */
object EncryptedPush {

  const val CONTRACT_COMMIT = "5a2f0cc2590efac50aa4238aeeb19de667e6d4eb"

  /** Padded plaintext size of the wire profile, and the largest event JSON before the padding. */
  const val MAX_INPUT_BYTES = 3993
  const val MAX_JSON_BYTES = 3072
  const val MAX_REPLAY_RECORDS = 512
  private const val REPLAY_GRACE_SECONDS = 60L
  private const val MAX_LIFETIME_SECONDS = 300L
  private const val MAX_FUTURE_SECONDS = 60L
  private const val MAX_JSON_INT = 9007199254740991L

  enum class Kind { WAKE, TEST, STATUS }

  data class Event(
    val version: Int,
    val eventId: String,
    val instance: String,
    val epoch: Long,
    val kind: Kind,
    val code: String?,
    val issuedAt: Long,
    val expiresAt: Long,
  )

  enum class Decision {
    ACCEPT_WAKE, ACCEPT_TEST, ACCEPT_STATUS,
    DROP_LOCKED, DROP_NOT_DECRYPTED, DROP_INVALID, DROP_ENROLLMENT_MISMATCH,
    DROP_CLOCK_ROLLBACK, DROP_DUPLICATE, DROP_REPLAY_CAPACITY;

    val accepted get() = this == ACCEPT_WAKE || this == ACCEPT_TEST || this == ACCEPT_STATUS
  }

  /** What the receiver remembers across restarts: the clock high-water mark and the live event ids. */
  data class ReplayState(val clockHighWater: Long, val seen: List<Seen>) {
    data class Seen(val eventId: String, val expiresAt: Long)

    fun serialize(): String = mapper.writeValueAsString(
      mapOf("hw" to clockHighWater, "seen" to seen.map { listOf(it.eventId, it.expiresAt) })
    )

    companion object {
      /** Returns null for anything unreadable; the caller must then drop rather than guess. */
      fun parse(text: String?): ReplayState? = try {
        val root = mapper.readTree(text ?: return null)
        ReplayState(
          root["hw"].takeIf { it.canConvertToLong() }!!.asLong(),
          root["seen"].map { Seen(it[0].asText(), it[1].takeIf { n -> n.canConvertToLong() }!!.asLong()) },
        )
      } catch (e: Exception) {
        null
      }
    }
  }

  class Outcome(val decision: Decision, val event: Event?, val state: ReplayState)

  private val mapper = ObjectMapper()
  private val eventMapper = ObjectMapper(JsonFactory().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION))
  private val EVENT_ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
  private val INSTANCE = Regex("[A-Za-z0-9._:-]{1,128}")
  private val FIELDS = setOf("version", "event_id", "instance", "subscription_epoch", "kind", "issued_at", "expires_at", "code")
  private val REQUIRED = FIELDS - "code"
  const val CODE_SUBSCRIPTION_UPDATED = "subscription_updated"
  private val CODES = setOf("connection_conflict", "connection_unavailable")
  private val CODES_V2 = CODES + CODE_SUBSCRIPTION_UPDATED

  /**
   * Parses and validates one event against `event.schema.json` plus the time rules of `protocol.md`. Only the
   * event versions in [versions] are accepted.
   * Returns null for anything else: bad UTF-8, a BOM, duplicate or unknown fields, nulls, floats, trailing data,
   * wrong types or values, a lifetime outside 1..300 seconds, an issue time more than 60 seconds ahead of [now],
   * or an event already expired at [now]. Trailing ASCII spaces are the wire profile's padding.
   */
  fun parse(bytes: ByteArray, now: Long, versions: Set<Int> = setOf(1)): Event? = try {
    parseStrict(bytes, now, versions)
  } catch (e: Exception) {
    null
  }

  private fun parseStrict(bytes: ByteArray, now: Long, versions: Set<Int>): Event? {
    if (bytes.size > MAX_INPUT_BYTES) return null
    var end = bytes.size
    while (end > 0 && bytes[end - 1] == ' '.code.toByte()) end--
    if (end > MAX_JSON_BYTES) return null

    val text = Charsets.UTF_8.newDecoder()
      .onMalformedInput(CodingErrorAction.REPORT)
      .onUnmappableCharacter(CodingErrorAction.REPORT)
      .decode(ByteBuffer.wrap(bytes)).toString()
    if (text.startsWith("﻿")) return null

    val root: JsonNode = eventMapper.factory.createParser(text).use { parser ->
      val node = eventMapper.readTree<JsonNode>(parser)
      if (parser.nextToken() != null) return null
      node
    }
    if (!root.isObject || root.fieldNames().asSequence().any { it !in FIELDS } || REQUIRED.any { !root.has(it) }) return null

    fun integer(name: String, min: Long): Long? =
      root[name].takeIf { it.isIntegralNumber && it.canConvertToLong() }?.asLong()?.takeIf { it in min..MAX_JSON_INT }
    fun string(name: String): String? = root[name].takeIf { it.isTextual }?.asText()

    val version = integer("version", 1)?.toInt()?.takeIf { it in versions } ?: return null
    val eventId = string("event_id")?.takeIf { EVENT_ID.matches(it) } ?: return null
    val instance = string("instance")?.takeIf { INSTANCE.matches(it) } ?: return null
    val epoch = integer("subscription_epoch", 1) ?: return null
    val kind = when (string("kind")) {
      "wake" -> Kind.WAKE
      "test" -> Kind.TEST
      "status" -> Kind.STATUS
      else -> return null
    }
    val issuedAt = integer("issued_at", 0) ?: return null
    val expiresAt = integer("expires_at", 1) ?: return null
    val code = if (root.has("code")) string("code")?.takeIf { it in (if (version >= 2) CODES_V2 else CODES) } ?: return null else null
    if ((kind == Kind.STATUS) != (code != null)) return null

    if (expiresAt - issuedAt !in 1..MAX_LIFETIME_SECONDS) return null
    if (issuedAt > now + MAX_FUTURE_SECONDS || expiresAt <= now) return null
    return Event(version, eventId, instance, epoch, kind, code, issuedAt, expiresAt)
  }

  /**
   * True if [bytes] is a JSON object carrying event fields, valid or not. Used only to keep a rejected event from
   * being mistaken for a plaintext wake; it never accepts anything.
   */
  fun isEventShaped(bytes: ByteArray): Boolean = try {
    val root = eventMapper.readTree(String(bytes, Charsets.UTF_8))
    root != null && root.isObject && (root.has("event_id") || root.has("subscription_epoch"))
  } catch (e: Exception) {
    false
  }

  /**
   * The contract's receiver rules in order: lock gate, connector decryption, event validity, active instance and
   * epoch, clock rollback, duplicate, replay capacity, then a second lock check before the state commit.
   * [locked] is called twice, once first and once just before the commit. The caller persists [Outcome.state]
   * only when the decision is an accept, and must run the effect only after that.
   */
  fun decide(
    content: ByteArray,
    decrypted: Boolean,
    now: Long,
    locked: () -> Boolean,
    activeInstance: String,
    acceptedEpochs: Set<Long>,
    state: ReplayState,
    versionForEpoch: (Long) -> Int = { 1 },
  ): Outcome {
    fun drop(decision: Decision, event: Event? = null) = Outcome(decision, event, state)

    if (locked()) return drop(Decision.DROP_LOCKED)
    if (!decrypted) return drop(Decision.DROP_NOT_DECRYPTED)
    val event = parse(content, now, setOf(1, 2)) ?: return drop(Decision.DROP_INVALID)
    if (event.instance != activeInstance || event.epoch !in acceptedEpochs) return drop(Decision.DROP_ENROLLMENT_MISMATCH)
    // A keyed (version 2) enrollment sends every event as version 2, and an unkeyed one only version 1.
    if (event.version != versionForEpoch(event.epoch)) return drop(Decision.DROP_INVALID)
    if (now < state.clockHighWater) return drop(Decision.DROP_CLOCK_ROLLBACK)
    val retained = state.seen.filter { it.expiresAt + REPLAY_GRACE_SECONDS > now }
    if (retained.any { it.eventId == event.eventId }) return drop(Decision.DROP_DUPLICATE)
    if (retained.size >= MAX_REPLAY_RECORDS) return drop(Decision.DROP_REPLAY_CAPACITY)
    if (locked()) return drop(Decision.DROP_LOCKED)

    val decision = when (event.kind) {
      Kind.WAKE -> Decision.ACCEPT_WAKE
      Kind.TEST -> Decision.ACCEPT_TEST
      Kind.STATUS -> Decision.ACCEPT_STATUS
    }
    return Outcome(decision, event, ReplayState(now, retained + ReplayState.Seen(event.eventId, event.expiresAt)))
  }
}
