/*
 * Copyright 2026 Collie contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.components.settings.app.collie.groups

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.signal.appsettings.collie.groups.GroupPrivacySettingsEvent
import org.signal.appsettings.collie.groups.GroupPrivacySettingsScreen
import org.signal.appsettings.routes.AppSettingsNavigationEvent

@Composable
fun GroupPrivacySettingsEntry(parentEventEmitter: (AppSettingsNavigationEvent) -> Unit) {
  val viewModel: GroupPrivacySettingsViewModel = viewModel { GroupPrivacySettingsViewModel(parentEventEmitter) }
  val state by viewModel.state.collectAsStateWithLifecycle()

  LifecycleResumeEffect(Unit) {
    viewModel.onEvent(GroupPrivacySettingsEvent.ScreenResumed)
    onPauseOrDispose {}
  }

  GroupPrivacySettingsScreen(state = state, onEvent = viewModel::onEvent)
}
