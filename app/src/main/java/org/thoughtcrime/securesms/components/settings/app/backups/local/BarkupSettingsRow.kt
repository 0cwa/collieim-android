/*
 * Copyright 2026 Collie contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package org.thoughtcrime.securesms.components.settings.app.backups.local

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.signal.appsettings.asCollieSettingTitle
import org.signal.core.ui.compose.Rows
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.backup.v2.barkup.BarkupClient
import org.thoughtcrime.securesms.backup.v2.barkup.BarkupHttpException
import org.thoughtcrime.securesms.backup.v2.barkup.BarkupOffer
import org.thoughtcrime.securesms.flags.CollieFlags
import org.thoughtcrime.securesms.jobs.CollieRemoteArchiveJob
import org.thoughtcrime.securesms.keyvalue.CollieBarkupValues
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.service.LocalBackupListener
import java.io.IOException

@Composable
internal fun BarkupSettingsRow(canPair: Boolean, viewRecoveryKey: () -> Unit) {
  if (!CollieFlags.barkupRemoteBackup) return

  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var visible by remember { mutableStateOf(false) }
  var offerText by remember { mutableStateOf("") }
  var busy by remember { mutableStateOf(false) }
  var error by remember { mutableStateOf<Int?>(null) }
  var summary by remember { mutableStateOf(currentSummary()) }
  val offer = remember(offerText) {
    if (offerText.isEmpty()) null else try {
      BarkupOffer.parse(offerText)
    } catch (_: IOException) {
      null
    }
  }

  Rows.TextRow(
    text = stringResource(R.string.BarkupSettings__title).asCollieSettingTitle(),
    label = AnnotatedString(summary?.host ?: stringResource(R.string.BarkupSettings__not_connected)),
    onClick = {
      summary = currentSummary()
      error = null
      visible = true
    }
  )

  if (!visible) return

  LaunchedEffect(Unit) {
    while (true) {
      summary = currentSummary()
      delay(1000)
    }
  }

  val current = summary
  val dismiss = {
    offerText = ""
    error = null
    visible = false
  }

  AlertDialog(
    onDismissRequest = { if (!busy) dismiss() },
    properties = DialogProperties(dismissOnBackPress = !busy, dismissOnClickOutside = !busy),
    title = { Text(stringResource(R.string.BarkupSettings__title)) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (current == null) {
          Text(stringResource(R.string.BarkupSettings__instructions))
          OutlinedTextField(
            value = offerText,
            onValueChange = { if (it.length <= 4096) offerText = it },
            label = { Text(stringResource(R.string.BarkupSettings__pairing_offer)) },
            modifier = Modifier.fillMaxWidth(),
            enabled = canPair && !busy,
            isError = offerText.isNotBlank() && offer == null,
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
          )
          if (offerText.isNotBlank() && offer == null) {
            Text(stringResource(R.string.BarkupSettings__invalid_offer), color = MaterialTheme.colorScheme.error)
          }
          if (offer != null) Text(stringResource(R.string.BarkupSettings__connect_to, offer.api.host))
        } else {
          Text(stringResource(R.string.BarkupSettings__connected_to, current.host))
          Text(
            if (current.status == CollieBarkupValues.Status.WORKING) {
              stringResource(R.string.BarkupSettings__backup_pending)
            } else if (current.status == CollieBarkupValues.Status.FAILED) {
              stringResource(R.string.BarkupSettings__backup_failed)
            } else if (current.lastSuccess != 0L) {
              stringResource(R.string.BarkupSettings__backup_saved)
            } else {
              stringResource(R.string.BarkupSettings__no_backup)
            }
          )
          TextButton(onClick = { dismiss(); viewRecoveryKey() }) {
            Text(stringResource(R.string.UnifiedOnDeviceBackupsSettingsScreen__view_recovery_key))
          }
          TextButton(
            enabled = !busy,
            onClick = {
              scope.launch {
                busy = true
                try {
                  if (withContext(Dispatchers.IO) { CollieRemoteArchiveJob.disconnect() }) {
                    summary = null
                    dismiss()
                  } else {
                    error = R.string.BarkupSettings__disconnect_failed
                  }
                } finally {
                  busy = false
                }
              }
            }
          ) {
            Text(stringResource(R.string.BarkupSettings__disconnect))
          }
          Text(stringResource(R.string.BarkupSettings__disconnect_info))
        }

        error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
      }
    },
    confirmButton = {
      if (current == null) {
        TextButton(
          enabled = offer != null && !busy && canPair,
          onClick = {
            val code = offer ?: return@TextButton
            val account = CollieRemoteArchiveJob.account() ?: return@TextButton
            val attempt = SignalStore.collieBarkup.beginPairing(account)
            scope.launch {
              busy = true
              offerText = ""
              error = null
              try {
                withContext(Dispatchers.IO) {
                  val credentials = BarkupClient(code.api).redeem(code)
                  currentCoroutineContext().ensureActive()
                  if (!SignalStore.collieBarkup.enable(attempt, code.api, credentials) { CollieRemoteArchiveJob.account() }) {
                    throw IOException("Barkup pairing was interrupted")
                  }
                  CollieRemoteArchiveJob.enqueue()
                  LocalBackupListener.schedule(context.applicationContext)
                }
                summary = currentSummary()
              } catch (failure: IOException) {
                error = if (failure is BarkupHttpException && failure.status == 403) {
                  R.string.BarkupSettings__offer_unavailable
                } else {
                  R.string.BarkupSettings__pairing_failed
                }
              } finally {
                busy = false
              }
            }
          }
        ) {
          Text(stringResource(if (busy) R.string.BarkupSettings__connecting else R.string.BarkupSettings__pair_and_enable))
        }
      } else {
        TextButton(
          enabled = !busy,
          onClick = {
            CollieRemoteArchiveJob.enqueue()
            dismiss()
          }
        ) {
          Text(stringResource(R.string.BarkupSettings__backup_now))
        }
      }
    },
    dismissButton = {
      TextButton(enabled = !busy, onClick = dismiss) {
        Text(stringResource(android.R.string.cancel))
      }
    }
  )
}

private fun currentSummary(): CollieBarkupValues.Summary? {
  return CollieRemoteArchiveJob.account()?.let { SignalStore.collieBarkup.summary(it) }
}
