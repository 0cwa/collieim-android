/*
 * Copyright 2026 Collie contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package org.thoughtcrime.securesms.calls.permissions

import org.signal.core.models.ServiceId
import org.signal.core.util.orNull
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.groups.GroupId
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.service.webrtc.links.CallLinkRoomId
import org.whispersystems.signalservice.api.crypto.EnvelopeMetadata

/** No native call manager, profile sharing, or recipient creation is needed for admission. */
object GroupCallAdmission {
  data class AdmittedOpaque(val authorization: CallAuthorization, val opaque: ByteArray)

  @JvmStatic
  fun admit(
    senderRecipient: Recipient,
    metadata: EnvelopeMetadata,
    opaqueBytes: ByteArray,
    messageAgeMillis: Long
  ): AdmittedOpaque? {
    if (!CallPermissions.isCallingAllowed() || metadata.sourceDeviceId <= 0) return null
    val sender = senderRecipient.resolve()
    val authenticatedSender = metadata.sourceServiceId as? ServiceId.ACI ?: return null
    if (sender.aci.orNull() != authenticatedSender || sender.isBlocked) return null

    val classified = RingRtcOpaqueClassifier.classify(opaqueBytes) ?: return null
    if (metadata.groupId != null && !metadata.groupId.contentEquals(classified.groupId)) return null

    return try {
      val groupId = GroupId.pushOrNull(classified.groupId) ?: return null
      if (!groupId.isV2) return null
      val group = SignalDatabase.groups.getGroup(groupId).orNull()
      val contextId = if (group != null) {
        if (!group.isActive || Recipient.resolved(group.recipientId).isBlocked || !group.memberLevel(sender).isInGroup) return null
        if ((classified.kind == GroupMessageKind.RING || classified.kind == GroupMessageKind.CANCEL) && group.isAnnouncementGroup && !group.isAdmin(sender)) return null
        if (classified.kind == GroupMessageKind.RING_RESPONSE && !sender.isSelf) return null
        group.recipientId
      } else {
        // Call links use the room ID as RingRTC's group context. Only an explicit
        // local link session can authorize their media-key/leaving messages.
        if (classified.kind != GroupMessageKind.GROUP_DATA) return null
        SignalDatabase.recipients.getByCallLinkRoomId(CallLinkRoomId.fromBytes(classified.groupId)).orNull() ?: return null
      }

      val authorization = CallPermissions.authorizeGroup(
        contextId,
        sender.id,
        classified.ringId,
        metadata.sourceDeviceId,
        classified.kind,
        messageAgeMillis
      ) ?: return null
      AdmittedOpaque(authorization, classified.opaque)
    } catch (e: RuntimeException) {
      // An unavailable/transitioning database never grants a new calling capability.
      null
    }
  }
}
