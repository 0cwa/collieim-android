/*
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.keyvalue

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.util.logging.Log
import org.signal.storage.keyvalue.PlainTextKeyValueStore
import org.signal.storage.keyvalue.KeyValueStore
import org.signal.storage.keyvalue.KeyValueDataSet
import org.signal.storage.keyvalue.MockKeyValuePersistentStorage
import org.thoughtcrime.securesms.BuildConfig
import org.thoughtcrime.securesms.testutil.DirectExecutor
import org.thoughtcrime.securesms.testutil.EmptyLogger
import org.thoughtcrime.securesms.util.Environment
import org.thoughtcrime.securesms.util.SecurePreferenceManager

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class MollySettingsMigrationTest {
  private lateinit var context: Application
  private lateinit var mollyPreferences: SharedPreferences
  private lateinit var store: KeyValueStore
  private var originalInstrumentation = false

  @Before
  fun setUp() {
    Log.initialize(EmptyLogger())
    context = ApplicationProvider.getApplicationContext()
    mollyPreferences = context.getSharedPreferences("molly-preferences-test", Context.MODE_PRIVATE)
    mollyPreferences.edit().clear().commit()
    PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
    mockkStatic(SecurePreferenceManager::class)
    every { SecurePreferenceManager.getSecurePreferences(context) } returns mollyPreferences
    PlainTextKeyValueStore.init(mollyPreferences, BuildConfig.VERSION_CODE)
    store = KeyValueStore(MockKeyValuePersistentStorage.withDataSet(KeyValueDataSet()), DirectExecutor())
    originalInstrumentation = Environment.IS_INSTRUMENTATION
    Environment.IS_INSTRUMENTATION = false
  }

  @After
  fun tearDown() {
    Environment.IS_INSTRUMENTATION = originalInstrumentation
    unmockkStatic(SecurePreferenceManager::class)
  }

  @Test
  fun `migration reads existing Molly preferences instead of the unrelated default file`() {
    mollyPreferences.edit()
      .putBoolean("pref_screen_security", true)
      .putBoolean("pref_incognito_keyboard", true)
      .putBoolean("pref_read_receipts", false)
      .putBoolean("pref_typing_indicators", false)
      .putString("pref_notification_privacy", "none")
      .putString("pref_language", "de")
      .commit()
    PreferenceManager.getDefaultSharedPreferences(context).edit()
      .putBoolean("pref_screen_security", false)
      .putBoolean("pref_incognito_keyboard", false)
      .putBoolean("pref_read_receipts", true)
      .putBoolean("pref_typing_indicators", true)
      .putString("pref_notification_privacy", "all")
      .putString("pref_language", "en")
      .commit()

    val settings = SettingsValues(store, context)

    assertTrue(settings.isScreenSecurityEnabled)
    assertTrue(settings.isIncognitoKeyboardEnabled)
    assertFalse(settings.isReadReceiptsEnabled)
    assertFalse(settings.isTypingIndicatorsEnabled)
    assertEquals("none", settings.messageNotificationsPrivacy.toString())
    assertEquals("de", settings.language)
  }

  @Test
  fun `existing chosen language survives migration and first launch`() {
    mollyPreferences.edit().putString("pref_language", "de").commit()
    PreferenceManager.getDefaultSharedPreferences(context).edit().putString("pref_language", "en").commit()

    val settings = SettingsValues(store, context)
    assertEquals("de", settings.language)

    settings.onFirstEverAppLaunch()
    assertEquals("de", settings.language)
  }

  @Test
  fun `changed language is visible through the settings reader`() {
    mollyPreferences.edit().putString("pref_language", "de").commit()
    val settings = SettingsValues(store, context)
    assertEquals("de", settings.language)

    settings.language = "fr"

    assertEquals("fr", settings.language)
  }

  @Test
  fun `new installs retain Molly privacy defaults`() {
    val settings = SettingsValues(store, context)

    assertTrue(settings.isScreenSecurityEnabled)
    assertTrue(settings.isIncognitoKeyboardEnabled)
    assertTrue(settings.isReadReceiptsEnabled)
    assertTrue(settings.isTypingIndicatorsEnabled)
    assertEquals("none", settings.messageNotificationsPrivacy.toString())
  }

  @Test
  fun `locked UI settings and explicit UnifiedPush choice survive store reconstruction`() {
    val settings = SettingsValues(store, context)
    settings.setTheme(SettingsValues.Theme.DARK, true)
    settings.isPreferSystemEmoji = true
    settings.language = "fr"
    settings.preferredNotificationMethod = SettingsValues.NotificationDeliveryMethod.UNIFIEDPUSH

    val restored = SettingsValues(store, context)
    assertEquals(SettingsValues.Theme.DARK, restored.theme)
    assertTrue(restored.isDynamicColorsEnabled)
    assertTrue(restored.isPreferSystemEmoji)
    assertEquals("fr", restored.language)
    assertEquals(SettingsValues.NotificationDeliveryMethod.UNIFIEDPUSH, restored.preferredNotificationMethod)
    assertEquals("dark", mollyPreferences.getString("pref_theme", null))
    assertEquals("fr", mollyPreferences.getString("pref_language", null))
    assertTrue(mollyPreferences.getBoolean("pref_system_emoji", false))
    assertTrue(PreferenceManager.getDefaultSharedPreferences(context).all.isEmpty())
  }

  @Test
  fun `moved secret and migration store preserves the Molly preference source`() {
    mollyPreferences.edit()
      .putString("pref_database_encrypted_secret", "existing-secret")
      .putInt("pref_app_migration_version", 123)
      .commit()

    assertEquals("existing-secret", PlainTextKeyValueStore.databaseEncryptedSecret)
    assertEquals(123, PlainTextKeyValueStore.appMigrationVersion)
    PlainTextKeyValueStore.databaseEncryptedSecret = "replacement-secret"
    assertEquals("replacement-secret", mollyPreferences.getString("pref_database_encrypted_secret", null))
    assertTrue(PreferenceManager.getDefaultSharedPreferences(context).all.isEmpty())
  }
}
