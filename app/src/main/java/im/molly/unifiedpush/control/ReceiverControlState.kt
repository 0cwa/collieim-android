package im.molly.unifiedpush.control

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import im.molly.unifiedpush.enrollment.EnrollmentFile
import im.molly.unifiedpush.receiver.EncryptedPush
import java.util.Base64

/** Durable identity and one retryable update for this phone's v4 receiver on a remote bridge. */
data class ReceiverControlState(
  val receiverId: String,
  val importId: String,
  val accountId: String,
  val bridgeDeviceId: Int,
  val instance: String,
  val hostLabel: String,
  val controlKey: String,
  val epoch: Long,
  val target: Target,
  val confirmed: Boolean = false,
  val pendingEpoch: Long? = null,
  val pendingTarget: Target? = null,
  val pendingFrame: ByteArray? = null,
  val pendingCreatedAt: Long = 0,
  val pendingSentAt: Long = 0,
) {
  fun serialize(): String = mapper.writeValueAsString(linkedMapOf(
    "receiver_id" to receiverId, "import_id" to importId, "account_id" to accountId,
    "bridge_device_id" to bridgeDeviceId, "instance" to instance, "host_label" to hostLabel, "control_key" to controlKey,
    "epoch" to epoch, "target" to targetMap(target), "confirmed" to confirmed, "pending_epoch" to pendingEpoch,
    "pending_target" to pendingTarget?.let(::targetMap),
    "pending_frame" to pendingFrame?.let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) },
    "pending_created_at" to pendingCreatedAt, "pending_sent_at" to pendingSentAt,
  ))

  fun identityMatches(event: EncryptedPush.Event, currentAccountId: String): Boolean = accountId == currentAccountId && event.version == 4 &&
    event.receiverId == receiverId && event.instance == instance && event.importId == importId && when {
      event.epoch == epoch -> if (!confirmed) event.kind == EncryptedPush.Kind.STATUS && event.code == EncryptedPush.CODE_ENROLLMENT_IMPORTED
        else true
      event.epoch == pendingEpoch -> event.kind == EncryptedPush.Kind.STATUS && event.code == EncryptedPush.CODE_SUBSCRIPTION_UPDATED
      else -> false
    }

  fun accepted(event: EncryptedPush.Event, currentAccountId: String): ReceiverControlState? {
    if (!identityMatches(event, currentAccountId) || event.kind != EncryptedPush.Kind.STATUS) return this
    return when {
      event.code == EncryptedPush.CODE_ENROLLMENT_IMPORTED && event.epoch == epoch -> copy(confirmed = true)
      event.code == EncryptedPush.CODE_SUBSCRIPTION_UPDATED && event.epoch == pendingEpoch -> copy(
        epoch = event.epoch,
        target = pendingTarget ?: return this,
        pendingEpoch = null, pendingTarget = null, pendingFrame = null, pendingCreatedAt = 0, pendingSentAt = 0,
      )
      else -> this
    }
  }

  companion object {
    private val mapper = ObjectMapper(JsonFactory().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION))
    private val UUID_V4 = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
    private val UUID_ANY = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    private val INSTANCE = Regex("[A-Za-z0-9._:-]{1,128}")
    private val FIELDS = setOf("receiver_id", "import_id", "account_id", "bridge_device_id", "instance", "host_label", "control_key", "epoch", "target", "confirmed", "pending_epoch", "pending_target", "pending_frame", "pending_created_at", "pending_sent_at")

    fun parse(text: String?): ReceiverControlState? {
      if (text == null) return null
      return try {
        val root: JsonNode = mapper.readTree(text)
        require(root.isObject && root.fieldNames().asSequence().toSet() == FIELDS)
        fun text(name: String) = root[name].takeIf { it.isTextual }!!.textValue()
        fun long(name: String) = root[name].takeIf { it.isIntegralNumber && it.canConvertToLong() }!!.longValue()
        fun target(node: JsonNode?): Target {
          require(node != null && node.isObject && node.fieldNames().asSequence().toSet() == setOf("endpoint", "p256dh", "auth"))
          val result = Target(node["endpoint"].textValue(), node["p256dh"].textValue(), node["auth"].textValue())
          val endpoint = ControlFrame.Subscription(result.endpoint, result.p256dh, result.auth)
          require(endpoint.endpoint == result.endpoint)
          return result
        }
        fun nullable(name: String): JsonNode? = root[name].takeUnless { it.isNull }
        val pendingEpoch = nullable("pending_epoch")?.let { long("pending_epoch") }
        val pendingFrame = nullable("pending_frame")?.let { Base64.getUrlDecoder().decode(text("pending_frame")) }
        val bridgeDeviceId = long("bridge_device_id")
        require(bridgeDeviceId in 2..Int.MAX_VALUE.toLong())
        val result = ReceiverControlState(
          text("receiver_id"), text("import_id"), text("account_id"), bridgeDeviceId.toInt(), text("instance"), text("host_label"),
          text("control_key"), long("epoch"), target(root["target"]), root["confirmed"].takeIf { it.isBoolean }!!.booleanValue(), pendingEpoch,
          nullable("pending_target")?.let(::target), pendingFrame, long("pending_created_at"), long("pending_sent_at"),
        )
        require(UUID_V4.matches(result.receiverId) && UUID_V4.matches(result.importId) && UUID_ANY.matches(result.accountId))
        require(INSTANCE.matches(result.instance))
        require(result.hostLabel.length in 1..64 && result.hostLabel.all { it in ' '..'~' })
        require(result.controlKey == EnrollmentFile.canonicalKey(result.controlKey, 32) && result.epoch in 1..MAX_EPOCH)
        require((result.pendingEpoch == null) == (result.pendingFrame == null) &&
          (result.pendingEpoch == null) == (result.pendingTarget == null))
        require(result.pendingEpoch == null || result.pendingEpoch in (result.epoch + 1)..MAX_EPOCH && result.pendingFrame!!.size in ControlFrameV4.HEADER_SIZE..ControlFrameV4.MAX_FRAME)
        require(result.pendingEpoch != null || result.pendingCreatedAt == 0L && result.pendingSentAt == 0L)
        result
      } catch (_: Exception) { null }
    }

    fun reconcile(state: ReceiverControlState, accountId: String, bridgeDeviceId: Int, subscription: ControlFrame.Subscription, now: Long, force: Boolean = false): ReceiverControlState? {
      if (state.accountId != accountId || state.bridgeDeviceId != bridgeDeviceId) return null
      if (state.pendingEpoch != null && state.pendingTarget?.matches(subscription) == true) return state
      if (state.pendingEpoch == null && !force && state.target.matches(subscription)) return null
      val epoch = (state.pendingEpoch ?: state.epoch) + 1
      if (epoch !in 1..MAX_EPOCH) throw EnrollmentFile.Invalid("invalid_epoch")
      val frame = ControlFrameV4.build(state.controlKey, accountId, bridgeDeviceId, state.receiverId, epoch, subscription)
      return state.copy(pendingEpoch = epoch, pendingTarget = Target(subscription.endpoint, subscription.p256dh, subscription.auth), pendingFrame = frame, pendingCreatedAt = now, pendingSentAt = 0)
    }

    fun recordSent(state: ReceiverControlState, expectedFrame: ByteArray, now: Long): ReceiverControlState =
      if (state.pendingFrame?.contentEquals(expectedFrame) == true) state.copy(pendingSentAt = now) else state

    private const val MAX_EPOCH = 9007199254740991L
    private fun targetMap(t: Target) = mapOf("endpoint" to t.endpoint, "p256dh" to t.p256dh, "auth" to t.auth)
  }
}
