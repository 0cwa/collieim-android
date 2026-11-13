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
import io.mockk.justRun
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
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
import org.signal.core.util.logging.Log
import org.signal.storage.keyvalue.KeyValueDataSet
import org.signal.storage.keyvalue.KeyValuePersistentStorage
import org.signal.storage.keyvalue.KeyValueStore
import org.signal.storage.keyvalue.MockKeyValuePersistentStorage
import org.signal.storage.keyvalue.PlainTextKeyValueStore
import org.thoughtcrime.securesms.BuildConfig
import org.thoughtcrime.securesms.ScreenLockController
import org.thoughtcrime.securesms.backup.proto.KeyValue
import org.thoughtcrime.securesms.backup.proto.SharedPreference
import org.thoughtcrime.securesms.keyvalue.SettingsValues
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.notifications.NotificationChannels
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
  private var originalAutoLock = false
  private var originalLockScreenAtStart = false

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    preferences = context.getSharedPreferences("backup-preferences-test", Context.MODE_PRIVATE)
    preferences.edit().clear().commit()
    mockkStatic(SecurePreferenceManager::class)
    every { SecurePreferenceManager.getSecurePreferences(context) } returns preferences
    PlainTextKeyValueStore.init(preferences, BuildConfig.VERSION_CODE)
    originalInstrumentation = Environment.IS_INSTRUMENTATION
    originalAutoLock = ScreenLockController.autoLock
    originalLockScreenAtStart = ScreenLockController.lockScreenAtStart
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
    ScreenLockController.enableAutoLock(originalAutoLock)
    ScreenLockController.lockScreenAtStart = originalLockScreenAtStart
    unmockkStatic(SecurePreferenceManager::class)
  }

  @Test
  fun `literal Molly and historical Collie filenames restore portable security preferences`() {
    // Molly and Molly-FOSS use the same package. These names must not be derived from this build.
    val filenames = listOf(
      "im.molly.app",
      "im.molly.app_preferences",
      "im.molly.app.staging",
      "im.molly.app.staging_preferences",
      "im.collie.app",
      "im.collie.app_preferences",
      "im.collie.app.staging",
      "im.collie.app.staging_preferences"
    )

    for (filename in filenames) {
      preferences.edit().clear().commit()
      val records = listOf(
        SharedPreference.Builder().file_(filename).key(TextSecurePreferences.BIOMETRIC_SCREEN_LOCK).booleanValue(true).build(),
        SharedPreference.Builder().file_(filename).key("pref_block_unknown").booleanValue(true).build(),
        SharedPreference.Builder().file_(filename).key(TextSecurePreferences.LOG_ENABLED).booleanValue(false).build()
      )

      records.forEach { FullBackupImporter.processPreference(context, SharedPreference.ADAPTER.decode(it.encode()), keyValueStorage) }

      assertTrue(filename, TextSecurePreferences.isBiometricScreenLockEnabled(context))
      assertTrue(filename, TextSecurePreferences.isBlockUnknownEnabled(context))
      assertFalse(filename, TextSecurePreferences.isLogEnabled(context))
    }
  }

  @Test
  fun `literal Molly file preserves timeout triggers and long and historical integer timeouts`() {
    val trigger = SharedPreference.Builder()
      .file_("im.molly.app")
      .key(TextSecurePreferences.PASSPHRASE_LOCK_TRIGGER)
      .isStringSetValue(true)
      .stringSetValue(listOf("timeout", "screen_off"))
      .build()
    val timeout = SharedPreference.Builder()
      .file_("im.molly.app")
      .key(TextSecurePreferences.PASSPHRASE_LOCK_TIMEOUT)
      .longValue(Int.MAX_VALUE.toLong() + 120L)
      .build()
    FullBackupImporter.processPreference(context, trigger, keyValueStorage)
    FullBackupImporter.processPreference(context, timeout, keyValueStorage)

    assertEquals(setOf("timeout", "screen_off"), TextSecurePreferences.getPassphraseLockTrigger(context).triggers)
    assertTrue(TextSecurePreferences.getPassphraseLockTrigger(context).isTimeoutEnabled)
    assertEquals(Int.MAX_VALUE.toLong() + 120L, TextSecurePreferences.getPassphraseLockTimeout(context))

    val oldTimeout = SharedPreference.Builder()
      .file_("im.molly.app")
      .key(TextSecurePreferences.PASSPHRASE_LOCK_TIMEOUT)
      .integerValue(-1)
      .build()
    FullBackupImporter.processPreference(context, SharedPreference.ADAPTER.decode(oldTimeout.encode()), keyValueStorage)
    assertEquals(0xffff_ffffL, TextSecurePreferences.getPassphraseLockTimeout(context))
  }

  @Test
  fun `Molly filename cannot change local passphrase configuration or inject unrelated keys`() {
    TextSecurePreferences.setPassphraseLockEnabled(context, true)
    val before = preferences.all.toMap()
    val records = listOf(
      SharedPreference.Builder().file_("im.molly.app").key(TextSecurePreferences.PASSPHRASE_LOCK).booleanValue(false).build(),
      SharedPreference.Builder().file_("im.molly.app").key("pref_identity_private_v3").value_("foreign-key").build(),
      SharedPreference.Builder().file_("im.molly.app").key("pref_proxy_type").value_("none").build(),
      SharedPreference.Builder().file_("im.molly.app").key("foreign-setting").booleanValue(true).build()
    )

    records.forEach { FullBackupImporter.processPreference(context, it, keyValueStorage) }

    assertEquals(before, preferences.all)
    assertTrue(TextSecurePreferences.isPassphraseLockEnabled(context))

    TextSecurePreferences.setPassphraseLockEnabled(context, false)
    FullBackupImporter.processPreference(
      context,
      SharedPreference.Builder().file_("im.molly.app").key(TextSecurePreferences.PASSPHRASE_LOCK).booleanValue(true).build(),
      keyValueStorage
    )
    assertFalse(TextSecurePreferences.isPassphraseLockEnabled(context))
  }

  @Test
  fun `malformed Molly setting values cannot replace valid security preferences`() {
    preferences.edit()
      .putBoolean(TextSecurePreferences.BIOMETRIC_SCREEN_LOCK, true)
      .putLong(TextSecurePreferences.PASSPHRASE_LOCK_TIMEOUT, 60L)
      .putStringSet(TextSecurePreferences.PASSPHRASE_LOCK_TRIGGER, setOf("timeout"))
      .commit()
    val before = preferences.all.toMap()
    val records = listOf(
      SharedPreference.Builder().file_("im.molly.app").key(TextSecurePreferences.BIOMETRIC_SCREEN_LOCK).value_("false").build(),
      SharedPreference.Builder().file_("im.molly.app").key(TextSecurePreferences.BIOMETRIC_SCREEN_LOCK).booleanValue(false).value_("false").build(),
      SharedPreference.Builder().file_("im.molly.app").key(TextSecurePreferences.BIOMETRIC_SCREEN_LOCK).booleanValue(false).stringSetValue(listOf("unexpected")).build(),
      SharedPreference.Builder().file_("im.molly.app").key(TextSecurePreferences.PASSPHRASE_LOCK_TIMEOUT).value_("0").build(),
      SharedPreference.Builder().file_("im.molly.app").key(TextSecurePreferences.PASSPHRASE_LOCK_TIMEOUT).longValue(-1L).build(),
      SharedPreference.Builder().file_("im.molly.app").key(TextSecurePreferences.PASSPHRASE_LOCK_TRIGGER).value_("none").build(),
      SharedPreference.Builder().file_("im.molly.app").key(TextSecurePreferences.BIOMETRIC_SCREEN_LOCK).build()
    )

    records.forEach { FullBackupImporter.processPreference(context, it, keyValueStorage) }

    assertEquals(before, preferences.all)
    assertTrue(TextSecurePreferences.isBiometricScreenLockEnabled(context))
    assertEquals(60L, TextSecurePreferences.getPassphraseLockTimeout(context))
  }

  @Test
  fun `incomplete preference records cannot target active preferences`() {
    val before = preferences.all.toMap()
    FullBackupImporter.processPreference(context, SharedPreference.Builder().key(TextSecurePreferences.BIOMETRIC_SCREEN_LOCK).booleanValue(false).build(), keyValueStorage)
    FullBackupImporter.processPreference(context, SharedPreference.Builder().file_("im.molly.app").booleanValue(false).build(), keyValueStorage)
    assertEquals(before, preferences.all)
  }

  @Test
  fun `post restore applies biometric lock and disabled logging without changing the local passphrase`() {
    TextSecurePreferences.setPassphraseLockEnabled(context, false)
    ScreenLockController.enableAutoLock(false)
    FullBackupImporter.processPreference(context, SharedPreference.Builder().file_("im.molly.app").key(TextSecurePreferences.BIOMETRIC_SCREEN_LOCK).booleanValue(true).build(), keyValueStorage)
    FullBackupImporter.processPreference(context, SharedPreference.Builder().file_("im.molly.app").key(TextSecurePreferences.LOG_ENABLED).booleanValue(false).build(), keyValueStorage)

    mockkStatic(NotificationChannels::class, Log::class)
    every { NotificationChannels.supported() } returns false
    justRun { Log.setLogging(any()) }
    justRun { Log.wipeLogs() }
    try {
      TextSecurePreferences.onPostBackupRestore(context)

      assertTrue(ScreenLockController.autoLock)
      assertTrue(ScreenLockController.shouldLockScreenAtStart())
      assertFalse(TextSecurePreferences.isPassphraseLockEnabled(context))
      verify(exactly = 1) { Log.setLogging(false) }
      verify(exactly = 1) { Log.wipeLogs() }
    } finally {
      unmockkStatic(NotificationChannels::class, Log::class)
    }
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
  fun `archive cannot overwrite the local UnifiedPush transition marker`() {
    SignalStore.unifiedpush.setIncompleteAccountTransition(true)
    val marker = KeyValue.Builder()
      .key("up.account.transition.pending")
      .booleanValue(false)
      .build()

    FullBackupImporter.processKeyValue(marker, keyValueStorage)

    assertTrue(SignalStore.unifiedpush.hasIncompleteAccountTransition)
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
