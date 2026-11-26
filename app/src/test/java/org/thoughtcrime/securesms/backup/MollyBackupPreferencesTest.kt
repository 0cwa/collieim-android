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
import org.thoughtcrime.securesms.keyvalue.MollyValues
import org.thoughtcrime.securesms.keyvalue.SettingsValues
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.notifications.NotificationChannels
import org.thoughtcrime.securesms.preferences.widgets.PassphraseLockTriggerPreference
import org.thoughtcrime.securesms.testutil.DirectExecutor
import org.thoughtcrime.securesms.util.Environment
import org.thoughtcrime.securesms.util.SecurePreferenceManager
import org.thoughtcrime.securesms.util.ThemePalettes

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
        SharedPreference.Builder().file_(filename).key(PlainTextKeyValueStore.BIOMETRIC_SCREEN_LOCK_ENABLED).booleanValue(true).build(),
        SharedPreference.Builder().file_(filename).key("pref_block_unknown").booleanValue(true).build(),
        SharedPreference.Builder().file_(filename).key(MollyValues.LOG_ENABLED).booleanValue(false).build()
      )

      records.forEach { FullBackupImporter.processPreference(context, SharedPreference.ADAPTER.decode(it.encode()), keyValueStorage) }

      assertTrue(filename, PlainTextKeyValueStore.biometricScreenLockEnabled)
      assertTrue(filename, SignalStore.molly.blockUnknownEnabled)
      assertFalse(filename, SignalStore.molly.logEnabled)
    }
  }

  @Test
  fun `literal Molly file preserves timeout triggers and long and historical integer timeouts`() {
    val trigger = SharedPreference.Builder()
      .file_("im.molly.app")
      .key(MollyValues.PASSPHRASE_LOCK_TRIGGER)
      .isStringSetValue(true)
      .stringSetValue(listOf("timeout", "screen_off"))
      .build()
    val timeout = SharedPreference.Builder()
      .file_("im.molly.app")
      .key(MollyValues.PASSPHRASE_LOCK_TIMEOUT)
      .longValue(Int.MAX_VALUE.toLong() + 120L)
      .build()
    FullBackupImporter.processPreference(context, trigger, keyValueStorage)
    FullBackupImporter.processPreference(context, timeout, keyValueStorage)

    assertEquals(setOf("timeout", "screen_off"), SignalStore.molly.passphraseLockTrigger.triggers)
    assertTrue(SignalStore.molly.passphraseLockTrigger.isTimeoutEnabled)
    assertEquals(Int.MAX_VALUE.toLong() + 120L, SignalStore.molly.passphraseLockTimeout)

    val oldTimeout = SharedPreference.Builder()
      .file_("im.molly.app")
      .key(MollyValues.PASSPHRASE_LOCK_TIMEOUT)
      .integerValue(-1)
      .build()
    FullBackupImporter.processPreference(context, SharedPreference.ADAPTER.decode(oldTimeout.encode()), keyValueStorage)
    // Molly: uint32 timeout records from old exporters always carried 0, so they never replace the MollyValues timeout.
    assertEquals(Int.MAX_VALUE.toLong() + 120L, SignalStore.molly.passphraseLockTimeout)
  }

  @Test
  fun `Molly filename cannot change local passphrase configuration or inject unrelated keys`() {
    PlainTextKeyValueStore.passphraseLockEnabled = true
    val before = preferences.all.toMap()
    val records = listOf(
      SharedPreference.Builder().file_("im.molly.app").key(PlainTextKeyValueStore.PASSPHRASE_LOCK_ENABLED).booleanValue(false).build(),
      SharedPreference.Builder().file_("im.molly.app").key("pref_identity_private_v3").value_("foreign-key").build(),
      SharedPreference.Builder().file_("im.molly.app").key("pref_proxy_type").value_("none").build(),
      SharedPreference.Builder().file_("im.molly.app").key("foreign-setting").booleanValue(true).build()
    )

    records.forEach { FullBackupImporter.processPreference(context, it, keyValueStorage) }

    assertEquals(before, preferences.all)
    assertTrue(PlainTextKeyValueStore.passphraseLockEnabled)

    PlainTextKeyValueStore.passphraseLockEnabled = false
    FullBackupImporter.processPreference(
      context,
      SharedPreference.Builder().file_("im.molly.app").key(PlainTextKeyValueStore.PASSPHRASE_LOCK_ENABLED).booleanValue(true).build(),
      keyValueStorage
    )
    assertFalse(PlainTextKeyValueStore.passphraseLockEnabled)
  }

  @Test
  fun `malformed Molly setting values cannot replace valid security preferences`() {
    preferences.edit()
      .putBoolean(PlainTextKeyValueStore.BIOMETRIC_SCREEN_LOCK_ENABLED, true)
      .commit()
    // The lock timeout and triggers live in MollyValues since the Molly 8.34 merge.
    SignalStore.molly.passphraseLockTimeout = 60L
    SignalStore.molly.passphraseLockTrigger = PassphraseLockTriggerPreference(setOf("timeout"))
    val before = preferences.all.toMap()
    val records = listOf(
      SharedPreference.Builder().file_("im.molly.app").key(PlainTextKeyValueStore.BIOMETRIC_SCREEN_LOCK_ENABLED).value_("false").build(),
      SharedPreference.Builder().file_("im.molly.app").key(PlainTextKeyValueStore.BIOMETRIC_SCREEN_LOCK_ENABLED).booleanValue(false).value_("false").build(),
      SharedPreference.Builder().file_("im.molly.app").key(PlainTextKeyValueStore.BIOMETRIC_SCREEN_LOCK_ENABLED).booleanValue(false).stringSetValue(listOf("unexpected")).build(),
      SharedPreference.Builder().file_("im.molly.app").key(MollyValues.PASSPHRASE_LOCK_TIMEOUT).value_("0").build(),
      SharedPreference.Builder().file_("im.molly.app").key(MollyValues.PASSPHRASE_LOCK_TIMEOUT).longValue(-1L).build(),
      SharedPreference.Builder().file_("im.molly.app").key(MollyValues.PASSPHRASE_LOCK_TRIGGER).value_("none").build(),
      SharedPreference.Builder().file_("im.molly.app").key(PlainTextKeyValueStore.BIOMETRIC_SCREEN_LOCK_ENABLED).build()
    )

    records.forEach { FullBackupImporter.processPreference(context, it, keyValueStorage) }

    assertEquals(before, preferences.all)
    assertTrue(PlainTextKeyValueStore.biometricScreenLockEnabled)
    assertEquals(60L, SignalStore.molly.passphraseLockTimeout)
    assertEquals(setOf("timeout"), SignalStore.molly.passphraseLockTrigger.triggers)
  }

  @Test
  fun `incomplete preference records cannot target active preferences`() {
    val before = preferences.all.toMap()
    FullBackupImporter.processPreference(context, SharedPreference.Builder().key(PlainTextKeyValueStore.BIOMETRIC_SCREEN_LOCK_ENABLED).booleanValue(false).build(), keyValueStorage)
    FullBackupImporter.processPreference(context, SharedPreference.Builder().file_("im.molly.app").booleanValue(false).build(), keyValueStorage)
    assertEquals(before, preferences.all)
  }

  @Test
  fun `post restore applies biometric lock and disabled logging without changing the local passphrase`() {
    PlainTextKeyValueStore.passphraseLockEnabled = false
    ScreenLockController.enableAutoLock(false)
    FullBackupImporter.processPreference(context, SharedPreference.Builder().file_("im.molly.app").key(PlainTextKeyValueStore.BIOMETRIC_SCREEN_LOCK_ENABLED).booleanValue(true).build(), keyValueStorage)
    FullBackupImporter.processPreference(context, SharedPreference.Builder().file_("im.molly.app").key(MollyValues.LOG_ENABLED).booleanValue(false).build(), keyValueStorage)

    mockkStatic(NotificationChannels::class, Log::class)
    every { NotificationChannels.supported() } returns false
    justRun { Log.setLogging(any()) }
    justRun { Log.wipeLogs() }
    try {
      CollieRestoredPreferences.applyAfterRestore(context)

      assertTrue(ScreenLockController.autoLock)
      assertTrue(ScreenLockController.shouldLockScreenAtStart())
      assertFalse(PlainTextKeyValueStore.passphraseLockEnabled)
      verify(exactly = 1) { Log.setLogging(false) }
      verify(exactly = 1) { Log.wipeLogs() }
    } finally {
      unmockkStatic(NotificationChannels::class, Log::class)
    }
  }

  @Test
  fun `Molly preferences round trip through the wire format without truncating long timeouts`() {
    // The lock timeout and triggers live in MollyValues and travel as KeyValue frames; dynamic colours stay a
    // preference record because they are needed before unlock.
    val timeout = Int.MAX_VALUE.toLong() + 120L
    SignalStore.molly.passphraseLockTimeout = timeout
    SignalStore.molly.passphraseLockTrigger = PassphraseLockTriggerPreference(setOf("screen_off", "background"))
    PlainTextKeyValueStore.isDynamicColorsEnabled = true

    assertTrue(SignalStore.keysToIncludeInBackup.contains(MollyValues.PASSPHRASE_LOCK_TIMEOUT))
    assertTrue(SignalStore.keysToIncludeInBackup.contains(MollyValues.PASSPHRASE_LOCK_TRIGGER))
    val exported = keyValueStorage.getDataSet()
    val timeoutFrame = KeyValue.Builder().key(MollyValues.PASSPHRASE_LOCK_TIMEOUT).longValue(exported.getLong(MollyValues.PASSPHRASE_LOCK_TIMEOUT, 0)).build()
    val triggerFrame = KeyValue.Builder().key(MollyValues.PASSPHRASE_LOCK_TRIGGER).blobValue(okio.ByteString.of(*exported.getBlob(MollyValues.PASSPHRASE_LOCK_TRIGGER, null)!!)).build()
    val records = FullBackupExporter.getPlainTextPreferencesToSaveToBackup()
    assertTrue(records.single { it.key == PlainTextKeyValueStore.DYNAMIC_COLORS_ENABLED }.booleanValue!!)

    preferences.edit().clear().commit()
    keyValueStorage = MockKeyValuePersistentStorage.withDataSet(KeyValueDataSet())
    SignalStore.testInject(SignalStore(context, KeyValueStore(keyValueStorage, DirectExecutor())))
    records.forEach { record ->
      FullBackupImporter.processPreference(context, SharedPreference.ADAPTER.decode(record.encode()), keyValueStorage)
    }
    FullBackupImporter.processKeyValue(KeyValue.ADAPTER.decode(timeoutFrame.encode()), keyValueStorage)
    FullBackupImporter.processKeyValue(KeyValue.ADAPTER.decode(triggerFrame.encode()), keyValueStorage)
    SignalStore.onPostBackupRestore()

    assertEquals(timeout, SignalStore.molly.passphraseLockTimeout)
    assertEquals(setOf("screen_off", "background"), SignalStore.molly.passphraseLockTrigger.triggers)
    assertTrue(PlainTextKeyValueStore.isDynamicColorsEnabled)
  }

  @Test
  fun `historical integer timeout records do not replace the MollyValues timeout`() {
    val record = SharedPreference.Builder()
      .file_(SecurePreferenceManager.getSecurePreferencesName())
      .key(MollyValues.PASSPHRASE_LOCK_TIMEOUT)
      .integerValue(-1)
      .build()

    SignalStore.molly.passphraseLockTimeout = 300L
    FullBackupImporter.processPreference(context, SharedPreference.ADAPTER.decode(record.encode()), keyValueStorage)

    assertEquals(300L, SignalStore.molly.passphraseLockTimeout)
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
  fun `palette ID round trips through current and historical preference filenames`() {
    val filenames = listOf(SecurePreferenceManager.getSecurePreferencesName()) + HISTORICAL_FILENAMES

    for (filename in filenames) {
      preferences.edit().clear().commit()
      PlainTextKeyValueStore.setPalette(dynamicColorsEnabled = false, paletteId = PILOT)
      val exported = FullBackupExporter.getPlainTextPreferencesToSaveToBackup()
        .map { SharedPreference.ADAPTER.decode(it.newBuilder().file_(filename).build().encode()) }
      preferences.edit().clear().commit()

      exported.forEach { FullBackupImporter.processPreference(context, it, keyValueStorage) }
      SignalStore.onPostBackupRestore()

      assertEquals(filename, PILOT, PlainTextKeyValueStore.themePalette)
      assertFalse(filename, PlainTextKeyValueStore.isDynamicColorsEnabled)
      assertEquals(filename, PILOT, ThemePalettes.selectedId())
    }
  }

  @Test
  fun `restoring an older backup without a palette record keeps Collie or System`() {
    for (dynamic in listOf(false, true)) {
      preferences.edit().clear().commit()
      val record = SharedPreference.Builder()
        .file_(SecurePreferenceManager.getSecurePreferencesName())
        .key(PlainTextKeyValueStore.DYNAMIC_COLORS_ENABLED)
        .booleanValue(dynamic)
        .build()

      FullBackupImporter.processPreference(context, record, keyValueStorage)
      SignalStore.onPostBackupRestore()

      assertNull(PlainTextKeyValueStore.themePalette)
      assertEquals(if (dynamic) ThemePalettes.SYSTEM else ThemePalettes.COLLIE, ThemePalettes.selectedId())
    }
  }

  @Test
  fun `palette records of the wrong type are skipped for every filename`() {
    for (filename in listOf(SecurePreferenceManager.getSecurePreferencesName()) + HISTORICAL_FILENAMES) {
      preferences.edit().clear().commit()
      val records = listOf(
        SharedPreference.Builder().file_(filename).key(PlainTextKeyValueStore.THEME_PALETTE).booleanValue(true).build(),
        SharedPreference.Builder().file_(filename).key(PlainTextKeyValueStore.THEME_PALETTE).integerValue(3).build(),
        SharedPreference.Builder().file_(filename).key(PlainTextKeyValueStore.THEME_PALETTE).value_(PILOT).booleanValue(true).build()
      )

      records.forEach { FullBackupImporter.processPreference(context, it, keyValueStorage) }

      assertFalse(filename, preferences.contains(PlainTextKeyValueStore.THEME_PALETTE))
      assertEquals(filename, ThemePalettes.COLLIE, ThemePalettes.selectedId())
    }
  }

  @Test
  fun `stale shared preferences for migrated Signal settings are not exported`() {
    preferences.edit()
      .putBoolean("pref_read_receipts", false)
      .putBoolean("pref_screen_security", false)
      .putString("pref_theme", "light")
      .putStringSet("pref_media_download_mobile", setOf("video"))
      .putBoolean(MollyValues.LOG_ENABLED, false)
      .putBoolean(PlainTextKeyValueStore.BIOMETRIC_SCREEN_LOCK_ENABLED, true)
      .commit()

    val records = FullBackupExporter.getPlainTextPreferencesToSaveToBackup()

    // Only the pre-unlock settings without a SignalStore mirror are exported as preference records.
    assertEquals(listOf(PlainTextKeyValueStore.BIOMETRIC_SCREEN_LOCK_ENABLED), records.map { it.key })
    assertTrue(records.single().booleanValue!!)
  }

  companion object {
    private const val PILOT = "teal_vibrant"

    private val HISTORICAL_FILENAMES = listOf(
      "im.molly.app",
      "im.molly.app_preferences",
      "im.molly.app.staging",
      "im.molly.app.staging_preferences",
      "im.collie.app",
      "im.collie.app_preferences",
      "im.collie.app.staging",
      "im.collie.app.staging_preferences"
    )
  }
}
