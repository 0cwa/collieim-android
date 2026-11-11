/*
 * Copyright 2026 Collie
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package im.molly.unifiedpush.control

import android.content.Context
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fasterxml.jackson.databind.ObjectMapper
import im.molly.unifiedpush.enrollment.EnrollmentExporter
import im.molly.unifiedpush.model.MollySocketDevice
import im.molly.unifiedpush.receiver.EncryptedPush
import im.molly.unifiedpush.receiver.LockedPushState
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
import org.thoughtcrime.securesms.ApplicationContext
import org.thoughtcrime.securesms.SignalInstrumentationApplicationContext
import org.thoughtcrime.securesms.crypto.ReentrantSessionLock
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.service.KeyCachingService
import org.thoughtcrime.securesms.testing.SignalActivityRule
import org.thoughtcrime.securesms.testing.endpoints.EndpointResponse
import org.thoughtcrime.securesms.testing.endpoints.MockEndpoints
import org.thoughtcrime.securesms.testing.endpoints.get
import org.thoughtcrime.securesms.testing.endpoints.put
import org.unifiedpush.android.connector.keys.DefaultKeyManager
import org.whispersystems.signalservice.api.crypto.SignalSessionBuilder
import java.io.File
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Drives the app's real endpoint-update path on the emulator: the connector's NEW_ENDPOINT broadcast, the stored
 * update, [org.thoughtcrime.securesms.jobs.UnifiedPushControlJob] and the real `SignalServiceMessageSender`
 * building the per-device request, then the connector's MESSAGE broadcast for the confirmation and a later wake.
 *
 * Stubbed: the Signal service. The test harness answers the sender's websocket requests (`PUT /v1/messages/{aci}`,
 * `GET /v2/keys/{aci}/{device}`) from an in-process responder, which records the request body for the host script
 * to hand to a real CollieSocket. Nothing here is a real Signal account or credential.
 */
@RunWith(AndroidJUnit4::class)
class EndpointUpdateTest {

  @get:Rule
  val harness = SignalActivityRule(othersCount = 0)

  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val context: Context = instrumentation.targetContext
  private val args = InstrumentationRegistry.getArguments()
  private val values get() = SignalStore.unifiedpush
  private val token = "synthetic-connector-token"
  private val mapper = ObjectMapper()
  private val dir get() = context.getExternalFilesDir(null)!!

  private class Remote(val deviceId: Int, val registrationId: Int) {
    // All devices of an account share its identity key.
    val identity: IdentityKeyPair = SignalStore.account.aciIdentityKey
    private val signed = ECKeyPair.generate()
    private val signature = identity.privateKey.calculateSignature(signed.publicKey.serialize())
    private val kyber = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
    private val kyberSignature = identity.privateKey.calculateSignature(kyber.publicKey.serialize())

    fun bundle() = PreKeyBundle(registrationId, deviceId, PreKeyBundle.NULL_PRE_KEY_ID, null, 1, signed.publicKey, signature, identity.publicKey, 1, kyber.publicKey, kyberSignature)

    /** The body of `GET /v2/keys/{aci}/{device}` for this device. */
    fun keysResponse(): String {
      fun enc(bytes: ByteArray) = Base64.getEncoder().withoutPadding().encodeToString(bytes)
      return """{"identityKey":"${enc(identity.publicKey.serialize())}","devices":[{"deviceId":$deviceId,"registrationId":$registrationId,""" +
        """"signedPreKey":{"keyId":1,"publicKey":"${enc(signed.publicKey.serialize())}","signature":"${enc(signature)}"},""" +
        """"pqPreKey":{"keyId":1,"publicKey":"${enc(kyber.publicKey.serialize())}","signature":"${enc(kyberSignature)}"}}]}"""
    }
  }

  private fun b64(s: String) = Base64.getUrlDecoder().decode(s)
  private fun now() = System.currentTimeMillis() / 1000

  private fun waitFor(what: String, seconds: Int = 60, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + seconds * 1000L
    while (!condition()) {
      if (System.currentTimeMillis() > deadline) throw AssertionError("timed out waiting for $what")
      Thread.sleep(200)
    }
  }

  private fun process(remote: Remote) {
    val aci = SignalStore.account.requireAci().toString()
    val local = SignalProtocolAddress(aci, SignalStore.account.deviceId)
    SignalSessionBuilder(ReentrantSessionLock.INSTANCE, SessionBuilder(AppDependencies.protocolStore.aci(), SignalProtocolAddress(aci, remote.deviceId), local)).process(remote.bundle())
  }

  private fun registerConnector(): Pair<ByteArray, ByteArray> {
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
    LockedPushState.setEncryptedEnrollment(context, false)
    return b64(set.pubKey) to b64(set.auth)
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

  /**
   * Host-driven (argument `ep_rotate`): a real CollieSocket applies the update the app sends and confirms it.
   * Order: the first wake proves the exported enrollment; the endpoint then changes while the app is locked, nothing
   * is sent; after unlock the update goes out through a 409 and a 410 and arrives with the identical blob; the
   * confirmation and a wake at the new epoch, both pushed to the new endpoint, are accepted.
   */
  @Test
  fun anEndpointRotationIsAppliedByCollieSocketAndConfirmed() {
    assumeTrue(args.getString("ep_rotate") != null)
    val rotatedUrl = args.getString("ep_rotated")!!
    registerConnector()
    // The instrumentation application context does not start the job loop by itself. Jobs the fresh account queued
    // (sticker packs, key sync) would only compete for the runners, so they are dropped first.
    AppDependencies.jobManager.find { true }.forEach { AppDependencies.jobManager.cancel(it.id) }
    (AppDependencies.application as SignalInstrumentationApplicationContext).beginJobLoopForTests()
    val aci = SignalStore.account.requireAci().toString()
    assertEquals("the local device is the primary or a linked one that is not 2 or 3", true, SignalStore.account.deviceId !in listOf(2, 3))

    // Sessions: CollieSocket's device 2 (registration ID 1111) and one ordinary linked device 3.
    val colliesocket = Remote(2, 1111)
    val laptop = Remote(3, 3333)
    val relinked = Remote(2, 2222)
    process(colliesocket)
    process(laptop)

    data class Seen(val path: String, val body: String)
    val requests = CopyOnWriteArrayList<Seen>()
    MockEndpoints.responder.get("/v2/keys/$aci/2") { EndpointResponse(200, relinked.keysResponse()) }
    MockEndpoints.responder.put("/v1/messages/$aci") { request ->
      requests += Seen(request.path, request.bodyString)
      val entries = mapper.readTree(request.bodyString)["messages"]
      val control = entries.single { it["destinationDeviceId"].asInt() == 2 }
      when (requests.size) {
        1 -> EndpointResponse(409, """{"missingDevices":[],"extraDevices":[9]}""")
        2 -> EndpointResponse(410, """{"staleDevices":[2]}""")
        else -> if (control["destinationRegistrationId"].asInt() != relinked.registrationId) EndpointResponse(410, """{"staleDevices":[2]}""")
        else {
          File(dir, "control-request.json").writeText(request.bodyString)
          EndpointResponse(200, """{"needsSync":false}""")
        }
      }
    }

    // Export and first wake: the enrollment is proven, the control key becomes the active one.
    File(dir, "inject-ids.txt").delete(); File(dir, "inject-ids2.txt").delete(); File(dir, "control-request.json").delete()
    EnrollmentExporter.export(context, null) { true }
    assertNotNull(values.pendingControlKey)
    assertNull(values.controlKey)
    File(dir, "ready.txt").writeText("ready")
    waitFor("first wake", 180) { File(dir, "inject-ids.txt").exists() }
    val firstWake = File(dir, "inject-ids.txt").readLines()[0]
    inject(File(dir, "inject-wake.bin").readBytes())
    assertTrue("first wake not recorded", seen().contains(firstWake))
    assertEquals(1L, values.enrollmentEpoch)
    assertNotNull(values.controlKey)
    assertNull(values.pendingControlKey)
    assertNull(values.controlUpdate)

    // The distributor hands out a new endpoint and new keys while the app is locked: it is kept, nothing is sent.
    val secret = KeyCachingService.getMasterSecret()
    try {
      KeyCachingService.clearMasterSecret()
      assertTrue(KeyCachingService.isLocked())
      DefaultKeyManager(context).generate("default")
      context.sendBroadcast(
        Intent("org.unifiedpush.android.connector.NEW_ENDPOINT")
          .setPackage(context.packageName)
          .putExtra("token", token)
          .putExtra("id", UUID.randomUUID().toString())
          .putExtra("endpoint", rotatedUrl)
      )
      Thread.sleep(3000)
      instrumentation.waitForIdleSync()
      val pending = context.getSharedPreferences("collie_locked_push", Context.MODE_PRIVATE).getString("pending_endpoint", null)
      assertNotNull("endpoint not kept while locked", pending)
      assertFalse(pending!!.contains(rotatedUrl.substringAfterLast('/')))
      assertTrue("a locked phone sent something", requests.isEmpty())
    } finally {
      KeyCachingService.setMasterSecret(secret)
    }
    assertNotEquals(rotatedUrl, values.endpoint)
    assertNull(values.controlUpdate)

    // Unlock: the endpoint is applied, the update is built at epoch 2 and sent.
    instrumentation.runOnMainSync { ApplicationContext.getInstance(context).onUnlock() }
    waitFor("the update to be stored") { values.controlUpdate != null }
    val update = ControlUpdate.parse(values.controlUpdate)!!
    assertEquals(2L, update.epoch)
    assertEquals(rotatedUrl, update.endpoint)
    assertEquals(2L, values.controlHighestEpoch)
    assertEquals("enrollment epoch stays until the confirmation", 1L, values.enrollmentEpoch)
    waitFor("the request CollieSocket's copy", 120) { File(dir, "control-request.json").exists() }

    // Three attempts on the wire: 409 then 410 then accepted. Same blob each time, only the registration ID changed.
    assertEquals(3, requests.size)
    val entries = requests.map { mapper.readTree(it.body) }
    entries.forEach {
      assertFalse("urgent", it["urgent"].asBoolean())
      assertFalse("online", it["online"].asBoolean())
      assertEquals(aci, it["destination"].asText())
    }
    fun control(i: Int) = entries[i]["messages"].single { it["destinationDeviceId"].asInt() == 2 }
    for (i in 0..2) {
      assertEquals(1, control(i)["type"].asInt())
      assertArrayEquals(update.frame, Base64.getDecoder().decode(control(i)["content"].asText()))
    }
    assertEquals(1111, control(0)["destinationRegistrationId"].asInt())
    assertEquals(2222, control(2)["destinationRegistrationId"].asInt())
    val other = entries[2]["messages"].single { it["destinationDeviceId"].asInt() == 3 }
    assertEquals(3333, other["destinationRegistrationId"].asInt())
    assertNotEquals("the other device must not receive the blob", control(2)["content"].asText(), other["content"].asText())
    assertEquals(2, entries[2]["messages"].size())
    // Sent, not confirmed: the update stays pending.
    assertNotNull(values.controlUpdate)
    assertEquals(1L, values.enrollmentEpoch)
    File(dir, "control-sent.txt").writeText("sent")

    // CollieSocket applied it and pushed the confirmation and a wake to the new endpoint (new keys).
    waitFor("the confirmation and wake bodies", 120) { File(dir, "inject-ids2.txt").exists() }
    val ids = File(dir, "inject-ids2.txt").readLines()
    val before = seen().size
    inject(File(dir, "inject-status.bin").readBytes())
    assertEquals("confirmation not recorded", before + 1, seen().size)
    assertNull("pending update not cleared by the confirmation", values.controlUpdate)
    assertEquals(2L, values.enrollmentEpoch)
    assertEquals(2L, values.controlHighestEpoch)
    assertNull("a confirmation is not a server problem", values.statusCode)
    values.lastReceivedTime = 0
    inject(File(dir, "inject-wake2.bin").readBytes())
    assertTrue("wake at the new endpoint not accepted", seen().contains(ids[0]))
    assertNotEquals(0L, values.lastReceivedTime)
    assertEquals(rotatedUrl, values.endpoint)
    File(dir, "result.txt").writeText("ok")
  }
}
