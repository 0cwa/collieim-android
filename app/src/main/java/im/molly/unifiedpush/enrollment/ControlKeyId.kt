package im.molly.unifiedpush.enrollment

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Stable v3 key identity. This is an identifier, not an event MAC or proof of delivery. */
object ControlKeyId {
  private val ACCOUNT_ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
  private val INSTANCE = Regex("[A-Za-z0-9._:-]{1,128}")
  private val DOMAIN = "COLLIESOCKET-EVENT-KEY-ID".toByteArray(Charsets.US_ASCII)

  fun derive(controlKey: String, accountId: String, deviceId: Int, instance: String): String {
    if (!ACCOUNT_ID.matches(accountId) || deviceId < 2 || !INSTANCE.matches(instance)) throw EnrollmentFile.Invalid("invalid_enrollment")
    val canonicalKey = EnrollmentFile.canonicalKey(controlKey, 32)
    if (canonicalKey != controlKey) throw EnrollmentFile.Invalid("invalid_key")
    val key = Base64.getUrlDecoder().decode(canonicalKey)
    val account = accountId.filterNot { it == '-' }.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    val instanceBytes = instance.toByteArray(Charsets.US_ASCII)
    val message = ByteBuffer.allocate(DOMAIN.size + 2 + account.size + 4 + 2 + instanceBytes.size)
      .order(ByteOrder.BIG_ENDIAN)
      .put(DOMAIN)
      .put(0.toByte())
      .put(3.toByte())
      .put(account)
      .putInt(deviceId)
      .putShort(instanceBytes.size.toShort())
      .put(instanceBytes)
      .array()
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key, "HmacSHA256"))
    return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(message))
  }

  fun isCanonical(value: String): Boolean = value.length == 43 && runCatching {
    Base64.getUrlEncoder().withoutPadding().encodeToString(Base64.getUrlDecoder().decode(value)) == value &&
      Base64.getUrlDecoder().decode(value).size == 32
  }.getOrDefault(false)
}
