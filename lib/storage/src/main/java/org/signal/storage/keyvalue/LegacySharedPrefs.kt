/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.storage.keyvalue

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri

/**
 * Read-only access to Molly's injected preferences for migration to [KeyValueStore].
 */
object LegacySharedPrefs {

  fun contains(context: Context, key: String): Boolean {
    return prefs(context).contains(key)
  }

  fun getBoolean(context: Context, key: String, defaultValue: Boolean): Boolean {
    return prefs(context).getBoolean(key, defaultValue)
  }

  fun getInteger(context: Context, key: String, defaultValue: Int): Int {
    return prefs(context).getInt(key, defaultValue)
  }

  fun getLong(context: Context, key: String, defaultValue: Long): Long {
    return prefs(context).getLong(key, defaultValue)
  }

  fun getString(context: Context, key: String, defaultValue: String): String {
    return prefs(context).getString(key, defaultValue) ?: defaultValue
  }

  fun getStringOrNull(context: Context, key: String): String? {
    return prefs(context).getString(key, null)
  }

  /** Some values were stored as strings, but some installs ended up with ints. */
  fun getIntegerFromString(context: Context, key: String, defaultValue: Int): Int {
    return try {
      getString(context, key, defaultValue.toString()).toInt()
    } catch (e: ClassCastException) {
      getInteger(context, key, defaultValue)
    } catch (e: NumberFormatException) {
      defaultValue
    }
  }

  /** Mirrors the URI fixup the old ringtone getters did. */
  fun getRingtone(context: Context, key: String, defaultValue: Uri): String {
    val result = getString(context, key, defaultValue.toString())

    return if (result.startsWith("file:")) {
      defaultValue.toString()
    } else {
      result
    }
  }

  fun getStringSet(context: Context, key: String, defaultValues: Set<String>?): Set<String>? {
    return if (prefs(context).contains(key)) {
      prefs(context).getStringSet(key, emptySet()) ?: emptySet()
    } else {
      defaultValues
    }
  }

  @Suppress("UNUSED_PARAMETER")
  private fun prefs(context: Context): SharedPreferences = PlainTextKeyValueStore.legacyPreferences
}
