/*
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.backup

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.just
import io.mockk.Runs
import io.mockk.mockk
import io.mockk.slot
import io.mockk.mockkStatic
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thoughtcrime.securesms.backup.proto.KeyValue
import org.signal.storage.keyvalue.KeyValueDatabase
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.signal.core.util.logging.Log
import org.signal.storage.keyvalue.PlainTextKeyValueStore
import org.thoughtcrime.securesms.BuildConfig
import org.thoughtcrime.securesms.testutil.EmptyLogger
import org.signal.storage.keyvalue.KeyValueDataSet
import org.signal.storage.keyvalue.KeyValuePersistentStorage
import org.signal.storage.keyvalue.KeyValueStore
import org.signal.storage.keyvalue.MockKeyValuePersistentStorage
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.keyvalue.SettingsValues
import org.thoughtcrime.securesms.testutil.DirectExecutor
import org.thoughtcrime.securesms.util.Environment
import org.thoughtcrime.securesms.util.SecurePreferenceManager
import org.thoughtcrime.securesms.util.TextSecurePreferences

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class MollyLanguageBackupTest {

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
    PlainTextKeyValueStore.init(preferences, BuildConfig.VERSION_CODE)
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
  fun `backup exports chosen language on upgrade and after a language change`() {
    Log.initialize(EmptyLogger())
    preferences.edit().putString("pref_language", "de").commit()
    keyValueStorage = MockKeyValuePersistentStorage.withDataSet(KeyValueDataSet())
    SignalStore.testInject(SignalStore(context, KeyValueStore(keyValueStorage, DirectExecutor())))

    assertEquals("de", exportedLanguage())
    SignalStore.settings.language = "fr"
    assertEquals("fr", exportedLanguage())
  }

  private fun exportedLanguage(): String? {
    val preference = TextSecurePreferences.getPreferencesToSaveToBackup(context)
      .lastOrNull { it.key == "pref_language" }?.value_
    val keys = SignalStore.keysToIncludeInBackup
    assertTrue(keys.contains(SettingsValues.LANGUAGE))
    val database = mockk<KeyValueDatabase>()
    val output = mockk<BackupFrameOutputStream>()
    val frame = slot<KeyValue>()
    every { database.getDataSet() } answers { keyValueStorage.getDataSet() }
    every { output.write(capture(frame)) } just Runs
    mockkObject(KeyValueDatabase.Companion)
    mockkStatic(AppDependencies::class)
    try {
      every { KeyValueDatabase.getInstance(context) } returns database
      every { AppDependencies.application } returns context
      val method = FullBackupExporter::class.java.getDeclaredMethod(
        "exportKeyValues", BackupFrameOutputStream::class.java, List::class.java,
        Int::class.javaPrimitiveType, Long::class.javaPrimitiveType, FullBackupExporter.BackupCancellationSignal::class.java
      )
      method.isAccessible = true
      method.invoke(null, output, listOf(SettingsValues.LANGUAGE), 0, 1L, FullBackupExporter.BackupCancellationSignal { false })
      return if (frame.isCaptured) frame.captured.stringValue else preference
    } finally {
      unmockkObject(KeyValueDatabase.Companion)
      unmockkStatic(AppDependencies::class)
    }
  }
}
