/*
 * Copyright 2026 Molly Instant Messenger
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.keyvalue

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.signal.storage.keyvalue.KeyValueDataSet
import org.signal.storage.keyvalue.KeyValueStore
import org.signal.storage.keyvalue.MockKeyValuePersistentStorage
import org.signal.storage.keyvalue.PlainTextKeyValueStore
import org.thoughtcrime.securesms.BuildConfig
import org.thoughtcrime.securesms.testutil.DirectExecutor
import org.thoughtcrime.securesms.testutil.MasterSecretRule
import org.thoughtcrime.securesms.util.DynamicTheme
import org.thoughtcrime.securesms.util.SecurePreferenceManager
import org.thoughtcrime.securesms.util.ThemePalettes

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class SettingsValuesAppearanceTest {

  @get:Rule
  val masterSecret = MasterSecretRule()

  private val context: Application
    get() = ApplicationProvider.getApplicationContext()

  @Before
  fun setUp() {
    PlainTextKeyValueStore.init(SecurePreferenceManager.getSecurePreferences(context), BuildConfig.VERSION_CODE)
    clearLegacyBootstrapValues()
  }

  @After
  fun tearDown() {
    clearLegacyBootstrapValues()
  }

  @Test
  fun `pre-integration installation migrates theme and system emoji from secure preferences`() {
    legacyPreferences().edit()
      .putString(PlainTextKeyValueStore.THEME, "dark")
      .putBoolean(PlainTextKeyValueStore.SYSTEM_EMOJI_PREFERRED, true)
      .commit()

    // Pre-integration main has no SCREEN_SECURITY_ENABLED migration marker.
    val store = KeyValueStore(MockKeyValuePersistentStorage.withDataSet(KeyValueDataSet()), DirectExecutor())

    SettingsValues(store, context)

    assertTrue(store.containsKey(SettingsValues.SCREEN_SECURITY_ENABLED))
    assertEquals("dark", store.getString(SettingsValues.THEME, null))
    assertTrue(store.getBoolean(SettingsValues.PREFER_SYSTEM_EMOJI, false))
  }

  @Test
  fun `general migration preserves existing database appearance values`() {
    legacyPreferences().edit()
      .putString(PlainTextKeyValueStore.THEME, "dark")
      .putBoolean(PlainTextKeyValueStore.SYSTEM_EMOJI_PREFERRED, true)
      .commit()

    val dataSet = KeyValueDataSet()
    dataSet.putString(SettingsValues.THEME, "light")
    dataSet.putBoolean(SettingsValues.PREFER_SYSTEM_EMOJI, false)
    val store = KeyValueStore(MockKeyValuePersistentStorage.withDataSet(dataSet), DirectExecutor())

    SettingsValues(store, context)

    assertEquals("light", store.getString(SettingsValues.THEME, null))
    assertFalse(store.getBoolean(SettingsValues.PREFER_SYSTEM_EMOJI, true))
  }

  @Test
  fun `fresh installation migrates defaults and synchronizes them on first launch`() {
    val store = KeyValueStore(MockKeyValuePersistentStorage.withDataSet(KeyValueDataSet()), DirectExecutor())
    val values = SettingsValues(store, context)
    val expectedTheme = if (DynamicTheme.systemThemeAvailable()) "system" else "light"

    assertEquals(expectedTheme, store.getString(SettingsValues.THEME, null))
    assertFalse(store.getBoolean(SettingsValues.PREFER_SYSTEM_EMOJI, true))

    values.onFirstEverAppLaunch()

    assertEquals(expectedTheme, PlainTextKeyValueStore.theme)
    assertFalse(PlainTextKeyValueStore.isSystemEmojiPreferred)
  }

  @Test
  fun `direct post-restore synchronization updates theme without changing dynamic colors`() {
    legacyPreferences().edit()
      .putString(PlainTextKeyValueStore.THEME, "light")
      .putBoolean(PlainTextKeyValueStore.DYNAMIC_COLORS_ENABLED, true)
      .commit()

    val dataSet = KeyValueDataSet()
    dataSet.putString(SettingsValues.THEME, "dark")
    val store = KeyValueStore(MockKeyValuePersistentStorage.withDataSet(dataSet), DirectExecutor())
    val values = SettingsValues(store, context)

    values.onPostBackupRestore()

    assertEquals("dark", PlainTextKeyValueStore.theme)
    assertTrue(PlainTextKeyValueStore.isDynamicColorsEnabled)
    assertEquals("dark", store.getString(SettingsValues.THEME, null))
  }

  @Test
  fun `appearance update persists both values before notifying once`() {
    val store = KeyValueStore(MockKeyValuePersistentStorage.withDataSet(KeyValueDataSet()), DirectExecutor())
    val values = SettingsValues(store, context)
    val events = mutableListOf<String>()

    values.onConfigurationSettingChanged.observeForever { key ->
      events += key
      assertEquals("dark", PlainTextKeyValueStore.theme)
      assertTrue(PlainTextKeyValueStore.isDynamicColorsEnabled)
    }

    values.setAppearance(SettingsValues.Theme.DARK, true)
    shadowOf(Looper.getMainLooper()).idle()

    assertEquals(listOf(SettingsValues.THEME), events)
    assertEquals("dark", store.getString(SettingsValues.THEME, null))
    assertTrue(PlainTextKeyValueStore.isDynamicColorsEnabled)
  }

  @Test
  fun `palette choices commit together and notify once each`() {
    val store = KeyValueStore(MockKeyValuePersistentStorage.withDataSet(KeyValueDataSet()), DirectExecutor())
    val values = SettingsValues(store, context)
    val events = mutableListOf<String>()
    values.onConfigurationSettingChanged.observeForever { events += it }

    values.setColorPalette(PILOT)
    shadowOf(Looper.getMainLooper()).idle()
    assertEquals(PILOT, PlainTextKeyValueStore.themePalette)
    assertFalse(PlainTextKeyValueStore.isDynamicColorsEnabled)
    assertEquals(PILOT, values.colorPalette)

    values.setColorPalette(ThemePalettes.SYSTEM)
    shadowOf(Looper.getMainLooper()).idle()
    assertNull(PlainTextKeyValueStore.themePalette)
    assertTrue(PlainTextKeyValueStore.isDynamicColorsEnabled)
    assertEquals(ThemePalettes.SYSTEM, values.colorPalette)

    values.setColorPalette(PILOT)
    shadowOf(Looper.getMainLooper()).idle()
    values.setColorPalette(ThemePalettes.COLLIE)
    shadowOf(Looper.getMainLooper()).idle()
    assertNull(PlainTextKeyValueStore.themePalette)
    assertFalse(PlainTextKeyValueStore.isDynamicColorsEnabled)
    assertEquals(ThemePalettes.COLLIE, values.colorPalette)

    assertEquals(List(4) { SettingsValues.THEME }, events)
  }

  @Test(expected = IllegalArgumentException::class)
  fun `unknown palettes cannot be selected`() {
    val store = KeyValueStore(MockKeyValuePersistentStorage.withDataSet(KeyValueDataSet()), DirectExecutor())
    SettingsValues(store, context).setColorPalette("not_a_palette")
  }

  @Test
  fun `brightness changes and legacy dynamic writers keep the stored palette`() {
    val store = KeyValueStore(MockKeyValuePersistentStorage.withDataSet(KeyValueDataSet()), DirectExecutor())
    val values = SettingsValues(store, context)
    values.setColorPalette(PILOT)

    values.theme = SettingsValues.Theme.DARK
    values.setAppearance(SettingsValues.Theme.LIGHT, false)
    values.isDynamicColorsEnabled = false
    assertEquals(PILOT, PlainTextKeyValueStore.themePalette)
    assertEquals("light", PlainTextKeyValueStore.theme)

    // The legacy Dynamic Colors writer turning System on is the System choice and clears the palette.
    values.isDynamicColorsEnabled = true
    assertNull(PlainTextKeyValueStore.themePalette)
    assertTrue(PlainTextKeyValueStore.isDynamicColorsEnabled)
  }

  @Test
  fun `post-restore theme synchronization keeps a restored palette and an unknown ID`() {
    legacyPreferences().edit()
      .putString(PlainTextKeyValueStore.THEME, "light")
      .putBoolean(PlainTextKeyValueStore.DYNAMIC_COLORS_ENABLED, false)
      .putString(PlainTextKeyValueStore.THEME_PALETTE, "from_a_newer_version")
      .commit()

    val dataSet = KeyValueDataSet()
    dataSet.putString(SettingsValues.THEME, "dark")
    val values = SettingsValues(KeyValueStore(MockKeyValuePersistentStorage.withDataSet(dataSet), DirectExecutor()), context)

    values.onPostBackupRestore()

    assertEquals("dark", PlainTextKeyValueStore.theme)
    assertEquals("from_a_newer_version", PlainTextKeyValueStore.themePalette)
    assertEquals(ThemePalettes.COLLIE, values.colorPalette)
  }

  private fun legacyPreferences() = SecurePreferenceManager.getSecurePreferences(context)

  private fun clearLegacyBootstrapValues() {
    legacyPreferences().edit()
      .remove(PlainTextKeyValueStore.THEME)
      .remove(PlainTextKeyValueStore.DYNAMIC_COLORS_ENABLED)
      .remove(PlainTextKeyValueStore.THEME_PALETTE)
      .remove(PlainTextKeyValueStore.SYSTEM_EMOJI_PREFERRED)
      .commit()
  }

  companion object {
    private const val PILOT = "teal_vibrant"
  }
}
