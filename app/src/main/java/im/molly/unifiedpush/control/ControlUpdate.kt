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
) {
  override fun toString() = "ControlUpdate(epoch=$epoch)"

  fun sameTarget(subscription: ControlFrame.Subscription) =
    endpoint == subscription.endpoint && p256dh == subscription.p256dh && auth == subscription.auth

  fun afterAttempt(now: Long) = ControlUpdate(epoch, endpoint, p256dh, auth, frame, createdAt, attempts + 1, now)

  fun serialize(): String = mapper.writeValueAsString(
    mapOf(
      "epoch" to epoch, "endpoint" to endpoint, "p256dh" to p256dh, "auth" to auth,
      "frame" to Base64.getUrlEncoder().withoutPadding().encodeToString(frame),
      "created" to createdAt, "attempts" to attempts, "last" to lastAttemptAt,
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
      ).takeIf { it.epoch > 0 && it.frame.size in ControlFrame.HEADER_SIZE..ControlFrame.MAX_FRAME }
    } catch (e: Exception) {
      null
    }
  }
}

/** What the control logic reads and writes; [UnifiedPushValues][org.thoughtcrime.securesms.keyvalue.UnifiedPushValues] in the app. */
interface ControlStore {
  val controlKey: String?
  val enrollmentEpoch: Long
  val pendingEnrollmentEpoch: Long
  val highestEpoch: Long
  val update: ControlUpdate?

  /** Durably stores [update] and [highestEpoch] together, before anything is sent. */
  fun commit(update: ControlUpdate)

  /** Replaces the stored update of the same epoch (attempt bookkeeping only). */
  fun replace(update: ControlUpdate)
}

/** The rules of the endpoint-update state machine in `contracts/encrypted-push/v2` ("Confirmation event and phone state machine"). */
object EndpointUpdates {

  private const val MAX_EPOCH = 9007199254740991L

  /**
   * Records the update for [subscription] and returns it, or returns null if endpoint updates are unavailable
   * (no imported control key, or the server has not proven it imported the enrollment). An unconfirmed update for
   * the same subscription is returned unchanged, so a repeated endpoint callback never allocates another epoch or
   * builds another blob. A different subscription supersedes it with an epoch above everything allocated so far.
   * The caller sends the result.
   */
  fun begin(
    store: ControlStore,
    accountId: String,
    deviceId: Int,
    subscription: ControlFrame.Subscription,
    now: Long,
    nonce: ByteArray? = null,
  ): ControlUpdate? {
    val key = store.controlKey ?: return null
    if (store.enrollmentEpoch <= 0) return null
    store.update?.let { if (it.sameTarget(subscription)) return it }

    val epoch = maxOf(store.highestEpoch, store.enrollmentEpoch, store.pendingEnrollmentEpoch) + 1
    if (epoch > MAX_EPOCH) throw EnrollmentFile.Invalid("invalid_epoch")
    val frame = if (nonce == null) ControlFrame.build(key, accountId, deviceId, epoch, subscription)
    else ControlFrame.build(key, accountId, deviceId, epoch, subscription, nonce)
    val update = ControlUpdate(epoch, subscription.endpoint, subscription.p256dh, subscription.auth, frame, now)
    store.commit(update)
    return update
  }

  /** Only a status event with code `subscription_updated` at the pending update's epoch confirms it. */
  fun confirms(update: ControlUpdate?, event: EncryptedPush.Event): Boolean =
    update != null && event.kind == EncryptedPush.Kind.STATUS &&
      event.code == EncryptedPush.CODE_SUBSCRIPTION_UPDATED && event.epoch == update.epoch

  /** Epochs a receiver accepts events at: the active one, an exported one awaiting import, an update awaiting confirmation. */
  fun acceptedEpochs(store: ControlStore, active: Long, pendingExport: Long): Set<Long> =
    setOf(active, pendingExport, store.update?.epoch ?: 0).filter { it > 0 }.toSet()
}
