/*
 * Copyright 2026 Molly Project
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.backup

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thoughtcrime.securesms.backup.proto.SharedPreference
import org.thoughtcrime.securesms.util.SecurePreferenceManager
import org.thoughtcrime.securesms.util.TextSecurePreferences

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class MollyBackupPreferencesTest {

  private lateinit var context: Application
  private lateinit var preferences: SharedPreferences

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    preferences = context.getSharedPreferences("backup-preferences-test", Context.MODE_PRIVATE)
    preferences.edit().clear().commit()
    mockkStatic(SecurePreferenceManager::class)
    every { SecurePreferenceManager.getSecurePreferences(context) } returns preferences
  }

  @After
  fun tearDown() {
    unmockkStatic(SecurePreferenceManager::class)
  }

  @Test
  fun `Molly preferences round trip through the wire format without truncating long timeouts`() {
    val timeout = Int.MAX_VALUE.toLong() + 120L
    preferences.edit()
      .putLong(TextSecurePreferences.PASSPHRASE_LOCK_TIMEOUT, timeout)
      .putBoolean(TextSecurePreferences.DYNAMIC_COLORS_ENABLED, true)
      .putStringSet(TextSecurePreferences.PASSPHRASE_LOCK_TRIGGER, setOf("screen_off", "background"))
      .commit()

    val records = TextSecurePreferences.getPreferencesToSaveToBackup(context)
    assertEquals(records.size.toLong(), TextSecurePreferences.getPreferencesToSaveToBackupCount(context))
    val timeoutRecord = records.single { it.key == TextSecurePreferences.PASSPHRASE_LOCK_TIMEOUT }
    assertEquals(timeout, timeoutRecord.longValue!!)
    assertNull(timeoutRecord.integerValue)

    preferences.edit().clear().commit()
    records.forEach { record ->
      FullBackupImporter.processPreference(context, SharedPreference.ADAPTER.decode(record.encode()))
    }

    assertEquals(timeout, TextSecurePreferences.getPassphraseLockTimeout(context))
    assertTrue(TextSecurePreferences.isDynamicColorsEnabled(context))
    assertEquals(setOf("screen_off", "background"), preferences.getStringSet(TextSecurePreferences.PASSPHRASE_LOCK_TRIGGER, emptySet()))
  }

  @Test
  fun `historical integer timeout records restore with the long type expected by Molly`() {
    val record = SharedPreference.Builder()
      .file_(SecurePreferenceManager.getSecurePreferencesName())
      .key(TextSecurePreferences.PASSPHRASE_LOCK_TIMEOUT)
      .integerValue(-1)
      .build()

    FullBackupImporter.processPreference(context, SharedPreference.ADAPTER.decode(record.encode()))

    assertEquals(0xffff_ffffL, TextSecurePreferences.getPassphraseLockTimeout(context))
  }

  @Test
  fun `stale shared preferences for migrated Signal settings are not exported`() {
    preferences.edit()
      .putBoolean("pref_read_receipts", false)
      .putBoolean("pref_screen_security", false)
      .putString("pref_theme", "light")
      .putStringSet("pref_media_download_mobile", setOf("video"))
      .putBoolean(TextSecurePreferences.LOG_ENABLED, false)
      .commit()

    val records = TextSecurePreferences.getPreferencesToSaveToBackup(context)

    assertEquals(listOf(TextSecurePreferences.LOG_ENABLED), records.map { it.key })
    assertFalse(records.single().booleanValue!!)
  }
}
