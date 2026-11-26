/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.appsettings.appearance

import org.signal.core.ui.compose.theme.UserPalettes

/**
 * Everything [AppearanceSettingsScreen] needs to render.
 */
data class AppearanceSettingsState(
  val language: String = "zz",
  val theme: Theme = Theme.SYSTEM,
  /** Collie: [UserPalettes.COLLIE_ID], [UserPalettes.SYSTEM_ID] or a built-in palette ID. */
  val palette: String = UserPalettes.COLLIE_ID,
  /** Collie: the color choices on this device, in display order. System is listed only where it is supported. */
  val availablePalettes: List<String> = listOf(UserPalettes.COLLIE_ID),
  /** Collie: black base surfaces whenever the app is dark. */
  val pureBlack: Boolean = false,
  val messageFontSize: Int = 16,
  val isCompactNavigationBar: Boolean = false,
  val isSystemThemeAvailable: Boolean = true,
  val isAppIconSupported: Boolean = true,
  val dialog: Dialog = Dialog.None
) {

  /** MOLLY: whether the System palette (Dynamic Colors) is the color choice. */
  val dynamicColors: Boolean
    get() = palette == UserPalettes.SYSTEM_ID

  /** The themes the user can choose from. Following the system theme needs OS support. */
  val availableThemes: List<Theme>
    get() = if (isSystemThemeAvailable) Theme.entries else Theme.entries - Theme.SYSTEM

  enum class Theme {
    SYSTEM,
    LIGHT,
    DARK
  }

  /** Whichever dialog the screen is showing, if any. */
  sealed interface Dialog {
    data object None : Dialog

    /** Warns that switching to [language] restarts the app. */
    data class ConfirmLanguageChange(val language: String) : Dialog

    /** Lets the user choose between a full-sized and a compact navigation bar. [isCompact] is the choice so far. */
    data class NavigationBarSize(val isCompact: Boolean) : Dialog
  }
}
