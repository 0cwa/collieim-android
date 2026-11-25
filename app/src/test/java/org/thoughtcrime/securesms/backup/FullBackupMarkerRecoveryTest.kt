package org.thoughtcrime.securesms.backup

import android.app.Application
import androidx.test.core.app.ApplicationProvider
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
import org.signal.storage.keyvalue.KeyValueDataSet
import org.signal.storage.keyvalue.KeyValuePersistentStorage
import org.signal.storage.keyvalue.KeyValueStore
import org.signal.storage.keyvalue.MockKeyValuePersistentStorage
import org.signal.storage.keyvalue.PlainTextKeyValueStore
import org.thoughtcrime.securesms.BuildConfig
import org.thoughtcrime.securesms.backup.proto.KeyValue
import org.thoughtcrime.securesms.keyvalue.SettingsValues
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.keyvalue.UnifiedPushValues
import org.thoughtcrime.securesms.testutil.DirectExecutor
import org.thoughtcrime.securesms.testutil.SystemOutLogger

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class FullBackupMarkerRecoveryTest {
  private lateinit var storage: KeyValuePersistentStorage

  @Before fun setup() {
    Log.initialize(SystemOutLogger())
    val context = ApplicationProvider.getApplicationContext<Application>()
    val prefs = context.getSharedPreferences("backup-marker-recovery-test", android.content.Context.MODE_PRIVATE)
    prefs.edit().clear().commit()
    PlainTextKeyValueStore.init(prefs, BuildConfig.VERSION_CODE)
    storage = MockKeyValuePersistentStorage.withDataSet(KeyValueDataSet())
    SignalStore.testInject(SignalStore(context, KeyValueStore(storage, DirectExecutor())))
  }

  @After fun cleanup() = SignalStore.testInject(null)

  @Test fun `false archive marker cannot open locally closed state after cache reload`() {
    SignalStore.unifiedpush.setIncompleteAccountTransition(true)
    val archived = KeyValue.Builder().key(UnifiedPushValues.UNIFIEDPUSH_ACCOUNT_TRANSITION_PENDING).booleanValue(false).build()
    FullBackupImporter.processKeyValue(archived, storage)
    SignalStore.onPostBackupRestore()
    assertTrue(SignalStore.unifiedpush.hasIncompleteAccountTransition)
  }

  @Test fun `archive marker cannot close previously open local state and other frames still import`() {
    SignalStore.unifiedpush.setIncompleteAccountTransition(false)
    val archived = KeyValue.Builder().key(UnifiedPushValues.UNIFIEDPUSH_ACCOUNT_TRANSITION_PENDING).booleanValue(true).build()
    val other = KeyValue.Builder().key(SettingsValues.MESSAGE_NOTIFICATION_PRIVACY).stringValue("none").build()
    FullBackupImporter.processKeyValue(archived, storage)
    FullBackupImporter.processKeyValue(other, storage)
    SignalStore.onPostBackupRestore()
    assertFalse(SignalStore.unifiedpush.hasIncompleteAccountTransition)
    assertEquals("none", SignalStore.settings.messageNotificationsPrivacy.toString())
  }

  @Test fun `wrong-type archive marker cannot replace locally closed state after cache reload`() {
    SignalStore.unifiedpush.setIncompleteAccountTransition(true)
    val archived = KeyValue.Builder().key(UnifiedPushValues.UNIFIEDPUSH_ACCOUNT_TRANSITION_PENDING).stringValue("invalid").build()
    FullBackupImporter.processKeyValue(archived, storage)
    SignalStore.onPostBackupRestore()
    assertTrue(SignalStore.unifiedpush.hasIncompleteAccountTransition)
  }
}
