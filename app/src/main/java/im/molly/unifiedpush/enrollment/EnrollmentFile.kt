package im.molly.unifiedpush.enrollment

import java.math.BigInteger
import java.util.Base64

/**
 * Builds the protected enrollment file that CollieSocket's `enrollment import` reads.
 *
 * V1 and v2 remain frozen. V3 is defined by `contracts/encrypted-push/v3` in the separate pinned contract resource.
 * The v2 source pin remains [V2_CONTRACT_COMMIT]; the v1 event source pin is held by [EncryptedPush].
 *
 * The file holds the linked-device password, so nothing in here logs or echoes a value: failures carry
 * only a fixed [Invalid.code].
 */
object EnrollmentFile {

  const val V2_CONTRACT_COMMIT = "371a744b2b15ead17cf50000e59cc7d347574b7c"
  /** Canonical source revision for the pinned v3 contract artifacts in test resources. */
  const val V3_CONTRACT_COMMIT = "9751c1effba7c94f49474febd941ca7fe3ba31cc"

  class Invalid(val code: String) : Exception(code)

  data class Input(
    val requestId: String,
    val accountId: String,
    val deviceId: Int,
    val password: String,
    val endpoint: String,
    val p256dh: String,
    val auth: String,
    val instance: String,
    val epoch: Long,
    /** Canonical unpadded base64url of 32 random bytes, see [newControlKey]. */
    val controlKey: String,
    /** Null emits the byte-compatible v2 file; positive values emit v3. */
    val importSequence: Long? = null,
  ) {
    override fun toString() = "EnrollmentFile.Input"
  }

  private val UUID_V4 = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
  private val UUID_ANY = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
  private val PASSWORD = Regex("[ -~]{16,256}")
  private val INSTANCE = Regex("[A-Za-z0-9._:-]{1,128}")
  private val ENDPOINT = Regex("(?i)(https?)://([^/?#]*)(.*)")
  private const val MAX_EPOCH = 9007199254740991L

  /** A fresh 32-byte control key as canonical unpadded base64url. Every export and every new enrollment gets its own. */
  fun newControlKey(random: java.security.SecureRandom = java.security.SecureRandom()): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))

  /** The connector hands keys over as base64url; padding or the standard alphabet are normalized. */
  fun canonicalKey(value: String, size: Int): String {
    val normalized = value.trimEnd('=').replace('+', '-').replace('/', '_')
    val bytes = try {
      Base64.getUrlDecoder().decode(normalized)
    } catch (e: IllegalArgumentException) {
      throw Invalid("invalid_key")
    }
    val canonical = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    if (bytes.size != size || canonical != normalized) throw Invalid("invalid_key")
    return canonical
  }

  // NIST P-256 (secp256r1): y^2 = x^3 - 3x + b over GF(p)
  private val P = BigInteger("ffffffff00000001000000000000000000000000ffffffffffffffffffffffff", 16)
  private val B = BigInteger("5ac635d8aa3a93e7b3ebbd55769886bc651d06b0cc53b0f63bce3c3e27d2604b", 16)

  internal fun requireOnCurve(point: ByteArray) {
    if (point[0] != 4.toByte()) throw Invalid("invalid_key")
    val x = BigInteger(1, point.copyOfRange(1, 33))
    val y = BigInteger(1, point.copyOfRange(33, 65))
    if (x >= P || y >= P) throw Invalid("invalid_key")
    val left = y.multiply(y).mod(P)
    val right = x.pow(3).subtract(x.multiply(BigInteger.valueOf(3))).add(B).mod(P)
    if (left != right) throw Invalid("invalid_key")
  }

  internal fun requireEndpoint(endpoint: String) {
    if (endpoint.length !in 1..2048 || endpoint.any { it !in '!'..'~' } || endpoint.contains('\\') || endpoint.contains('#')) {
      throw Invalid("invalid_endpoint")
    }
    val match = ENDPOINT.matchEntire(endpoint) ?: throw Invalid("invalid_endpoint")
    val authority = match.groupValues[2]
    if (authority.isEmpty() || authority.contains('@')) throw Invalid("invalid_endpoint")
    val hostEnd = if (authority.startsWith("[")) authority.indexOf(']') + 1 else authority.indexOf(':').let { if (it < 0) authority.length else it }
    if (hostEnd <= 0 || (authority.startsWith("[") && hostEnd <= 2)) throw Invalid("invalid_endpoint")
    if (authority.startsWith(":")) throw Invalid("invalid_endpoint")
    val rest = authority.substring(hostEnd)
    if (rest.isNotEmpty()) {
      val port = if (rest.startsWith(":")) rest.substring(1).takeIf { it.isNotEmpty() && it.length <= 5 && it.all { c -> c in '0'..'9' } }?.toInt() else null
      if (port == null || port !in 1..65535) throw Invalid("invalid_endpoint")
    }
  }

  /** Validates [input] against the selected v2/v3 contract and returns the file contents. */
  fun render(input: Input): String {
    if (!UUID_V4.matches(input.requestId) || !UUID_ANY.matches(input.accountId)) throw Invalid("invalid_enrollment")
    if (input.deviceId < 2) throw Invalid("invalid_enrollment")
    if (!PASSWORD.matches(input.password)) throw Invalid("invalid_enrollment")
    if (!INSTANCE.matches(input.instance)) throw Invalid("invalid_enrollment")
    if (input.epoch !in 1..MAX_EPOCH) throw Invalid("invalid_enrollment")
    requireEndpoint(input.endpoint)
    val p256dh = canonicalKey(input.p256dh, 65)
    requireOnCurve(Base64.getUrlDecoder().decode(p256dh))
    val auth = canonicalKey(input.auth, 16)
    val controlKey = canonicalKey(input.controlKey, 32)
    val version = if (input.importSequence == null) 2 else 3
    if (version == 3 && (input.importSequence!! <= 0 || input.controlKey != controlKey)) throw Invalid("invalid_enrollment")

    return buildString {
      append("{\"enrollment_version\":").append(version)
      append(",\"request_id\":").append(json(input.requestId))
      if (version == 3) append(",\"import_seq\":").append(input.importSequence)
      append(",\"account_id\":").append(json(input.accountId))
      append(",\"device_id\":").append(input.deviceId)
      append(",\"password\":").append(json(input.password))
      append(",\"subscription\":{\"endpoint\":").append(json(input.endpoint))
      append(",\"p256dh\":").append(json(p256dh))
      append(",\"auth\":").append(json(auth))
      append(",\"instance\":").append(json(input.instance))
      append(",\"subscription_epoch\":").append(input.epoch)
      append(",\"encryption_required\":true")
      append(if (version == 3) ",\"supported_event_versions\":[1,2,3]" else ",\"supported_event_versions\":[1,2]")
      append(",\"capabilities\":[\"wake\",\"test\",\"status\"]}")
      append(",\"control_key\":").append(json(controlKey)).append("}")
    }
  }

  /** Every value is validated printable ASCII by now, so only the two JSON-significant characters need escaping. */
  internal fun json(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

  /**
   * CollieSocket accepts epoch 1 for an account it has no row for, and otherwise exactly its stored epoch + 1.
   * The app cannot know the server's epoch, so the operator reads it from `enrollment status` and enters it here
   * (null: not enrolled).
   */
  fun nextEpoch(serverEpoch: Long?): Long {
    val current = serverEpoch ?: 0
    if (current !in 0 until MAX_EPOCH) throw Invalid("invalid_epoch")
    return current + 1
  }
}
