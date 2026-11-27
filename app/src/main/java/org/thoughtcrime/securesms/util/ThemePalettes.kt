/*
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.util

import android.content.Context
import androidx.core.content.edit
import org.signal.core.ui.compose.theme.UserPalettes
import org.signal.storage.keyvalue.PlainTextKeyValueStore
import org.thoughtcrime.securesms.flags.CollieFlags

/**
 * Resolves the color palette choice. The device-local choice is authoritative in Beta. An unknown ID shows
 * Collie while the stored value is kept until the next explicit choice.
 */
object ThemePalettes {

  const val COLLIE = UserPalettes.COLLIE_ID
  const val SYSTEM = UserPalettes.SYSTEM_ID

  private const val PREFERENCES = "collie_appearance"
  private const val THEME_PALETTE = "pref_theme_palette"
  private const val PURE_BLACK = "pref_pure_black"

  // The V1 importer targets ordinary app preferences. The device-protected file keeps appearance local.
  private fun preferences(context: Context) =
    context.createDeviceProtectedStorageContext().getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

  internal fun storedId(context: Context): String? {
    return try {
      preferences(context).getString(THEME_PALETTE, null)
    } catch (e: ClassCastException) {
      null
    }
  }

  internal fun setStoredId(context: Context, id: String?) {
    preferences(context).edit(commit = true) { putString(THEME_PALETTE, id) }
  }

  @JvmStatic
  fun isPureBlackEnabled(context: Context): Boolean {
    if (!CollieFlags.userTheming) return false
    return try {
      preferences(context).getBoolean(PURE_BLACK, false)
    } catch (e: ClassCastException) {
      false
    }
  }

  internal fun setPureBlack(context: Context, enabled: Boolean) {
    if (!CollieFlags.userTheming) return
    preferences(context).edit(commit = true) { putBoolean(PURE_BLACK, enabled) }
  }

  internal fun setSelection(context: Context, id: String) {
    require(isSelectable(id)) { "Unknown palette: $id" }
    setStoredId(context, id)
  }

  /**
   * The choices to offer, in display order: Collie, System where Dynamic Colors is supported, then the built-in
   * palettes.
   */
  @JvmStatic
  fun selectableIds(systemAvailable: Boolean): List<String> {
    return listOf(COLLIE) + (if (systemAvailable) listOf(SYSTEM) else emptyList()) + GeneratedPaletteOverlays.IDS
  }

  /** Whether [id] names Collie, System or a built-in palette. */
  @JvmStatic
  fun isSelectable(id: String): Boolean = id == COLLIE || id == SYSTEM || id in GeneratedPaletteOverlays.IDS

  /** The built-in palette for a resolved choice, or null for System and Collie. */
  @JvmStatic
  fun builtInPaletteId(selection: String): String? = selection.takeIf { it in GeneratedPaletteOverlays.IDS }

  /** The existing Dynamic Colors flag is a fallback for users who have no device-local choice. */
  @JvmStatic
  fun selectedId(context: Context): String {
    if (CollieFlags.userTheming) {
      storedId(context)?.let { return if (isSelectable(it)) it else COLLIE }
    }
    return if (PlainTextKeyValueStore.isDynamicColorsEnabled) SYSTEM else COLLIE
  }
}
