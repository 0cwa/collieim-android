package im.molly.unifiedpush.components.settings.app.notifications

import android.app.Application
import androidx.lifecycle.Observer
import androidx.test.core.app.ApplicationProvider
import im.molly.unifiedpush.control.ControlState
import im.molly.unifiedpush.control.ImportIntent
import im.molly.unifiedpush.control.LegacyPending
import im.molly.unifiedpush.control.Target
import im.molly.unifiedpush.enrollment.ControlKeyId
import im.molly.unifiedpush.enrollment.EnrollmentExporter
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import org.signal.core.util.logging.Log
import org.signal.storage.keyvalue.KeyValueDataSet
import org.signal.storage.keyvalue.KeyValuePersistentStorage
import org.signal.storage.keyvalue.KeyValueStore
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.keyvalue.AccountValues
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.keyvalue.UnifiedPushValues
import org.thoughtcrime.securesms.testutil.MockSignalStoreRule
import org.thoughtcrime.securesms.testutil.SnapshotKeyValuePersistentStorage
import org.thoughtcrime.securesms.testutil.SystemOutLogger
import org.thoughtcrime.securesms.util.livedata.LiveDataRule
import org.unifiedpush.android.connector.UnifiedPush
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [35])
class UnifiedPushSettingsViewModelPersistenceTest {
  private val signalStore = MockSignalStoreRule(setOf(AccountValues::class))

  @get:Rule
  val rules: RuleChain = RuleChain.outerRule(LiveDataRule()).around(signalStore)

  private val application: Application = ApplicationProvider.getApplicationContext()
  private lateinit var storage: WriteThenFailStorage
  private lateinit var values: UnifiedPushValues

  @Before
  fun setUp() {
    Log.initialize(SystemOutLogger())
    storage = WriteThenFailStorage(KeyValueDataSet())
    values = UnifiedPushValues(KeyValueStore(storage, Executor { it.run() }))
    every { SignalStore.unifiedpush } returns values
    every { signalStore.account.aci } returns null

    mockkStatic(UnifiedPush::class)
    every { UnifiedPush.getDistributors(application) } returns emptyList()
    every { UnifiedPush.getSavedDistributor(application) } returns null
    every { UnifiedPush.getAckDistributor(application) } returns null
  }

  @After
  fun tearDown() {
    EnrollmentExporter.removeFiles(application)
    unmockkStatic(UnifiedPush::class)
  }

  @Test
  fun `forgetting an intent reports failed acknowledgement and displays persisted state`() {
    val requestId = "22222222-2222-4222-8222-222222222222"
    values.mutateControl {
      ControlState(highestEpoch = 1, importSequence = 1, intents = listOf(intent(requestId)))
    }
    val viewModel = UnifiedPushSettingsViewModel(application)
    storage.failAfterNextWrite = true

    awaitRefresh(viewModel, { it.savedExports.isEmpty() }) {
      viewModel.forgetExport(requestId)
    }

    assertTrue(values.controlState.intents.isEmpty())
    assertEquals(application.getString(R.string.UnifiedPushSettingsFragment__export_enrollment_failed), ShadowToast.getTextOfLatestToast().toString())
  }

  @Test
  fun `forgetting a legacy export reports failed acknowledgement and displays persisted state`() {
    values.mutateControl {
      ControlState(highestEpoch = 1, legacyPending = LegacyPending(null, 1, null))
    }
    val viewModel = UnifiedPushSettingsViewModel(application)
    storage.failAfterNextWrite = true

    awaitRefresh(viewModel, { !it.hasLegacyPendingExport }) {
      viewModel.forgetLegacyExport()
    }

    assertEquals(null, values.controlState.legacyPending)
    assertEquals(application.getString(R.string.UnifiedPushSettingsFragment__export_enrollment_failed), ShadowToast.getTextOfLatestToast().toString())
  }

  @Test
  fun `corrupt state recovery reports failed acknowledgement and displays persisted state`() {
    storage = WriteThenFailStorage(KeyValueDataSet().apply { putString("up.control.state", "{") })
    values = UnifiedPushValues(KeyValueStore(storage, Executor { it.run() }))
    every { SignalStore.unifiedpush } returns values
    val viewModel = UnifiedPushSettingsViewModel(application)
    storage.failAfterNextWrite = true

    awaitRefresh(viewModel, { !it.controlStateCorrupt }) {
      viewModel.recoverCorruptControlState()
    }

    assertTrue(values.controlState.intents.isEmpty())
    assertEquals(application.getString(R.string.UnifiedPushSettingsFragment__export_enrollment_failed), ShadowToast.getTextOfLatestToast().toString())
  }

  private fun awaitRefresh(
    viewModel: UnifiedPushSettingsViewModel,
    expected: (UnifiedPushSettingsState) -> Boolean,
    action: () -> Unit,
  ) {
    val refreshed = CountDownLatch(1)
    val observer = Observer<UnifiedPushSettingsState> { state ->
      if (expected(state)) refreshed.countDown()
    }
    viewModel.state.observeForever(observer)
    try {
      action()
      assertTrue("settings state did not refresh from persistent storage", refreshed.await(5, TimeUnit.SECONDS))
    } finally {
      viewModel.state.removeObserver(observer)
    }
  }

  private fun intent(requestId: String) = ImportIntent(
    requestId = requestId,
    sequence = 1,
    epoch = 1,
    controlKey = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8",
    keyId = ControlKeyId.derive(
      "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8",
      "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
      2,
      "fixture-instance",
    ),
    target = Target(
      "https://push.example.invalid/synthetic-endpoint",
      "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4",
      "BTBZMqHH6r4Tts7J_aSIgg",
    ),
    accountId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
    deviceId = 2,
    password = "SYNTHETIC-NOT-A-SIGNAL-CREDENTIAL",
    instance = "fixture-instance",
    recovery = false,
  )

  private class WriteThenFailStorage(initial: KeyValueDataSet) : KeyValuePersistentStorage {
    private val delegate = SnapshotKeyValuePersistentStorage(initial)

    @Volatile
    var failAfterNextWrite = false

    override fun writeDataSet(dataSet: KeyValueDataSet, removes: Collection<String>) {
      delegate.writeDataSet(dataSet, removes)
      if (failAfterNextWrite) {
        failAfterNextWrite = false
        throw IllegalStateException("synthetic write committed before acknowledgement failed")
      }
    }

    override fun getDataSet(): KeyValueDataSet = delegate.getDataSet()
  }
}
