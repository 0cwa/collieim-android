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
    SignalStore.unifiedpush.enrollmentFingerprint = null
  }

  @Test
  fun exportWritesTheContractFileToTheAppSpecificExternalDirectory() {
    seed()

    val file = EnrollmentExporter.export(context)

    assertEquals(File(context.getExternalFilesDir(null), EnrollmentExporter.FILE_NAME), file)
    assertFalse(File(context.getExternalFilesDir(null), EnrollmentExporter.FILE_NAME + ".tmp").exists())
    val json = ObjectMapper().readTree(file.readText())
    assertEquals(1, json["enrollment_version"].asInt())
    assertEquals(SignalStore.account.aci.toString(), json["account_id"].asText())
    assertEquals(2, json["device_id"].asInt())
    assertEquals(1, json["subscription"]["subscription_epoch"].asInt())
    assertEquals(SignalStore.unifiedpush.endpoint, json["subscription"]["endpoint"].asText())

    // A second export replaces the file with a fresh request id and keeps the epoch for unchanged data.
    val second = ObjectMapper().readTree(EnrollmentExporter.export(context).readText())
    assertTrue(second["request_id"].asText() != json["request_id"].asText())
    assertEquals(1, second["subscription"]["subscription_epoch"].asInt())
  }

  @Test
  fun exportWithoutEndpointKeysRefusesAndWritesNothing() {
    seed(withKeys = false)
    File(context.getExternalFilesDir(null), EnrollmentExporter.FILE_NAME).delete()

    try {
      EnrollmentExporter.export(context)
      fail("expected refusal")
    } catch (e: EnrollmentFile.Invalid) {
      assertEquals("not_ready", e.code)
    }
    assertFalse(File(context.getExternalFilesDir(null), EnrollmentExporter.FILE_NAME).exists())
  }

  @Test
  fun deleteStaleRemovesAnEarlierExport() {
    seed()
    val file = EnrollmentExporter.export(context)
    assertTrue(file.exists())

    EnrollmentExporter.deleteStale(context)

    val deadline = System.currentTimeMillis() + 5000
    while (file.exists() && System.currentTimeMillis() < deadline) Thread.sleep(50)
    assertFalse(file.exists())
  }
}
