/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.components.settings.app.notifications

import androidx.lifecycle.viewModelScope
<<<<<<< MOLLY
import im.molly.unifiedpush.UnifiedPushDistributor
import im.molly.unifiedpush.components.settings.app.notifications.updateMollySocket
import im.molly.unifiedpush.model.MollySocket
import kotlinx.coroutines.Dispatchers
=======
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
>>>>>>> SIGNAL
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
<<<<<<< MOLLY
import org.thoughtcrime.securesms.ApplicationContext
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.keyvalue.SettingsValues.NotificationDeliveryMethod
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.notifications.DeviceSpecificNotificationConfig
import org.thoughtcrime.securesms.notifications.NotificationChannels
import org.thoughtcrime.securesms.notifications.SlowNotificationHeuristics
import org.thoughtcrime.securesms.preferences.widgets.NotificationPrivacyPreference
import org.thoughtcrime.securesms.util.TextSecurePreferences
=======
import org.signal.appsettings.notifications.NotificationsSettingsAction
import org.signal.appsettings.notifications.NotificationsSettingsEvent
import org.signal.appsettings.notifications.NotificationsSettingsScreen
import org.signal.appsettings.notifications.NotificationsSettingsState
import org.signal.appsettings.routes.AppSettingsNavigationEvent
import org.signal.appsettings.routes.AppSettingsRoute
import org.signal.appsettings.routes.navigateBack
import org.signal.appsettings.routes.navigateTo
import org.signal.core.ui.compose.EventDrivenViewModel
import org.signal.core.util.logging.Log
>>>>>>> SIGNAL

/**
 * Drives [NotificationsSettingsScreen].
 */
class NotificationsSettingsViewModel(
  private val parentEventEmitter: (AppSettingsNavigationEvent) -> Unit
) : EventDrivenViewModel<NotificationsSettingsEvent>(TAG) {

  companion object {
    private val TAG = Log.tag(NotificationsSettingsViewModel::class)
  }

  private val _state = MutableStateFlow(
    NotificationsSettingsRepository.run {
      syncFromNotificationChannel()
      getState(NotificationsSettingsState())
    }
  )
  private val _actions = Channel<NotificationsSettingsAction>(Channel.BUFFERED)

  val state: StateFlow<NotificationsSettingsState> = _state.asStateFlow()
  val actions: Flow<NotificationsSettingsAction> = _actions.receiveAsFlow()

  init {
    viewModelScope.launch {
      val troubleshoot = NotificationsSettingsRepository.isTroubleshootingRecommended()
      _state.update { it.copy(messageNotificationsState = it.messageNotificationsState.copy(troubleshootNotifications = troubleshoot)) }
    }

    viewModelScope.launch {
      NotificationsSettingsRepository.observeSelfChanges().collect { refresh() }
    }
  }

<<<<<<< MOLLY
  fun refresh() {
    store.update { getState(currentState = it) }
  }

  fun setMessageNotificationsEnabled(enabled: Boolean) {
    SignalStore.settings.isMessageNotificationsEnabled = enabled
    refresh()
  }

  fun setMessageNotificationsSound(sound: Uri?) {
    val messageSound = sound ?: Uri.EMPTY
    SignalStore.settings.messageNotificationSound = messageSound
    NotificationChannels.getInstance().updateMessageRingtone(messageSound)
    refresh()
  }

  fun setMessageNotificationVibration(enabled: Boolean) {
    SignalStore.settings.isMessageVibrateEnabled = enabled
    NotificationChannels.getInstance().updateMessageVibrate(enabled)
    refresh()
  }

  fun setMessageNotificationLedColor(color: String) {
    SignalStore.settings.messageLedColor = color
    NotificationChannels.getInstance().updateMessagesLedColor(color)
    refresh()
  }

  fun setMessageNotificationLedBlink(blink: String) {
    SignalStore.settings.messageLedBlinkPattern = blink
    refresh()
  }

  fun setMessageNotificationInChatSoundsEnabled(enabled: Boolean) {
    SignalStore.settings.isMessageNotificationsInChatSoundsEnabled = enabled
    refresh()
  }

  fun setMessageRepeatAlerts(repeats: Int) {
    SignalStore.settings.messageNotificationsRepeatAlerts = repeats
    refresh()
  }

  fun setMessageNotificationPrivacy(preference: String) {
    SignalStore.settings.messageNotificationsPrivacy = NotificationPrivacyPreference(preference)
    refresh()
  }

  fun setMessageNotificationPriority(priority: Int) {
    sharedPreferences.edit().putString(TextSecurePreferences.NOTIFICATION_PRIORITY_PREF, priority.toString()).apply()
    refresh()
  }

  fun setCallNotificationsEnabled(enabled: Boolean) {
    SignalStore.settings.isCallNotificationsEnabled = enabled
    refresh()
  }

  fun setCallRingtone(ringtone: Uri?) {
    SignalStore.settings.callRingtone = ringtone ?: Uri.EMPTY
    refresh()
  }

  fun setCallVibrateEnabled(enabled: Boolean) {
    SignalStore.settings.isCallVibrateEnabled = enabled
    refresh()
  }

  fun setNotifyWhenContactJoinsSignal(enabled: Boolean) {
    SignalStore.settings.isNotifyWhenContactJoinsSignal = enabled
    refresh()
  }

  fun setNotifyWhileLocked(enabled: Boolean) {
    TextSecurePreferences.setPassphraseLockNotificationsEnabled(AppDependencies.application, enabled)
    refresh()
  }

  fun setPreferredNotificationMethod(method: NotificationDeliveryMethod) {
    SignalStore.settings.preferredNotificationMethod = method
    TextSecurePreferences.setPromptedOptimizeDoze(AppDependencies.application, false)
    ApplicationContext.getInstance(AppDependencies.application).updatePushNotificationServices()
    AppDependencies.resetNetwork(true)
    refresh()
  }

  fun selectFirstDistributor() {
    UnifiedPushDistributor.selectFirstDistributor()
  }

  fun initializeMollySocket(mollySocket: MollySocket) {
    SignalStore.unifiedpush.updateMollySocket(mollySocket)
  }

  fun setPlayServicesErrorCode(errorCode: Int?) {
    store.update { it.copy(playServicesErrorCode = errorCode) }
  }

  /**
   * @param currentState If provided and [calculateSlowNotifications] = false, then we will copy the slow notification state from it
   * @param calculateSlowNotifications If true, calculate the true slow notification state (this is not main-thread safe). Otherwise, it will copy from
   * [currentState] or default to false.
   */
  private fun getState(currentState: NotificationsSettingsState? = null, calculateSlowNotifications: Boolean = false): NotificationsSettingsState = NotificationsSettingsState(
    messageNotificationsState = MessageNotificationsState(
      notificationsEnabled = SignalStore.settings.isMessageNotificationsEnabled && canEnableNotifications(),
      canEnableNotifications = canEnableNotifications(),
      sound = SignalStore.settings.messageNotificationSound,
      vibrateEnabled = SignalStore.settings.isMessageVibrateEnabled,
      ledColor = SignalStore.settings.messageLedColor,
      ledBlink = SignalStore.settings.messageLedBlinkPattern,
      inChatSoundsEnabled = SignalStore.settings.isMessageNotificationsInChatSoundsEnabled,
      repeatAlerts = SignalStore.settings.messageNotificationsRepeatAlerts,
      messagePrivacy = SignalStore.settings.messageNotificationsPrivacy.toString(),
      priority = TextSecurePreferences.getNotificationPriority(AppDependencies.application),
      troubleshootNotifications = if (calculateSlowNotifications) {
        (SlowNotificationHeuristics.isBatteryOptimizationsOn() && SlowNotificationHeuristics.isHavingDelayedNotifications()) ||
          SlowNotificationHeuristics.getDeviceSpecificShowCondition() == DeviceSpecificNotificationConfig.ShowCondition.ALWAYS
      } else if (currentState != null) {
        currentState.messageNotificationsState.troubleshootNotifications
      } else {
        false
      }
    ),
    callNotificationsState = CallNotificationsState(
      notificationsEnabled = SignalStore.settings.isCallNotificationsEnabled && canEnableNotifications(),
      canEnableNotifications = canEnableNotifications(),
      ringtone = SignalStore.settings.callRingtone,
      vibrateEnabled = SignalStore.settings.isCallVibrateEnabled
    ),
    notifyWhileLocked = TextSecurePreferences.isPassphraseLockNotificationsEnabled(AppDependencies.application) && SignalStore.account.pushAvailable,
    canEnableNotifyWhileLocked = SignalStore.account.pushAvailable,
    isLinkedDevice = SignalStore.account.isLinkedDevice,
    preferredNotificationMethod = SignalStore.settings.preferredNotificationMethod,
    playServicesErrorCode = currentState?.playServicesErrorCode,
    canReceiveFcm = SignalStore.account.canReceiveFcm,
    canReceiveUnifiedPush = SignalStore.unifiedpush.isAvailableOrAirGapped,
    notifyWhenContactJoinsSignal = SignalStore.settings.isNotifyWhenContactJoinsSignal
  )

  private fun canEnableNotifications(): Boolean {
    val areNotificationsDisabledBySystem = Build.VERSION.SDK_INT >= 26 &&
      (
        !NotificationChannels.getInstance().isMessageChannelEnabled ||
          !NotificationChannels.getInstance().isMessagesChannelGroupEnabled ||
          !NotificationChannels.getInstance().areNotificationsEnabled()
        )

    return !areNotificationsDisabledBySystem
=======
  override suspend fun processEvent(event: NotificationsSettingsEvent) {
    when (event) {
      NotificationsSettingsEvent.ScreenResumed -> refresh()
      NotificationsSettingsEvent.NavigateBackClicked -> parentEventEmitter.navigateBack()
      NotificationsSettingsEvent.TurnOnNotificationsClicked -> _actions.send(NotificationsSettingsAction.ShowTurnOnNotificationsSheet)
      is NotificationsSettingsEvent.MessageNotificationsToggled -> changeSetting { setMessageNotificationsEnabled(event.enabled) }
      is NotificationsSettingsEvent.MessagePrivacySelected -> changeSetting { setMessageNotificationPrivacy(event.value) }
      NotificationsSettingsEvent.WhileMutedClicked -> parentEventEmitter.navigateTo(AppSettingsRoute.NotificationsRoute.MutedNotifications)
      is NotificationsSettingsEvent.ReactionNotificationsToggled -> changeSetting { setReactionNotificationsEnabled(event.enabled) }
      is NotificationsSettingsEvent.UnreadRemindersToggled -> changeSetting { setUnreadReminderEnabled(event.enabled) }
      is NotificationsSettingsEvent.ContactJoinsSignalToggled -> changeSetting { setNotifyWhenContactJoinsSignal(event.enabled) }
      is NotificationsSettingsEvent.RepeatAlertsSelected -> changeSetting { setMessageRepeatAlerts(event.value.toInt()) }
      NotificationsSettingsEvent.CustomizeClicked -> _actions.send(NotificationsSettingsAction.OpenMessageChannelSettings)
      NotificationsSettingsEvent.MessageSoundClicked -> _actions.send(NotificationsSettingsAction.LaunchMessageSoundPicker)
      is NotificationsSettingsEvent.MessageSoundPicked -> changeSetting { setMessageNotificationsSound(event.sound) }
      is NotificationsSettingsEvent.MessageVibrateToggled -> changeSetting { setMessageNotificationVibration(event.enabled) }
      is NotificationsSettingsEvent.LedColorSelected -> changeSetting { setMessageNotificationLedColor(event.value) }
      is NotificationsSettingsEvent.LedBlinkSelected -> changeSetting { setMessageNotificationLedBlink(event.value) }
      is NotificationsSettingsEvent.InChatSoundsToggled -> changeSetting { setMessageNotificationInChatSoundsEnabled(event.enabled) }
      NotificationsSettingsEvent.TroubleshootClicked -> _actions.send(NotificationsSettingsAction.ShowTroubleshootNotificationsDialog)
      NotificationsSettingsEvent.PriorityClicked -> _actions.send(NotificationsSettingsAction.LaunchNotificationPrioritySettings)
      is NotificationsSettingsEvent.PrioritySelected -> changeSetting { setMessageNotificationPriority(event.value.toInt()) }
      is NotificationsSettingsEvent.CallNotificationsToggled -> changeSetting { setCallNotificationsEnabled(event.enabled) }
      NotificationsSettingsEvent.CallRingtoneClicked -> _actions.send(NotificationsSettingsAction.LaunchCallRingtonePicker)
      is NotificationsSettingsEvent.CallRingtonePicked -> changeSetting { setCallRingtone(event.ringtone) }
      is NotificationsSettingsEvent.CallVibrateToggled -> changeSetting { setCallVibrateEnabled(event.enabled) }
      NotificationsSettingsEvent.SoundPickerUnavailable -> _state.update { it.copy(snackbar = NotificationsSettingsState.Snackbar.SoundPickerUnavailable) }
      NotificationsSettingsEvent.NotificationProfilesClicked -> parentEventEmitter.navigateTo(AppSettingsRoute.NotificationsRoute.NotificationProfiles)
      NotificationsSettingsEvent.ResetClicked -> _state.update { it.copy(dialog = NotificationsSettingsState.Dialog.ConfirmReset) }
      NotificationsSettingsEvent.ResetConfirmed -> {
        _state.update { it.copy(dialog = NotificationsSettingsState.Dialog.None) }
        changeSetting { resetSettings() }
      }
      NotificationsSettingsEvent.DialogDismissed -> _state.update { it.copy(dialog = NotificationsSettingsState.Dialog.None) }
      NotificationsSettingsEvent.SnackbarDismissed -> _state.update { it.copy(snackbar = NotificationsSettingsState.Snackbar.None) }
    }
>>>>>>> SIGNAL
  }

  /** Applies [change] to the stored settings, then reads them back. */
  private inline fun changeSetting(change: NotificationsSettingsRepository.() -> Unit) {
    NotificationsSettingsRepository.change()
    refresh()
  }

  private fun refresh() {
    _state.update { NotificationsSettingsRepository.getState(it) }
  }
}
