/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.components.settings.app.appearance.appicon

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.signal.appsettings.appicon.AppIconSelectionScreen
import org.signal.appsettings.appicon.AppIconSelectionViewModel
import org.signal.appsettings.appicon.AppIconUtility
import org.signal.appsettings.routes.AppSettingsNavigationEvent

/**
 * The app icon picker, wired to its view model.
 */
@Composable
fun AppIconSelectionEntry(parentEventEmitter: (AppSettingsNavigationEvent) -> Unit) {
  val context = LocalContext.current
  val appIconUtility = remember(context) { AppIconUtility(context) }
  val viewModel: AppIconSelectionViewModel = viewModel { AppIconSelectionViewModel(appIconUtility, parentEventEmitter) }
  val state by viewModel.state.collectAsStateWithLifecycle()

  AppIconSelectionScreen(
    state = state,
    appIconMetadata = appIconUtility.appIconMetadata,
    onEvent = viewModel::onEvent
  )
}
