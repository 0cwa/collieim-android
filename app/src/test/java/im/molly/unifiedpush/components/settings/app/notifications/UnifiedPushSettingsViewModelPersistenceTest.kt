package im.molly.unifiedpush.components.settings.app.notifications

import android.app.Application
import androidx.lifecycle.Observer
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
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
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.keyvalue.AccountValues
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.keyvalue.UnifiedPushValues
import org.thoughtcrime.securesms.service.KeyCachingService
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

    mockkStatic(AppDependencies::class, UnifiedPush::class)
    every { AppDependencies.application } returns application
    every { UnifiedPush.getDistributors(application) } returns emptyList()
    every { UnifiedPush.getSavedDistributor(application) } returns null
    every { UnifiedPush.getAckDistributor(application) } returns null
  }

  @After
  fun tearDown() {
    unmockkStatic(AppDependencies::class, UnifiedPush::class)
  }

  @Test
  fun `choosing a replacement for a paired route stages the distributor until the next QR`() {
    val previous = "org.example.old"
    val replacement = "org.example.new"
    val endpoint = "https://old.example.test/push"
    val binding = UnifiedPushValues.PairingEndpointBinding(
      "pairing-v1-old", previous, "old-vapid", null, "https://old.example.test",
      "old-session", Long.MAX_VALUE, "account-aci", 1, "Old relay",
    )
    values.setPairingEndpointRegistration(endpoint, "old-key", "old-auth", binding)
    every { UnifiedPush.getDistributors(application) } returns listOf(previous, replacement)
    every { UnifiedPush.getSavedDistributor(application) } returns previous
    every { UnifiedPush.getAckDistributor(application) } returns previous
    mockkStatic(KeyCachingService::class)
    try {
      every { KeyCachingService.isLocked() } returns false
      val viewModel = UnifiedPushSettingsViewModel(application)

      viewModel.setUnifiedPushDistributor(replacement)

      assertEquals(replacement, viewModel.pairingSelectedDistributor())
      assertEquals(previous, viewModel.state.value?.distributors?.get(0)?.applicationId)
      assertEquals(0, viewModel.state.value?.selected)
      assertEquals(endpoint, values.endpoint)
      assertEquals(binding, values.pairingEndpointBinding)
      verify(exactly = 0) { UnifiedPush.saveDistributor(any(), any()) }
      verify(exactly = 0) { UnifiedPush.unregisterApp(any(), any()) }
    } finally {
      unmockkStatic(KeyCachingService::class)
    }
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
    assertEquals(application.getString(R.string.UnifiedPushSettingsFragment__control_state_reset_failed), ShadowToast.getTextOfLatestToast().toString())
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
