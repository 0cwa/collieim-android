package org.thoughtcrime.securesms.messages

import org.thoughtcrime.securesms.calls.permissions.DirectMessageKind
import org.whispersystems.signalservice.internal.push.CallMessage

/** Managed validation performed before any calling dependency is obtained. */
internal data class CallMessageShape(val kind: DirectMessageKind?, val callId: Long?) {
  companion object {
    fun parse(message: CallMessage): CallMessageShape? {
      // These fields are optional, not a protobuf oneof. Never admit one field and
      // then let another field choose a different path into native calling code.
      val populated = listOf(
        message.offer != null,
        message.answer != null,
        message.iceUpdate.isNotEmpty(),
        message.hangup != null,
        message.busy != null,
        message.opaque != null
      ).count { it }
      if (populated != 1) return null

      message.offer?.let { offer ->
        if (offer.id == null || offer.type == null || offer.opaque == null) return null
        return CallMessageShape(DirectMessageKind.OFFER, offer.id)
      }
      message.answer?.let { answer ->
        if (answer.id == null || answer.opaque == null) return null
        return CallMessageShape(DirectMessageKind.ANSWER, answer.id)
      }
      if (message.iceUpdate.isNotEmpty()) {
        val id = message.iceUpdate.first().id ?: return null
        if (message.iceUpdate.any { it.id != id || it.opaque == null }) return null
        return CallMessageShape(DirectMessageKind.ICE, id)
      }
      message.hangup?.let { return it.id?.let { id -> CallMessageShape(DirectMessageKind.HANGUP, id) } }
      message.busy?.let { return it.id?.let { id -> CallMessageShape(DirectMessageKind.BUSY, id) } }
      return if (message.opaque?.data_ != null) CallMessageShape(null, null) else null
    }
  }
}
