package org.thoughtcrime.securesms.components.settings.app.privacy

<<<<<<< MOLLY
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
=======
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import org.signal.appsettings.privacy.PrivacySettingsAction
import org.signal.appsettings.privacy.PrivacySettingsEvent
import org.signal.appsettings.privacy.PrivacySettingsState
import org.signal.appsettings.privacy.PrivacySettingsState.Dialog
import org.signal.appsettings.privacy.PrivacySettingsState.Snackbar
import org.signal.appsettings.routes.AppSettingsNavigationEvent
import org.signal.appsettings.routes.AppSettingsRoute
import org.signal.appsettings.routes.navigateBack
import org.signal.appsettings.routes.navigateTo
import org.signal.core.ui.compose.EventDrivenViewModel
import org.signal.core.util.logging.Log
>>>>>>> SIGNAL

/**
 * Drives [org.signal.appsettings.privacy.PrivacySettingsScreen]. Navigation goes straight to [parentEventEmitter].
 */
class PrivacySettingsViewModel(
  private val parentEventEmitter: (AppSettingsNavigationEvent) -> Unit,
  scrollToPaymentLock: Boolean = false
) : EventDrivenViewModel<PrivacySettingsEvent>(TAG) {

<<<<<<< MOLLY
  private val application: Application = AppDependencies.application

  private val store = Store(getState())
=======
  companion object {
    private val TAG = Log.tag(PrivacySettingsViewModel::class)
  }
>>>>>>> SIGNAL

  private val _state = MutableStateFlow(PrivacySettingsState(scrollToPaymentLock = scrollToPaymentLock))
  private val _actions = Channel<PrivacySettingsAction>(Channel.BUFFERED)

  val state: StateFlow<PrivacySettingsState> = _state.asStateFlow()
  val actions: Flow<PrivacySettingsAction> = _actions.receiveAsFlow()

  init {
    refresh()
  }

  override suspend fun processEvent(event: PrivacySettingsEvent) {
    when (event) {
      PrivacySettingsEvent.ScreenResumed -> {
        refresh()
        _state.update { it.copy(blockedCount = PrivacySettingsRepository.getBlockedCount()) }
      }

      PrivacySettingsEvent.NavigateBackClicked -> parentEventEmitter.navigateBack()

      PrivacySettingsEvent.ScrolledToPaymentLock -> _state.update { it.copy(scrollToPaymentLock = false) }

      PrivacySettingsEvent.PhoneNumberClicked -> parentEventEmitter.navigateTo(AppSettingsRoute.PrivacyRoute.PhoneNumberPrivacy)

      PrivacySettingsEvent.BlockedClicked -> parentEventEmitter.navigateTo(AppSettingsRoute.PrivacyRoute.BlockedUsers)

      is PrivacySettingsEvent.ReadReceiptsToggled -> {
        PrivacySettingsRepository.setReadReceiptsEnabled(event.enabled)
        refresh()
      }

      is PrivacySettingsEvent.TypingIndicatorsToggled -> {
        PrivacySettingsRepository.setTypingIndicatorsEnabled(event.enabled)
        refresh()
      }

      PrivacySettingsEvent.DefaultTimerClicked -> parentEventEmitter.navigateTo(AppSettingsRoute.PrivacyRoute.ExpiringMessages)

      PrivacySettingsEvent.DisablePassphraseClicked -> _state.update { it.copy(dialog = Dialog.ConfirmDisablePassphrase) }

      PrivacySettingsEvent.DisablePassphraseConfirmed -> {
        if (_state.value.dialog != Dialog.ConfirmDisablePassphrase) return
        _state.update { it.copy(dialog = Dialog.None) }
        PrivacySettingsRepository.disableObsoletePassphrase()
        refresh()
      }

      PrivacySettingsEvent.ChangePassphraseClicked -> {
        if (PrivacySettingsRepository.isObsoletePassphraseInitialized()) {
          _actions.send(PrivacySettingsAction.LaunchChangePassphrase)
        } else {
          _state.update { it.copy(snackbar = Snackbar.PassphraseNotSet) }
        }
      }

      is PrivacySettingsEvent.PassphraseTimeoutToggled -> {
        PrivacySettingsRepository.setObsoletePassphraseTimeoutEnabled(event.enabled)
        refresh()
      }

      PrivacySettingsEvent.PassphraseTimeoutIntervalClicked -> _state.update { it.copy(dialog = Dialog.PassphraseTimeout) }

      is PrivacySettingsEvent.PassphraseTimeoutSelected -> {
        if (_state.value.dialog != Dialog.PassphraseTimeout) return
        _state.update { it.copy(dialog = Dialog.None) }
        PrivacySettingsRepository.setObsoletePassphraseTimeout(event.timeout)
        refresh()
      }

      PrivacySettingsEvent.ScreenLockClicked -> parentEventEmitter.navigateTo(AppSettingsRoute.PrivacyRoute.ScreenLock)

      PrivacySettingsEvent.DisabledScreenLockClicked -> _state.update { it.copy(snackbar = Snackbar.ScreenLockNeedsDeviceSecurity) }

      is PrivacySettingsEvent.ScreenSecurityToggled -> {
        PrivacySettingsRepository.setScreenSecurityEnabled(event.enabled)
        refresh()
        _actions.send(PrivacySettingsAction.ApplyScreenSecurity(event.enabled))
      }

      is PrivacySettingsEvent.IncognitoKeyboardToggled -> {
        PrivacySettingsRepository.setIncognitoKeyboardEnabled(event.enabled)
        refresh()
      }

      PrivacySettingsEvent.IncognitoKeyboardLearnMoreClicked -> _actions.send(PrivacySettingsAction.OpenIncognitoKeyboardLearnMore)

      PrivacySettingsEvent.EnablePaymentLockClicked -> {
        if (PrivacySettingsRepository.isDeviceSecure()) {
          PrivacySettingsRepository.setPaymentLockEnabled(true)
          refresh()
        } else {
          _state.update { it.copy(dialog = Dialog.PaymentLockNeedsDeviceSecurity) }
        }
      }

      PrivacySettingsEvent.PaymentLockDisableAuthenticated -> {
        PrivacySettingsRepository.setPaymentLockEnabled(false)
        refresh()
      }

      PrivacySettingsEvent.OpenDeviceSecuritySettingsClicked -> {
        _state.update { it.copy(dialog = Dialog.None) }
        _actions.send(PrivacySettingsAction.OpenDeviceSecuritySettings)
      }

      PrivacySettingsEvent.DeviceSecuritySettingsUnavailable -> _state.update { it.copy(snackbar = Snackbar.DeviceSecuritySettingsUnavailable) }

      PrivacySettingsEvent.AdvancedClicked -> parentEventEmitter.navigateTo(AppSettingsRoute.PrivacyRoute.Advanced)

      PrivacySettingsEvent.DialogDismissed -> _state.update { it.copy(dialog = Dialog.None) }

      PrivacySettingsEvent.SnackbarDismissed -> _state.update { it.copy(snackbar = Snackbar.None) }
    }
  }

<<<<<<< MOLLY
  fun setBlockUnknownEnabled(enabled: Boolean) {
    TextSecurePreferences.setBlockUnknownEnabled(application, enabled)
    refresh()
  }

  fun setReadReceiptsEnabled(enabled: Boolean) {
    sharedPreferences.edit().putBoolean(TextSecurePreferences.READ_RECEIPTS_PREF, enabled).apply()
    repository.syncReadReceiptState()
    refresh()
  }

  fun setTypingIndicatorsEnabled(enabled: Boolean) {
    sharedPreferences.edit().putBoolean(TextSecurePreferences.TYPING_INDICATORS, enabled).apply()
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
    sharedPreferences.edit().putBoolean(TextSecurePreferences.SCREEN_SECURITY_PREF, enabled).apply()
    refresh()
  }

  fun setIncognitoKeyboard(enabled: Boolean) {
    sharedPreferences.edit().putBoolean(TextSecurePreferences.INCOGNITO_KEYBOARD_PREF, enabled).apply()
    refresh()
  }

  fun refresh() {
    store.update(this::updateState)
  }

  private fun getState(): PrivacySettingsState {
    return PrivacySettingsState(
      blockedCount = 0,
      blockUnknown = TextSecurePreferences.isBlockUnknownEnabled(application),
      readReceipts = TextSecurePreferences.isReadReceiptsEnabled(application),
      typingIndicators = TextSecurePreferences.isTypingIndicatorsEnabled(application),
      passphraseLock = TextSecurePreferences.isPassphraseLockEnabled(application),
      passphraseLockTriggerValues = TextSecurePreferences.getPassphraseLockTrigger(application).triggers,
      passphraseLockTimeout = TextSecurePreferences.getPassphraseLockTimeout(application),
      biometricScreenLock = TextSecurePreferences.isBiometricScreenLockEnabled(application),
      screenSecurity = TextSecurePreferences.isScreenSecurityEnabled(application),
      incognitoKeyboard = TextSecurePreferences.isIncognitoKeyboardEnabled(application),
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
=======
  private fun refresh() {
    _state.update {
      it.copy(
        hasPhoneNumber = PrivacySettingsRepository.hasPhoneNumber(),
        readReceipts = PrivacySettingsRepository.isReadReceiptsEnabled(),
        typingIndicators = PrivacySettingsRepository.isTypingIndicatorsEnabled(),
        universalExpireTimer = PrivacySettingsRepository.getUniversalExpireTimer(),
        isObsoletePassphraseEnabled = PrivacySettingsRepository.isObsoletePassphraseEnabled(),
        isObsoletePassphraseTimeoutEnabled = PrivacySettingsRepository.isObsoletePassphraseTimeoutEnabled(),
        obsoletePassphraseTimeout = PrivacySettingsRepository.getObsoletePassphraseTimeout(),
        isDeviceSecure = PrivacySettingsRepository.isDeviceSecure(),
        screenLock = PrivacySettingsRepository.isScreenLockEnabled(),
        screenLockTimeout = PrivacySettingsRepository.getScreenLockTimeout(),
        screenSecurity = PrivacySettingsRepository.isScreenSecurityEnabled(),
        incognitoKeyboard = PrivacySettingsRepository.isIncognitoKeyboardEnabled(),
        paymentLock = PrivacySettingsRepository.isPaymentLockEnabled()
      )
>>>>>>> SIGNAL
    }
  }
}
