package im.molly.unifiedpush.enrollment

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import im.molly.unifiedpush.components.settings.app.notifications.UnifiedPushSettingsViewModel
import im.molly.unifiedpush.lifecycle.UnifiedPushAccountTransition
import im.molly.unifiedpush.lifecycle.UnifiedPushLifecycle
import im.molly.unifiedpush.model.MollySocket
import im.molly.unifiedpush.model.MollySocketDevice
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.spyk
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.models.ServiceId
import org.signal.core.util.logging.Log
import org.signal.storage.keyvalue.KeyValueDataSet
import org.signal.storage.keyvalue.KeyValueStore
import org.signal.storage.keyvalue.MockKeyValuePersistentStorage
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.keyvalue.SettingsValues.ForceWebsocketMode
import org.thoughtcrime.securesms.keyvalue.SettingsValues.NotificationDeliveryMethod
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.keyvalue.UnifiedPushValues
import org.thoughtcrime.securesms.service.KeyCachingService
import org.thoughtcrime.securesms.testutil.MockSignalStoreRule
import org.thoughtcrime.securesms.testutil.SystemOutLogger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.unifiedpush.android.connector.UnifiedPush

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class RelayDisclosureTest {
  @get:Rule val signalStore = MockSignalStoreRule()

  private val application: Application get() = ApplicationProvider.getApplicationContext()
  private val values = spyk(UnifiedPushValues(KeyValueStore(MockKeyValuePersistentStorage.withDataSet(KeyValueDataSet()))))
  private var accountId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
  private var localDeviceId = 1
  private var locked = false
  private var unauthorized = false
  private var distributor: String? = "example.provider"
  private var deliveryMethod = NotificationDeliveryMethod.UNIFIEDPUSH
  private val password = "SYNTHETIC-NOT-A-SIGNAL-CREDENTIAL"
  private val originalUrl = "https://relay.example.invalid"
  private val proposed = MollySocket.WebServer("fixture-vapid", "https://new-relay.example.invalid")

  @Before fun setUp() {
    Log.initialize(SystemOutLogger())
    UnifiedPushLifecycle.resetForTests()
    mockkObject(AppDependencies)
    mockkStatic(AppDependencies::class, KeyCachingService::class, UnifiedPush::class)
    every { AppDependencies.application } returns application
    every { AppDependencies.isInitialized } returns false
    every { KeyCachingService.isLocked() } answers { locked }
    every { UnifiedPush.getSavedDistributor(any()) } answers { distributor }
    every { UnifiedPush.getDistributors(any()) } returns emptyList()
    every { UnifiedPush.getAckDistributor(any()) } returns null
    every { SignalStore.unifiedpush } returns values
    val aci = mockk<ServiceId.ACI>()
    every { aci.toString() } answers { accountId }
    every { signalStore.account.aci } returns aci
    every { signalStore.account.isRegistered } returns true
    every { signalStore.account.isUnauthorizedReceived } answers { unauthorized }
    every { signalStore.account.hasAciIdentityKey() } returns true
    every { signalStore.account.servicePassword } returns "SYNTHETIC-SERVICE-PASSWORD"
    every { signalStore.account.deviceId } answers { localDeviceId }
    every { signalStore.settings.preferredNotificationMethod } answers { deliveryMethod }
    every { signalStore.settings.preferredNotificationMethod = any() } answers { deliveryMethod = firstArg() }
    every { signalStore.settings.forceWebsocketMode } returns ForceWebsocketMode.DISABLED_AUTOMATICALLY
    values.device = MollySocketDevice(2, password)
    values.endpoint = "https://push.example.invalid/endpoint"
    values.endpointP256dh = "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4"
    values.endpointAuth = "BTBZMqHH6r4Tts7J_aSIgg"
    values.endpointInstance = "fixture-instance"
    values.airGapped = false
    values.mollySocketUrl = originalUrl
    values.vapidPublicKey = proposed.vapid
  }

  @After fun tearDown() {
    EnrollmentExporter.removeFiles(application)
    unmockkStatic(AppDependencies::class, KeyCachingService::class, UnifiedPush::class)
    unmockkObject(AppDependencies)
  }

  @Test fun `missing consent performs no local disclosure`() {
    var effects = 0
    assertFalse(RelayDisclosure.runIfCurrent(null) { effects++ })
    assertEquals(0, effects)
  }

  @Test fun `current confirmation permits its local disclosure`() {
    var effects = 0
    assertTrue(RelayDisclosure.runIfCurrent(capture()) { effects++ })
    assertEquals(1, effects)
  }

  @Test fun `account and destination changes invalidate the displayed confirmation`() {
    val changes: List<Pair<String, () -> Unit>> = listOf(
      "account" to { accountId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb" },
      "local device" to { localDeviceId = 2 },
      "relay credential" to { values.device = MollySocketDevice(2, "replacement") },
      "endpoint" to { values.endpoint = "https://push.example.invalid/replaced" },
      "endpoint key" to { values.endpointP256dh = "replacement" },
      "endpoint auth" to { values.endpointAuth = "replacement" },
      "instance" to { values.endpointInstance = "replacement" },
      "relay" to { values.mollySocketUrl = "https://other.example.invalid" },
      "VAPID" to { values.vapidPublicKey = "replacement" },
      "manual mode" to { values.airGapped = true },
      "delivery method" to { deliveryMethod = NotificationDeliveryMethod.WEBSOCKET },
      "provider" to { distributor = "replacement.provider" },
    )
    for ((name, change) in changes) {
      val snapshot = capture()
      change()
      var disclosed = false
      assertFalse(name, RelayDisclosure.runIfCurrent(snapshot) { disclosed = true })
      assertFalse(name, disclosed)
    }
  }

  @Test fun `a lifecycle change invalidates even an otherwise identical screen`() = runBlocking {
    val snapshot = capture()
    UnifiedPushAccountTransition.withTransition {}
    assertFalse(RelayDisclosure.runIfCurrent(snapshot) { throw AssertionError("stale effect") })
  }

  @Test fun `locked or unauthorized accounts cannot authorize a disclosure`() {
    val snapshot = capture()
    locked = true
    assertNull(RelayDisclosure.capture())
    assertFalse(RelayDisclosure.runIfCurrent(snapshot) {})
    locked = false
    unauthorized = true
    assertNull(RelayDisclosure.capture())
    assertFalse(RelayDisclosure.runIfCurrent(snapshot) {})
  }

  @Test fun `clipboard copies only the confirmed account credential`() {
    val snapshot = capture()
    val viewModel = UnifiedPushSettingsViewModel(application)
    viewModel.copyParamsToClipboard(application, snapshot)
    val clipboard = application.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val authorizedText = "connection add $accountId 2 $password ${values.endpoint}"
    assertEquals(authorizedText, clipboard.primaryClip?.getItemAt(0)?.text.toString())

    accountId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
    viewModel.refresh()
    viewModel.copyParamsToClipboard(application, snapshot)
    assertEquals(authorizedText, clipboard.primaryClip?.getItemAt(0)?.text.toString())
  }

  @Test fun `accepting a relay commits only that destination and returns its bound authorization`() = runBlocking {
    val authorization = RelayDisclosure.selectRelay(capture(), proposed)
    assertNotNull(authorization)
    assertEquals(proposed.url, values.mollySocketUrl)
    assertEquals(accountId, authorization?.accountId)
    assertEquals(proposed.url, authorization?.url)
    assertTrue(UnifiedPushLifecycle.isReady())
    assertFalse(values.hasIncompleteAccountTransition)
  }

  @Test fun `a stale selection does not save the proposed relay`() = runBlocking {
    val snapshot = capture()
    accountId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
    assertNull(RelayDisclosure.selectRelay(snapshot, proposed))
    assertEquals(originalUrl, values.mollySocketUrl)
  }

  @Test fun `selection rechecks context after draining existing credentialed work`() = runBlocking {
    val snapshot = capture()
    val lease = checkNotNull(UnifiedPushAccountTransition.acquireLease())
    val admitted = signalAdmission()
    val selected = async(Dispatchers.Default) { RelayDisclosure.selectRelay(snapshot, proposed) }
    try {
      assertTrue(admitted.await(5, TimeUnit.SECONDS))
      synchronized(UnifiedPushLifecycle.monitor) { accountId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb" }
    } finally {
      lease.close()
    }
    assertNull(withTimeout(5000) { selected.await() })
    assertEquals(originalUrl, values.mollySocketUrl)
    assertTrue(UnifiedPushLifecycle.isReady())
    assertFalse(values.hasIncompleteAccountTransition)
  }

  @Test fun `cancelled selection completes its admitted no-op without disabling existing delivery`() = runBlocking {
    val snapshot = capture()
    val lease = checkNotNull(UnifiedPushAccountTransition.acquireLease())
    val admitted = signalAdmission()
    val selected = launch(Dispatchers.Default) { RelayDisclosure.selectRelay(snapshot, proposed) }
    try {
      assertTrue(admitted.await(5, TimeUnit.SECONDS))
      selected.cancel()
    } finally {
      lease.close()
    }
    withTimeout(5000) { selected.join() }
    assertEquals(originalUrl, values.mollySocketUrl)
    assertTrue(UnifiedPushLifecycle.isReady())
    assertFalse(values.hasIncompleteAccountTransition)
  }

  @Test fun `setup continuation survives normal endpoint registration but not another account`() = runBlocking {
    val authorization = checkNotNull(RelayDisclosure.selectRelay(capture(), proposed))
    UnifiedPushAccountTransition.withTransition { values.endpoint = "https://push.example.invalid/new-endpoint" }
    var continued = 0
    assertTrue(RelayDisclosure.completeSelection(authorization) { continued++ })
    accountId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
    assertFalse(RelayDisclosure.completeSelection(authorization) { continued++ })
    assertEquals(1, continued)
  }

  @Test fun `setup continuation waits for an in-flight endpoint transition and runs once afterward`() = runBlocking {
    val authorization = checkNotNull(RelayDisclosure.selectRelay(capture(), proposed))
    val entered = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    val transition = launch(Dispatchers.Default) {
      UnifiedPushAccountTransition.withTransition {
        entered.complete(Unit)
        release.await()
        values.endpoint = "https://push.example.invalid/renewed"
      }
    }
    var effects = 0
    try {
      withTimeout(5000) { entered.await() }
      // Run the first readiness check before asserting, without racing virtual time against Default/IO.
      val completion = async(start = CoroutineStart.UNDISPATCHED) {
        RelayDisclosure.completeSelection(authorization) { effects++ }
      }
      assertFalse(completion.isCompleted)
      assertEquals(0, effects)
      release.complete(Unit)
      withTimeout(5000) {
        transition.join()
        assertTrue(completion.await())
      }
      assertEquals(1, effects)
    } finally {
      release.complete(Unit)
    }
  }

  @Test fun `setup continuation expires without an effect when lifecycle does not become ready`() = runTest {
    val authorization = checkNotNull(RelayDisclosure.selectRelay(capture(), proposed))
    UnifiedPushLifecycle.resetForTests(initiallyReady = false)
    var effects = 0
    val completion = async { RelayDisclosure.completeSelection(authorization) { effects++ } }
    advanceUntilIdle()
    assertFalse(completion.await())
    assertEquals(0, effects)
  }

  @Test fun `cancelled setup continuation cannot act after readiness returns`() = runTest {
    val authorization = checkNotNull(RelayDisclosure.selectRelay(capture(), proposed))
    UnifiedPushLifecycle.resetForTests(initiallyReady = false)
    var effects = 0
    val completion = async { RelayDisclosure.completeSelection(authorization) { effects++ } }
    runCurrent()
    completion.cancelAndJoin()
    UnifiedPushLifecycle.resetForTests()
    advanceUntilIdle()
    assertEquals(0, effects)
  }

  @Test fun `an export waiting for the class monitor does not block an account transition`() {
    val first = export(capture())
    val bytes = first.file.readBytes()
    val snapshot = capture()
    val sequence = values.controlState.importSequence
    val exportFailure = AtomicReference<Throwable?>()
    val transitionFailure = AtomicReference<Throwable?>()
    val transitionFinished = CountDownLatch(1)
    val exporter = Thread({
      try {
        export(snapshot)
      } catch (failure: Throwable) {
        exportFailure.set(failure)
      }
    }, "relay-disclosure-export").apply { isDaemon = true }
    val transition = Thread({
      try {
        runBlocking {
          UnifiedPushAccountTransition.withTransition {
            synchronized(UnifiedPushLifecycle.monitor) {
              accountId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
            }
          }
        }
      } catch (failure: Throwable) {
        transitionFailure.set(failure)
      } finally {
        transitionFinished.countDown()
      }
    }, "relay-disclosure-account-transition").apply { isDaemon = true }
    var transitionCompletedWhileClassHeld = false

    try {
      // @JvmStatic + @Synchronized export methods acquire this monitor before the singleton instance.
      synchronized(EnrollmentExporter::class.java) {
        exporter.start()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        fun waitingForExporterMonitor(): Boolean {
          if (exporter.state != Thread.State.BLOCKED) return false
          val frame = exporter.stackTrace.firstOrNull() ?: return false
          return frame.className == RelayDisclosure::class.java.name || frame.className == EnrollmentExporter::class.java.name
        }
        while (exporter.isAlive && !waitingForExporterMonitor() && System.nanoTime() < deadline) Thread.sleep(1)
        assertTrue("The export must reach the held exporter monitor", waitingForExporterMonitor())

        transition.start()
        transitionCompletedWhileClassHeld = transitionFinished.await(5, TimeUnit.SECONDS)
      }
    } finally {
      // The class monitor is released before either join, including when the old lock order is detected.
      exporter.join(5000)
      transition.join(5000)
    }

    assertFalse("Export worker must finish after the class monitor is released", exporter.isAlive)
    assertFalse("Account transition worker must finish", transition.isAlive)
    assertNull("Account transition must not fail", transitionFailure.get())
    assertTrue("An export waiting for its class monitor must not hold the lifecycle monitor", transitionCompletedWhileClassHeld)
    assertTrue("The account change must invalidate the waiting export", exportFailure.get() is EnrollmentFile.Invalid)
    assertEquals("disclosure_changed", (exportFailure.get() as EnrollmentFile.Invalid).code)
    assertEquals(bytes.toList(), first.file.readBytes().toList())
    assertEquals(sequence, values.controlState.importSequence)
  }

  @Test fun `a stale export does not delete the existing file or allocate an intent`() {
    val first = export(capture())
    val bytes = first.file.readBytes()
    val snapshot = capture()
    val sequence = values.controlState.importSequence
    values.mollySocketUrl = "https://other.example.invalid"
    assertEquals("disclosure_changed", refused { export(snapshot) })
    assertEquals(bytes.toList(), first.file.readBytes().toList())
    assertEquals(sequence, values.controlState.importSequence)
  }

  @Test fun `saved rerender retains original bytes after a pre-confirmation endpoint and credential change`() {
    val first = export(capture())
    val bytes = first.file.readBytes()
    values.endpoint = "https://push.example.invalid/new-endpoint"
    values.device = MollySocketDevice(3, "REPLACEMENT-SYNTHETIC-CREDENTIAL")
    val sequence = values.controlState.importSequence

    val rerendered = export(capture(), ExportMode.RERENDER, first.requestId)

    assertEquals(bytes.toList(), rerendered.file.readBytes().toList())
    assertEquals(sequence, values.controlState.importSequence)
  }

  @Test fun `superseded or forgotten saved intent cannot be authorized by an older dialog`() {
    val first = export(capture())
    val snapshot = capture()
    val replacement = export(capture(), ExportMode.RECOVERY)
    val bytes = replacement.file.readBytes()
    val sequence = values.controlState.importSequence
    assertFalse(values.controlState.intents.single { it.requestId == first.requestId }.eligible)
    assertEquals("disclosure_changed", refused { export(snapshot, ExportMode.RERENDER, first.requestId) })
    assertEquals(bytes.toList(), replacement.file.readBytes().toList())
    assertEquals(sequence, values.controlState.importSequence)

    val replacementSnapshot = capture()
    assertTrue(EnrollmentExporter.forgetIntent(replacement.requestId))
    assertEquals("disclosure_changed", refused { export(replacementSnapshot, ExportMode.RERENDER, replacement.requestId) })
    assertEquals(bytes.toList(), replacement.file.readBytes().toList())
    assertEquals(sequence, values.controlState.importSequence)
  }

  @Test fun `saved intent from another account is refused even when present on the new screen`() {
    val first = export(capture())
    accountId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
    assertEquals("disclosure_changed", refused { export(capture(), ExportMode.RERENDER, first.requestId) })
  }

  @Test fun `recovery export still uses the existing v3 sequence and recovery semantics`() {
    val first = export(capture())
    val before = values.controlState
    val recovered = export(capture(), ExportMode.RECOVERY)
    val after = values.controlState
    val recovery = after.intents.single { it.requestId == recovered.requestId }
    assertEquals(first.epoch, recovered.epoch)
    assertFalse(first.requestId == recovered.requestId)
    assertEquals(before.importSequence + 1, after.importSequence)
    assertEquals(after.importSequence, recovery.sequence)
    assertTrue(recovery.recovery)
    assertTrue(recovery.eligible)
    assertEquals(before.intents.single().controlKey, recovery.controlKey)
    assertEquals(before.intents.single().keyId, recovery.keyId)
    // Export only saves a candidate; a matching authenticated receipt activates it.
    assertEquals(before.key, after.key)
    assertEquals(before.activeEpoch, after.activeEpoch)
    assertEquals(before.activeImportId, after.activeImportId)
    assertEquals(before.activeImportSeq, after.activeImportSeq)
  }

  @Test fun `snapshots and queued authorization do not print credentials`() = runBlocking {
    val snapshot = capture()
    val authorization = checkNotNull(RelayDisclosure.selectRelay(snapshot, proposed))
    assertFalse(snapshot.toString().contains(password))
    assertFalse(authorization.toString().contains(password))
    assertFalse(snapshot.toString().contains(accountId))
  }

  private fun capture() = checkNotNull(RelayDisclosure.capture(application))

  private fun export(snapshot: RelayDisclosure.Snapshot, mode: ExportMode = ExportMode.NEW_INTENT, requestId: String? = null) =
    RelayDisclosure.exportIfCurrent(snapshot, mode, requestId) {
      EnrollmentExporter.exportWithEpoch(application, 0, mode, requestId) { true }
    }

  private fun refused(action: () -> Any): String? = try {
    action()
    null
  } catch (e: EnrollmentFile.Invalid) {
    e.code
  }

  private fun signalAdmission(): CountDownLatch = CountDownLatch(1).also { admitted ->
    every { values.setIncompleteAccountTransition(any()) } answers {
      callOriginal()
      if (firstArg<Boolean>()) admitted.countDown()
    }
  }
}
