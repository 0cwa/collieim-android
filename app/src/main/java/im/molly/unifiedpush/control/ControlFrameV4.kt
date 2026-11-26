package im.molly.unifiedpush.control

import im.molly.unifiedpush.enrollment.EnrollmentFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Receiver-specific encrypted-push v4 Signal control frame. */
object ControlFrameV4 {
  private const val MAGIC = "COLLIESOCKET-CTRL"
  const val HEADER_SIZE = 62
  const val NONCE_SIZE = 12
  const val MAX_PLAINTEXT = 3072
  const val MAX_FRAME = HEADER_SIZE + NONCE_SIZE + MAX_PLAINTEXT + 16
  private const val MAX_EPOCH = 9007199254740991L
  private val UUID_ANY = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
  private val UUID_V4 = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")

  fun plaintext(subscription: ControlFrame.Subscription): ByteArray {
    EnrollmentFile.requireEndpoint(subscription.endpoint)
    if (!subscription.endpoint.startsWith("https://")) throw EnrollmentFile.Invalid("invalid_endpoint")
    return "{\"endpoint\":${EnrollmentFile.json(subscription.endpoint)},\"p256dh\":${EnrollmentFile.json(subscription.p256dh)},\"auth\":${EnrollmentFile.json(subscription.auth)}}"
      .toByteArray(Charsets.UTF_8).also { if (it.size > MAX_PLAINTEXT) throw EnrollmentFile.Invalid("invalid_endpoint") }
  }

  fun header(accountId: String, deviceId: Int, receiverId: String, epoch: Long): ByteArray {
    if (!UUID_ANY.matches(accountId) || deviceId < 2 || !UUID_V4.matches(receiverId) || epoch !in 1..MAX_EPOCH) {
      throw EnrollmentFile.Invalid("invalid_enrollment")
    }
    return ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.BIG_ENDIAN)
      .put(MAGIC.toByteArray(Charsets.US_ASCII)).put(4)
      .put(uuidBytes(accountId)).putInt(deviceId).put(uuidBytes(receiverId)).putLong(epoch).array()
  }

  fun matchesIdentity(frame: ByteArray, accountId: String, deviceId: Int, receiverId: String, epoch: Long): Boolean =
    frame.size >= HEADER_SIZE && runCatching { frame.copyOfRange(0, HEADER_SIZE).contentEquals(header(accountId, deviceId, receiverId, epoch)) }.getOrDefault(false)

  fun build(
    controlKey: String,
    accountId: String,
    deviceId: Int,
    receiverId: String,
    epoch: Long,
    subscription: ControlFrame.Subscription,
    nonce: ByteArray = ByteArray(NONCE_SIZE).also(SecureRandom()::nextBytes),
  ): ByteArray {
    require(nonce.size == NONCE_SIZE)
    val key = Base64.getUrlDecoder().decode(EnrollmentFile.canonicalKey(controlKey, 32))
    val header = header(accountId, deviceId, receiverId, epoch)
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
    cipher.updateAAD(header)
    return (header + nonce + cipher.doFinal(plaintext(subscription))).also { check(it.size <= MAX_FRAME) }
  }

  private fun uuidBytes(value: String): ByteArray = value.replace("-", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
