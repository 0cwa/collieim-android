/*
 * Copyright 2026 Collie contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.appsettings.collie

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import org.signal.appsettings.R
import org.signal.core.ui.compose.Rows

/** The choices are explicit; displaying the current default never converts INHERIT into a stored exception. */
@Composable
fun CallPermissionOverrideRow(
  state: CallPermissionOverrideState,
  onSelected: (CallPermissionChoice) -> Unit,
  onErrorDismissed: () -> Unit,
  modifier: Modifier = Modifier
) {
  val allow = stringResource(if (state.group) R.string.CollieCalls__allow_group else R.string.CollieCalls__allow_incoming)
  val block = stringResource(if (state.group) R.string.CollieCalls__block_group else R.string.CollieCalls__block_incoming)
  val inherited = stringResource(R.string.CollieCalls__inherit_summary, if (state.defaultAllowsCalls) allow else block)

  Rows.RadioListRow(
    text = stringResource(if (state.group) R.string.CollieCalls__groups else R.string.CollieCalls__incoming),
    labels = arrayOf(inherited, allow, block),
    values = CallPermissionChoice.entries.map { it.name }.toTypedArray(),
    selectedValue = state.choice.name,
    enabled = state.available && !state.loading && !state.saving && !state.allCallingDisabled,
    onSelected = { onSelected(CallPermissionChoice.valueOf(it)) },
    modifier = modifier.testTag(CallsSettingsTestTags.OVERRIDE)
  )
  CallPermissionDescription(
    stringResource(
      when {
        state.loading -> R.string.CollieCalls__loading
        !state.available -> R.string.CollieCalls__unavailable
        state.allCallingDisabled -> R.string.CollieCalls__all_disabled
        state.saving -> R.string.CollieCalls__saving
        state.group -> R.string.CollieCalls__group_override_description
        else -> R.string.CollieCalls__direct_override_description
      }
    )
  )
  CallPermissionSaveError(state.saveFailed, onErrorDismissed)
}
