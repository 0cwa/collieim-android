/*
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.core.ui.compose.theme

import androidx.annotation.StringRes
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color

/**
 * A built-in fixed-seed palette. The schemes are generated together with the View theme overlays by
 * tooling/user-theming/generate.py, so both toolkits render the same roles at the same brightness.
 */
class UserPalette internal constructor(
  /** Stable ID stored in preferences and backups. */
  val id: String,
  /** Translatable display name, named after the palette's rendered primary color. */
  @StringRes val nameRes: Int,
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
  /** Collie's own palette, stored as the absence of a palette ID. */
  const val COLLIE_ID = "collie"

  /** Android's system (dynamic) palette, stored as the Dynamic Colors flag. */
  const val SYSTEM_ID = "system"

  /** The built-in palettes, in display order. */
  val all: List<UserPalette>
    get() = GeneratedUserPalettes.ALL

  /** The palette with [id], or null for Collie, System and unknown IDs. */
  @JvmStatic
  fun byId(id: String?): UserPalette? = all.firstOrNull { it.id == id }
}

/**
 * Collie: Pure black replaces these base roles with black in an effectively dark theme. Elevated containers, accents
 * and foregrounds keep their palette values, so cards, sheets and dialogs still stand out from the background.
 */
fun ColorScheme.withPureBlack(): ColorScheme {
  return copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceDim = Color.Black,
    surfaceContainerLowest = Color.Black
  )
}
