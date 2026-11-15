/*
 * Copyright 2026 Collie
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package im.molly.unifiedpush.receiver

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import im.molly.unifiedpush.control.ControlReset
import im.molly.unifiedpush.control.EndpointUpdates
import im.molly.unifiedpush.enrollment.EnrollmentExporter
import im.molly.unifiedpush.enrollment.ExportMode
import im.molly.unifiedpush.lifecycle.UnifiedPushAccountTransition
import im.molly.unifiedpush.lifecycle.UnifiedPushLifecycle
import im.molly.unifiedpush.model.MollySocketDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.net.Networking
import org.thoughtcrime.securesms.testing.SignalActivityRule
import org.unifiedpush.android.connector.UnifiedPush
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * Host-driven physical-phone test: registers this isolated build through stock ntfy, exports a synthetic v3
 * enrollment, then waits while CollieSocket sends a status and wake through the real ntfy distributor. The host
 * follows with a one-bit corrupted copy and an exact replay of the accepted ciphertext.
 *
 * The only files exchanged with the host are synthetic enrollment/test evidence under app-specific external files.
 */
@RunWith(AndroidJUnit4::class)
class NtfyV3PhysicalChainTest {

  private val harness = SignalActivityRule(othersCount = 0, networkEnabled = false)

  @get:Rule
  val isolatedHarness: TestRule = RuleChain.outerRule(object : ExternalResource() {
    override fun before() {
      assumeTrue("Physical push proof requires the isolated app", context.packageName == "im.collie.app.imo")
      val runId = InstrumentationRegistry.getArguments().getString("phoneproof_run_id")
      assumeTrue("Physical push proof requires an explicit run ID", runId?.matches(Regex("[0-9a-f]{32}")) == true)
    }
  }).around(harness)

  private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
  private val values get() = SignalStore.unifiedpush
  private val distributor = "io.heckel.ntfy"
  private val directory by lazy { File(context.filesDir, "phoneproof").apply { mkdirs() } }

  private fun file(name: String) = File(directory, name)

  private fun write(name: String, text: String) {
    file(name).writeText(text)
    file(name).setReadable(false, false)
    file(name).setReadable(true, true)
    file(name).setWritable(false, false)
    file(name).setWritable(true, true)
  }

  private fun waitFor(label: String, timeoutSeconds: Int = 180, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutSeconds * 1000L
    while (!condition()) {
      if (System.currentTimeMillis() > deadline) {
        throw AssertionError("timed out waiting for $label")
      }
      Thread.sleep(200)
    }
  }

  private fun replayState() = EncryptedPush.ReplayState.parse(values.replayState)
    ?: throw AssertionError("phone replay state unavailable")

  private fun snapshot(): String {
    val replay = replayState()
    val text = "lastReceivedTime=${values.lastReceivedTime}\n" +
      "replayCount=${replay.seen.size}\n" +
      "replaySha256=${sha256(replay.serialize())}\n" +
      "enrollmentEpoch=${values.enrollmentEpoch}\n" +
      "pendingEpoch=${values.pendingEnrollmentEpoch}\n" +
      "statusCode=${values.statusCode.orEmpty()}\n"
    return text
  }

  private fun sha256(text: String) = MessageDigest.getInstance("SHA-256")
    .digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

  @Test
  fun realNtfyDeliveryAcceptsWakeAndRejectsTamperAndReplay() {
    assertTrue("Signal network must remain disabled for the synthetic account", !Networking.isEnabled)
    listOf("phone.ready", "phone.context-state.txt", "phone.imported", "phone.valid-accepted", "phone.tamper-rejected", "phone.replay-rejected",
      "host.imported", "host.valid-sent", "host.valid-observed", "host.tamper-sent", "host.tamper-observed",
      "host.replay-sent", "host.replay-observed", "wake-event-id.txt",
      "colliesocket-enrollment.json", "state-valid.txt", "state-after-tamper.txt", "state-after-replay.txt")
      .forEach { file(it).delete() }

    val runId = requireNotNull(InstrumentationRegistry.getArguments().getString("phoneproof_run_id"))
    require(runId.matches(Regex("[0-9a-f]{32}")))
    ControlReset.discard(context)
    values.device = MollySocketDevice(deviceId = 2, password = "SYNTHETIC-NOT-A-SIGNAL-CREDENTIAL")
    values.enabled = true
    values.enrollmentEpoch = 0
    values.pendingEnrollmentEpoch = 0
    values.lastReceivedTime = 0
    values.setEndpointRegistration(null, null, null, null)
    LockedPushState.setEncryptedEnrollment(context, false)

    UnifiedPush.saveDistributor(context, distributor)
    UnifiedPush.registerApp(context)
    waitFor("ntfy endpoint and public key") {
      UnifiedPush.getAckDistributor(context) == distributor &&
        values.endpoint != null && values.endpointP256dh != null && values.endpointAuth != null
    }

    val exported = EnrollmentExporter.exportWithEpoch(
      context = context,
      serverEpoch = null,
      mode = ExportMode.NEW_INTENT,
      isDistributorActive = { UnifiedPush.getAckDistributor(context) == distributor },
    )
    val enrollment = File(context.getExternalFilesDir(null), EnrollmentExporter.FILE_NAME)
    assertTrue("synthetic enrollment was not exported", enrollment.isFile && enrollment.length() > 0)
    val protectedExport = file(EnrollmentExporter.FILE_NAME)
    enrollment.copyTo(protectedExport, overwrite = true)
    protectedExport.setReadable(false, false)
    protectedExport.setReadable(true, true)
    protectedExport.setWritable(false, false)
    protectedExport.setWritable(true, true)
    enrollment.delete()

    val setupState = values.controlState
    val pendingIntent = setupState.intents.firstOrNull { it.requestId == exported.requestId }
    val currentContext = synchronized(UnifiedPushLifecycle.monitor) {
      UnifiedPushAccountTransition.captureContextLocked()
    }
    val lifecycleReady = UnifiedPushLifecycle.isReady()
    val coherentContext = UnifiedPushAccountTransition.hasCoherentRegisteredContext()
    val contextMatchesIntent = pendingIntent != null && currentContext != null &&
      pendingIntent.accountId == currentContext.accountId &&
      pendingIntent.deviceId == currentContext.pushDevice?.deviceId &&
      pendingIntent.instance == currentContext.instance
    val intentMatchesExport = pendingIntent != null &&
      pendingIntent.requestId == exported.requestId && pendingIntent.epoch == exported.epoch
    write("phone.context-state.txt", listOf(
      "lifecycle_ready=$lifecycleReady",
      "lifecycle_generation=${UnifiedPushLifecycle.currentGeneration()}",
      "registered_context_coherent=$coherentContext",
      "receiver_context_present=${currentContext != null}",
      "relay_device_id=${currentContext?.pushDevice?.deviceId ?: -1}",
      "relay_instance=${currentContext?.instance ?: "missing"}",
      "pending_import_id=${pendingIntent?.requestId ?: "missing"}",
      "pending_epoch=${pendingIntent?.epoch ?: 0}",
      "pending_sequence=${pendingIntent?.sequence ?: 0}",
      "pending_eligible=${pendingIntent?.eligible ?: false}",
      "pending_matches_export=$intentMatchesExport",
      "pending_matches_receiver_context=$contextMatchesIntent",
      "active_import_present=${setupState.activeImportId != null}",
      "active_epoch=${setupState.activeEpoch}",
    ).joinToString("\n", postfix = "\n"))
    assertTrue("UnifiedPush lifecycle was not ready before publication", lifecycleReady)
    assertTrue("Synthetic account context was not coherent before publication", coherentContext)
    assertTrue("Exported import intent did not match the pending receiver intent", intentMatchesExport)
    assertTrue("Pending receiver intent did not match the current account and relay context", contextMatchesIntent)
    write("phone.ready", "$runId\n")

    waitFor("host enrollment import") { file("host.imported").exists() }
    waitFor("v3 enrollment confirmation") { values.enrollmentEpoch == 1L && values.controlState.activeImportId != null }
    write("phone.imported", snapshot())

    waitFor("host wake publish") { file("host.valid-sent").exists() }
    waitFor("accepted wake effect", condition = { values.lastReceivedTime > 0L && replayState().seen.size >= 2 })
    waitFor("external observer production receiver ACCEPT_WAKE") { file("host.valid-observed").exists() }
    val accepted = snapshot()
    assertEquals("enrollment changed while accepting wake", 1L, values.enrollmentEpoch)
    write("state-valid.txt", accepted)
    write("phone.valid-accepted", accepted)

    waitFor("tampered ciphertext publish") { file("host.tamper-sent").exists() }
    waitFor("external observer connector decryption rejection") { file("host.tamper-observed").exists() }
    val afterTamper = snapshot()
    assertEquals("tampered ciphertext changed app state", accepted, afterTamper)
    write("state-after-tamper.txt", afterTamper)
    write("phone.tamper-rejected", "DROP_NOT_DECRYPTED; app state unchanged\n")

    waitFor("exact replay publish") { file("host.replay-sent").exists() }
    waitFor("host wake event id") { file("wake-event-id.txt").isFile }
    waitFor("external observer production receiver DROP_DUPLICATE") { file("host.replay-observed").exists() }
    val afterReplay = snapshot()
    assertEquals("replay changed app state", accepted, afterReplay)

    val eventId = file("wake-event-id.txt").readText().trim()
    val state = values.controlState
    val duplicateJson = """{"version":3,"event_id":"$eventId","instance":"${values.endpointInstance ?: "default"}","subscription_epoch":${state.activeEpoch},"kind":"wake","issued_at":${System.currentTimeMillis() / 1000},"expires_at":${System.currentTimeMillis() / 1000 + 300},"key_id":"${state.activeKeyId}","import_id":"${state.activeImportId}"}"""
    val current = state.activeAccountId?.let {
      EndpointUpdates.RegistrationContext(it, state.activeDeviceId!!, state.activeInstance!!)
    }
    val duplicateDecision = EncryptedPush.decide(
      content = duplicateJson.toByteArray(),
      decrypted = true,
      now = System.currentTimeMillis() / 1000,
      locked = { false },
      activeInstance = values.endpointInstance ?: "default",
      acceptedEpochs = EndpointUpdates.acceptedEpochs(state),
      state = replayState(),
      versionsForEpoch = { epoch -> EndpointUpdates.versionsForEpoch(state, epoch) },
      identityMatches = { event -> EndpointUpdates.identityMatches(state, event, current) },
    ).decision
    assertEquals(EncryptedPush.Decision.DROP_DUPLICATE, duplicateDecision)
    write("state-after-replay.txt", afterReplay + "duplicateDecision=$duplicateDecision\n")
    write("phone.replay-rejected", afterReplay + "duplicateDecision=$duplicateDecision\n")
  }
}
