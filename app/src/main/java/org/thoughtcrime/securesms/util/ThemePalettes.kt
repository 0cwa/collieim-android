/*
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.util

import org.signal.storage.keyvalue.PlainTextKeyValueStore

/**
 * Resolves the color palette choice. The System flag stays authoritative; otherwise a stored built-in palette ID
 * applies, and missing or unknown IDs show Collie while the stored value is kept until the next explicit choice.
 */
object ThemePalettes {

  const val COLLIE = "collie"
  const val SYSTEM = "system"

  /** Whether [id] names Collie, System or a built-in palette. */
  @JvmStatic
  fun isSelectable(id: String): Boolean = id == COLLIE || id == SYSTEM || id in GeneratedPaletteOverlays.IDS

  /** The built-in palette to apply, or null for Collie and System. Readable before unlock. */
  @JvmStatic
  fun activePaletteId(): String? {
    if (PlainTextKeyValueStore.isDynamicColorsEnabled) {
      return null
    }
    return PlainTextKeyValueStore.themePalette?.takeIf { it in GeneratedPaletteOverlays.IDS }
  }

  /** The current choice: [SYSTEM], [COLLIE] or a built-in palette ID. */
  @JvmStatic
  fun selectedId(): String {
    return if (PlainTextKeyValueStore.isDynamicColorsEnabled) SYSTEM else activePaletteId() ?: COLLIE
  }
}
