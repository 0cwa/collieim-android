package org.thoughtcrime.securesms.linkdevice

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.signal.core.ui.compose.Buttons
import org.signal.core.ui.compose.DayNightPreviews
import org.signal.core.ui.compose.Previews
import org.thoughtcrime.securesms.R

@Composable
fun LinkDeviceManualEntryScreen(
  onLinkNewDeviceWithUrl: (String) -> Unit,
  modifier: Modifier = Modifier
) {
  val context = LocalContext.current
  val keyboard = LocalSoftwareKeyboardController.current
  // Provisioning codes are temporary: never save them in instance state.
  var linkingCode by remember { mutableStateOf("") }
  var clipboardEmpty by remember { mutableStateOf(false) }
  val parsedCode = remember(linkingCode) { LinkDeviceRepository.parseLinkingCode(linkingCode) }
  val invalidCode = linkingCode.isNotBlank() && parsedCode == null
  val onContinue: () -> Unit = {
    parsedCode?.let {
      keyboard?.hide()
      onLinkNewDeviceWithUrl(it.toString())
    }
  }

  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    modifier = modifier
      .fillMaxSize()
      .imePadding()
  ) {
    LazyColumn(
      modifier = Modifier
        .weight(1f)
        .padding(horizontal = 24.dp)
    ) {
      item {
        Text(
          text = stringResource(R.string.LinkDeviceCode__paste_instructions),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(vertical = 12.dp)
        )
      }

      item {
        OutlinedTextField(
          value = linkingCode,
          onValueChange = {
            linkingCode = it
            clipboardEmpty = false
          },
          label = { Text(stringResource(R.string.LinkDeviceCode__linking_code)) },
          isError = invalidCode || clipboardEmpty,
          supportingText = if (invalidCode || clipboardEmpty) {
            {
              Text(stringResource(if (clipboardEmpty) R.string.LinkDeviceCode__clipboard_empty else R.string.LinkDeviceCode__invalid_code))
            }
          } else {
            null
          },
          keyboardOptions = KeyboardOptions(
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Done
          ),
          keyboardActions = KeyboardActions(onDone = { onContinue() }),
          minLines = 3,
          maxLines = 5,
          modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
        )
        TextButton(
          onClick = {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = clipboard.primaryClip
            // Only read plain text after the user asks to paste; do not open clipboard URIs.
            val text = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).text?.toString() else null
            clipboardEmpty = text.isNullOrBlank()
            if (!text.isNullOrBlank()) {
              linkingCode = text
            }
          }
        ) {
          Text(stringResource(R.string.LinkDeviceCode__paste_code))
        }
      }

      item {
        Text(
          text = stringResource(R.string.LinkDeviceCode__only_link_your_devices),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(top = 12.dp)
        )
        Text(
          text = stringResource(R.string.AddLinkDeviceFragment__this_device_will_see_your_groups_contacts),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(vertical = 12.dp)
        )
      }
    }

    Buttons.LargeTonal(
      enabled = parsedCode != null,
      onClick = onContinue,
      modifier = Modifier
        .defaultMinSize(minWidth = 220.dp)
        .padding(16.dp)
    ) {
      Text(stringResource(R.string.DeviceProvisioningActivity_continue))
    }
  }
}

@DayNightPreviews
@Composable
private fun LinkDeviceManualEntryScreenPreview() {
  Previews.Preview {
    LinkDeviceManualEntryScreen(onLinkNewDeviceWithUrl = {})
  }
}
