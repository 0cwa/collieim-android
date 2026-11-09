package org.thoughtcrime.securesms.components.settings.app.privacy

import android.app.Application
import android.content.SharedPreferences
import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import org.thoughtcrime.securesms.ScreenLockController
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.util.TextSecurePreferences
import org.thoughtcrime.securesms.util.livedata.Store

class PrivacySettingsViewModel(
  private val sharedPreferences: SharedPreferences,
  private val repository: PrivacySettingsRepository
) : ViewModel() {

  private val application: Application = AppDependencies.application

  private val store = Store(getState())

  val state: LiveData<PrivacySettingsState> = store.stateLiveData

  fun refreshBlockedCount() {
    repository.getBlockedCount { count ->
      store.update { it.copy(blockedCount = count) }
      refresh()
    }
  }

  fun setBlockUnknownEnabled(enabled: Boolean) {
    TextSecurePreferences.setBlockUnknownEnabled(application, enabled)
    refresh()
  }

  fun setReadReceiptsEnabled(enabled: Boolean) {
    SignalStore.settings.isReadReceiptsEnabled = enabled
    repository.syncReadReceiptState()
    refresh()
  }

  fun setTypingIndicatorsEnabled(enabled: Boolean) {
    SignalStore.settings.isTypingIndicatorsEnabled = enabled
    repository.syncTypingIndicatorsState()
    refresh()
  }

  fun setPassphraseLockEnabled(enabled: Boolean) {
    TextSecurePreferences.setPassphraseLockEnabled(application, enabled)
    refresh()
  }

  fun setPassphraseLockTrigger(resultSet: Set<String>) {
    sharedPreferences.edit().putStringSet(TextSecurePreferences.PASSPHRASE_LOCK_TRIGGER, resultSet).apply()
    refresh()
  }

  fun setPassphraseLockTimeout(seconds: Long) {
    sharedPreferences.edit().putLong(TextSecurePreferences.PASSPHRASE_LOCK_TIMEOUT, seconds).apply()
    refresh()
  }

  fun setBiometricScreenLock(enabled: Boolean) {
    TextSecurePreferences.setBiometricScreenLockEnabled(application, enabled)
    ScreenLockController.enableAutoLock(enabled)
    ScreenLockController.lockScreenAtStart = false
    refresh()
  }

  fun setScreenSecurityEnabled(enabled: Boolean) {
    SignalStore.settings.isScreenSecurityEnabled = enabled
    refresh()
  }

  fun setIncognitoKeyboard(enabled: Boolean) {
    SignalStore.settings.isIncognitoKeyboardEnabled = enabled
    refresh()
  }

  fun refresh() {
    store.update(this::updateState)
  }

  private fun getState(): PrivacySettingsState {
    return PrivacySettingsState(
      hasPhoneNumber = !SignalStore.account.isPhoneNumberless,
      blockedCount = 0,
      blockUnknown = TextSecurePreferences.isBlockUnknownEnabled(application),
      readReceipts = SignalStore.settings.isReadReceiptsEnabled,
      typingIndicators = SignalStore.settings.isTypingIndicatorsEnabled,
      passphraseLock = TextSecurePreferences.isPassphraseLockEnabled(application),
      passphraseLockTriggerValues = TextSecurePreferences.getPassphraseLockTrigger(application).triggers,
      passphraseLockTimeout = TextSecurePreferences.getPassphraseLockTimeout(application),
      biometricScreenLock = TextSecurePreferences.isBiometricScreenLockEnabled(application),
      screenSecurity = SignalStore.settings.isScreenSecurityEnabled,
      incognitoKeyboard = SignalStore.settings.isIncognitoKeyboardEnabled,
      universalExpireTimer = SignalStore.settings.universalExpireTimer
    )
  }

  private fun updateState(state: PrivacySettingsState): PrivacySettingsState {
    return getState().copy(blockedCount = state.blockedCount)
  }

  class Factory(
    private val sharedPreferences: SharedPreferences,
    private val repository: PrivacySettingsRepository
  ) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
      return requireNotNull(modelClass.cast(PrivacySettingsViewModel(sharedPreferences, repository)))
    }
  }
}
