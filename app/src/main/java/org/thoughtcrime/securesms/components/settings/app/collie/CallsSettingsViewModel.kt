/*
 * Copyright 2026 Collie contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.components.settings.app.collie

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.signal.appsettings.collie.CallsSettingsEvent
import org.signal.appsettings.collie.CallsSettingsState
import org.signal.appsettings.routes.AppSettingsNavigationEvent
import org.signal.appsettings.routes.navigateBack
import org.signal.core.util.concurrent.SignalDispatchers

/** Publishes a changed setting only after the policy's durable write succeeds. */
internal class CallsSettingsViewModel(
  private val parentEventEmitter: (AppSettingsNavigationEvent) -> Unit,
  private val access: CallPermissionsSettingsAccess = LocalCallPermissionsSettingsAccess,
  private val ioDispatcher: CoroutineDispatcher = SignalDispatchers.IO
) : ViewModel() {
  private val mutableState = MutableStateFlow(CallsSettingsState())
  val state = mutableState.asStateFlow()

  init {
    viewModelScope.launch { access.changes.collect { refresh() } }
  }

  fun onEvent(event: CallsSettingsEvent) {
    when (event) {
      CallsSettingsEvent.NavigateBack -> parentEventEmitter.navigateBack()
      CallsSettingsEvent.ErrorDismissed -> mutableState.update { it.copy(saveFailed = false) }
      CallsSettingsEvent.Refresh -> viewModelScope.launch { refresh() }
      is CallsSettingsEvent.DisableAllChanged -> if (state.value.allCallingDisabled != event.disabled) change { access.setAllCallingDisabled(event.disabled) }
      is CallsSettingsEvent.IncomingChanged -> if (!state.value.allCallingDisabled && state.value.allowIncoming != event.allowed) change { access.setIncomingDefault(event.allowed) }
      is CallsSettingsEvent.GroupsChanged -> if (!state.value.allCallingDisabled && state.value.allowGroups != event.allowed) change { access.setGroupDefault(event.allowed) }
    }
  }

  private fun change(operation: () -> Unit) {
    if (state.value.loading || !state.value.available || state.value.saving) return
    mutableState.update { it.copy(saving = true, saveFailed = false) }
    viewModelScope.launch {
      try {
        withContext(ioDispatcher) { operation() }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        mutableState.update { it.copy(saveFailed = true) }
      } finally {
        refresh()
        mutableState.update { it.copy(saving = false) }
      }
    }
  }

  private suspend fun refresh() {
    val snapshot = try {
      withContext(ioDispatcher) { access.readDevice() }
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (_: Exception) {
      CallsSettingsState(loading = false, available = false)
    }
    mutableState.update { snapshot.copy(saving = it.saving, saveFailed = it.saveFailed) }
  }
}
