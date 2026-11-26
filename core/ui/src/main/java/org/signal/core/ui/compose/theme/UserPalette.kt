/*
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.core.ui.compose.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color

/**
 * A built-in fixed-seed palette. The schemes are generated together with the View theme overlays by
 * tooling/user-theming/generate.py, so both toolkits render the same roles at the same brightness.
 */
class UserPalette internal constructor(
  /** Stable ID stored in preferences and backups. */
  val id: String,
  val seed: Color,
  /** Material dynamic scheme the roles were generated with, such as "vibrant". */
  val scheme: String,
  val light: ColorScheme,
  val dark: ColorScheme,
  /** Primary, secondary container, tertiary and surface, for a palette preview. */
  val lightSwatch: List<Color>,
  val darkSwatch: List<Color>
) {
  fun colorScheme(isDarkMode: Boolean): ColorScheme = if (isDarkMode) dark else light

  fun swatch(isDarkMode: Boolean): List<Color> = if (isDarkMode) darkSwatch else lightSwatch
}

object UserPalettes {
  /** The built-in palettes, in display order. */
  val all: List<UserPalette>
    get() = GeneratedUserPalettes.ALL

  /** The palette with [id], or null for Collie, System and unknown IDs. */
  @JvmStatic
  fun byId(id: String?): UserPalette? = all.firstOrNull { it.id == id }
}
