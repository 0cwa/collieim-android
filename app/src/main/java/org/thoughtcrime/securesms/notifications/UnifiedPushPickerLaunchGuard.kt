package org.thoughtcrime.securesms.notifications

/** Tracks an outstanding picker across activity recreation and its replayed vitals check. */
internal class UnifiedPushPickerLaunchGuard {
  var outstanding: Boolean = false
    private set

  fun restore(saved: Boolean) {
    outstanding = saved
  }

  fun tryLaunch(): Boolean {
    if (outstanding) return false
    outstanding = true
    return true
  }

  fun onResult() {
    outstanding = false
  }
}
