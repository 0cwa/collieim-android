package im.molly.unifiedpush.enrollment

import android.app.Application
import android.content.Context
import com.fasterxml.jackson.databind.ObjectMapper
import im.molly.unifiedpush.model.MollySocketDevice
import io.mockk.every
import io.mockk.mockk
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
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.signal.core.models.ServiceId
import org.signal.core.util.logging.Log
import org.signal.storage.keyvalue.KeyValueDataSet
import org.signal.storage.keyvalue.KeyValueStore
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.keyvalue.UnifiedPushValues
import org.thoughtcrime.securesms.testutil.MockSignalStoreRule
import org.thoughtcrime.securesms.testutil.SnapshotKeyValuePersistentStorage
import org.thoughtcrime.securesms.testutil.SystemOutLogger
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [35])
class EnrollmentExporterPersistenceTest {
  @get:Rule val signalStore = MockSignalStoreRule()

  private val context: Context get() = RuntimeEnvironment.getApplication()
  private val accountId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
  private val endpoint = "https://push.example.invalid/synthetic-endpoint"
  private val p256dh = "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4"
  private val auth = "BTBZMqHH6r4Tts7J_aSIgg"
  private lateinit var storage: SnapshotKeyValuePersistentStorage
  private lateinit var executor: CountingExecutor
  private lateinit var values: UnifiedPushValues

  @Before
  fun setUp() {
    Log.initialize(SystemOutLogger())
    storage = SnapshotKeyValuePersistentStorage(KeyValueDataSet())
    executor = CountingExecutor()
    values = UnifiedPushValues(KeyValueStore(storage, executor))
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
    values.store.blockUntilAllWritesFinished()
  }

  @After
  fun tearDown() {
    EnrollmentExporter.removeFiles(context)
    executor.shutdown()
  }

  @Test
  fun `new intent is persisted with replay state before publication and survives restart`() {
    val export = EnrollmentExporter.exportWithEpoch(context, 4, ExportMode.NEW_INTENT) { true }
    val persisted = storage.getDataSet()

    assertTrue(storage.lastWriteKeys.containsAll(setOf("up.control.state", "up.enrollment.replay")))
    assertNotNull(persisted.getString("up.control.state", null))
    assertNotNull(persisted.getString("up.enrollment.replay", null))
    assertTrue(export.file.exists())

    val restored = UnifiedPushValues(KeyValueStore(storage, Executor { it.run() }))
    assertEquals(export.requestId, restored.controlState.intents.single().requestId)
    assertEquals(1, restored.controlState.importSequence)
    assertNotNull(restored.replayState)
  }

  @Test
  fun `database failure leaves cache and enrollment file unchanged`() {
    val before = values.controlState
    storage.nextWriteFailure = IllegalStateException("synthetic database write failure")

    val failure = runCatching {
      EnrollmentExporter.exportWithEpoch(context, 4, ExportMode.NEW_INTENT) { true }
    }.exceptionOrNull()

    assertNotNull("the failed database result must reach the caller", failure)
    assertFalse(File(checkNotNull(context.getExternalFilesDir(null)), EnrollmentExporter.FILE_NAME).exists())
    assertEquals(before, values.controlState)
    assertEquals(before, UnifiedPushValues(KeyValueStore(storage, Executor { it.run() })).controlState)
    assertNull(values.replayState)
  }

  @Test
  fun `interrupted wait cannot publish while the control write is queued`() {
    val blockerStarted = CountDownLatch(1)
    val releaseBlocker = CountDownLatch(1)
    val baseSubmissions = executor.submittedCount
    executor.execute {
      blockerStarted.countDown()
      check(releaseBlocker.await(10, TimeUnit.SECONDS))
    }
    assertTrue(blockerStarted.await(5, TimeUnit.SECONDS))

    val finished = CountDownLatch(1)
    val result = AtomicReference<EnrollmentExporter.Export?>()
    val failure = AtomicReference<Throwable?>()
    val thread = Thread {
      try {
        result.set(EnrollmentExporter.exportWithEpoch(context, 4, ExportMode.NEW_INTENT) { true })
      } catch (e: Throwable) {
        failure.set(e)
      } finally {
        finished.countDown()
      }
    }
    thread.start()

    assertTrue(executor.awaitSubmissions(baseSubmissions + 2, 5, TimeUnit.SECONDS))
    thread.interrupt()
    assertFalse("an interrupted queued write must not be treated as complete", finished.await(300, TimeUnit.MILLISECONDS))
    assertFalse(File(checkNotNull(context.getExternalFilesDir(null)), EnrollmentExporter.FILE_NAME).exists())

    releaseBlocker.countDown()
    assertTrue(finished.await(10, TimeUnit.SECONDS))
    thread.join(1000)
    assertNull(result.get())
    assertNotNull("the interrupted persistence wait must fail", failure.get())
    assertFalse(File(checkNotNull(context.getExternalFilesDir(null)), EnrollmentExporter.FILE_NAME).exists())

    val state = values.controlState
    assertEquals(1, state.importSequence)
    val intent = state.intents.single()
    assertEquals(intent.requestId, UnifiedPushValues(KeyValueStore(storage, Executor { it.run() })).controlState.intents.single().requestId)
    assertNotNull(values.replayState)

    val rerendered = EnrollmentExporter.exportWithEpoch(context, null, ExportMode.RERENDER, intent.requestId) { true }
    assertEquals(intent.requestId, rerendered.requestId)
    assertTrue(rerendered.file.exists())
  }

  @Test
  fun `rerender initializes missing replay state durably before publishing`() {
    val original = EnrollmentExporter.exportWithEpoch(context, 4, ExportMode.NEW_INTENT) { true }
    storage.removePersistedKey("up.enrollment.replay")
    values.store.resetCache()
    assertNull(values.replayState)
    storage.nextWriteFailure = IllegalStateException("synthetic replay initialization failure")

    val failure = runCatching {
      EnrollmentExporter.exportWithEpoch(context, null, ExportMode.RERENDER, original.requestId) { true }
    }.exceptionOrNull()

    assertNotNull("replay initialization failure must stop publication", failure)
    assertFalse(File(checkNotNull(context.getExternalFilesDir(null)), EnrollmentExporter.FILE_NAME).exists())
    assertNull(values.replayState)
    assertEquals(original.requestId, values.controlState.intents.single().requestId)
  }

  private class CountingExecutor : Executor {
    private val delegate = Executors.newSingleThreadExecutor()
    private val lock = Object()
    private val submitted = AtomicInteger()
    val submittedCount: Int get() = submitted.get()

    override fun execute(command: Runnable) {
      submitted.incrementAndGet()
      synchronized(lock) { lock.notifyAll() }
      delegate.execute(command)
    }

    fun awaitSubmissions(target: Int, timeout: Long, unit: TimeUnit): Boolean {
      val deadline = System.nanoTime() + unit.toNanos(timeout)
      synchronized(lock) {
        while (submitted.get() < target) {
          val remaining = deadline - System.nanoTime()
          if (remaining <= 0) return false
          TimeUnit.NANOSECONDS.timedWait(lock, remaining)
        }
      }
      return true
    }

    fun shutdown() {
      delegate.shutdownNow()
      delegate.awaitTermination(5, TimeUnit.SECONDS)
    }
  }

}
