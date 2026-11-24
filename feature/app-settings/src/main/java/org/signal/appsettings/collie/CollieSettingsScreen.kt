/*
 * Copyright 2026 Collie contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.appsettings.collie

import androidx.annotation.VisibleForTesting
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
import org.signal.appsettings.labs.LabsSettingsEvent
import org.signal.appsettings.labs.LabsSettingsState
import org.signal.core.ui.compose.DayNightPreviews
import org.signal.core.ui.compose.Rows
import org.signal.core.ui.compose.Scaffolds
import org.signal.core.ui.compose.SignalIcons
import org.signal.core.ui.compose.SignalPreviewWrapper
import org.signal.core.ui.compose.Texts
import org.signal.core.ui.compose.horizontalGutters

@VisibleForTesting
object CollieSettingsTestTags {
  const val SCROLLER = "collie-settings-scroller"
  const val ROW_APP_UPDATES = "collie-app-updates"
  const val ROW_RECOVERY_PIN_GUARD = "collie-recovery-pin-guard"
  const val ROW_STORY_ARCHIVE = "collie-story-archive"
  const val ROW_BETTER_SEARCH = "collie-better-search"
  const val ROW_STARRED_MESSAGES = "collie-starred-messages"
  const val ROW_GROUP_PRIVACY = "collie-group-privacy"
}

/** Collie-specific settings and selected experimental features offered to all users. */
@Composable
fun CollieSettingsScreen(
  state: LabsSettingsState,
  onEvent: (LabsSettingsEvent) -> Unit,
  modifier: Modifier = Modifier,
  protectBackupKey: Boolean = false,
  onProtectBackupKeyChanged: (Boolean) -> Unit = {},
  recoveryPinGuardEnabled: Boolean = false,
  onRecoveryPinGuardChanged: (Boolean) -> Unit = {},
  showAppUpdates: Boolean = false,
  onAppUpdatesClick: () -> Unit = {},
  onGroupPrivacyClicked: () -> Unit = {}
) {
  Scaffolds.Settings(
    title = stringResource(R.string.CollieSettings__title),
    navigationContentDescription = stringResource(R.string.AppSettingsScreen__go_back),
    navigationIcon = SignalIcons.ArrowStart.imageVector,
    onNavigationClick = { onEvent(LabsSettingsEvent.NavigateBackClicked) },
    modifier = modifier,
    expandableTitle = true
  ) { contentPadding ->
    AppSettingsLazyColumn(
      contentPadding = contentPadding,
      modifier = Modifier.testTag(CollieSettingsTestTags.SCROLLER)
    ) {
      if (showAppUpdates) {
        item {
          Rows.TextRow(
            text = stringResource(R.string.preferences_app_updates__title),
            icon = SignalIcons.Calendar.painter,
            onClick = onAppUpdatesClick,
            modifier = Modifier.testTag(CollieSettingsTestTags.ROW_APP_UPDATES)
          )
        }
      }

      item {
        Texts.SectionHeader(text = stringResource(R.string.CollieSettings__privacy))
      }

      item {
        Rows.TextRow(
          text = stringResource(R.string.GroupPrivacySettings__title),
          label = stringResource(R.string.GroupPrivacySettings__summary),
          onClick = onGroupPrivacyClicked,
          modifier = Modifier.testTag(CollieSettingsTestTags.ROW_GROUP_PRIVACY)
        )
      }

      item {
        Texts.SectionHeader(text = stringResource(R.string.CollieSettings__hardening))
      }

      item {
        Rows.ToggleRow(
          checked = recoveryPinGuardEnabled,
          text = stringResource(R.string.CollieSettings__recovery_pin_guard),
          label = stringResource(R.string.CollieSettings__recovery_pin_guard_description),
          onCheckChanged = onRecoveryPinGuardChanged,
          modifier = Modifier.testTag(CollieSettingsTestTags.ROW_RECOVERY_PIN_GUARD)
        )
      }

      item {
        Rows.ToggleRow(
          checked = protectBackupKey,
          text = stringResource(R.string.CollieSettings__protect_backup_key),
          label = stringResource(R.string.CollieSettings__protect_backup_key_description),
          onCheckChanged = onProtectBackupKeyChanged
        )
      }

      item {
        Texts.SectionHeader(text = stringResource(R.string.CollieSettings__labs))
        Text(
          text = stringResource(R.string.CollieSettings__labs_description),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.horizontalGutters().padding(bottom = 16.dp)
        )
      }

      item {
        Rows.ToggleRow(
          checked = state.storyArchive,
          text = stringResource(R.string.CollieSettings__story_archive),
          label = stringResource(R.string.CollieSettings__story_archive_description),
          onCheckChanged = { onEvent(LabsSettingsEvent.StoryArchiveToggled(it)) },
          modifier = Modifier.testTag(CollieSettingsTestTags.ROW_STORY_ARCHIVE)
        )
      }

      item {
        Rows.ToggleRow(
          checked = state.betterSearch,
          text = stringResource(R.string.CollieSettings__better_search),
          label = stringResource(R.string.CollieSettings__better_search_description),
          onCheckChanged = { onEvent(LabsSettingsEvent.BetterSearchToggled(it)) },
          modifier = Modifier.testTag(CollieSettingsTestTags.ROW_BETTER_SEARCH)
        )
      }

      item {
        Rows.ToggleRow(
          checked = state.starredMessages,
          text = stringResource(R.string.CollieSettings__starred_messages),
          label = stringResource(R.string.CollieSettings__starred_messages_description),
          onCheckChanged = { onEvent(LabsSettingsEvent.StarredMessagesToggled(it)) },
          modifier = Modifier.testTag(CollieSettingsTestTags.ROW_STARRED_MESSAGES)
        )
      }
    }
  }
}

@PreviewWrapper(SignalPreviewWrapper::class)
@DayNightPreviews
@Composable
private fun CollieSettingsScreenPreview() {
  CollieSettingsScreen(state = LabsSettingsState(), onEvent = {}, showAppUpdates = true)
}
