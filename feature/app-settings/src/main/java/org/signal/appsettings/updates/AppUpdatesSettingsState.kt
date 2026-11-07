/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.appsettings.updates

import kotlin.time.Duration

/**
 * [AppUpdatesSettingsScreen] state.
 *
 * This is logged, so be sure `toString()` contains nothing sensitive.
 */
data class AppUpdatesSettingsState(
  /** When we last successfully checked for an update, or [Duration.ZERO] if we never have. */
  val lastCheckedTime: Duration = Duration.ZERO,
  /** Whether Molly periodically checks for updates and asks before installing. */
  val autoUpdateEnabled: Boolean = false,
  /** Whether this build manages its own updates instead of relying on an app store. */
  val isAutoUpdateSupported: Boolean = false,
  val includeBetaEnabled: Boolean = false,
  val versionName: String = ""
)
