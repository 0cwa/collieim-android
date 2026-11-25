/*
 * Copyright 2026 Collie contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.components.settings.app.collie

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.signal.appsettings.collie.CollieSettingsScreen
import org.signal.appsettings.routes.AppSettingsNavigationEvent
import org.signal.appsettings.routes.AppSettingsRoute
import org.signal.appsettings.routes.navigateTo
import org.thoughtcrime.securesms.components.settings.app.AppSettingsRepository
import org.thoughtcrime.securesms.components.settings.app.labs.LabsSettingsViewModel
import org.thoughtcrime.securesms.flags.CollieFlags

/** Reuses the existing Labs preferences and toggle handling for Collie's selected features. */
@Composable
fun CollieSettingsEntry(parentEventEmitter: (AppSettingsNavigationEvent) -> Unit) {
  val viewModel: LabsSettingsViewModel = viewModel { LabsSettingsViewModel(parentEventEmitter) }
  val state by viewModel.state.collectAsStateWithLifecycle()

  CollieSettingsScreen(
    state = state,
    onEvent = viewModel::onEvent,
    showAppUpdates = CollieFlags.appUpdatesInCollieSettings && AppSettingsRepository.showAppUpdates(),
    onAppUpdatesClick = { parentEventEmitter.navigateTo(AppSettingsRoute.AppUpdates) }
  )
}
