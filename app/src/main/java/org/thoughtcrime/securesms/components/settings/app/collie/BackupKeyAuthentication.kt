/*
 * Copyright 2026 Collie contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package org.thoughtcrime.securesms.components.settings.app.collie

import android.content.Context
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import org.thoughtcrime.securesms.R

/** This preference protects manual display, export and rotation of the backup recovery key. */
object BackupKeyAuthentication {
  private const val PREFERENCES = "collie_backup_key_authentication"
  private const val ENABLED = "enabled"

  fun isEnabled(context: Context): Boolean = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getBoolean(ENABLED, false)

  fun setEnabled(context: Context, enabled: Boolean) {
    context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().putBoolean(ENABLED, enabled).apply()
  }
}

/** An authorization is deliberately kept only in this composition, never in saved state. */
@Composable
fun BackupKeyAuthenticationGate(onAuthorizationChanged: (Boolean) -> Unit = {}, content: @Composable () -> Unit) {
  val context = LocalContext.current
  val enabled = remember { BackupKeyAuthentication.isEnabled(context) }
  if (!enabled) {
    content()
    return
  }

  var authorized by remember { mutableStateOf(false) }
  val authenticate = rememberBackupKeyPhoneAuthentication(stringResource(R.string.RemoteBackupsSettingsFragment__unlock_to_view_backup_key))

  LaunchedEffect(Unit) {
    authenticate { authorized = true }
  }

  if (authorized) {
    DisposableEffect(Unit) {
      onAuthorizationChanged(true)
      onDispose { onAuthorizationChanged(false) }
    }
    content()
  } else {
    TextButton(onClick = { authenticate { authorized = true } }) {
      Text(stringResource(R.string.RemoteBackupsSettingsFragment__unlock_to_view_backup_key))
    }
  }
}
