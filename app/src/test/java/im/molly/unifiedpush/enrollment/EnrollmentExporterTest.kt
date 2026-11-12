package im.molly.unifiedpush.enrollment

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import com.fasterxml.jackson.databind.ObjectMapper
import im.molly.unifiedpush.control.ControlState
import im.molly.unifiedpush.control.ControlStateRead
import im.molly.unifiedpush.control.ImportIntent
import im.molly.unifiedpush.control.Target
import im.molly.unifiedpush.model.MollySocketDevice
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.signal.core.models.ServiceId
import org.signal.core.util.logging.Log
import org.signal.storage.keyvalue.KeyValueDataSet
import org.signal.storage.keyvalue.KeyValueStore
import org.signal.storage.keyvalue.MockKeyValuePersistentStorage
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.keyvalue.UnifiedPushValues
import org.thoughtcrime.securesms.testutil.MockSignalStoreRule
import org.thoughtcrime.securesms.testutil.SystemOutLogger
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [35])
class EnrollmentExporterTest {
  @get:Rule val signalStore = MockSignalStoreRule()

  private val context: Context get() = RuntimeEnvironment.getApplication()
  private val values = spyk(UnifiedPushValues(KeyValueStore(MockKeyValuePersistentStorage.withDataSet(KeyValueDataSet()))))
  private val accountId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
  private val endpoint = "https://push.example.invalid/synthetic-endpoint"
  private val p256dh = "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4"
  private val auth = "BTBZMqHH6r4Tts7J_aSIgg"
  private val key = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"

  @Before fun setUp() {
    Log.initialize(SystemOutLogger())
    every { SignalStore.unifiedpush } returns values
    val aci = mockk<ServiceId.ACI>()
    every { aci.toString() } returns accountId
    every { signalStore.account.aci } returns aci
    values.device = MollySocketDevice(2, "SYNTHETIC-NOT-A-SIGNAL-CREDENTIAL")
    values.endpoint = endpoint
    values.endpointInstance = "fixture-instance"
    values.endpointP256dh = p256dh
    values.endpointAuth = auth
    values.mutateControl { it.copy(highestEpoch = 8) }
  }

  @After fun cleanup() = EnrollmentExporter.removeFiles(context)

  @Test
  fun `four concurrent new intents are capped and every saved intent shares the same epoch key`() {
    val workerCount = 5
    val ready = CountDownLatch(workerCount)
    val start = CountDownLatch(1)
    val exports = ConcurrentLinkedQueue<EnrollmentExporter.Export>()
    val failures = ConcurrentLinkedQueue<String>()
    val pool = Executors.newFixedThreadPool(workerCount)
    repeat(workerCount) {
      pool.execute {
        ready.countDown()
        if (!start.await(5, TimeUnit.SECONDS)) throw AssertionError("exports did not start")
        try {
          exports.add(EnrollmentExporter.exportWithEpoch(context, 4, ExportMode.NEW_INTENT) { true })
        } catch (e: EnrollmentFile.Invalid) {
          failures.add(e.code)
        }
      }
    }
    assertTrue(ready.await(5, TimeUnit.SECONDS))
    start.countDown()
    pool.shutdown()
    assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS))

    assertEquals(4, exports.size)
    assertEquals(listOf("pending_limit"), failures.toList())
    val state = values.controlState
    assertEquals(4, state.intents.size)
    assertEquals(4, state.importSequence)
    assertEquals(5, state.pendingEpoch)
    assertEquals(1, state.intents.count { it.eligible })
    assertEquals(1, state.intents.map { it.controlKey }.toSet().size)
    val eligible = state.intents.single { it.eligible }
    val rerendered = EnrollmentExporter.exportWithEpoch(context, null, ExportMode.RERENDER, eligible.requestId) { true }

    val dir = checkNotNull(context.getExternalFilesDir(null))
    val output = File(dir, EnrollmentExporter.FILE_NAME)
    val json = ObjectMapper().readTree(output.readText())
    assertFalse(File(dir, "${EnrollmentExporter.FILE_NAME}.tmp").exists())
    assertEquals(3, json["enrollment_version"].asInt())
    assertEquals(5, json["subscription"]["subscription_epoch"].asLong())
    assertEquals(eligible.sequence, json["import_seq"].asLong())
    assertEquals(eligible.requestId, json["request_id"].asText())
    assertEquals(eligible.controlKey, json["control_key"].asText())
    assertEquals(eligible.requestId, rerendered.requestId)
    assertTrue(exports.all { it.epoch == 5L && it.highestUsed == 8L && it.collides })
  }

  @Test
  fun `exports at different epochs retain both importable intents across restart`() {
    val earlier = EnrollmentExporter.exportWithEpoch(context, 4, ExportMode.NEW_INTENT) { true }
    val later = EnrollmentExporter.exportWithEpoch(context, 5, ExportMode.NEW_INTENT) { true }

    assertEquals(5, earlier.epoch)
    assertEquals(6, later.epoch)
    assertEquals(2, values.controlState.importSequence)
    assertEquals(listOf(earlier.requestId, later.requestId), values.controlState.intents.map { it.requestId })
    assertTrue(values.controlState.intents.all { it.eligible })
    assertEquals(2, values.controlState.intents.map { it.controlKey }.toSet().size)

    val restored = (ControlState.parse(values.controlState.serialize()) as ControlStateRead.Current).state
    assertEquals(values.controlState.intents, restored.intents)
    assertEquals(2, restored.importSequence)
    assertTrue(restored.intents.all { it.eligible })
  }

  @Test
  fun `explicit same epoch recovery reuses the active key and binds a fresh sequence and ID`() {
    val serverTarget = Target(endpoint, p256dh, auth)
    values.mutateControl { ControlState(key = key, activeEpoch = 5, highestEpoch = 5, serverTarget = serverTarget) }

    val export = EnrollmentExporter.exportWithEpoch(context, 4, ExportMode.RECOVERY) { true }
    val json = ObjectMapper().readTree(export.file.readText())
    val state = values.controlState
    assertEquals(5, export.epoch)
    assertEquals(key, json["control_key"].asText())
    assertEquals(1, json["import_seq"].asLong())
    assertEquals(state.intents.single().requestId, json["request_id"].asText())
    assertEquals(key, state.key)
    assertEquals(5, state.activeEpoch)
    assertEquals(state.intents.single().keyId, ControlKeyId.derive(key, accountId, 2, "fixture-instance"))
  }

  @Test
  fun `same epoch key reuse is limited to the same account device and UnifiedPush instance`() {
    data class ContextCase(val name: String, val account: String, val deviceId: Int, val instance: String, val reusesKey: Boolean)
    val accountB = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
    val cases = listOf(
      ContextCase("same", accountId, 2, "fixture-instance", true),
      ContextCase("account", accountB, 2, "fixture-instance", false),
      ContextCase("device", accountId, 3, "fixture-instance", false),
      ContextCase("instance", accountId, 2, "replacement-instance", false),
    )

    for ((index, case) in cases.withIndex()) {
      val oldId = "00000000-0000-4000-8000-%012d".format(index + 1)
      val oldIntent = ImportIntent(
        requestId = oldId,
        sequence = 1,
        epoch = 1,
        controlKey = key,
        keyId = ControlKeyId.derive(key, accountId, 2, "fixture-instance"),
        target = Target(endpoint, p256dh, auth),
        accountId = accountId,
        deviceId = 2,
        password = "SYNTHETIC-NOT-A-SIGNAL-CREDENTIAL",
        instance = "fixture-instance",
        recovery = false,
      )
      values.mutateControl { ControlState(intents = listOf(oldIntent), importSequence = 1, highestEpoch = 1) }
      values.device = MollySocketDevice(case.deviceId, "SYNTHETIC-NOT-A-SIGNAL-CREDENTIAL")
      values.endpointInstance = case.instance
      val aci = mockk<ServiceId.ACI>()
      every { aci.toString() } returns case.account
      every { signalStore.account.aci } returns aci

      val export = EnrollmentExporter.exportWithEpoch(context, 0, ExportMode.NEW_INTENT) { true }
      val next = values.controlState.intents.single { it.sequence == 2L }
      val exportedKey = ObjectMapper().readTree(export.file.readText())["control_key"].asText()

      assertEquals(case.name, case.reusesKey, exportedKey == key)
      assertEquals(case.name, case.account, next.accountId)
      assertEquals(case.name, case.deviceId, next.deviceId)
      assertEquals(case.name, case.instance, next.instance)
      assertFalse(values.controlState.intents.single { it.sequence == 1L }.eligible)
    }
  }

  @Test
  fun `new intent at a remembered epoch requires explicit recovery mode`() {
    values.mutateControl { ControlState(key = key, activeEpoch = 5, highestEpoch = 5, serverTarget = Target(endpoint, p256dh, auth)) }
    val failure = try {
      EnrollmentExporter.exportWithEpoch(context, 4, ExportMode.NEW_INTENT) { true }
      null
    } catch (e: EnrollmentFile.Invalid) { e.code }
    assertEquals("recovery_ack_required", failure)
    assertEquals(0, values.controlState.importSequence)
  }

  @Test
  fun `missing live account or device context refuses export without changing key or sequence`() {
    val initial = ControlState(key = key, activeEpoch = 3, highestEpoch = 3, importSequence = 7)
    for (missing in listOf("account", "device")) {
      values.mutateControl { initial }
      if (missing == "account") {
        every { signalStore.account.aci } returns null
      } else {
        values.device = null
      }

      val failure = try {
        EnrollmentExporter.exportWithEpoch(context, 0, ExportMode.NEW_INTENT) { true }
        null
      } catch (e: EnrollmentFile.Invalid) { e.code }

      assertEquals(missing, "not_ready", failure)
      assertEquals(missing, initial, values.controlState)
      assertEquals(missing, key, values.controlState.key)
      assertEquals(missing, 7, values.controlState.importSequence)
      assertTrue(missing, values.controlState.intents.isEmpty())
    }
  }

  @Test
  fun `rerender after restart reuses saved bytes and does not allocate a slot`() {
    val first = EnrollmentExporter.exportWithEpoch(context, 4, ExportMode.NEW_INTENT) { true }
    val firstBytes = first.file.readBytes()
    val sequence = values.controlState.importSequence
    values.endpoint = "https://push.example.invalid/changed-after-export"
    values.device = MollySocketDevice(2, "CHANGED-CREDENTIALS-AFTER-EXPORT")

    val rerendered = EnrollmentExporter.exportWithEpoch(context, null, ExportMode.RERENDER, first.requestId) { true }
    assertEquals(firstBytes.toList(), rerendered.file.readBytes().toList())
    assertEquals(sequence, values.controlState.importSequence)
    assertEquals(1, values.controlState.intents.size)
    assertEquals(first.requestId, rerendered.requestId)
  }

  @Test
  fun `failed publication retains its allocated counter and intent for rerender`() {
    val blocker = File(context.cacheDir, "external-files-is-a-file").apply { writeText("blocker") }
    val invalidExternalDir = object : ContextWrapper(context) {
      override fun getExternalFilesDir(type: String?): File = blocker
    }
    try {
      EnrollmentExporter.exportWithEpoch(invalidExternalDir, 4, ExportMode.NEW_INTENT) { true }
      throw AssertionError("expected file publication failure")
    } catch (_: java.io.IOException) {
      // The sequence and request identity must remain committed for the retry.
    }
    val saved = values.controlState.intents.single()
    assertEquals(1, values.controlState.importSequence)
    assertEquals(saved.sequence, values.controlState.importSequence)
    val recovered = EnrollmentExporter.exportWithEpoch(context, null, ExportMode.RERENDER, saved.requestId) { true }
    assertEquals(saved.requestId, recovered.requestId)
    assertEquals(1, values.controlState.importSequence)
  }

  @Test
  fun `corrupt local state refuses export before publishing a file`() {
    every { values.controlStateRead } returns ControlStateRead.Corrupt
    val dir = checkNotNull(context.getExternalFilesDir(null))
    try {
      EnrollmentExporter.exportWithEpoch(context, 4, ExportMode.NEW_INTENT) { true }
      throw AssertionError("expected state refusal")
    } catch (e: EnrollmentFile.Invalid) {
      assertEquals("state_unavailable", e.code)
    }
    assertFalse(File(dir, EnrollmentExporter.FILE_NAME).exists())
    assertFalse(File(dir, "${EnrollmentExporter.FILE_NAME}.tmp").exists())
  }
}
