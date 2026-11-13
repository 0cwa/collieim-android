/*
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.keyvalue

import org.signal.storage.keyvalue.KeyValueStore
import org.signal.storage.keyvalue.SignalStoreValues

/** Local presentation choices, separate from APK checking and account-backed preferences. */
class UpdateNoticeValues(store: KeyValueStore) : SignalStoreValues(store) {

  override fun onFirstEverAppLaunch() = Unit

  override val keysToIncludeInBackup: List<String> = emptyList()

  fun isDismissed(noticeId: String): Boolean = getBoolean(dismissedKey(noticeId), false)

  fun setDismissed(noticeId: String, dismissed: Boolean) {
    putBoolean(dismissedKey(noticeId), dismissed)
  }

  private fun dismissedKey(noticeId: String): String {
    require(noticeId.isNotBlank())
    return "collie.update_notice.dismissed.$noticeId"
  }
}
