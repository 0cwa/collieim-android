package im.molly.unifiedpush.enrollment

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.security.SecureRandom
import java.util.Base64

/** Builds the receiver-only encrypted-push v4 import. It deliberately has no Signal password field. */
object ReceiverEnrollmentV4 {
  const val CONTRACT_COMMIT = "f8db2525d9ea25661efd4903037cc3f2ecdbe918"
  private val mapper = ObjectMapper(JsonFactory().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION))
  private val UUID_V4 = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
  private val UUID_ANY = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
  private val INSTANCE = Regex("[A-Za-z0-9._:-]{1,128}")

  data class Input(
    val requestId: String,
    val accountId: String,
    val bridgeDeviceId: Int,
    val endpoint: String,
    val p256dh: String,
    val auth: String,
    val instance: String,
    val receiverId: String,
    val controlKey: String,
    val label: String? = null,
  )

  fun newUuid(random: SecureRandom = SecureRandom()): String {
    val bytes = ByteArray(16).also(random::nextBytes)
    bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x40).toByte()
    bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
    val hex = bytes.joinToString("") { "%02x".format(it) }
    return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}"
  }

  fun sanitizedLabel(value: String): String = value.map { if (it in ' '..'~') it else '-' }.joinToString("")
    .take(64).ifEmpty { "Receiver" }

  fun render(input: Input): String {
    if (!UUID_V4.matches(input.requestId) || !UUID_ANY.matches(input.accountId) || !UUID_V4.matches(input.receiverId) ||
      input.bridgeDeviceId !in 2..Int.MAX_VALUE || !INSTANCE.matches(input.instance)) throw EnrollmentFile.Invalid("invalid_enrollment")
    EnrollmentFile.requireEndpoint(input.endpoint)
    if (!input.endpoint.startsWith("https://")) throw EnrollmentFile.Invalid("invalid_endpoint")
    val p256dh = EnrollmentFile.canonicalKey(input.p256dh, 65)
    EnrollmentFile.requireOnCurve(Base64.getUrlDecoder().decode(p256dh))
    val auth = EnrollmentFile.canonicalKey(input.auth, 16)
    val controlKey = EnrollmentFile.canonicalKey(input.controlKey, 32)
    if (input.controlKey != controlKey) throw EnrollmentFile.Invalid("invalid_enrollment")
    val label = input.label?.also { if (it.length !in 1..64 || it.any { c -> c !in ' '..'~' }) throw EnrollmentFile.Invalid("invalid_enrollment") }
    return buildString {
      append("{\"enrollment_version\":4,\"request_id\":").append(EnrollmentFile.json(input.requestId))
      append(",\"import_seq\":1,\"account_id\":").append(EnrollmentFile.json(input.accountId))
      append(",\"device_id\":").append(input.bridgeDeviceId)
      append(",\"subscription\":{\"endpoint\":").append(EnrollmentFile.json(input.endpoint))
      append(",\"p256dh\":").append(EnrollmentFile.json(p256dh)).append(",\"auth\":").append(EnrollmentFile.json(auth))
      append(",\"instance\":").append(EnrollmentFile.json(input.instance)).append(",\"subscription_epoch\":1")
      append(",\"encryption_required\":true,\"supported_event_versions\":[1,2,3,4],\"capabilities\":[\"wake\",\"test\",\"status\"]}")
      append(",\"control_key\":").append(EnrollmentFile.json(controlKey))
      append(",\"receiver_id\":").append(EnrollmentFile.json(input.receiverId))
      if (label != null) append(",\"label\":").append(EnrollmentFile.json(label))
      append('}')
    }.also(::validate)
  }

  /** Strict structural validation before pairing encryption; schema fixtures pin exact shape and constants. */
  fun validate(json: String) {
    if (json.toByteArray(Charsets.UTF_8).size !in 1..8192 || json.startsWith('\uFEFF')) throw EnrollmentFile.Invalid("invalid_enrollment")
    try {
      val root = mapper.factory.createParser(json).use { parser ->
        val value = mapper.readTree<JsonNode>(parser) ?: throw IllegalArgumentException()
        if (parser.nextToken() != null) throw IllegalArgumentException()
        value
      }
      val required = setOf("enrollment_version", "request_id", "import_seq", "account_id", "device_id", "subscription", "control_key", "receiver_id")
      val fields = required + "label"
      require(root.isObject && root.fieldNames().asSequence().toSet().all(fields::contains) && required.all(root::has))
      require(root["enrollment_version"].intValue() == 4 && root["import_seq"].longValue() == 1L)
      val subscription = root["subscription"]
      require(subscription.isObject && subscription.fieldNames().asSequence().toSet() == setOf("endpoint", "p256dh", "auth", "instance", "subscription_epoch", "encryption_required", "supported_event_versions", "capabilities"))
      require(subscription["subscription_epoch"].longValue() == 1L && subscription["encryption_required"].booleanValue())
      require(subscription["supported_event_versions"].toString() == "[1,2,3,4]" && subscription["capabilities"].toString() == "[\"wake\",\"test\",\"status\"]")
      val input = Input(root["request_id"].textValue(), root["account_id"].textValue(), root["device_id"].intValue(),
        subscription["endpoint"].textValue(), subscription["p256dh"].textValue(), subscription["auth"].textValue(),
        subscription["instance"].textValue(), root["receiver_id"].textValue(), root["control_key"].textValue(), root["label"]?.textValue())
      // Reuse render's field-level contract checks without generating or changing any identity.
      validateFields(input)
    } catch (_: Exception) {
      throw EnrollmentFile.Invalid("invalid_enrollment")
    }
  }

  private fun validateFields(input: Input) {
    if (!UUID_V4.matches(input.requestId) || !UUID_ANY.matches(input.accountId) || !UUID_V4.matches(input.receiverId) || input.bridgeDeviceId < 2 || !INSTANCE.matches(input.instance)) throw EnrollmentFile.Invalid("invalid_enrollment")
    EnrollmentFile.requireEndpoint(input.endpoint)
    require(input.endpoint.startsWith("https://"))
    val point = Base64.getUrlDecoder().decode(EnrollmentFile.canonicalKey(input.p256dh, 65))
    EnrollmentFile.requireOnCurve(point)
    EnrollmentFile.canonicalKey(input.auth, 16)
    require(input.controlKey == EnrollmentFile.canonicalKey(input.controlKey, 32))
    input.label?.let { require(it.length in 1..64 && it.all { c -> c in ' '..'~' }) }
  }
}
