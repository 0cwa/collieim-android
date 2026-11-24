/*
 * Copyright 2026 Collie contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.components.settings.app.collie

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.signal.appsettings.collie.CallPermissionOverrideRow
import org.signal.appsettings.collie.CallsSettingsScreen
import org.signal.appsettings.routes.AppSettingsNavigationEvent
import org.thoughtcrime.securesms.recipients.Recipient

@Composable
fun CallsSettingsEntry(parentEventEmitter: (AppSettingsNavigationEvent) -> Unit) {
  val viewModel: CallsSettingsViewModel = viewModel { CallsSettingsViewModel(parentEventEmitter) }
  val state by viewModel.state.collectAsStateWithLifecycle()
  CallsSettingsScreen(state = state, onEvent = viewModel::onEvent)
}

@Composable
fun CallPermissionOverrideEntry(recipient: Recipient) {
  val viewModel: CallPermissionOverrideViewModel = viewModel(key = "call-permission-${recipient.id.serialize()}") {
    CallPermissionOverrideViewModel(recipient)
  }
  val state by viewModel.state.collectAsStateWithLifecycle()
  CallPermissionOverrideRow(state = state, onSelected = viewModel::select, onErrorDismissed = viewModel::dismissError, onRetry = viewModel::retry)
}
