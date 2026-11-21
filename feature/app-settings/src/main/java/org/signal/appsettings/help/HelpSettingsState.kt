/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.appsettings.help

/**
 * [HelpSettingsScreen] state.
 *
 * This is logged, so be sure `toString()` contains nothing sensitive.
 */
data class HelpSettingsState(
  val logEnabled: Boolean = false,
  /** The installed app version, shown on the version row and copied when it is long-pressed. */
  val versionName: String = "",
  /** COLLIE: Whether this build has in-app updates, so the app-update settings can be opened from here. */
  val showAppUpdates: Boolean = false,
  /** The snackbar to show, or [Snackbar.None]. */
  val snackbar: Snackbar = Snackbar.None
) {

  /** Whichever snackbar the screen is showing, if any. */
  sealed interface Snackbar {
    data object None : Snackbar

    /** The version was copied to the clipboard. */
    data object VersionCopied : Snackbar
  }
}
