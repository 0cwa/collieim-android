package im.molly.unifiedpush.control

import im.molly.unifiedpush.enrollment.EnrollmentFile
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The endpoint-update frame of `contracts/encrypted-push/v2` ("Exact control wire format") in
 * https://github.com/0cwa/colliesocket, frozen at the v2 source pin [EnrollmentFile.V2_CONTRACT_COMMIT].
 *
 * The frame is the entire content of the Signal envelope CollieSocket receives, so it is not Signal ciphertext:
 * header (magic, version, account, device, epoch), a 12-byte nonce, then AES-256-GCM of the new subscription with
 * the header as additional data. Pure functions; nothing here logs a key, a blob or an endpoint.
 */
object ControlFrame {

  private const val MAGIC = "COLLIESOCKET-CTRL"
  private const val VERSION = 2
  const val HEADER_SIZE = 46
  const val NONCE_SIZE = 12
  private const val TAG_BITS = 128
  const val MAX_PLAINTEXT = 3072
  const val MAX_FRAME = HEADER_SIZE + NONCE_SIZE + MAX_PLAINTEXT + 16
  private const val MAX_EPOCH = 9007199254740991L
  private val HEX_UUID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

  /** The three fields the update carries, validated by the v1 enrollment rules (URL, on-curve key, 16-byte secret). */
  class Subscription(val endpoint: String, p256dh: String, auth: String) {
    val p256dh: String
    val auth: String

    init {
      EnrollmentFile.requireEndpoint(endpoint)
      this.p256dh = EnrollmentFile.canonicalKey(p256dh, 65)
      EnrollmentFile.requireOnCurve(Base64.getUrlDecoder().decode(this.p256dh))
      this.auth = EnrollmentFile.canonicalKey(auth, 16)
    }

    override fun toString() = "ControlFrame.Subscription"
  }

  /** Object members in the order of the contract's fixture; the receiver does not depend on the order. */
  fun plaintext(subscription: Subscription): ByteArray {
    val json = "{\"auth\":" + EnrollmentFile.json(subscription.auth) +
      ",\"endpoint\":" + EnrollmentFile.json(subscription.endpoint) +
      ",\"p256dh\":" + EnrollmentFile.json(subscription.p256dh) + "}"
    return json.toByteArray(Charsets.UTF_8).also { if (it.size > MAX_PLAINTEXT) throw EnrollmentFile.Invalid("invalid_endpoint") }
  }

  fun header(accountId: String, deviceId: Int, epoch: Long): ByteArray {
    if (!HEX_UUID.matches(accountId) || deviceId < 2 || epoch !in 1..MAX_EPOCH) throw EnrollmentFile.Invalid("invalid_enrollment")
    val accountBytes = accountId.replace("-", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    return ByteBuffer.allocate(HEADER_SIZE)
      .put(MAGIC.toByteArray(Charsets.US_ASCII))
      .put(VERSION.toByte())
      .put(accountBytes)
      .putInt(deviceId)
      .putLong(epoch)
      .array()
  }

  /**
   * Builds one frame. A new update uses a fresh random [nonce]; a retry must resend these same bytes and never call
   * this again for the same epoch.
   */
  fun build(
    controlKey: String,
    accountId: String,
    deviceId: Int,
    epoch: Long,
    subscription: Subscription,
    nonce: ByteArray = ByteArray(NONCE_SIZE).also { SecureRandom().nextBytes(it) },
  ): ByteArray {
    require(nonce.size == NONCE_SIZE)
    val key = Base64.getUrlDecoder().decode(EnrollmentFile.canonicalKey(controlKey, 32))
    val header = header(accountId, deviceId, epoch)
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
    cipher.updateAAD(header)
    val sealed = cipher.doFinal(plaintext(subscription))
    return (header + nonce + sealed).also { check(it.size <= MAX_FRAME) }
  }
}
