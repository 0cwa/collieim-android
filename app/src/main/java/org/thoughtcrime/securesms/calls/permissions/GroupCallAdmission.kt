/*
 * Copyright 2026 Collie contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package org.thoughtcrime.securesms.calls.permissions

import android.os.SystemClock
import org.signal.core.models.ServiceId
import org.signal.core.util.orNull
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.groups.GroupId
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.service.webrtc.WebRtcData
import org.thoughtcrime.securesms.service.webrtc.links.CallLinkRoomId
import org.whispersystems.signalservice.api.crypto.EnvelopeMetadata
import java.util.UUID

/** No native call manager, profile sharing, or recipient creation is needed for admission. */
object GroupCallAdmission {
  data class AdmittedOpaque(val authorization: CallAuthorization, val opaque: ByteArray)
  data class DeferredOpaque(val authorization: CallAuthorization, val metadata: WebRtcData.OpaqueMessageMetadata)

  private val pendingLinkMessages = PendingCallLinkMessages(SystemClock::elapsedRealtime)

  @JvmStatic
  fun clearPending() = pendingLinkMessages.clear()

  /** Only the exact current GroupCall's serialized SFU callbacks may publish this roster. */
  @JvmStatic
  fun updateCallLinkParticipants(key: CallSessionKey, devices: Map<Long, UUID>, joinedMembers: Collection<UUID>): List<DeferredOpaque> {
    if (!CallPermissions.updateCallLinkMembers(key, devices, joinedMembers)) return emptyList()
    val ready = ArrayList<DeferredOpaque>()
    for (message in pendingLinkMessages.take(key)) {
      val authorization = CallPermissions.authorizePendingCallLink(message.authorization)
      if (authorization != null) {
        ready += DeferredOpaque(
          authorization,
          WebRtcData.OpaqueMessageMetadata(message.sender, message.opaque, authorization.sourceDevice, message.ageAt(SystemClock.elapsedRealtime()) / 1000)
        )
      } else if (CallPermissions.isPendingCallLinkCurrent(message.authorization)) {
        pendingLinkMessages.put(message)
      }
    }
    return ready
  }

  @JvmStatic
  @JvmOverloads
  fun admit(
    senderRecipient: Recipient,
    metadata: EnvelopeMetadata,
    opaqueBytes: ByteArray,
    messageAgeMillis: Long,
    expectedGeneration: Long? = null
  ): AdmittedOpaque? {
    if (!CallPermissions.isCallingAllowed() || metadata.sourceDeviceId <= 0) return null
    val sender = try {
      senderRecipient.resolve()
    } catch (_: RuntimeException) {
      return null
    }
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

      var authorization = CallPermissions.authorizeGroup(
        contextId,
        sender.id,
        classified.ringId,
        metadata.sourceDeviceId,
        classified.kind,
        messageAgeMillis,
        expectedGeneration
      ) ?: return null
      if (group == null) {
        authorization = CallPermissions.bindCallLinkDevice(authorization, classified.demuxId ?: return null) ?: return null
        if (!CallPermissions.isAuthorized(authorization)) {
          val promoted = CallPermissions.authorizePendingCallLink(authorization)
          if (promoted != null) {
            return AdmittedOpaque(promoted, classified.opaque)
          }
          if (classified.isMediaKey && CallPermissions.isPendingCallLinkCurrent(authorization) &&
            pendingLinkMessages.offer(authorization, authenticatedSender.rawUuid, classified.opaque, messageAgeMillis)
          ) {
            // Close the race where a roster callback drains the queue just before this frame
            // is inserted. Claiming ownership also prevents duplicate native dispatch if the
            // callback has already taken this frame for promotion.
            val afterEnqueue = CallPermissions.authorizePendingCallLink(authorization)
            if (afterEnqueue != null && pendingLinkMessages.remove(authorization)) {
              return AdmittedOpaque(afterEnqueue, classified.opaque)
            }
            if (!CallPermissions.isPendingCallLinkCurrent(authorization)) pendingLinkMessages.remove(authorization)
          }
          return null
        }
      }
      AdmittedOpaque(authorization, classified.opaque)
    } catch (e: RuntimeException) {
      // An unavailable/transitioning database never grants a new calling capability.
      null
    }
  }
}
