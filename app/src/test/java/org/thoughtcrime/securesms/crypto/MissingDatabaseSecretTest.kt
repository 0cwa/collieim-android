/*
 * Copyright 2026 Molly Instant Messenger
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.crypto

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.storage.database.DatabaseSecretProvider
import org.signal.storage.keyvalue.KeyValueDatabase
import org.signal.storage.keyvalue.PlainTextKeyValueStore
import org.thoughtcrime.securesms.BuildConfig
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.util.SecurePreferenceManager

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class MissingDatabaseSecretTest {

  @After
  fun tearDown() {
    DatabaseSecretProvider.existingDatabaseCheck = { KeyValueDatabase.exists(it) }
  }

  @Test
  fun `existing database never causes replacement database or attachment secrets`() {
    val context = ApplicationProvider.getApplicationContext<Application>()
    val prefsFile = SignalDatabase.getDatabaseFile(context)
    prefsFile.parentFile?.mkdirs()
    prefsFile.createNewFile()

    val preferences = SecurePreferenceManager.getSecurePreferences(context)
    PlainTextKeyValueStore.init(preferences, BuildConfig.VERSION_CODE)
    // ApplicationContext installs this check; Robolectric's plain Application does not run it.
    DatabaseSecretProvider.existingDatabaseCheck = { SignalDatabase.databaseFileExists(it) || KeyValueDatabase.exists(it) }

    val databaseError = assertThrows(IllegalStateException::class.java) {
      DatabaseSecretProvider.getOrCreateDatabaseSecret(context)
    }
    val attachmentError = assertThrows(IllegalStateException::class.java) {
      AppAttachmentSecretStore.getAttachmentEncryptedSecret(context)
    }

    assertEquals("Database secret is missing for an existing protected database", databaseError.message)
    assertEquals("Attachment secret is missing for an existing protected database", attachmentError.message)

    assertFalse(preferences.contains(DATABASE_SECRET_KEY))
    assertFalse(preferences.contains(ATTACHMENT_SECRET_KEY))
  }

  companion object {
    private const val DATABASE_SECRET_KEY = "pref_database_encrypted_secret"
    private const val ATTACHMENT_SECRET_KEY = "pref_attachment_encrypted_secret"
  }
}
