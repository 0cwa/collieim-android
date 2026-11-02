/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.appsettings.updates

import android.text.format.DateUtils
import androidx.annotation.VisibleForTesting
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewWrapper
import androidx.lifecycle.compose.LifecycleResumeEffect
import org.signal.appsettings.R
import org.signal.core.ui.compose.DayNightPreviews
import org.signal.core.ui.compose.Rows
import org.signal.core.ui.compose.Scaffolds
import org.signal.core.ui.compose.SignalIcons
import org.signal.core.ui.compose.SignalPreviewWrapper
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

@VisibleForTesting
object AppUpdatesSettingsTestTags {
  const val SCROLLER = "scroller"
  const val ROW_AUTO_UPDATE = "row-auto-update"
  const val ROW_CHECK_FOR_UPDATES = "row-check-for-updates"
  const val ROW_INCLUDE_BETA = "row-include-beta"
}

/** Settings for Molly updates, or app-store guidance for externally managed builds. */
@Composable
fun AppUpdatesSettingsScreen(
  state: AppUpdatesSettingsState,
  onEvent: (AppUpdatesSettingsEvent) -> Unit,
  modifier: Modifier = Modifier
) {
  LifecycleResumeEffect(Unit) {
    onEvent(AppUpdatesSettingsEvent.ScreenResumed)
    onPauseOrDispose {}
  }

  Scaffolds.Settings(
    title = stringResource(R.string.preferences_app_updates__title),
    onNavigationClick = { onEvent(AppUpdatesSettingsEvent.NavigateBackClicked) },
    navigationIcon = SignalIcons.ArrowStart.imageVector,
    modifier = modifier
  ) { paddingValues ->
    LazyColumn(
      modifier = Modifier
        .padding(paddingValues)
        .testTag(AppUpdatesSettingsTestTags.SCROLLER)
    ) {
      if (!state.isAutoUpdateSupported) {
        item {
          Rows.TextRow(
            text = {
              Text(
                text = stringResource(R.string.HelpSettingsFragment_for_updates_please_check_your_app_store),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
              )
            }
          )
        }
      }

      item {
        Rows.TextRow(
          text = stringResource(R.string.HelpSettingsFragment__version),
          label = state.versionName
        )
      }

      if (state.isAutoUpdateSupported) {
        item {
          Rows.ToggleRow(
            checked = state.autoUpdateEnabled,
            text = stringResource(R.string.preferences__autoupdate_molly),
            label = stringResource(R.string.preferences__periodically_check_for_new_releases_and_ask_to_install_them),
            onCheckChanged = { onEvent(AppUpdatesSettingsEvent.AutoUpdateToggled(it)) },
            modifier = Modifier.testTag(AppUpdatesSettingsTestTags.ROW_AUTO_UPDATE)
          )
        }

        item {
          Rows.ToggleRow(
            checked = state.includeBetaEnabled,
            text = stringResource(R.string.preferences__include_beta_updates),
            label = stringResource(R.string.preferences__beta_versions_are_intended_for_testing_purposes_and_may_contain_bugs),
            onCheckChanged = { onEvent(AppUpdatesSettingsEvent.IncludeBetaToggled(it)) },
            modifier = Modifier.testTag(AppUpdatesSettingsTestTags.ROW_INCLUDE_BETA)
          )
        }

        item {
          Rows.TextRow(
            text = stringResource(R.string.EnableAppUpdatesMegaphone_check_for_updates),
            label = stringResource(R.string.AppUpdatesSettingsFragment__last_checked_s, rememberLastSuccessfulUpdateString(state.lastCheckedTime)),
            enabled = state.autoUpdateEnabled,
            onClick = { onEvent(AppUpdatesSettingsEvent.CheckForUpdatesClicked) },
            modifier = Modifier.testTag(AppUpdatesSettingsTestTags.ROW_CHECK_FOR_UPDATES)
          )
        }
      }
    }
  }
}

@Composable
private fun rememberLastSuccessfulUpdateString(lastUpdateTime: Duration): String {
  val context = LocalContext.current
  val never = stringResource(R.string.preferences__never)
  return remember(context, lastUpdateTime, never) {
    if (lastUpdateTime > Duration.ZERO) {
      DateUtils.getRelativeDateTimeString(context, lastUpdateTime.inWholeMilliseconds, DateUtils.MINUTE_IN_MILLIS, DateUtils.WEEK_IN_MILLIS, 0).toString()
    } else {
      never
    }
  }
}

@PreviewWrapper(SignalPreviewWrapper::class)
@DayNightPreviews
@Composable
private fun AppUpdatesSettingsScreenPreview() {
  AppUpdatesSettingsScreen(
    state = AppUpdatesSettingsState(
      lastCheckedTime = System.currentTimeMillis().milliseconds,
      autoUpdateEnabled = true,
      isAutoUpdateSupported = true,
      includeBetaEnabled = true,
      versionName = "8.33.2"
    ),
    onEvent = {}
  )
}

@PreviewWrapper(SignalPreviewWrapper::class)
@DayNightPreviews
@Composable
private fun AppUpdatesSettingsScreenExternallyManagedPreview() {
  AppUpdatesSettingsScreen(
    state = AppUpdatesSettingsState(versionName = "8.33.2"),
    onEvent = {}
  )
}
