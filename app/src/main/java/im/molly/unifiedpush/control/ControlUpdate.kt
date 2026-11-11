package im.molly.unifiedpush.control

import com.fasterxml.jackson.databind.ObjectMapper
import im.molly.unifiedpush.enrollment.EnrollmentFile
import im.molly.unifiedpush.receiver.EncryptedPush
import java.util.Base64

/**
 * An endpoint update the phone has built but the server has not confirmed. [frame] is the exact encrypted blob,
 * kept so every retry resends the same bytes; the target subscription is kept to tell a retry from a newer
 * endpoint. Persisted as JSON in the encrypted key-value store, never logged.
 */
class ControlUpdate(
  val epoch: Long,
  val endpoint: String,
  val p256dh: String,
  val auth: String,
  val frame: ByteArray,
  val createdAt: Long,
  val attempts: Int = 0,
  val lastAttemptAt: Long = 0,
  /** When a send of this blob last succeeded (0: never). A send success is not a confirmation. */
  val sentAt: Long = 0,
  /** Fixed code of the last failed send, never a message or a value. */
  val failure: String? = null,
) {
  override fun toString() = "ControlUpdate(epoch=$epoch)"

  fun sameTarget(subscription: ControlFrame.Subscription) =
    endpoint == subscription.endpoint && p256dh == subscription.p256dh && auth == subscription.auth

  val target get() = Target(endpoint, p256dh, auth)

  /** [error] is a fixed code, or null when the send succeeded. */
  fun afterAttempt(now: Long, error: String? = null) =
    ControlUpdate(epoch, endpoint, p256dh, auth, frame, createdAt, attempts + 1, now, if (error == null) now else sentAt, error)

  fun serialize(): String = mapper.writeValueAsString(
    mapOf(
      "epoch" to epoch, "endpoint" to endpoint, "p256dh" to p256dh, "auth" to auth,
      "frame" to Base64.getUrlEncoder().withoutPadding().encodeToString(frame),
      "created" to createdAt, "attempts" to attempts, "last" to lastAttemptAt,
      "sent" to sentAt, "failure" to failure,
    )
  )

  companion object {
    private val mapper = ObjectMapper()

    /** Null for anything unreadable; the caller then treats the update as lost and builds a new one. */
    fun parse(text: String?): ControlUpdate? = try {
      val root = mapper.readTree(text ?: return null)
      ControlUpdate(
        epoch = root["epoch"].asLong(),
        endpoint = root["endpoint"].asText(),
        p256dh = root["p256dh"].asText(),
        auth = root["auth"].asText(),
        frame = Base64.getUrlDecoder().decode(root["frame"].asText()),
        createdAt = root["created"].asLong(),
        attempts = root["attempts"].asInt(),
        lastAttemptAt = root["last"].asLong(),
        sentAt = root["sent"]?.asLong() ?: 0,
        failure = root["failure"]?.takeIf { it.isTextual }?.asText(),
      ).takeIf { it.epoch > 0 && it.frame.size in ControlFrame.HEADER_SIZE..ControlFrame.MAX_FRAME }
    } catch (e: Exception) {
      null
    }
  }
}
