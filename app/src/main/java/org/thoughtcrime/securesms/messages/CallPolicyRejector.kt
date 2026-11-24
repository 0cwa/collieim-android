package org.thoughtcrime.securesms.messages

import android.os.SystemClock
import org.signal.core.models.ServiceId
import org.signal.core.util.concurrent.SignalExecutors
import org.signal.core.util.logging.Log
import org.signal.libsignal.protocol.NoSessionException
import org.thoughtcrime.securesms.calls.permissions.CallPermissions
import org.thoughtcrime.securesms.crypto.SealedSenderAccessUtil
import org.thoughtcrime.securesms.database.CallTable
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.recipients.RecipientUtil
import org.whispersystems.signalservice.api.crypto.EnvelopeMetadata
import org.whispersystems.signalservice.api.crypto.UntrustedIdentityException
import org.whispersystems.signalservice.api.messages.calls.BusyMessage
import org.whispersystems.signalservice.api.messages.calls.HangupMessage
import org.whispersystems.signalservice.api.messages.calls.SignalServiceCallMessage
import org.whispersystems.signalservice.api.push.SignalServiceAddress
import java.io.IOException
import java.util.Optional

/** A refusal is ordinary encrypted Signal transport, with no RingRTC callbacks or profile sharing. */
internal object CallPolicyRejector {
  private val TAG = Log.tag(CallPolicyRejector::class.java)
  private const val MAX_PENDING_REFUSALS = 32
  private const val REFUSAL_INTERVAL_MILLIS = 5_000L
  private val recentRecipients = LinkedHashMap<String, Long>()

  fun recordMissedCall(recipient: Recipient, callId: Long, timestamp: Long, isVideoOffer: Boolean) {
    val update = SignalDatabase.calls.updateOneToOneCall(callId, CallTable.Event.MISSED)
    if (update is CallTable.OneToOneCallUpdate.NotFound) {
      val type = if (isVideoOffer) CallTable.Type.VIDEO_CALL else CallTable.Type.AUDIO_CALL
      SignalDatabase.calls.insertOneToOneCall(callId, timestamp, recipient.id, type, CallTable.Direction.INCOMING, CallTable.Event.MISSED, false)
    }
  }

  fun declineOffer(recipient: Recipient, metadata: EnvelopeMetadata, callId: Long, messageAgeMillis: Long, policyDenied: Boolean = true) {
    if (recipient.isBlocked || metadata.sourceDeviceId <= 0 || messageAgeMillis !in 0..120_000L) return
    if (!shouldReply(policyDenied, SignalStore.account.isMultiDevice || !SignalStore.account.isPrimaryDevice)) return
    val trusted = RecipientUtil.isCallRequestAccepted(recipient)
    // Preserve the existing silent treatment of unknown callers to a PNI.
    if (!trusted && metadata.destinationServiceId is ServiceId.PNI) return

    val now = SystemClock.elapsedRealtime()
    synchronized(recentRecipients) {
      recentRecipients.entries.removeAll { now - it.value >= REFUSAL_INTERVAL_MILLIS }
      val key = metadata.sourceServiceId.toString()
      if (recentRecipients.containsKey(key) || recentRecipients.size >= MAX_PENDING_REFUSALS) return
      recentRecipients[key] = now
    }

    val generation = CallPermissions.generation()
    val senderId = recipient.id
    val address = SignalServiceAddress(metadata.sourceServiceId, Optional.ofNullable(metadata.sourceE164))
    val type = if (trusted) HangupMessage.Type.NORMAL else HangupMessage.Type.NEED_PERMISSION
    val message = if (policyDenied && trusted && CallPermissions.shouldSendBusy(senderId)) {
      SignalServiceCallMessage.forBusy(BusyMessage(callId), metadata.sourceDeviceId)
    } else {
      SignalServiceCallMessage.forHangup(HangupMessage(callId, type, 0), metadata.sourceDeviceId)
    }

    SignalExecutors.BOUNDED_IO.execute {
      val current = Recipient.resolved(senderId)
      if (current.isBlocked || CallPermissions.generation() != generation || SystemClock.elapsedRealtime() - now + messageAgeMillis > 120_000L) return@execute
      CallPermissions.runForGeneration(generation) {
        try {
          AppDependencies.signalServiceMessageSender.sendCallMessage(address, SealedSenderAccessUtil.getSealedSenderAccessFor(current), message)
        } catch (e: UntrustedIdentityException) {
          Log.w(TAG, "Could not send call refusal to an untrusted identity")
        } catch (e: NoSessionException) {
          Log.w(TAG, "Could not send call refusal without a session")
        } catch (e: IOException) {
          Log.w(TAG, "Could not send call refusal", e)
        }
      }
    }
  }

  internal fun shouldReply(policyDenied: Boolean, hasLinkedDevices: Boolean): Boolean {
    // RingRTC's Normal/NeedPermission/Busy refusals terminate the caller's whole
    // call and propagate to our other devices. The protocol has no per-device
    // refusal. A local policy therefore drops silently when linked devices may
    // still accept; existing trust/PNI refusal behavior remains unchanged.
    return !policyDenied || !hasLinkedDevices
  }
}
