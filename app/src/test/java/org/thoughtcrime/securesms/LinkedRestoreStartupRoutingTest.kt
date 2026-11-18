package org.thoughtcrime.securesms

import android.app.Application
import android.content.Intent
import androidx.core.content.IntentCompat
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.util.logging.Log
import org.signal.storage.keyvalue.KeyValueDataSet
import org.signal.storage.keyvalue.KeyValueStore
import org.signal.storage.keyvalue.MockKeyValuePersistentStorage
import org.signal.storage.keyvalue.PlainTextKeyValueStore
import org.signal.registration.RegistrationRoute
import org.thoughtcrime.securesms.registration.ui.RegistrationActivity
import org.thoughtcrime.securesms.registration.v2.AppRegistrationStorageController
import org.thoughtcrime.securesms.crypto.MasterSecretUtil
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.keyvalue.UnifiedPushValues
import org.thoughtcrime.securesms.migrations.ApplicationMigrations
import org.thoughtcrime.securesms.testutil.DirectExecutor
import org.thoughtcrime.securesms.testutil.MockAppDependenciesRule
import org.thoughtcrime.securesms.testutil.MockSignalStoreRule
import org.thoughtcrime.securesms.testutil.SystemOutLogger

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE)
class LinkedRestoreStartupRoutingTest {
  class TestActivity : PassphraseRequiredActivity()
  @get:Rule val storeRule = MockSignalStoreRule()
  @get:Rule val dependencies = MockAppDependenciesRule()

  @Before fun setup() {
    Log.initialize(SystemOutLogger())
    val app = ApplicationProvider.getApplicationContext<Application>()
    PlainTextKeyValueStore.init(app.getSharedPreferences("linked-restore-routing-test", 0), BuildConfig.VERSION_CODE)
    mockkStatic(MasterSecretUtil::class)
    mockkStatic(ApplicationMigrations::class)
    every { MasterSecretUtil.isPassphraseInitialized(any()) } returns true
    every { ApplicationMigrations.isUpdate(any()) } returns false
  }

  @After fun cleanup() {
    unmockkStatic(MasterSecretUtil::class)
    unmockkStatic(ApplicationMigrations::class)
  }

  @Test fun `completed registration resumes message sync after interrupted linked restore`() {
    val persistent = MockKeyValuePersistentStorage.withDataSet(KeyValueDataSet())
    val store = KeyValueStore(persistent, DirectExecutor())
    UnifiedPushValues(store).setLinkRestoreTransition(true)
    every { SignalStore.unifiedpush } returns UnifiedPushValues(KeyValueStore(persistent, DirectExecutor()))
    every { SignalStore.registration.isRegistrationComplete } returns true
    val activity = Robolectric.buildActivity(TestActivity::class.java).get()
    val method = PassphraseRequiredActivity::class.java.getDeclaredMethod("getApplicationState", Boolean::class.javaPrimitiveType)
    method.isAccessible = true
    // STATE_RESUME_LINKING_REG routes to RegistrationActivity at MessageSync.
    val state = method.invoke(activity, false) as Int
    assertEquals(12, state)
    val intentMethod = PassphraseRequiredActivity::class.java.getDeclaredMethod("getIntentForState", Int::class.javaPrimitiveType)
    intentMethod.isAccessible = true
    val intent = intentMethod.invoke(activity, state) as Intent
    assertEquals(RegistrationActivity::class.java.name, intent.component?.className)
    assertEquals(RegistrationRoute.MessageSync, IntentCompat.getParcelableExtra(intent, "start_destination", RegistrationRoute::class.java))
  }

  @Test fun `pending endpoint alone does not require link restore recovery`() {
    val values = UnifiedPushValues(KeyValueStore(MockKeyValuePersistentStorage.withDataSet(KeyValueDataSet()), DirectExecutor()))
    values.setIncompleteEndpointTransition(true)
    every { SignalStore.unifiedpush } returns values
    val controller = AppRegistrationStorageController(ApplicationProvider.getApplicationContext())
    org.junit.Assert.assertFalse(kotlinx.coroutines.runBlocking { controller.isLinkAndSyncRecoveryRequired() })
  }

}
