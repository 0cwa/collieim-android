/*
 * Copyright 2026 Collie
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package im.molly.unifiedpush.health

import android.content.Context
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fasterxml.jackson.databind.ObjectMapper
import im.molly.unifiedpush.components.settings.app.notifications.UnifiedPushSettingsViewModel
import im.molly.unifiedpush.enrollment.EnrollmentExporter
import im.molly.unifiedpush.model.MollySocketDevice
import im.molly.unifiedpush.receiver.EncryptedPush
import im.molly.unifiedpush.receiver.LockedPushState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.SessionBuilder
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyType
import org.signal.libsignal.protocol.state.PreKeyBundle
import org.thoughtcrime.securesms.SignalInstrumentationApplicationContext
import org.thoughtcrime.securesms.crypto.ReentrantSessionLock
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.jobs.UnifiedPushHealthJob
import org.thoughtcrime.securesms.keyvalue.SettingsValues.ForceWebsocketMode
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.testing.SignalActivityRule
import org.thoughtcrime.securesms.testing.endpoints.EndpointResponse
import org.thoughtcrime.securesms.testing.endpoints.MockEndpoints
import org.thoughtcrime.securesms.testing.endpoints.put
import org.unifiedpush.android.connector.keys.DefaultKeyManager
import org.whispersystems.signalservice.api.crypto.SignalSessionBuilder
import java.io.File
import java.util.Base64
import java.util.UUID

/**
 * Host-driven (argument `ep_health`, see the script next to the pull request): pings through the app's real path to a
 * real CollieSocket, then forces failures. Stubbed: the Signal service (an in-process responder).
 *
 * 1. A ping that reaches CollieSocket is confirmed by a push and the test passes.
 * 2. The Signal send fails (the responder refuses it): the verdict names the Signal hop.
 * 3. The send goes out but is never delivered to CollieSocket: the verdict names CollieSocket or the provider, and the
 *    second failure in a row switches the app to WebSocket mode.
 * 4. Delivery works again, the ping passes, and the app returns to push.
 */
@RunWith(AndroidJUnit4::class)
class PushHealthE2eTest {

  @get:Rule
  val harness = SignalActivityRule(othersCount = 0)

  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val context: Context = instrumentation.targetContext
  private val args = InstrumentationRegistry.getArguments()
  private val values get() = SignalStore.unifiedpush
  private val token = "synthetic-connector-token"
  private val mapper = ObjectMapper()
  private val dir get() = context.getExternalFilesDir(null)!!

  private fun b64(s: String) = Base64.getUrlDecoder().decode(s)

  private fun waitFor(what: String, seconds: Int = 60, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + seconds * 1000L
    while (!condition()) {
      if (System.currentTimeMillis() > deadline) throw AssertionError("timed out waiting for $what")
      Thread.sleep(200)
    }
  }

  private fun registerConnector() {
    val keys = DefaultKeyManager(context)
    keys.generate("default")
    val set = keys.getPublicKeySet("default")!!
    check(context.getSharedPreferences("unifiedpush.connector", Context.MODE_PRIVATE).edit()
      .putStringSet("unifiedpush.instances", setOf("default"))
      .putString("default/unifiedpush.connector", token)
      .commit())
    values.device = MollySocketDevice(deviceId = 2, password = "SYNTHETIC-NOT-A-SIGNAL-CREDENTIAL")
    values.endpoint = args.getString("ep_endpoint")
    values.endpointInstance = "default"
    values.endpointP256dh = set.pubKey
    values.endpointAuth = set.auth
    values.enabled = true
    values.enrollmentEpoch = 0
    values.pendingEnrollmentEpoch = 0
    values.clearControl()
    values.replayState = null
    values.lastReceivedTime = 0
    values.health.failures = 0
    values.health.fallback = false
    values.health.lastResult = null
    values.health.lastResultAt = 0
    values.health.lastMissAt = 0
    LockedPushState.setEncryptedEnrollment(context, false)
  }

  private fun inject(body: ByteArray) {
    context.sendBroadcast(
      Intent("org.unifiedpush.android.connector.MESSAGE")
        .setPackage(context.packageName)
        .putExtra("token", token)
        .putExtra("id", UUID.randomUUID().toString())
        .putExtra("bytesMessage", body)
    )
    Thread.sleep(1500)
    instrumentation.waitForIdleSync()
  }

  private fun seen() = EncryptedPush.ReplayState.parse(values.replayState)!!.seen.map { it.eventId }

  private enum class Signal { DELIVER, REFUSE, DROP }

  @Test
  fun pingsNameTheFailingHopAndTwoFailuresSwitchToWebSocketMode() {
    assumeTrue(args.getString("ep_health") != null)
    registerConnector()
    UnifiedPushHealthJob.distributorActive = { true }
    // The fresh account queued jobs (billing, stickers) that need servers; drop them before the job loop starts.
    AppDependencies.jobManager.find { true }.forEach { AppDependencies.jobManager.cancel(it.id) }
    (AppDependencies.application as SignalInstrumentationApplicationContext).beginJobLoopForTests()
    val aci = SignalStore.account.requireAci().toString()

    // CollieSocket's linked device 2 has a session, as it has after the app created it.
    val identity: IdentityKeyPair = SignalStore.account.aciIdentityKey
    val signed = ECKeyPair.generate()
    val kyber = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
    val bundle = PreKeyBundle(
      1111, 2, PreKeyBundle.NULL_PRE_KEY_ID, null, 1, signed.publicKey,
      identity.privateKey.calculateSignature(signed.publicKey.serialize()), identity.publicKey, 1, kyber.publicKey,
      identity.privateKey.calculateSignature(kyber.publicKey.serialize())
    )
    SignalSessionBuilder(
      ReentrantSessionLock.INSTANCE,
      SessionBuilder(AppDependencies.protocolStore.aci(), SignalProtocolAddress(aci, 2), SignalProtocolAddress(aci, SignalStore.account.deviceId))
    ).process(bundle)

    var signal = Signal.DELIVER
    var sends = 0
    MockEndpoints.responder.put("/v1/messages/$aci") { request ->
      sends++
      when (signal) {
        Signal.REFUSE -> EndpointResponse(429, "")
        Signal.DROP -> EndpointResponse(200, """{"needsSync":false}""")
        Signal.DELIVER -> {
          val entry = mapper.readTree(request.bodyString)["messages"].single { it["destinationDeviceId"].asInt() == 2 }
          val frame = Base64.getDecoder().decode(entry["content"].asText())
          val epoch = java.nio.ByteBuffer.wrap(frame, 38, 8).long
          File(dir, "accepted-$epoch.json").writeText(request.bodyString)
          EndpointResponse(200, """{"needsSync":false}""")
        }
      }
    }

    // Enrollment proven by a first wake, as in the endpoint update test.
    File(dir, "inject-ids.txt").delete()
    EnrollmentExporter.export(context, null) { true }
    File(dir, "ready.txt").writeText("ready")
    waitFor("first wake", 180) { File(dir, "inject-ids.txt").exists() }
    inject(File(dir, "inject-wake.bin").readBytes())
    assertEquals(1L, values.enrollmentEpoch)
    assertNotNull(values.controlKey)

    val viewModel = UnifiedPushSettingsViewModel(context.applicationContext as android.app.Application)
    instrumentation.runOnMainSync { viewModel.state.observeForever { } } // a LiveData only updates while observed
    fun controlQueueReset() = AppDependencies.jobManager.cancelAllInQueue("UnifiedPushControlJob")
    fun state() = viewModel.state.value!!
    fun runPing(): PushHealth.Hop? {
      val before = values.health.lastResultAt
      Thread.sleep(1100) // result times are whole seconds
      instrumentation.runOnMainSync { viewModel.testPush() }
      waitFor("a test verdict", 120) { values.health.lastResultAt > before }
      instrumentation.runOnMainSync { viewModel.refresh() }
      return PushHealth.Hop.fromCode(values.health.lastResult)
    }

    // 1. Real round trip: the confirmation is a push CollieSocket sends to this endpoint.
    UnifiedPushHealthJob.timeoutMs = 60_000
    instrumentation.runOnMainSync { viewModel.testPush() }
    waitFor("the ping's confirmation body", 120) { File(dir, "inject-status-2.bin").exists() }
    inject(File(dir, "inject-status-2.bin").readBytes())
    waitFor("the verdict", 90) { values.health.lastResult == "ok" }
    assertEquals(0, values.health.failures)
    assertEquals(2L, values.enrollmentEpoch)
    assertNull(values.controlState.update)
    assertFalse(values.health.fallback)
    assertEquals(PushHealth.Hop.NONE, PushHealth.Hop.fromCode(values.health.lastResult))
    File(dir, "step1-ok.txt").writeText("ok")

    // 2. The Signal send fails: the verdict names the Signal hop.
    UnifiedPushHealthJob.timeoutMs = 12_000
    signal = Signal.REFUSE
    controlQueueReset()
    assertEquals(PushHealth.Hop.SIGNAL_SEND, runPing())
    assertEquals(1, values.health.failures)
    assertFalse(values.health.fallback)
    assertEquals(ForceWebsocketMode.DISABLED_AUTOMATICALLY, SignalStore.settings.forceWebsocketMode)
    File(dir, "step2-signal.txt").writeText("ok")

    // 3. The send goes out and is never delivered: the second failure in a row switches to WebSocket mode.
    signal = Signal.DROP
    controlQueueReset()
    val sendsBefore = sends
    assertEquals(PushHealth.Hop.COLLIESOCKET_OR_PROVIDER, runPing())
    assertTrue("nothing was sent", sends > sendsBefore)
    assertEquals(2, values.health.failures)
    assertTrue(values.health.fallback)
    assertEquals(ForceWebsocketMode.ENABLED_AUTOMATICALLY, SignalStore.settings.forceWebsocketMode)
    assertTrue("WebSocket mode must be forced", SignalStore.settings.forceWebsocketMode.isEnabled)
    instrumentation.runOnMainSync { viewModel.refresh() }
    Thread.sleep(500)
    assertTrue(state().pushFallback)
    assertEquals(PushHealth.Hop.COLLIESOCKET_OR_PROVIDER, state().pushTestResult)
    File(dir, "step3-fallback.txt").writeText("ok")

    // 4. Delivery works again: the same pending update is delivered, confirmed, and push is back.
    signal = Signal.DELIVER
    controlQueueReset()
    UnifiedPushHealthJob.timeoutMs = 60_000
    val before = values.health.lastResultAt
    Thread.sleep(1100)
    instrumentation.runOnMainSync { viewModel.testPush() }
    waitFor("the recovery confirmation body", 120) { File(dir, "inject-status-3.bin").exists() }
    inject(File(dir, "inject-status-3.bin").readBytes())
    waitFor("the recovery verdict", 90) { values.health.lastResultAt > before }
    assertEquals("ok", values.health.lastResult)
    assertFalse(values.health.fallback)
    assertEquals(0, values.health.failures)
    assertEquals(ForceWebsocketMode.DISABLED_AUTOMATICALLY, SignalStore.settings.forceWebsocketMode)
    assertEquals(3L, values.enrollmentEpoch)

    // 5. No distributor: the first hop is named without sending anything.
    UnifiedPushHealthJob.distributorActive = { false }
    val sendsNow = sends
    assertEquals(PushHealth.Hop.DISTRIBUTOR, runPing())
    assertEquals(sendsNow, sends)
    assertEquals(1, values.health.failures)
    UnifiedPushHealthJob.distributorActive = { true }
    File(dir, "result.txt").writeText("ok")
  }
}
