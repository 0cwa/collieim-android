/*
 * Copyright 2025 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.components.settings.app.updates

import android.app.Application
import im.molly.app.base.ApkInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.signal.appsettings.routes.AppSettingsNavigationEvent
import org.signal.appsettings.routes.navigateBack
import org.signal.appsettings.updates.AppUpdatesSettingsEvent
import org.signal.appsettings.updates.AppUpdatesSettingsState
import org.signal.core.ui.compose.EventDrivenViewModel
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.BuildConfig
import org.thoughtcrime.securesms.apkupdate.ApkUpdateNotifications
import org.thoughtcrime.securesms.apkupdate.ApkUpdateRefreshListener
import org.thoughtcrime.securesms.apkupdate.CollieUpdateProfile
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.jobs.ApkUpdateJob
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.util.CommunicationActions
import kotlin.time.Duration.Companion.milliseconds

class AppUpdatesSettingsViewModel(
  private val parentEventEmitter: (AppSettingsNavigationEvent) -> Unit,
  isAutoUpdateSupported: Boolean = BuildConfig.MANAGE_COLLIE_UPDATES,
  private val openRelease: (String) -> Unit = { CommunicationActions.openBrowserLink(AppDependencies.application, it) },
  applicationId: String = BuildConfig.APPLICATION_ID
) : EventDrivenViewModel<AppUpdatesSettingsEvent>(TAG) {

  companion object {
    private val TAG = Log.tag(AppUpdatesSettingsViewModel::class)
  }

  private val application: Application = AppDependencies.application
  private val updateProfile = CollieUpdateProfile.forApplicationId(applicationId)
  private val updatesSupported = isAutoUpdateSupported && updateProfile != null
  private val _state = MutableStateFlow(readState())

  val state: StateFlow<AppUpdatesSettingsState> = _state.asStateFlow()

  override suspend fun processEvent(event: AppUpdatesSettingsEvent) {
    when (event) {
      AppUpdatesSettingsEvent.ScreenResumed -> refresh()
      AppUpdatesSettingsEvent.NavigateBackClicked -> parentEventEmitter.navigateBack()
      is AppUpdatesSettingsEvent.AutoUpdateToggled -> {
        if (updatesSupported) {
          SignalStore.molly.updateApkEnabled = event.enabled
          if (event.enabled) {
            checkForUpdates()
          } else {
            ApkUpdateNotifications.dismissUpdatePrompt(application)
          }
          refresh()
        }
      }
      AppUpdatesSettingsEvent.ViewReleaseClicked -> {
        val profile = updateProfile
        if (updatesSupported && profile != null) {
          readState().availableVersionName?.let { openRelease(profile.releasePage(it)) }
        }
      }
      AppUpdatesSettingsEvent.CheckForUpdatesClicked -> {
        if (updatesSupported && _state.value.autoUpdateEnabled) {
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
      autoUpdateEnabled = SignalStore.molly.updateApkEnabled,
      isAutoUpdateSupported = updatesSupported,
      lastCheckFailed = SignalStore.apkUpdate.lastCheckFailed,
      availableVersionName = SignalStore.apkUpdate.availableVersionName?.takeIf {
        updatesSupported && SignalStore.apkUpdate.availableVersionCode > ApkInfo.versionCode && updateProfile?.isValidVersionName(it) == true
      },
      versionName = ApkInfo.versionName
    )
  }
}
