/*
 * Copyright 2026 Collie contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package org.thoughtcrime.securesms.components.webrtc.v2

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.recipients.Recipient

/** Progress before calling exists. This layout has no renderer, camera, microphone or call controls. */
@Composable
fun CallStartingScreen(recipient: Recipient, onCancel: () -> Unit) {
  Column(
    modifier = Modifier.fillMaxSize().background(Color.Black).systemBarsPadding().padding(24.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically)
  ) {
    Text(
      text = recipient.getDisplayName(LocalContext.current),
      style = MaterialTheme.typography.headlineSmall,
      color = MaterialTheme.colorScheme.onSurface,
      textAlign = TextAlign.Center
    )
    CircularProgressIndicator(color = MaterialTheme.colorScheme.onSurface)
    Text(
      text = stringResource(R.string.CallPermissions__starting_call),
      style = MaterialTheme.typography.bodyLarge,
      color = MaterialTheme.colorScheme.onSurface,
      textAlign = TextAlign.Center
    )
    TextButton(onClick = onCancel) {
      Text(stringResource(android.R.string.cancel))
    }
  }
}
