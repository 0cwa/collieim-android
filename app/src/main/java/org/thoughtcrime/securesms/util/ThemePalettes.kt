/*
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.util

import android.content.Context
import androidx.core.content.edit
import org.signal.storage.keyvalue.PlainTextKeyValueStore
import org.thoughtcrime.securesms.flags.CollieFlags

/**
 * Resolves the color palette choice. The device-local choice is authoritative in Beta. An unknown ID shows
 * Collie while the stored value is kept until the next explicit choice.
 */
object ThemePalettes {

  const val COLLIE = "collie"
  const val SYSTEM = "system"

  private const val PREFERENCES = "collie_appearance"
  private const val THEME_PALETTE = "pref_theme_palette"

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

  internal fun setSelection(context: Context, id: String) {
    require(isSelectable(id)) { "Unknown palette: $id" }
    setStoredId(context, id)
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
