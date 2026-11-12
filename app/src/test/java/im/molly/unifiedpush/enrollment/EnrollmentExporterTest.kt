/*
 * Copyright 2026 Collie
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package im.molly.unifiedpush.enrollment

import android.app.Application
import android.content.Context
import com.fasterxml.jackson.databind.ObjectMapper
import im.molly.unifiedpush.model.MollySocketDevice
import io.mockk.every
import io.mockk.mockk
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

  @get:Rule
  val signalStore = MockSignalStoreRule()

  private val context: Context get() = RuntimeEnvironment.getApplication()
  private val values = UnifiedPushValues(KeyValueStore(MockKeyValuePersistentStorage.withDataSet(KeyValueDataSet())))

  @Before
  fun setUp() {
    Log.initialize(SystemOutLogger())
    every { SignalStore.unifiedpush } returns values
    val aci = mockk<ServiceId.ACI>()
    every { aci.toString() } returns "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    every { signalStore.account.aci } returns aci

    values.device = MollySocketDevice(2, "SYNTHETIC-NOT-A-SIGNAL-CREDENTIAL")
    values.endpoint = "https://push.example.invalid/synthetic-endpoint"
    values.endpointInstance = "default"
    values.endpointP256dh = "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4"
    values.endpointAuth = "BTBZMqHH6r4Tts7J_aSIgg"
    values.mutateControl { it.copy(highestEpoch = 8) }
  }

  @Test
  fun `concurrent exports publish a file matching the persisted pending key`() {
    val workerCount = 6
    val ready = CountDownLatch(workerCount)
    val start = CountDownLatch(1)
    val exports = ConcurrentLinkedQueue<EnrollmentExporter.Export>()
    val failures = ConcurrentLinkedQueue<Throwable>()
    val pool = Executors.newFixedThreadPool(workerCount)

    repeat(workerCount) {
      pool.execute {
        ready.countDown()
        try {
          if (!start.await(5, TimeUnit.SECONDS)) throw AssertionError("exports did not start")
          exports.add(EnrollmentExporter.exportWithEpoch(context, 4) { true })
        } catch (failure: Throwable) {
          failures.add(failure)
        }
      }
    }

    assertTrue("export workers are ready", ready.await(5, TimeUnit.SECONDS))
    start.countDown()
    pool.shutdown()
    assertTrue("exports finish", pool.awaitTermination(20, TimeUnit.SECONDS))
    assertTrue("every export succeeds: ${failures.map { it.javaClass.simpleName }}", failures.isEmpty())
    assertEquals(workerCount, exports.size)

    val dir = checkNotNull(context.getExternalFilesDir(null))
    val target = File(dir, EnrollmentExporter.FILE_NAME)
    val json = ObjectMapper().readTree(target.readText())
    assertFalse(File(dir, "${EnrollmentExporter.FILE_NAME}.tmp").exists())
    assertEquals(5L, json["subscription"]["subscription_epoch"].asLong())
    assertEquals(5L, values.controlState.pendingEpoch)
    assertEquals(json["control_key"].asText(), values.controlState.pendingKey)
    assertTrue(exports.all { it.file == target && it.epoch == 5L && it.highestUsed == 8L && it.collides })
  }
}
