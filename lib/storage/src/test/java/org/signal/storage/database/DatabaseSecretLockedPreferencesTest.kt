package org.signal.storage.database

import android.app.Application
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.storage.keyvalue.PlainTextKeyValueStore
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class DatabaseSecretLockedPreferencesTest {
  @Test
  fun `corrupt sealed database secret fails without replacing it`() {
    val context = ApplicationProvider.getApplicationContext<Application>()
    val prefs = context.getSharedPreferences("corrupt-database-secret-test", 0)
    val key = "pref_database_encrypted_secret"
    prefs.edit().clear().putString(key, "not valid JSON").commit()
    val instanceField = DatabaseSecretProvider::class.java.getDeclaredField("instance").apply { isAccessible = true }
    val previousInstance = instanceField.get(null)
    val prefsField = PlainTextKeyValueStore::class.java.getDeclaredField("sharedPrefs").apply { isAccessible = true }
    val previousPrefs = prefsField.get(null) as SharedPreferences?
    val versionField = PlainTextKeyValueStore::class.java.getDeclaredField("currentVersionCode").apply { isAccessible = true }
    val previousVersion = versionField.getInt(null)
    try {
      instanceField.set(null, null)
      PlainTextKeyValueStore.init(prefs, 123)
      assertThrows(AssertionError::class.java) { DatabaseSecretProvider.getOrCreateDatabaseSecret(context) }
      assertEquals("not valid JSON", prefs.getString(key, null))
    } finally {
      instanceField.set(null, previousInstance)
      prefsField.set(null, previousPrefs)
      versionField.setInt(null, previousVersion)
      prefs.edit().clear().commit()
    }
  }

  @Test
  fun `locked preferences cannot cause a replacement database secret to be stored`() {
    val context = ApplicationProvider.getApplicationContext<Application>()
    val instanceField = DatabaseSecretProvider::class.java.getDeclaredField("instance").apply { isAccessible = true }
    val previousInstance = instanceField.get(null)
    val prefsField = PlainTextKeyValueStore::class.java.getDeclaredField("sharedPrefs").apply { isAccessible = true }
    val previousPrefs = prefsField.get(null) as SharedPreferences?
    val versionField = PlainTextKeyValueStore::class.java.getDeclaredField("currentVersionCode").apply { isAccessible = true }
    val previousVersion = versionField.getInt(null)
    var writes = 0
    val lockedPrefs = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, _ ->
      when (method.name) {
        "getString" -> throw IllegalStateException("locked")
        "edit" -> { writes++; throw AssertionError("locked preferences must not be written") }
        else -> throw AssertionError("unexpected preference access: ${method.name}")
      }
    } as SharedPreferences

    try {
      instanceField.set(null, null)
      PlainTextKeyValueStore.init(lockedPrefs, 123)
      assertThrows(IllegalStateException::class.java) { DatabaseSecretProvider.getOrCreateDatabaseSecret(context) }
      assertEquals(0, writes)
    } finally {
      instanceField.set(null, previousInstance)
      prefsField.set(null, previousPrefs)
      versionField.setInt(null, previousVersion)
    }
  }
}
