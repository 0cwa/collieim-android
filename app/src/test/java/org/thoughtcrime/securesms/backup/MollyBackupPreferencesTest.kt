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
import org.thoughtcrime.securesms.BuildConfig
import org.thoughtcrime.securesms.backup.proto.KeyValue
import org.thoughtcrime.securesms.backup.proto.SharedPreference
import org.thoughtcrime.securesms.keyvalue.KeyValueDataSet
import org.thoughtcrime.securesms.keyvalue.KeyValuePersistentStorage
import org.thoughtcrime.securesms.keyvalue.KeyValueStore
import org.thoughtcrime.securesms.keyvalue.MockKeyValuePersistentStorage
import org.thoughtcrime.securesms.keyvalue.PlainTextKeyValueStore
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.keyvalue.SettingsValues
import org.thoughtcrime.securesms.testutil.DirectExecutor
import org.thoughtcrime.securesms.util.Environment
import org.thoughtcrime.securesms.util.SecurePreferenceManager
import org.thoughtcrime.securesms.util.TextSecurePreferences

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class MollyBackupPreferencesTest {

  private lateinit var context: Application
  private lateinit var preferences: SharedPreferences
  private lateinit var keyValueStorage: KeyValuePersistentStorage
  private var originalInstrumentation = false

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    preferences = context.getSharedPreferences("backup-preferences-test", Context.MODE_PRIVATE)
    preferences.edit().clear().commit()
    mockkStatic(SecurePreferenceManager::class)
    every { SecurePreferenceManager.getSecurePreferences(context) } returns preferences
    PlainTextKeyValueStore.init(context)
    originalInstrumentation = Environment.IS_INSTRUMENTATION
    Environment.IS_INSTRUMENTATION = false
    keyValueStorage = MockKeyValuePersistentStorage.withDataSet(KeyValueDataSet())
    SignalStore.testInject(
      SignalStore(
        context,
        KeyValueStore(keyValueStorage, DirectExecutor())
      )
    )
  }

  @After
  fun tearDown() {
    SignalStore.testInject(null)
    Environment.IS_INSTRUMENTATION = originalInstrumentation
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
      FullBackupImporter.processPreference(context, SharedPreference.ADAPTER.decode(record.encode()), keyValueStorage)
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

    FullBackupImporter.processPreference(context, SharedPreference.ADAPTER.decode(record.encode()), keyValueStorage)

    assertEquals(0xffff_ffffL, TextSecurePreferences.getPassphraseLockTimeout(context))
  }

  @Test
  fun `legacy preference-only settings restore into SignalStore`() {
    val languageRecord = SharedPreference.Builder()
      .file_(BuildConfig.SIGNAL_PACKAGE_NAME + "_preferences")
      .key("pref_language")
      .value_("fr")
      .build()
    val privacyRecord = SharedPreference.Builder()
      .file_(BuildConfig.SIGNAL_PACKAGE_NAME + "_preferences")
      .key("pref_read_receipts")
      .booleanValue(false)
      .build()
    val mediaRecord = SharedPreference.Builder()
      .file_(BuildConfig.SIGNAL_PACKAGE_NAME + "_preferences")
      .key("pref_media_download_mobile")
      .isStringSetValue(true)
      .stringSetValue(listOf("video"))
      .build()

    FullBackupImporter.processPreference(context, languageRecord, keyValueStorage)
    FullBackupImporter.processPreference(context, privacyRecord, keyValueStorage)
    FullBackupImporter.processPreference(context, mediaRecord, keyValueStorage)
    SignalStore.onPostBackupRestore()
    SignalStore.settings.onFirstEverAppLaunch()

    assertEquals("fr", SignalStore.settings.language)
    assertFalse(SignalStore.settings.isReadReceiptsEnabled)
    assertEquals(setOf("video"), SignalStore.settings.mobileMediaDownloadAllowed)
  }

  @Test
  fun `later KeyValue setting wins over its legacy preference frame`() {
    val legacyPreference = SharedPreference.Builder()
      .file_(BuildConfig.SIGNAL_PACKAGE_NAME + "_preferences")
      .key("pref_notification_privacy")
      .value_("all")
      .build()

    FullBackupImporter.processPreference(context, legacyPreference, keyValueStorage)

    // The legacy exporter writes KeyValue frames after SharedPreference frames.
    val keyValue = KeyValue.Builder()
      .key(SettingsValues.MESSAGE_NOTIFICATION_PRIVACY)
      .stringValue("none")
      .build()
    FullBackupImporter.processKeyValue(keyValue, keyValueStorage)
    SignalStore.onPostBackupRestore()

    assertEquals("none", SignalStore.settings.messageNotificationsPrivacy.toString())
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
