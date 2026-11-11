package im.molly.unifiedpush.control

import android.content.Context
import im.molly.unifiedpush.receiver.LockedPushState
import org.thoughtcrime.securesms.keyvalue.SignalStore

object ControlReset {
  /**
   * The encrypted enrollment no longer applies (its linked device or server is gone): forgets the control record and
   * clears the flag the receiver reads while locked, so a recreated stock device is not refused plaintext wakes.
   * The replay state stays, because the contract forbids lowering its clock mark.
   */
  @JvmStatic
  fun discard(context: Context) {
    SignalStore.unifiedpush.clearControl()
    LockedPushState.setEncryptedEnrollment(context, false)
  }
}
