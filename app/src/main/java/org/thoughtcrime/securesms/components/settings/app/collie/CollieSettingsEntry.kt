/*
 * Copyright 2026 Collie contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.components.settings.app.collie

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.pin.RecoveryPinGuard
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.signal.appsettings.collie.CollieSettingsScreen
import org.signal.appsettings.routes.AppSettingsNavigationEvent
import org.signal.appsettings.routes.AppSettingsRoute
import org.signal.appsettings.routes.navigateTo
import org.thoughtcrime.securesms.components.settings.app.AppSettingsRepository
import org.thoughtcrime.securesms.components.settings.app.labs.LabsSettingsViewModel

/** Reuses the existing Labs preferences and toggle handling for Collie's selected features. */
@Composable
fun CollieSettingsEntry(parentEventEmitter: (AppSettingsNavigationEvent) -> Unit) {
  val viewModel: LabsSettingsViewModel = viewModel { LabsSettingsViewModel(parentEventEmitter) }
  val state by viewModel.state.collectAsStateWithLifecycle()

  val context = LocalContext.current
  var enabled by remember { mutableStateOf(SignalStore.pin.recoveryPinGuardEnabled) }
  var protectBackupKey by remember { mutableStateOf(BackupKeyAuthentication.isEnabled(context)) }
  val authenticate = rememberBackupKeyPhoneAuthentication(stringResource(R.string.RemoteBackupsSettingsFragment__unlock_to_view_backup_key))
  CollieSettingsScreen(
    state = state,
    onEvent = viewModel::onEvent,
    showAppUpdates = AppSettingsRepository.showAppUpdates(),
    onAppUpdatesClick = { parentEventEmitter.navigateTo(AppSettingsRoute.AppUpdates) },
    protectBackupKey = protectBackupKey,
    onProtectBackupKeyChanged = { desired ->
      authenticate {
        BackupKeyAuthentication.setEnabled(context, desired)
        protectBackupKey = desired
      }
    },
    recoveryPinGuardEnabled = enabled,
    onRecoveryPinGuardChanged = { desired ->
      RecoveryPinGuard.verify(context) {
        SignalStore.pin.recoveryPinGuardEnabled = desired
        enabled = desired
      }
    }
  )
}
