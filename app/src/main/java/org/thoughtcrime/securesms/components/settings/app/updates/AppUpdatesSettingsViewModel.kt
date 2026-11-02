/*
 * Copyright 2025 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.components.settings.app.updates

import android.app.Application
import im.molly.app.base.ApkInfo
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import org.signal.appsettings.updates.AppUpdatesSettingsAction
import org.signal.appsettings.updates.AppUpdatesSettingsEvent
import org.signal.appsettings.updates.AppUpdatesSettingsState
import org.signal.core.ui.compose.EventDrivenViewModel
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.BuildConfig
import org.thoughtcrime.securesms.apkupdate.ApkUpdateNotifications
import org.thoughtcrime.securesms.apkupdate.ApkUpdateRefreshListener
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.jobs.ApkUpdateJob
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.util.TextSecurePreferences
import kotlin.time.Duration.Companion.milliseconds

class AppUpdatesSettingsViewModel(
  private val isAutoUpdateSupported: Boolean = BuildConfig.MANAGE_MOLLY_UPDATES
) : EventDrivenViewModel<AppUpdatesSettingsEvent>(TAG) {

  companion object {
    private val TAG = Log.tag(AppUpdatesSettingsViewModel::class)
  }

  private val application: Application = AppDependencies.application
  private val _state = MutableStateFlow(readState())
  private val _actions = Channel<AppUpdatesSettingsAction>(Channel.BUFFERED)

  val state: StateFlow<AppUpdatesSettingsState> = _state.asStateFlow()
  val actions: Flow<AppUpdatesSettingsAction> = _actions.receiveAsFlow()

  override suspend fun processEvent(event: AppUpdatesSettingsEvent) {
    when (event) {
      AppUpdatesSettingsEvent.ScreenResumed -> refresh()
      AppUpdatesSettingsEvent.NavigateBackClicked -> _actions.send(AppUpdatesSettingsAction.NavigateBack)
      is AppUpdatesSettingsEvent.AutoUpdateToggled -> {
        if (isAutoUpdateSupported) {
          TextSecurePreferences.setUpdateApkEnabled(application, event.enabled)
          if (event.enabled) {
            checkForUpdates()
          }
          refresh()
        }
      }
      is AppUpdatesSettingsEvent.IncludeBetaToggled -> {
        if (isAutoUpdateSupported) {
          TextSecurePreferences.setUpdateApkIncludeBetaEnabled(application, event.enabled)
          ApkUpdateNotifications.dismissInstallPrompt(application)
          checkForUpdates()
          refresh()
        }
      }
      AppUpdatesSettingsEvent.CheckForUpdatesClicked -> {
        if (isAutoUpdateSupported && _state.value.autoUpdateEnabled) {
          checkForUpdates()
        }
      }
    }
  }

  private fun checkForUpdates() {
    ApkUpdateRefreshListener.scheduleIfAllowed(application)
    AppDependencies.jobManager.add(ApkUpdateJob())
  }

  private fun refresh() {
    _state.value = readState()
  }

  private fun readState(): AppUpdatesSettingsState {
    return AppUpdatesSettingsState(
      lastCheckedTime = SignalStore.apkUpdate.lastSuccessfulCheck.milliseconds,
      autoUpdateEnabled = TextSecurePreferences.isUpdateApkEnabled(application),
      isAutoUpdateSupported = isAutoUpdateSupported,
      includeBetaEnabled = TextSecurePreferences.isUpdateApkIncludeBetaEnabled(application),
      versionName = ApkInfo.versionName
    )
  }
}
