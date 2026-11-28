/*
 * Copyright 2026 Molly Instant Messenger
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.keyvalue

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.storage.keyvalue.KeyValueDataSet
import org.signal.storage.keyvalue.KeyValueStore
import org.signal.storage.keyvalue.MockKeyValuePersistentStorage
import org.signal.storage.keyvalue.PlainTextKeyValueStore
import org.thoughtcrime.securesms.BuildConfig
import org.thoughtcrime.securesms.testutil.DirectExecutor
import org.thoughtcrime.securesms.testutil.MasterSecretRule
import org.thoughtcrime.securesms.util.SecurePreferenceManager

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class SettingsValuesMigrationTest {

  @get:Rule
  val masterSecret = MasterSecretRule()

  private val context: Application
    get() = ApplicationProvider.getApplicationContext()

  @Before
  fun setUp() {
    PlainTextKeyValueStore.init(SecurePreferenceManager.getSecurePreferences(context), BuildConfig.VERSION_CODE)
    clearLegacyPrivacyValues()
  }

  @After
  fun tearDown() {
    clearLegacyPrivacyValues()
  }

  @Test
  fun `saved websocket preference still falls back when push becomes unavailable`() {
    val mode = SettingsValues.ForceWebsocketMode.deserialize(3)

    assertFalse(mode.shouldStayConnected(pushAvailable = true))
    assertTrue(mode.shouldStayConnected(pushAvailable = false))
    assertFalse(mode.shouldStayConnected(pushAvailable = true))
  }

  @Test
  fun `absent privacy preferences retain Molly defaults during migration`() {
    val values = createValues()

    assertTrue(values.isScreenSecurityEnabled)
    assertTrue(values.isIncognitoKeyboardEnabled)
    assertTrue(values.isReadReceiptsEnabled)
    assertTrue(values.isTypingIndicatorsEnabled)
    assertEquals("none", values.messageNotificationsPrivacy.toString())
  }

  @Test
  fun `explicit privacy preferences retain their values during migration`() {
    legacyPreferences().edit()
      .putBoolean("pref_screen_security", false)
      .putBoolean("pref_incognito_keyboard", false)
      .putBoolean("pref_read_receipts", false)
      .putBoolean("pref_typing_indicators", false)
      .putString("pref_notification_privacy", "all")
      .commit()

    val values = createValues()

    assertFalse(values.isScreenSecurityEnabled)
    assertFalse(values.isIncognitoKeyboardEnabled)
    assertFalse(values.isReadReceiptsEnabled)
    assertFalse(values.isTypingIndicatorsEnabled)
    assertEquals("all", values.messageNotificationsPrivacy.toString())
  }

  private fun createValues(): SettingsValues {
    val store = KeyValueStore(MockKeyValuePersistentStorage.withDataSet(KeyValueDataSet()), DirectExecutor())
    return SettingsValues(store, context)
  }

  private fun legacyPreferences() = SecurePreferenceManager.getSecurePreferences(context)

  private fun clearLegacyPrivacyValues() {
    legacyPreferences().edit()
      .remove("pref_screen_security")
      .remove("pref_incognito_keyboard")
      .remove("pref_read_receipts")
      .remove("pref_typing_indicators")
      .remove("pref_notification_privacy")
      .commit()
  }
}
