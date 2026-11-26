package im.molly.unifiedpush.components.settings.app.notifications

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Looper
import androidx.lifecycle.Observer
import androidx.test.core.app.ApplicationProvider
import im.molly.unifiedpush.control.ControlState
import im.molly.unifiedpush.control.ImportIntent
import im.molly.unifiedpush.control.LegacyPending
import im.molly.unifiedpush.control.Target
import im.molly.unifiedpush.enrollment.ControlKeyId
import im.molly.unifiedpush.enrollment.EnrollmentExporter
import im.molly.unifiedpush.enrollment.ExportMode
import im.molly.unifiedpush.enrollment.RelayDisclosure
import im.molly.unifiedpush.lifecycle.UnifiedPushLifecycle
import im.molly.unifiedpush.model.MollySocketDevice
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import org.signal.core.models.ServiceId
import org.signal.core.util.logging.Log
import org.signal.storage.keyvalue.KeyValueDataSet
import org.signal.storage.keyvalue.KeyValuePersistentStorage
import org.signal.storage.keyvalue.KeyValueStore
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.keyvalue.AccountValues
import org.thoughtcrime.securesms.keyvalue.SettingsValues.NotificationDeliveryMethod
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.keyvalue.UnifiedPushValues
import org.thoughtcrime.securesms.service.KeyCachingService
import org.thoughtcrime.securesms.testutil.MockSignalStoreRule
import org.thoughtcrime.securesms.testutil.SnapshotKeyValuePersistentStorage
import org.thoughtcrime.securesms.testutil.SystemOutLogger
import org.thoughtcrime.securesms.util.livedata.LiveDataRule
import org.unifiedpush.android.connector.UnifiedPush
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

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
    EnrollmentExporter.removeFiles(application)
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

  @Test
  fun `server parameter clipboard refuses a credential that became pending after rendering`() {
    seedDisclosureAccount()
    val device = MollySocketDevice(2, "synthetic-relay-password")
    values.device = device
    values.endpoint = "https://push.example.invalid/endpoint"
    val clipboard = application.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    mockkStatic(KeyCachingService::class)
    try {
      every { KeyCachingService.isLocked() } returns false
      val model = UnifiedPushSettingsViewModel(application)
      val disclosure = checkNotNull(model.state.value?.relayDisclosure)
      model.copyParamsToClipboard(application, disclosure)
      assertEquals(model.state.value?.serverParameters, clipboard.primaryClip?.getItemAt(0)?.text?.toString())

      clipboard.setPrimaryClip(ClipData.newPlainText("fixture", "keep me"))
      values.commitProvisionedDevice(device)
      model.copyParamsToClipboard(application, disclosure)
      assertEquals("keep me", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
      assertEquals(null, UnifiedPushSettingsViewModel(application).state.value?.serverParameters)
    } finally {
      clipboard.clearPrimaryClip()
      unmockkStatic(KeyCachingService::class)
    }
  }

  @Test
  fun `successful export and collision emit the complete result message`() {
    seedDisclosureAccount()
    mockkStatic(KeyCachingService::class)
    every { KeyCachingService.isLocked() } returns false
    val file = File(application.getExternalFilesDir(null), EnrollmentExporter.FILE_NAME)
    val requestId = "22222222-2222-4222-8222-222222222222"
    val viewModel = UnifiedPushSettingsViewModel(application)
    val reopenedSettings = UnifiedPushSettingsViewModel(application)
    val disclosure = checkNotNull(viewModel.state.value?.relayDisclosure)
    val messages = mutableListOf<String>()
    val observer = Observer<UnifiedPushSettingsViewModel.ExportState?> { result -> result?.message?.let(messages::add) }
    mockkStatic(EnrollmentExporter::class)
    reopenedSettings.exportState.observeForever(observer)
    try {
      for ((index, highestUsed) in listOf(1L, 3L).withIndex()) {
        val result = EnrollmentExporter.Export(file, 2L, highestUsed, requestId)
        every { EnrollmentExporter.exportWithEpoch(application, 1L, ExportMode.NEW_INTENT, null, any()) } returns result
        val expected = if (result.collides) {
          application.getString(R.string.UnifiedPushSettingsFragment__export_enrollment_done_collides, file.absolutePath, 2L, highestUsed)
        } else {
          application.getString(R.string.UnifiedPushSettingsFragment__export_enrollment_done, file.absolutePath)
        }
        val count = messages.size
        viewModel.exportEnrollment(application, "1", ExportMode.NEW_INTENT, disclosure = disclosure)
        assertTrue(reopenedSettings.isExportPending())
        reopenedSettings.exportEnrollment(application, "1", ExportMode.NEW_INTENT, disclosure = disclosure)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (messages.size == count && System.nanoTime() < deadline) {
          shadowOf(Looper.getMainLooper()).idle()
          if (messages.size == count) Thread.sleep(10)
        }
        assertEquals(listOf(expected), messages.drop(count))
        val unread = checkNotNull(UnifiedPushSettingsViewModel.unreadExport())
        assertEquals(expected, unread.message)
        every { signalStore.account.isRegistered } returns false
        assertEquals(null, UnifiedPushSettingsViewModel.unreadExport())
        every { signalStore.account.isRegistered } returns true
        reopenedSettings.exportEnrollment(application, "1", ExportMode.NEW_INTENT, disclosure = disclosure)
        UnifiedPushSettingsViewModel.acknowledgeExportResult(unread.id)
        assertEquals(null, UnifiedPushSettingsViewModel.unreadExport())
        assertFalse(reopenedSettings.isExportPending())
        verify(exactly = index + 1) { EnrollmentExporter.exportWithEpoch(application, 1L, ExportMode.NEW_INTENT, null, any()) }
      }
    } finally {
      reopenedSettings.exportState.removeObserver(observer)
      unmockkStatic(EnrollmentExporter::class)
      unmockkStatic(KeyCachingService::class)
    }
  }

  @Test
  fun `the selected document receives enrollment bytes outside the lifecycle monitor`() {
    seedDisclosureAccount()
    mockkStatic(KeyCachingService::class)
    mockkObject(EnrollmentExporter)
    val destination = Uri.parse("content://example.test/enrollment")
    val contents = """{"enrollment_version":3}"""
    val stream = object : ByteArrayOutputStream() {
      override fun write(bytes: ByteArray, offset: Int, length: Int) {
        assertFalse(Thread.holdsLock(UnifiedPushLifecycle.monitor))
        super.write(bytes, offset, length)
      }
    }
    shadowOf(application.contentResolver).registerOutputStream(destination, stream)
    try {
      every { KeyCachingService.isLocked() } returns false
      every { EnrollmentExporter.prepareDocument(application, 1L, ExportMode.NEW_INTENT, null) } answers {
        assertTrue(Thread.holdsLock(UnifiedPushLifecycle.monitor))
        EnrollmentExporter.DocumentExport(2L, 1L, contents)
      }
      val viewModel = UnifiedPushSettingsViewModel(application)
      val disclosure = checkNotNull(viewModel.state.value?.relayDisclosure)
      val messages = mutableListOf<String>()
      val observer = Observer<UnifiedPushSettingsViewModel.ExportState?> { result -> result?.message?.let(messages::add) }
      viewModel.exportState.observeForever(observer)
      try {
        viewModel.exportEnrollment(application, "1", ExportMode.NEW_INTENT, disclosure = disclosure, destination = destination)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (messages.isEmpty() && System.nanoTime() < deadline) {
          shadowOf(Looper.getMainLooper()).idle()
          if (messages.isEmpty()) Thread.sleep(10)
        }
        assertEquals(listOf(application.getString(R.string.UnifiedPushSettingsFragment__export_enrollment_saved)), messages)
        assertEquals(contents, stream.toByteArray().decodeToString())
        UnifiedPushSettingsViewModel.acknowledgeExportResult(checkNotNull(UnifiedPushSettingsViewModel.unreadExport()).id)
      } finally {
        viewModel.exportState.removeObserver(observer)
      }
    } finally {
      unmockkObject(EnrollmentExporter)
      unmockkStatic(KeyCachingService::class)
    }
  }

  @Test
  fun `locking during export removes the written file and clears the result`() {
    seedDisclosureAccount()
    val locked = AtomicBoolean()
    val file = File(application.getExternalFilesDir(null), EnrollmentExporter.FILE_NAME)
    mockkStatic(KeyCachingService::class, EnrollmentExporter::class)
    try {
      every { KeyCachingService.isLocked() } answers { locked.get() }
      every { EnrollmentExporter.exportWithEpoch(application, 1L, ExportMode.NEW_INTENT, null, any()) } answers {
        file.writeText("synthetic enrollment")
        locked.set(true)
        EnrollmentExporter.Export(file, 2L, 1L, "22222222-2222-4222-8222-222222222222")
      }
      val viewModel = UnifiedPushSettingsViewModel(application)
      val disclosure = checkNotNull(viewModel.state.value?.relayDisclosure)
      viewModel.exportEnrollment(application, "1", ExportMode.NEW_INTENT, disclosure = disclosure)
      val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
      while (viewModel.exportState.value != null && System.nanoTime() < deadline) {
        shadowOf(Looper.getMainLooper()).idle()
        if (viewModel.exportState.value != null) Thread.sleep(10)
      }

      assertTrue(locked.get())
      assertFalse(file.exists())
      assertEquals(null, viewModel.exportState.value)
      locked.set(false)
      assertEquals(null, UnifiedPushSettingsViewModel.unreadExport())
    } finally {
      unmockkStatic(EnrollmentExporter::class)
      unmockkStatic(KeyCachingService::class)
    }
  }

  private fun seedDisclosureAccount() {
    UnifiedPushLifecycle.resetForTests()
    val account = mockk<ServiceId.ACI>()
    every { account.toString() } returns "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    every { signalStore.account.aci } returns account
    every { signalStore.account.isRegistered } returns true
    every { signalStore.account.isUnauthorizedReceived } returns false
    every { signalStore.account.hasAciIdentityKey() } returns true
    every { signalStore.account.servicePassword } returns "synthetic-service-password"
    every { signalStore.account.deviceId } returns 1
    every { signalStore.settings.preferredNotificationMethod } returns NotificationDeliveryMethod.UNIFIEDPUSH
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
