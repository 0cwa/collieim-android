/*
 * Copyright 2026 Collie contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.components.settings.app.collie.groups

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.signal.appsettings.collie.groups.GroupPrivacySettingsEvent
import org.signal.appsettings.collie.groups.GroupPrivacySettingsState
import org.signal.appsettings.routes.AppSettingsNavigationEvent
import org.signal.appsettings.routes.AppSettingsRoute
import org.signal.appsettings.routes.navigateBack
import org.signal.appsettings.routes.navigateTo
import org.signal.core.ui.compose.EventDrivenViewModel
import org.signal.core.util.logging.Log

class GroupPrivacySettingsViewModel(
  private val parentEventEmitter: (AppSettingsNavigationEvent) -> Unit
) : EventDrivenViewModel<GroupPrivacySettingsEvent>(TAG) {
  companion object {
    private val TAG = Log.tag(GroupPrivacySettingsViewModel::class)
  }

  private val _state = MutableStateFlow(GroupPrivacySettingsState())
  val state: StateFlow<GroupPrivacySettingsState> = _state.asStateFlow()

  init {
    onEvent(GroupPrivacySettingsEvent.ScreenResumed)
  }

  override suspend fun processEvent(event: GroupPrivacySettingsEvent) {
    when (event) {
      GroupPrivacySettingsEvent.ScreenResumed,
      GroupPrivacySettingsEvent.RetryClicked -> reload()
      GroupPrivacySettingsEvent.NavigateBackClicked -> parentEventEmitter.navigateBack()
      GroupPrivacySettingsEvent.ChoosePeopleClicked -> {
        if (_state.value.canChange) parentEventEmitter.navigateTo(AppSettingsRoute.CollieRoute.ChooseGroupPeople)
      }
      is GroupPrivacySettingsEvent.ModeSelected -> {
        if (_state.value.canChange && _state.value.mode != event.mode) {
          write { GroupPrivacySettingsRepository.setMode(event.mode) }
        }
      }
      is GroupPrivacySettingsEvent.RemovePersonClicked -> {
        if (_state.value.canChange && _state.value.allowedPeople.any { it.aci == event.aci }) {
          write { GroupPrivacySettingsRepository.removePerson(event.aci) }
        }
      }
    }
  }

  private suspend fun reload() {
    _state.update { it.copy(isLoading = true, isSaving = false, error = null) }
    try {
      _state.value = GroupPrivacySettingsRepository.load()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.w(TAG, "Could not load group privacy controls", e)
      _state.update { it.copy(isLoading = false, error = GroupPrivacySettingsState.Error.LOAD_FAILED) }
    }
  }

  private suspend fun write(persist: suspend () -> Unit) {
    _state.update { it.copy(isSaving = true, error = null) }
    try {
      persist()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.w(TAG, "Could not save group privacy controls", e)
      _state.update { it.copy(isSaving = false, error = GroupPrivacySettingsState.Error.SAVE_FAILED) }
      return
    }
    reload()
  }
}
