/*
 * Copyright 2026 Collie contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package org.thoughtcrime.securesms.calls.permissions

import org.thoughtcrime.securesms.calls.permissions.proto.OpaqueCallMessage
import java.io.IOException

/**
 * Bounded, generated managed decoding before native calling code sees remote bytes.
 * This follows the exact schema pinned in CollieCalling.proto. It does not guess a
 * call ID for group media messages: those are scoped to an authorized live context.
 */
object RingRtcOpaqueClassifier {
  const val MAX_MESSAGE_BYTES = 64 * 1024
  private const val GROUP_ID_BYTES = 32

  data class Classified(
    val kind: GroupMessageKind,
    val groupId: ByteArray,
    val ringId: Long?,
    val opaque: ByteArray
  )

  @JvmStatic
  fun classify(bytes: ByteArray): Classified? {
    if (bytes.isEmpty() || bytes.size > MAX_MESSAGE_BYTES) return null

    val message = try {
      OpaqueCallMessage.ADAPTER.decode(bytes)
    } catch (e: IOException) {
      return null
    } catch (e: IllegalArgumentException) {
      return null
    }

    if (message.unknownFields.size != 0) return null
    if (listOf(message.ringIntention, message.ringResponse, message.groupCallMessage).count { it != null } != 1) return null

    message.ringIntention?.let {
      if (it.unknownFields.size != 0 || it.groupId?.size != GROUP_ID_BYTES || it.ringId == null || it.type == null) return null
      val kind = when (it.type) {
        OpaqueCallMessage.RingIntention.Type.RING -> GroupMessageKind.RING
        OpaqueCallMessage.RingIntention.Type.CANCELLED -> GroupMessageKind.CANCEL
      }
      return Classified(kind, it.groupId.toByteArray(), it.ringId, message.encode())
    }

    message.ringResponse?.let {
      if (it.unknownFields.size != 0 || it.groupId?.size != GROUP_ID_BYTES || it.ringId == null || it.type == null) return null
      // RINGING is never valid as a received self-device response in the pinned native code.
      if (it.type == OpaqueCallMessage.RingResponse.Type.RINGING) return null
      return Classified(GroupMessageKind.RING_RESPONSE, it.groupId.toByteArray(), it.ringId, message.encode())
    }

    val group = message.groupCallMessage ?: return null
    if (group.unknownFields.size != 0 || group.groupId?.size != GROUP_ID_BYTES) return null
    if (listOf(group.mediaKey, group.heartbeat, group.leaving, group.reaction, group.remoteMuteRequest).count { it != null } != 1) return null

    // Heartbeat/reaction/mute requests belong to RTP, not the signaling transport.
    if (group.heartbeat != null || group.reaction != null || group.remoteMuteRequest != null) return null
    group.mediaKey?.let {
      if (it.unknownFields.size != 0 || it.demuxId == null || it.ratchetCounter == null || it.ratchetCounter !in 0..255 || it.secret?.size != 32) return null
    }
    group.leaving?.let {
      if (it.unknownFields.size != 0 || it.demuxId == null) return null
    }

    // Re-encoding prevents different duplicate-field merge behavior in Wire and
    // RingRTC's protobuf decoder from changing the context after admission.
    return Classified(GroupMessageKind.GROUP_DATA, group.groupId.toByteArray(), null, message.encode())
  }
}
