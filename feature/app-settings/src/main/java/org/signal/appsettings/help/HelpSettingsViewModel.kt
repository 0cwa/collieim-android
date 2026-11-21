/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.appsettings.help

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import org.signal.appsettings.routes.AppSettingsNavigationEvent
import org.signal.appsettings.routes.AppSettingsRoute
import org.signal.appsettings.routes.navigateBack
import org.signal.appsettings.routes.navigateTo
import org.signal.core.ui.compose.EventDrivenViewModel
import org.signal.core.util.logging.Log

/**
 * Drives [HelpSettingsScreen].
 *
 * MOLLY: [getLogEnabled] and [setLogEnabled] read and store the debug-logging choice, which lives outside this module.
 */
class HelpSettingsViewModel(
  private val getLogEnabled: () -> Boolean,
  private val setLogEnabled: (Boolean) -> Unit,
  versionName: String,
  showAppUpdates: Boolean = false,
  private val parentEventEmitter: (AppSettingsNavigationEvent) -> Unit
) : EventDrivenViewModel<HelpSettingsEvent>(TAG) {

  companion object {
    private val TAG = Log.tag(HelpSettingsViewModel::class)
  }

  private val _state = MutableStateFlow(HelpSettingsState(versionName = versionName, logEnabled = getLogEnabled(), showAppUpdates = showAppUpdates))
  private val _actions = Channel<HelpSettingsAction>(Channel.BUFFERED)

  val state: StateFlow<HelpSettingsState> = _state.asStateFlow()
  val actions: Flow<HelpSettingsAction> = _actions.receiveAsFlow()

  override suspend fun processEvent(event: HelpSettingsEvent) {
    when (event) {
      HelpSettingsEvent.WebsiteClicked -> _actions.send(HelpSettingsAction.OpenWebsite)
      HelpSettingsEvent.NavigateBackClicked -> parentEventEmitter.navigateBack()
      HelpSettingsEvent.SupportCenterClicked -> _actions.send(HelpSettingsAction.OpenSupportCenter)
      HelpSettingsEvent.ContactUsClicked -> parentEventEmitter.navigateTo(AppSettingsRoute.HelpRoute.Help())
      HelpSettingsEvent.VersionClicked -> if (_state.value.showAppUpdates) parentEventEmitter.navigateTo(AppSettingsRoute.AppUpdates)
      HelpSettingsEvent.VersionLongClicked -> {
        _actions.send(HelpSettingsAction.CopyVersionToClipboard(_state.value.versionName))
        _state.update { it.copy(snackbar = HelpSettingsState.Snackbar.VersionCopied) }
      }
      is HelpSettingsEvent.DebugLoggingChanged -> {
        if (event.enabled) {
          applyLogEnabled(true)
        } else {
          _actions.send(HelpSettingsAction.ConfirmDisableDebugLogging)
        }
      }
      HelpSettingsEvent.DisableDebugLoggingConfirmed -> applyLogEnabled(false)
      HelpSettingsEvent.DebugLogClicked -> parentEventEmitter.navigateTo(AppSettingsRoute.HelpRoute.DebugLog)
      HelpSettingsEvent.LicensesClicked -> parentEventEmitter.navigateTo(AppSettingsRoute.HelpRoute.Licenses)
      HelpSettingsEvent.TermsAndPrivacyPolicyClicked -> _actions.send(HelpSettingsAction.OpenTermsAndPrivacyPolicy)
      HelpSettingsEvent.SnackbarDismissed -> _state.update { it.copy(snackbar = HelpSettingsState.Snackbar.None) }
    }
  }

  /** Reloads the externally stored logging choice when returning to the screen. */
  fun refresh() {
    _state.update { it.copy(logEnabled = getLogEnabled()) }
  }

  private fun applyLogEnabled(enabled: Boolean) {
    setLogEnabled(enabled)
    if (enabled) {
      Log.setLogging(true)
    } else {
      Log.disableAndWipeLogs()
    }
    refresh()
  }
}
