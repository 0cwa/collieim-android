package im.molly.unifiedpush.control

import com.fasterxml.jackson.databind.ObjectMapper

/** A subscription as the server holds it or as an exported file carries it. Never printed. */
class Target(val endpoint: String, val p256dh: String, val auth: String) {
  fun matches(subscription: ControlFrame.Subscription) =
    endpoint == subscription.endpoint && p256dh == subscription.p256dh && auth == subscription.auth

  override fun equals(other: Any?) = other is Target && endpoint == other.endpoint && p256dh == other.p256dh && auth == other.auth
  override fun hashCode() = listOf(endpoint, p256dh, auth).hashCode()
  override fun toString() = "Target"
}

/**
 * Everything the phone knows about the server's copy of its subscription, in one record that is only ever replaced as
 * a whole (see [ControlStore.mutate]).
 *
 * @property key control key of the enrollment the server imported (null: no endpoint updates over Signal).
 * @property pendingKey key of the last export, which becomes [key] once the server proves it imported that file.
 * @property pendingEpoch epoch of that export (0: none), [pendingTarget] the subscription its file carries.
 * @property activeEpoch epoch the server is known to be at, [serverTarget] the subscription it is known to hold.
 * @property highestEpoch the highest epoch the phone has put into an export or an update, sent or not.
 * @property update the unconfirmed endpoint update, if any.
 */
data class ControlState(
  val key: String? = null,
  val pendingKey: String? = null,
  val pendingEpoch: Long = 0,
  val pendingTarget: Target? = null,
  val activeEpoch: Long = 0,
  val highestEpoch: Long = 0,
  val update: ControlUpdate? = null,
  val serverTarget: Target? = null,
) {
  override fun toString() = "ControlState(active=$activeEpoch, pending=$pendingEpoch, highest=$highestEpoch)"

  fun serialize(): String = mapper.writeValueAsString(
    mapOf(
      "key" to key, "pendingKey" to pendingKey, "pendingEpoch" to pendingEpoch, "pendingTarget" to pendingTarget?.let(::targetMap),
      "activeEpoch" to activeEpoch, "highestEpoch" to highestEpoch, "update" to update?.serialize(), "serverTarget" to serverTarget?.let(::targetMap),
    )
  )

  companion object {
    private val mapper = ObjectMapper()

    private fun targetMap(t: Target) = mapOf("endpoint" to t.endpoint, "p256dh" to t.p256dh, "auth" to t.auth)

    private fun target(node: com.fasterxml.jackson.databind.JsonNode?): Target? =
      node?.takeIf { it.isObject }?.let { Target(it["endpoint"].asText(), it["p256dh"].asText(), it["auth"].asText()) }

    /** Null for anything unreadable; the caller then treats the state as empty. */
    fun parse(text: String?): ControlState? = try {
      val root = mapper.readTree(text ?: return null)
      ControlState(
        key = root["key"]?.takeIf { it.isTextual }?.asText(),
        pendingKey = root["pendingKey"]?.takeIf { it.isTextual }?.asText(),
        pendingEpoch = root["pendingEpoch"].asLong(),
        pendingTarget = target(root["pendingTarget"]),
        activeEpoch = root["activeEpoch"].asLong(),
        highestEpoch = root["highestEpoch"].asLong(),
        update = root["update"]?.takeIf { it.isTextual }?.let { ControlUpdate.parse(it.asText()) },
        serverTarget = target(root["serverTarget"]),
      )
    } catch (e: Exception) {
      null
    }
  }
}
