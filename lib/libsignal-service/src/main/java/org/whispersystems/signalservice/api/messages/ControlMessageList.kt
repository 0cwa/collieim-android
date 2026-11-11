package org.whispersystems.signalservice.api.messages

import org.signal.core.util.Base64
import org.whispersystems.signalservice.internal.push.OutgoingPushMessage
import org.whispersystems.signalservice.internal.push.OutgoingPushMessageList

/**
 * The per-device request body for an endpoint-update frame (see `contracts/encrypted-push/v2` in
 * https://github.com/0cwa/colliesocket, "Placing the blob in the Signal send").
 *
 * Only CollieSocket's own entry carries the frame, as standard padded base64 of the frame bytes with type 1 and the
 * registration ID the Signal server expects for that device. Every other device gets an ordinary entry from
 * [encryptOther]. The blob is never given to another device and never encrypted.
 */
object ControlMessageList {

  /** `DOUBLE_RATCHET`, named `CIPHERTEXT` in older definitions. CollieSocket never reads it. */
  const val DOUBLE_RATCHET_TYPE = 1

  @JvmStatic
  fun build(
    destination: String,
    timestamp: Long,
    controlDeviceId: Int,
    controlRegistrationId: Int,
    frame: ByteArray,
    otherDeviceIds: Collection<Int>,
    encryptOther: (Int) -> OutgoingPushMessage
  ): OutgoingPushMessageList {
    val others = otherDeviceIds.filter { it != controlDeviceId }.distinct().sorted().map { encryptOther(it) }
    val control = OutgoingPushMessage(DOUBLE_RATCHET_TYPE, controlDeviceId, controlRegistrationId, Base64.encodeWithPadding(frame))
    return OutgoingPushMessageList(destination, timestamp, others + control, online = false, urgent = false)
  }
}
