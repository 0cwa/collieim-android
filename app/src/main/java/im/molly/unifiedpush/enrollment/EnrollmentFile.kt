package im.molly.unifiedpush.enrollment

import java.math.BigInteger
import java.security.MessageDigest
import java.util.Base64

/**
 * Builds the protected enrollment file that CollieSocket's `enrollment import` reads.
 *
 * The format is defined by `contracts/encrypted-push/v1` (`enrollment.schema.json`, `protocol.md`) in
 * https://github.com/0cwa/colliesocket, pinned at [CONTRACT_COMMIT].
 *
 * The file holds the linked-device password, so nothing in here logs or echoes a value: failures carry
 * only a fixed [Invalid.code].
 */
object EnrollmentFile {

  const val CONTRACT_COMMIT = "5a2f0cc2590efac50aa4238aeeb19de667e6d4eb"

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
  ) {
    override fun toString() = "EnrollmentFile.Input"
  }

  private val UUID_V4 = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
  private val UUID_ANY = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
  private val PASSWORD = Regex("[ -~]{16,256}")
  private val INSTANCE = Regex("[A-Za-z0-9._:-]{1,128}")
  private val ENDPOINT = Regex("(?i)(https?)://([^/?#]*)(.*)")
  private const val MAX_EPOCH = 9007199254740991L

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

  private fun requireOnCurve(point: ByteArray) {
    if (point[0] != 4.toByte()) throw Invalid("invalid_key")
    val x = BigInteger(1, point.copyOfRange(1, 33))
    val y = BigInteger(1, point.copyOfRange(33, 65))
    if (x >= P || y >= P) throw Invalid("invalid_key")
    val left = y.multiply(y).mod(P)
    val right = x.pow(3).subtract(x.multiply(BigInteger.valueOf(3))).add(B).mod(P)
    if (left != right) throw Invalid("invalid_key")
  }

  private fun requireEndpoint(endpoint: String) {
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

  /** Validates [input] against the v1 contract and returns the file contents. */
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

    return buildString {
      append("{\"enrollment_version\":1")
      append(",\"request_id\":").append(json(input.requestId))
      append(",\"account_id\":").append(json(input.accountId))
      append(",\"device_id\":").append(input.deviceId)
      append(",\"password\":").append(json(input.password))
      append(",\"subscription\":{\"endpoint\":").append(json(input.endpoint))
      append(",\"p256dh\":").append(json(p256dh))
      append(",\"auth\":").append(json(auth))
      append(",\"instance\":").append(json(input.instance))
      append(",\"subscription_epoch\":").append(input.epoch)
      append(",\"encryption_required\":true")
      append(",\"supported_event_versions\":[1]")
      append(",\"capabilities\":[\"wake\",\"test\",\"status\"]}}")
    }
  }

  /** Every value is validated printable ASCII by now, so only the two JSON-significant characters need escaping. */
  private fun json(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

  /**
   * CollieSocket requires epoch 1 first and exactly previous+1 for any changed endpoint, key, instance or
   * credential. An unchanged subscription keeps its epoch (the server answers `unchanged`).
   */
  fun epochFor(lastEpoch: Long, lastFingerprint: String?, fingerprint: String): Long = when {
    lastFingerprint == null || lastEpoch < 1 -> 1
    lastFingerprint == fingerprint -> lastEpoch
    else -> lastEpoch + 1
  }

  /** Digest of everything a changed epoch must cover; stored instead of the secrets themselves. */
  fun fingerprint(input: Input): String {
    val text = listOf(input.accountId, input.deviceId.toString(), input.password, input.endpoint, input.p256dh, input.auth, input.instance).joinToString("\n")
    return MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
  }
}
