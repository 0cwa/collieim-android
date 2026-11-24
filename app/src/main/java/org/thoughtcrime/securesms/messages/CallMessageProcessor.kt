package org.thoughtcrime.securesms.messages

import org.signal.core.models.ServiceId
import org.signal.core.models.recipients.RecipientId
import org.signal.core.util.orNull
import org.signal.ringrtc.CallId
import org.thoughtcrime.securesms.calls.permissions.CallAuthorization
import org.thoughtcrime.securesms.calls.permissions.CallPermissions
import org.thoughtcrime.securesms.calls.permissions.DirectMessageKind
import org.thoughtcrime.securesms.calls.permissions.GroupCallAdmission
import org.thoughtcrime.securesms.database.model.IdentityRecord
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.jobs.ProfileKeySendJob
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.messages.MessageContentProcessor.Companion.log
import org.thoughtcrime.securesms.messages.MessageContentProcessor.Companion.warn
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.recipients.RecipientUtil
import org.thoughtcrime.securesms.ringrtc.RemotePeer
import org.thoughtcrime.securesms.service.webrtc.WebRtcData.AnswerMetadata
import org.thoughtcrime.securesms.service.webrtc.WebRtcData.CallMetadata
import org.thoughtcrime.securesms.service.webrtc.WebRtcData.HangupMetadata
import org.thoughtcrime.securesms.service.webrtc.WebRtcData.OfferMetadata
import org.thoughtcrime.securesms.service.webrtc.WebRtcData.OpaqueMessageMetadata
import org.thoughtcrime.securesms.service.webrtc.WebRtcData.ReceivedAnswerMetadata
import org.thoughtcrime.securesms.service.webrtc.WebRtcData.ReceivedOfferMetadata
import org.whispersystems.signalservice.api.crypto.EnvelopeMetadata
import org.whispersystems.signalservice.api.messages.calls.HangupMessage
import org.whispersystems.signalservice.api.messages.calls.OfferMessage
import org.whispersystems.signalservice.internal.push.CallMessage
import org.whispersystems.signalservice.internal.push.CallMessage.Offer
import org.whispersystems.signalservice.internal.push.Content
import org.whispersystems.signalservice.internal.push.Envelope
import kotlin.time.Duration.Companion.milliseconds

object CallMessageProcessor {
  fun process(
    senderRecipient: Recipient,
    envelope: Envelope,
    content: Content,
    metadata: EnvelopeMetadata,
    serverDeliveredTimestamp: Long
  ) {
    val callMessage = content.callMessage!!
    val shape = CallMessageShape.parse(callMessage) ?: return
    val recipient = runCatching { senderRecipient.resolve() }.getOrNull() ?: return
    val authenticatedIdentity = when (metadata.sourceServiceId) {
      is ServiceId.ACI -> recipient.aci.orNull()
      is ServiceId.PNI -> recipient.pni.orNull()
    }
    val currentDestination = runCatching {
      when (metadata.destinationServiceId) {
        is ServiceId.ACI -> SignalStore.account.aci
        is ServiceId.PNI -> SignalStore.account.pni
      }
    }.getOrNull()
    if (authenticatedIdentity != metadata.sourceServiceId || currentDestination != metadata.destinationServiceId || recipient.isBlocked || metadata.sourceDeviceId <= 0 ||
      callMessage.destinationDeviceId?.let { it != SignalStore.account.deviceId } == true
    ) {
      return
    }

    val serverTimestamp = envelope.serverTimestamp
    val ageMillis = if (serverTimestamp != null && serverTimestamp in 1..serverDeliveredTimestamp) {
      serverDeliveredTimestamp - serverTimestamp
    } else {
      Long.MAX_VALUE
    }

    if (shape.kind == null) {
      if (metadata.destinationServiceId is ServiceId.PNI) return
      val admitted = GroupCallAdmission.admit(recipient, metadata, callMessage.opaque!!.data_!!.toByteArray(), ageMillis) ?: return
      CallPermissions.runAuthorized(admitted.authorization) {
        // Use the managed decoder's canonical bytes, so admission and native
        // dispatch agree even for duplicate or otherwise ambiguous wire fields.
        handleCallOpaqueMessage(envelope, metadata, admitted.opaque, metadata.sourceServiceId, serverDeliveredTimestamp, admitted.authorization)
      }
      return
    }

    val authorization = CallPermissions.admitDirect(recipient.id, shape.callId!!, metadata.sourceDeviceId, shape.kind, ageMillis)
    if (authorization == null) {
      if (shape.kind == DirectMessageKind.OFFER) {
        CallPolicyRejector.declineOffer(recipient, metadata, shape.callId, ageMillis)
      }
      return
    }

    // This check covers manager construction and the PNI profile handshake.
    // SignalCallManager checks the same ticket again on its serialized queue.
    CallPermissions.runAuthorized(authorization) {
      processAdmitted(recipient, envelope, callMessage, metadata, serverDeliveredTimestamp, authorization)
    }
  }

  private fun processAdmitted(
    senderRecipient: Recipient,
    envelope: Envelope,
    callMessage: CallMessage,
    metadata: EnvelopeMetadata,
    serverDeliveredTimestamp: Long,
    authorization: CallAuthorization
  ) {
    val offer = callMessage.offer
    if (metadata.destinationServiceId is ServiceId.PNI) {
      if (offer == null) return
      if (RecipientUtil.isCallRequestAccepted(senderRecipient)) {
        if (!CallPermissions.isDispatchAuthorized()) return
        log(envelope.clientTimestamp!!, "Received call offer message at our PNI from trusted sender, responding with profile and pni signature")
        RecipientUtil.shareProfileIfFirstSecureMessage(senderRecipient)
        ProfileKeySendJob.createForCall(senderRecipient, false)?.let { AppDependencies.jobManager.add(it) }
      }
      // A PNI offer is a profile/ACI handshake, never a native call. Preserve
      // its missed-call record and trusted-only reply without creating RingRTC.
      declineAdmittedOffer(senderRecipient, envelope, metadata, offer, serverDeliveredTimestamp)
      return
    }

    if (offer != null && !RecipientUtil.isCallRequestAccepted(senderRecipient) && !CallPermissions.hasExplicitIncomingAllow(senderRecipient.id)) {
      declineAdmittedOffer(senderRecipient, envelope, metadata, offer, serverDeliveredTimestamp)
      return
    }

    when {
      callMessage.offer != null -> handleCallOfferMessage(envelope, metadata, callMessage.offer!!, senderRecipient.id, serverDeliveredTimestamp, authorization)
      callMessage.answer != null -> handleCallAnswerMessage(envelope, metadata, callMessage.answer!!, senderRecipient.id, authorization)
      callMessage.iceUpdate.isNotEmpty() -> handleCallIceUpdateMessage(envelope, metadata, callMessage.iceUpdate, senderRecipient.id, authorization)
      callMessage.hangup != null -> handleCallHangupMessage(envelope, metadata, callMessage.hangup!!, senderRecipient.id, authorization)
      callMessage.busy != null -> handleCallBusyMessage(envelope, metadata, callMessage.busy!!, senderRecipient.id, authorization)
    }
  }

  private fun declineAdmittedOffer(recipient: Recipient, envelope: Envelope, metadata: EnvelopeMetadata, offer: Offer, serverDeliveredTimestamp: Long) {
    if (!CallPermissions.isDispatchAuthorized()) return
    CallPolicyRejector.declineOffer(recipient, metadata, offer.id!!, serverDeliveredTimestamp - envelope.serverTimestamp!!, policyDenied = false)
    CallPolicyRejector.recordMissedCall(recipient, offer.id!!, envelope.serverTimestamp!!, offer.type == Offer.Type.OFFER_VIDEO_CALL)
    CallPermissions.endCall(recipient.id, offer.id!!)
  }

  private fun handleCallOfferMessage(envelope: Envelope, metadata: EnvelopeMetadata, offer: Offer, senderRecipientId: RecipientId, serverDeliveredTimestamp: Long, authorization: CallAuthorization) {
    log(envelope.clientTimestamp!!, "handleCallOfferMessage...")

    val offerId = if (offer.id != null && offer.type != null && offer.opaque != null) {
      offer.id!!
    } else {
      warn(envelope.clientTimestamp!!, "Invalid offer, missing id, type, or opaque")
      return
    }

    val remotePeer = RemotePeer(senderRecipientId, CallId(offerId))
    val remoteIdentityKey = AppDependencies.protocolStore.get(metadata.destinationServiceId).identities().getIdentityRecord(senderRecipientId).map { (_, identityKey): IdentityRecord -> identityKey.serialize() }.orNull()
    if (remoteIdentityKey == null) {
      declineAdmittedOffer(Recipient.resolved(senderRecipientId), envelope, metadata, offer, serverDeliveredTimestamp)
      return
    }

    if (!CallPermissions.isDispatchAuthorized()) return
    AppDependencies.signalCallManager
      .receivedOffer(
        authorization,
        CallMetadata(remotePeer, metadata.sourceDeviceId),
        OfferMetadata(offer.opaque?.toByteArray(), OfferMessage.Type.fromProto(offer.type!!)),
        ReceivedOfferMetadata(
          metadata.destinationServiceId,
          remoteIdentityKey,
          envelope.serverTimestamp!!,
          serverDeliveredTimestamp
        )
      )
  }

  private fun handleCallAnswerMessage(
    envelope: Envelope,
    metadata: EnvelopeMetadata,
    answer: CallMessage.Answer,
    senderRecipientId: RecipientId,
    authorization: CallAuthorization
  ) {
    log(envelope.clientTimestamp!!, "handleCallAnswerMessage...")

    val answerId = if (answer.id != null && answer.opaque != null) {
      answer.id!!
    } else {
      warn(envelope.clientTimestamp!!, "Invalid answer, missing id or opaque")
      return
    }

    val remotePeer = RemotePeer(senderRecipientId, CallId(answerId))
    val remoteIdentityKey = AppDependencies.protocolStore.aci().identities().getIdentityRecord(senderRecipientId).map { (_, identityKey): IdentityRecord -> identityKey.serialize() }.orNull() ?: return

    if (!CallPermissions.isDispatchAuthorized()) return
    AppDependencies.signalCallManager
      .receivedAnswer(
        authorization,
        CallMetadata(remotePeer, metadata.sourceDeviceId),
        AnswerMetadata(answer.opaque?.toByteArray()),
        ReceivedAnswerMetadata(remoteIdentityKey)
      )
  }

  private fun handleCallIceUpdateMessage(
    envelope: Envelope,
    metadata: EnvelopeMetadata,
    iceUpdateList: List<CallMessage.IceUpdate>,
    senderRecipientId: RecipientId,
    authorization: CallAuthorization
  ) {
    log(envelope.clientTimestamp!!, "handleCallIceUpdateMessage... " + iceUpdateList.size)

    val iceCandidates: MutableList<ByteArray> = ArrayList(iceUpdateList.size)
    var callId: Long = -1

    iceUpdateList
      .filter { it.opaque != null && it.id != null }
      .forEach { iceUpdate ->
        iceCandidates += iceUpdate.opaque!!.toByteArray()
        callId = iceUpdate.id!!
      }

    if (iceCandidates.isNotEmpty()) {
      val remotePeer = RemotePeer(senderRecipientId, CallId(callId))
      if (!CallPermissions.isDispatchAuthorized()) return
      AppDependencies.signalCallManager
        .receivedIceCandidates(
          authorization,
          CallMetadata(remotePeer, metadata.sourceDeviceId),
          iceCandidates
        )
    } else {
      warn(envelope.clientTimestamp!!, "Invalid ice updates, all missing opaque and/or call id")
    }
  }

  private fun handleCallHangupMessage(
    envelope: Envelope,
    metadata: EnvelopeMetadata,
    hangup: CallMessage.Hangup?,
    senderRecipientId: RecipientId,
    authorization: CallAuthorization
  ) {
    log(envelope.clientTimestamp!!, "handleCallHangupMessage")

    val (hangupId: Long, hangupDeviceId: Int?) = if (hangup?.id != null) {
      hangup.id!! to hangup.deviceId
    } else {
      warn(envelope.clientTimestamp!!, "Invalid hangup, null message or missing id/deviceId")
      return
    }

    val remotePeer = RemotePeer(senderRecipientId, CallId(hangupId))
    if (!CallPermissions.isDispatchAuthorized()) return
    AppDependencies.signalCallManager
      .receivedCallHangup(
        authorization,
        CallMetadata(remotePeer, metadata.sourceDeviceId),
        HangupMetadata(HangupMessage.Type.fromProto(hangup.type ?: CallMessage.Hangup.Type.HANGUP_NORMAL), hangupDeviceId ?: 0)
      )
  }

  private fun handleCallBusyMessage(envelope: Envelope, metadata: EnvelopeMetadata, busy: CallMessage.Busy, senderRecipientId: RecipientId, authorization: CallAuthorization) {
    log(envelope.clientTimestamp!!, "handleCallBusyMessage")

    val busyId = if (busy.id != null) {
      busy.id!!
    } else {
      warn(envelope.clientTimestamp!!, "Invalid busy, missing call id")
      return
    }

    val remotePeer = RemotePeer(senderRecipientId, CallId(busyId))
    if (!CallPermissions.isDispatchAuthorized()) return
    AppDependencies.signalCallManager.receivedCallBusy(authorization, CallMetadata(remotePeer, metadata.sourceDeviceId))
  }

  private fun handleCallOpaqueMessage(envelope: Envelope, metadata: EnvelopeMetadata, data: ByteArray, senderServiceId: ServiceId, serverDeliveredTimestamp: Long, authorization: CallAuthorization) {
    log(envelope.clientTimestamp!!, "handleCallOpaqueMessage")

    var messageAgeSeconds: Long = 0
    if (envelope.serverTimestamp in 1..serverDeliveredTimestamp) {
      messageAgeSeconds = (serverDeliveredTimestamp - envelope.serverTimestamp!!).milliseconds.inWholeSeconds
    }

    if (!CallPermissions.isDispatchAuthorized()) return
    AppDependencies.signalCallManager
      .receivedOpaqueMessage(
        authorization,
        OpaqueMessageMetadata(
          senderServiceId.rawUuid,
          data,
          metadata.sourceDeviceId,
          messageAgeSeconds
        )
      )
  }
}
