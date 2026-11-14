package org.thoughtcrime.securesms.notifications

/** Suppresses replay of a prompt until the vitals state actually changes. */
internal class UnifiedPushPickerLaunchGuard {
  var outstanding: Boolean = false
    private set
  var promptHandled: Boolean = false
    private set

  fun restore(savedOutstanding: Boolean, savedHandled: Boolean) {
    outstanding = savedOutstanding
    promptHandled = savedHandled
  }

  fun tryLaunch(): Boolean {
    if (outstanding || promptHandled) return false
    outstanding = true
    promptHandled = true
    return true
  }

  fun onResult() { outstanding = false }

  fun onOtherVitalsState() { promptHandled = false }
}
