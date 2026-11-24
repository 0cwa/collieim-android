/*
 * Copyright 2026 Collie contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.appsettings.collie

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewWrapper
import androidx.compose.ui.unit.dp
import org.signal.appsettings.AppSettingsLazyColumn
import org.signal.appsettings.R
import org.signal.core.ui.compose.DayNightPreviews
import org.signal.core.ui.compose.Dialogs
import org.signal.core.ui.compose.Dividers
import org.signal.core.ui.compose.Rows
import org.signal.core.ui.compose.Scaffolds
import org.signal.core.ui.compose.SignalIcons
import org.signal.core.ui.compose.SignalPreviewWrapper
import org.signal.core.ui.compose.Texts
import org.signal.core.ui.compose.horizontalGutters

object CallsSettingsTestTags {
  const val SCROLLER = "collie-calls-scroller"
  const val DISABLE_ALL = "collie-calls-disable-all"
  const val ON_DEMAND = "collie-calls-on-demand"
  const val INCOMING = "collie-calls-incoming"
  const val GROUPS = "collie-calls-groups"
  const val OVERRIDE = "collie-calls-override"
}

/** The single Calls destination leaves room for later call-safety controls without expanding Collie's main screen. */
@Composable
fun CallsSettingsScreen(state: CallsSettingsState, onEvent: (CallsSettingsEvent) -> Unit, modifier: Modifier = Modifier) {
  val editable = state.available && !state.loading && !state.saving

  Scaffolds.Settings(
    title = stringResource(R.string.CollieCalls__title),
    navigationContentDescription = stringResource(R.string.AppSettingsScreen__go_back),
    navigationIcon = SignalIcons.ArrowStart.imageVector,
    onNavigationClick = { onEvent(CallsSettingsEvent.NavigateBack) },
    modifier = modifier,
    expandableTitle = true
  ) { padding ->
    AppSettingsLazyColumn(scaffoldPadding = padding, modifier = Modifier.testTag(CallsSettingsTestTags.SCROLLER)) {
      item { CallPermissionDescription(stringResource(R.string.CollieCalls__local_description)) }

      if (state.loading) {
        item { CallPermissionDescription(stringResource(R.string.CollieCalls__loading)) }
      } else if (!state.available) {
        item {
          CallPermissionDescription(stringResource(R.string.CollieCalls__unavailable))
          Rows.TextRow(text = stringResource(R.string.CollieCalls__try_again), onClick = { onEvent(CallsSettingsEvent.Refresh) })
        }
      }

      item { Texts.SectionHeader(stringResource(R.string.CollieCalls__device_restriction)) }
      item {
        Rows.ToggleRow(
          checked = state.allCallingDisabled,
          text = stringResource(R.string.CollieCalls__disable_all),
          label = stringResource(R.string.CollieCalls__disable_all_description),
          enabled = editable,
          onCheckChanged = { onEvent(CallsSettingsEvent.DisableAllChanged(it)) },
          modifier = Modifier.testTag(CallsSettingsTestTags.DISABLE_ALL)
        )
      }

      if (state.onDemandAvailable) {
        item { Dividers.Default() }
        item { Texts.SectionHeader(stringResource(R.string.CollieCalls__startup)) }
        item {
          Rows.ToggleRow(
            checked = state.onDemandCalling,
            text = stringResource(R.string.CollieCalls__on_demand),
            label = stringResource(R.string.CollieCalls__on_demand_description),
            enabled = editable && !state.allCallingDisabled,
            onCheckChanged = { onEvent(CallsSettingsEvent.OnDemandChanged(it)) },
            modifier = Modifier.testTag(CallsSettingsTestTags.ON_DEMAND)
          )
          CallPermissionDescription(stringResource(R.string.CollieCalls__engine_lifetime))
        }
      }

      item { Dividers.Default() }
      item { Texts.SectionHeader(stringResource(R.string.CollieCalls__defaults)) }
      item {
        Rows.RadioListRow(
          text = stringResource(R.string.CollieCalls__incoming),
          labels = arrayOf(stringResource(R.string.CollieCalls__allow_incoming), stringResource(R.string.CollieCalls__block_incoming)),
          values = arrayOf("allow", "block"),
          selectedValue = if (state.allowIncoming) "allow" else "block",
          enabled = editable && !state.allCallingDisabled,
          onSelected = { onEvent(CallsSettingsEvent.IncomingChanged(it == "allow")) },
          modifier = Modifier.testTag(CallsSettingsTestTags.INCOMING)
        )
        CallPermissionDescription(stringResource(R.string.CollieCalls__direct_default_description))
      }
      item {
        Rows.RadioListRow(
          text = stringResource(R.string.CollieCalls__groups),
          labels = arrayOf(stringResource(R.string.CollieCalls__allow_group), stringResource(R.string.CollieCalls__block_group)),
          values = arrayOf("allow", "block"),
          selectedValue = if (state.allowGroups) "allow" else "block",
          enabled = editable && !state.allCallingDisabled,
          onSelected = { onEvent(CallsSettingsEvent.GroupsChanged(it == "allow")) },
          modifier = Modifier.testTag(CallsSettingsTestTags.GROUPS)
        )
        CallPermissionDescription(stringResource(R.string.CollieCalls__group_default_description))
      }
      if (state.saving) {
        item { CallPermissionDescription(stringResource(R.string.CollieCalls__saving)) }
      }
    }
  }

  CallPermissionSaveError(state.saveFailed) { onEvent(CallsSettingsEvent.ErrorDismissed) }
}

@Composable
internal fun CallPermissionDescription(text: String) {
  Text(
    text = text,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    style = MaterialTheme.typography.bodyMedium,
    modifier = Modifier.horizontalGutters().padding(top = 8.dp, bottom = 16.dp)
  )
}

@Composable
internal fun CallPermissionSaveError(visible: Boolean, onDismiss: () -> Unit) {
  if (visible) {
    Dialogs.SimpleAlertDialog(
      title = stringResource(R.string.CollieCalls__save_failed_title),
      body = stringResource(R.string.CollieCalls__save_failed),
      confirm = stringResource(android.R.string.ok),
      onConfirm = onDismiss,
      onDismiss = onDismiss
    )
  }
}

@PreviewWrapper(SignalPreviewWrapper::class)
@DayNightPreviews
@Composable
private fun CallsSettingsScreenPreview() {
  CallsSettingsScreen(state = CallsSettingsState(loading = false, available = true, onDemandAvailable = true), onEvent = {})
}
