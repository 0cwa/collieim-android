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
    SignalStore.unifiedpush.pendingEnrollmentEpoch = 0
  }

  private val active = { true }
  private val dir get() = context.getExternalFilesDir(null)
  private fun export(serverEpoch: Long?) = EnrollmentExporter.exportWithEpoch(
    context,
    serverEpoch,
    ExportMode.NEW_INTENT,
    isDistributorActive = active,
  )
  private fun epochOf(file: File) = ObjectMapper().readTree(file.readText())["subscription"]["subscription_epoch"].asInt()

  @Test
  fun exportWritesTheContractFileToTheAppSpecificExternalDirectory() {
    seed()

    // Optional argument: the epoch CollieSocket reports for the account (absent: not enrolled).
    val serverEpoch = args.getString("ep_server_epoch")?.toLong()
    val first = export(serverEpoch)
    val file = first.file

    assertEquals(File(dir, EnrollmentExporter.FILE_NAME), file)
    assertFalse(File(dir, EnrollmentExporter.FILE_NAME + ".tmp").exists())
    val json = ObjectMapper().readTree(file.readText())
    assertEquals(3, json["enrollment_version"].asInt())
    assertEquals(first.requestId, json["request_id"].asText())
    assertTrue(json["import_seq"].asLong() > 0)
    assertEquals(SignalStore.account.aci.toString(), json["account_id"].asText())
    assertEquals(2, json["device_id"].asInt())
    assertEquals((serverEpoch ?: 0) + 1, json["subscription"]["subscription_epoch"].asLong())
    assertEquals(SignalStore.unifiedpush.endpoint, json["subscription"]["endpoint"].asText())
    assertEquals((serverEpoch ?: 0) + 1, SignalStore.unifiedpush.pendingEnrollmentEpoch)

    // A second export replaces the file with a fresh request id.
    val second = export(serverEpoch)
    val secondJson = ObjectMapper().readTree(second.file.readText())
    assertTrue(secondJson["request_id"].asText() != json["request_id"].asText())
    assertEquals(json["control_key"].asText(), secondJson["control_key"].asText())
    assertTrue(secondJson["import_seq"].asLong() > json["import_seq"].asLong())
  }

  @Test
  fun enteredServerEpochGivesTheNextEpoch() {
    seed()
    assertEquals(1, epochOf(export(null).file))
    assertEquals(2, epochOf(export(1).file))
    assertEquals(2L, SignalStore.unifiedpush.pendingEnrollmentEpoch)
    assertEquals(8, epochOf(export(7).file))
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

    assertRefused("invalid_epoch") { EnrollmentExporter.exportWithEpoch(context, -1, ExportMode.NEW_INTENT, isDistributorActive = active) }
    assertRefused("invalid_epoch") { EnrollmentExporter.exportWithEpoch(context, 9007199254740991L, ExportMode.NEW_INTENT, isDistributorActive = active) }
    assertRefused("not_ready") { EnrollmentExporter.exportWithEpoch(context, null, ExportMode.NEW_INTENT, isDistributorActive = { false }) }
    SignalStore.unifiedpush.endpointAuth = null
    assertRefused("not_ready") { export(null) }
  }

  @Test
  fun deleteStaleRemovesAnEarlierExport() {
    seed()
    val file = export(null).file
    assertTrue(file.exists())

    EnrollmentExporter.deleteStale(context)

    val deadline = System.currentTimeMillis() + 5000
    while (file.exists() && System.currentTimeMillis() < deadline) Thread.sleep(50)
    assertFalse(file.exists())
  }

  @Test
  fun anOverflowingEpochInTheDialogStillRemovesTheOldFile() {
    seed()
    val file = export(null).file
    assertTrue(file.exists())

    im.molly.unifiedpush.components.settings.app.notifications.UnifiedPushSettingsViewModel(context.applicationContext as android.app.Application)
      .exportEnrollment(context, "99999999999999999999", ExportMode.NEW_INTENT)

    val deadline = System.currentTimeMillis() + 5000
    while (file.exists() && System.currentTimeMillis() < deadline) Thread.sleep(50)
    assertFalse(file.exists())
  }
}
