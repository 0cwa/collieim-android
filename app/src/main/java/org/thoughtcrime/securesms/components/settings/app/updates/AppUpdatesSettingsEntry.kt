/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.components.settings.app.updates

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.signal.appsettings.routes.AppSettingsNavigationEvent
import org.signal.appsettings.updates.AppUpdatesSettingsEvent
import org.signal.appsettings.updates.AppUpdatesSettingsScreen
import org.signal.core.util.eventbus.EventBus
import org.signal.core.util.eventbus.ThreadMode
import org.thoughtcrime.securesms.events.ApkUpdateEvent

/**
 * App update settings, wired to their view model.
 */
@Composable
fun AppUpdatesSettingsEntry(parentEventEmitter: (AppSettingsNavigationEvent) -> Unit) {
  val viewModel: AppUpdatesSettingsViewModel = viewModel { AppUpdatesSettingsViewModel(parentEventEmitter) }
  val state by viewModel.state.collectAsStateWithLifecycle()

  DisposableEffect(viewModel) {
    val subscription = EventBus.default.subscribe<ApkUpdateEvent>(ThreadMode.MAIN) { viewModel.onEvent(AppUpdatesSettingsEvent.ScreenResumed) }
    onDispose { subscription.unsubscribe() }
  }

  AppUpdatesSettingsScreen(
    state = state,
    onEvent = viewModel::onEvent
  )
}
