/*
 * Copyright 2026 Collie
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package im.molly.unifiedpush.enrollment

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fasterxml.jackson.databind.ObjectMapper
import im.molly.unifiedpush.model.MollySocketDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.testing.SignalActivityRule
import java.io.File

/**
 * Runs the real export path against synthetic state (the rule registers a fake self; nothing talks to Signal).
 * Optional instrumentation arguments `ep_endpoint`, `ep_p256dh`, `ep_auth` seed a provider endpoint whose
 * keys the test harness holds, and the file is left in place so the harness can `adb pull` it.
 */
@RunWith(AndroidJUnit4::class)
class EnrollmentExportTest {

  @get:Rule
  val harness = SignalActivityRule(othersCount = 0)

  private val args = InstrumentationRegistry.getArguments()
  private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

  private fun seed(withKeys: Boolean = true) {
    SignalStore.unifiedpush.device = MollySocketDevice(deviceId = 2, password = "SYNTHETIC-NOT-A-SIGNAL-CREDENTIAL")
    SignalStore.unifiedpush.endpoint = args.getString("ep_endpoint") ?: "https://push.example.invalid/synthetic-endpoint"
    SignalStore.unifiedpush.endpointInstance = "default"
    SignalStore.unifiedpush.endpointP256dh = if (withKeys) args.getString("ep_p256dh") ?: "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4" else null
    SignalStore.unifiedpush.endpointAuth = if (withKeys) args.getString("ep_auth") ?: "BTBZMqHH6r4Tts7J_aSIgg" else null
    SignalStore.unifiedpush.enrollmentEpoch = 0
  }

  private val active = { true }
  private val dir get() = context.getExternalFilesDir(null)
  private fun epochOf(file: File) = ObjectMapper().readTree(file.readText())["subscription"]["subscription_epoch"].asInt()

  @Test
  fun exportWritesTheContractFileToTheAppSpecificExternalDirectory() {
    seed()

    // Optional argument: the epoch CollieSocket reports for the account (absent: not enrolled).
    val serverEpoch = args.getString("ep_server_epoch")?.toLong()
    val file = EnrollmentExporter.export(context, serverEpoch, active)

    assertEquals(File(dir, EnrollmentExporter.FILE_NAME), file)
    assertFalse(File(dir, EnrollmentExporter.FILE_NAME + ".tmp").exists())
    val json = ObjectMapper().readTree(file.readText())
    assertEquals(1, json["enrollment_version"].asInt())
    assertEquals(SignalStore.account.aci.toString(), json["account_id"].asText())
    assertEquals(2, json["device_id"].asInt())
    assertEquals((serverEpoch ?: 0) + 1, json["subscription"]["subscription_epoch"].asLong())
    assertEquals(SignalStore.unifiedpush.endpoint, json["subscription"]["endpoint"].asText())
    assertEquals((serverEpoch ?: 0) + 1, SignalStore.unifiedpush.enrollmentEpoch)

    // A second export replaces the file with a fresh request id.
    val second = ObjectMapper().readTree(EnrollmentExporter.export(context, serverEpoch, active).readText())
    assertTrue(second["request_id"].asText() != json["request_id"].asText())
  }

  @Test
  fun enteredServerEpochGivesTheNextEpoch() {
    seed()
    assertEquals(1, epochOf(EnrollmentExporter.export(context, null, active)))
    assertEquals(2, epochOf(EnrollmentExporter.export(context, 1, active)))
    assertEquals(2L, SignalStore.unifiedpush.enrollmentEpoch)
    assertEquals(8, epochOf(EnrollmentExporter.export(context, 7, active)))
  }

  @Test
  fun refusedExportsRemoveTheEarlierFileAndWriteNothing() {
    seed()

    fun assertRefused(code: String, block: () -> Unit) {
      File(dir, EnrollmentExporter.FILE_NAME).writeText("previous export")
      File(dir, EnrollmentExporter.FILE_NAME + ".tmp").writeText("partial")
      try {
        block()
        fail("expected refusal")
      } catch (e: EnrollmentFile.Invalid) {
        assertEquals(code, e.code)
      }
      assertFalse(File(dir, EnrollmentExporter.FILE_NAME).exists())
      assertFalse(File(dir, EnrollmentExporter.FILE_NAME + ".tmp").exists())
    }

    assertRefused("invalid_epoch") { EnrollmentExporter.export(context, -1, active) }
    assertRefused("invalid_epoch") { EnrollmentExporter.export(context, 9007199254740991L, active) }
    assertRefused("not_ready") { EnrollmentExporter.export(context, null) { false } }
    SignalStore.unifiedpush.endpointAuth = null
    assertRefused("not_ready") { EnrollmentExporter.export(context, null, active) }
  }

  @Test
  fun deleteStaleRemovesAnEarlierExport() {
    seed()
    val file = EnrollmentExporter.export(context, null, active)
    assertTrue(file.exists())

    EnrollmentExporter.deleteStale(context)

    val deadline = System.currentTimeMillis() + 5000
    while (file.exists() && System.currentTimeMillis() < deadline) Thread.sleep(50)
    assertFalse(file.exists())
  }
}
