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
import org.signal.appsettings.collie.CallPermissionChoice
import org.signal.appsettings.collie.CallPermissionOverrideState
import org.signal.core.util.concurrent.SignalDispatchers
import org.thoughtcrime.securesms.recipients.Recipient

internal class CallPermissionOverrideViewModel(
  private val recipient: Recipient,
  private val access: CallPermissionsSettingsAccess = LocalCallPermissionsSettingsAccess,
  private val ioDispatcher: CoroutineDispatcher = SignalDispatchers.IO
) : ViewModel() {
  private val mutableState = MutableStateFlow(CallPermissionOverrideState(group = recipient.isGroup))
  val state = mutableState.asStateFlow()

  init {
    viewModelScope.launch { access.changes.collect { refresh() } }
  }

  fun select(choice: CallPermissionChoice) {
    val current = state.value
    if (current.loading || !current.available || current.saving || current.allCallingDisabled || current.choice == choice) return
    mutableState.update { it.copy(saving = true, saveFailed = false) }
    viewModelScope.launch {
      try {
        withContext(ioDispatcher) { access.setOverride(recipient, choice) }
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

  fun dismissError() {
    mutableState.update { it.copy(saveFailed = false) }
  }

  private suspend fun refresh() {
    val snapshot = try {
      withContext(ioDispatcher) { access.readRecipient(recipient) }
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (_: Exception) {
      CallPermissionOverrideState(loading = false, available = false, group = recipient.isGroup)
    }
    mutableState.update { snapshot.copy(saving = it.saving, saveFailed = it.saveFailed) }
  }
}
