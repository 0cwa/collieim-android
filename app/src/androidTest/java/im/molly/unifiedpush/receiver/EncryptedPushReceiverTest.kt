/*
 * Copyright 2026 Collie
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package im.molly.unifiedpush.receiver

import android.content.Context
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import im.molly.unifiedpush.enrollment.EnrollmentExporter
import im.molly.unifiedpush.components.settings.app.notifications.updateMollySocket
import im.molly.unifiedpush.model.MollySocket
import im.molly.unifiedpush.model.MollySocketDevice
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
import org.thoughtcrime.securesms.ApplicationContext
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.service.KeyCachingService
import org.thoughtcrime.securesms.testing.SignalActivityRule
import org.unifiedpush.android.connector.keys.DefaultKeyManager
import java.io.File
import java.util.Base64
import java.util.UUID

/**
 * Injects pushes through the real connector entry point (the MESSAGE broadcast the distributor sends to
 * [UnifiedPushReceiver]), so the connector's own RFC 8291 decryption with its Keystore-held key runs before the
 * receiver sees the message. The fake account never talks to Signal. Nothing here is a real credential.
 */
@RunWith(AndroidJUnit4::class)
class EncryptedPushReceiverTest {

  @get:Rule
  val harness = SignalActivityRule(othersCount = 0)

  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val context: Context = instrumentation.targetContext
  private val args = InstrumentationRegistry.getArguments()
  private val values get() = SignalStore.unifiedpush
  private val token = "synthetic-connector-token"
  private val endpointUrl = args.getString("ep_endpoint") ?: "https://push.example.invalid/synthetic-endpoint"
  private lateinit var pub: ByteArray
  private lateinit var auth: ByteArray

  private fun b64(s: String) = Base64.getUrlDecoder().decode(s)

  /** Registers the connector instance exactly as the connector stores it, with a fresh Keystore-held keypair. */
  private fun registerConnector() {
    val keys = DefaultKeyManager(context)
    keys.generate("default")
    val set = keys.getPublicKeySet("default")!!
    pub = b64(set.pubKey)
    auth = b64(set.auth)
    check(context.getSharedPreferences("unifiedpush.connector", Context.MODE_PRIVATE).edit()
      .putStringSet("unifiedpush.instances", setOf("default"))
      .putString("default/unifiedpush.connector", token)
      .commit())
    values.device = MollySocketDevice(deviceId = 2, password = "SYNTHETIC-NOT-A-SIGNAL-CREDENTIAL")
    values.endpoint = endpointUrl
    values.endpointInstance = "default"
    values.endpointP256dh = set.pubKey
    values.endpointAuth = set.auth
    values.enabled = true
    values.enrollmentEpoch = 0
    values.pendingEnrollmentEpoch = 0
    values.replayState = null
    values.lastReceivedTime = 0
    LockedPushState.setEncryptedEnrollment(context, false)
  }

  private fun export(): File = EnrollmentExporter.export(context, null) { true }

  private fun now() = System.currentTimeMillis() / 1000

  private fun eventJson(kind: String = "wake", id: String = UUID.randomUUID().toString(), instance: String = "default", epoch: Long = 1,
                        issued: Long = now(), expires: Long = issued + 300, code: String? = null): String =
    """{"version":1,"event_id":"$id","instance":"$instance","subscription_epoch":$epoch,"kind":"$kind",""" +
      """"issued_at":$issued,"expires_at":$expires${code?.let { ""","code":"$it"""" } ?: ""}}"""

  /** The wire profile: JSON padded with spaces to 3993 bytes, then RFC 8291 for the connector's public key. */
  private fun encrypt(json: String): ByteArray {
    val padded = json.padEnd(EncryptedPush.MAX_INPUT_BYTES, ' ').toByteArray()
    val builder = Class.forName("com.google.crypto.tink.apps.fixed_webpush.WebPushHybridEncrypt\$Builder").getConstructor().newInstance()
    builder.javaClass.getMethod("withAuthSecret", ByteArray::class.java).invoke(builder, auth)
    builder.javaClass.getMethod("withRecipientPublicKey", ByteArray::class.java).invoke(builder, pub)
    val encrypter = builder.javaClass.getMethod("build").invoke(builder)
    return encrypter.javaClass.getMethod("encrypt", ByteArray::class.java, ByteArray::class.java).invoke(encrypter, padded, null) as ByteArray
  }

  private fun inject(body: ByteArray) {
    context.sendBroadcast(
      Intent("org.unifiedpush.android.connector.MESSAGE")
        .setPackage(context.packageName)
        .putExtra("token", token)
        .putExtra("id", UUID.randomUUID().toString())
        .putExtra("bytesMessage", body)
    )
    settle()
  }

  private fun settle() {
    Thread.sleep(1500)
    instrumentation.waitForIdleSync()
  }

  private fun seen(): List<String> = EncryptedPush.ReplayState.parse(values.replayState)!!.seen.map { it.eventId }

  private fun accepted(body: ByteArray, id: String) {
    val before = seen().size
    inject(body)
    assertTrue("$id not recorded", seen().contains(id))
    assertEquals(before + 1, seen().size)
  }

  private fun rejected(label: String, body: ByteArray) {
    val replay = values.replayState
    values.lastReceivedTime = 0
    val status = values.statusCode
    inject(body)
    assertEquals("$label changed the replay state", replay, values.replayState)
    assertEquals("$label took effect", 0L, values.lastReceivedTime)
    assertEquals("$label recorded a status", status, values.statusCode)
  }

  @Test
  fun injectedPushesAreAcceptedOrRejectedAsTheContractSays() {
    registerConnector()
    export()
    // The export alone switches nothing: the server has not proven it imported the file.
    assertEquals(0L, values.enrollmentEpoch)
    assertEquals(1L, values.pendingEnrollmentEpoch)
    assertFalse(LockedPushState.isEncryptedEnrollment(context))
    inject("""{"urgent":true}""".toByteArray())
    assertNotEquals("plaintext wake before promotion was refused", 0L, values.lastReceivedTime)
    values.lastReceivedTime = 0

    // A valid wake at the pending epoch is accepted, wakes the app and promotes the enrollment.
    val wakeId = UUID.randomUUID().toString()
    val wake = encrypt(eventJson(id = wakeId))
    accepted(wake, wakeId)
    assertNotEquals(0L, values.lastReceivedTime)
    assertEquals(1L, values.enrollmentEpoch)
    assertEquals(0L, values.pendingEnrollmentEpoch)
    assertTrue(LockedPushState.isEncryptedEnrollment(context))

    // The same body again is a replay: nothing changes.
    rejected("replay", wake)

    // The status event is recorded, generically.
    val statusId = UUID.randomUUID().toString()
    accepted(encrypt(eventJson("status", statusId, code = "connection_unavailable")), statusId)
    assertEquals("connection_unavailable", values.statusCode)

    // Not decrypted by the connector (plain bytes) or not a v1 event.
    rejected("plaintext urgent", """{"urgent":true}""".toByteArray())
    rejected("plaintext test", """{"test":true}""".toByteArray())
    rejected("undecrypted valid event", eventJson().toByteArray())
    rejected("encrypted plaintext urgent", encrypt("""{"urgent":true}"""))
    // Wrong instance, wrong epoch (older and newer, status included), expired, too long-lived, from the future.
    rejected("wrong instance", encrypt(eventJson(instance = "other")))
    rejected("newer epoch wake", encrypt(eventJson(epoch = 2)))
    rejected("newer epoch status", encrypt(eventJson("status", epoch = 2, code = "connection_conflict")))
    rejected("expired", encrypt(eventJson(issued = now() - 1000)))
    rejected("long life", encrypt(eventJson(expires = now() + 301)))
    rejected("future", encrypt(eventJson(issued = now() + 120, expires = now() + 400)))
    rejected("unknown field", encrypt(eventJson().dropLast(1) + ""","extra":1}"""))
    rejected("wrong key", ByteArray(4096).also { java.security.SecureRandom().nextBytes(it) })
    assertNull(values.statusCode?.takeIf { it != "connection_unavailable" })

    // Switching to another MollySocket server goes through the production path and brings stock back.
    val replay = values.replayState
    values.updateMollySocket(MollySocket.AirGapped("synthetic-new-vapid-key"))
    assertEquals(0L, values.enrollmentEpoch)
    assertEquals(0L, values.pendingEnrollmentEpoch)
    assertFalse(LockedPushState.isEncryptedEnrollment(context))
    assertEquals("replay state must be kept", replay, values.replayState)
    values.lastReceivedTime = 0
    inject("""{"urgent":true}""".toByteArray())
    assertNotEquals("stock plaintext wake was refused", 0L, values.lastReceivedTime)
  }

  @Test
  fun anExportNeverImportedKeepsTheActiveEpochAccepted() {
    registerConnector()
    export()
    val first = UUID.randomUUID().toString()
    accepted(encrypt(eventJson(id = first)), first)
    assertEquals(1L, values.enrollmentEpoch)
    // Re-export at the server's epoch 1: pending 2, nothing else changes.
    EnrollmentExporter.export(context, 1) { true }
    assertEquals(1L, values.enrollmentEpoch)
    assertEquals(2L, values.pendingEnrollmentEpoch)
    val second = UUID.randomUUID().toString()
    accepted(encrypt(eventJson(id = second)), second)
    assertEquals(1L, values.enrollmentEpoch)
    values.lastReceivedTime = 0
    rejected("plaintext while epoch 1 is active", """{"urgent":true}""".toByteArray())
    val third = UUID.randomUUID().toString()
    accepted(encrypt(eventJson(id = third, epoch = 2)), third)
    assertEquals(2L, values.enrollmentEpoch)
    assertEquals(0L, values.pendingEnrollmentEpoch)
    rejected("epoch 1 after promotion", encrypt(eventJson(epoch = 1)))
  }

  @Test
  fun lockedStateDropsEncryptedPushesAndKeepsNewEndpoints() {
    registerConnector()
    export()
    values.mutateControl(EncryptedPush.ReplayState(now() - 1, emptyList()).serialize()) { it.copy(activeEpoch = 1, pendingEpoch = 0, pendingKey = null, pendingTarget = null) }
    LockedPushState.setEncryptedEnrollment(context, true)
    val secret = KeyCachingService.getMasterSecret()
    val replay = values.replayState
    val newUrl = "https://push.example.invalid/rotated-endpoint-secret-path"

    try {
      KeyCachingService.clearMasterSecret()
      assertTrue(KeyCachingService.isLocked())
      assertTrue(LockedPushState.isEncryptedEnrollment(context))

      inject(encrypt(eventJson()))
      inject("""{"urgent":true}""".toByteArray())
      context.sendBroadcast(
        Intent("org.unifiedpush.android.connector.NEW_ENDPOINT")
          .setPackage(context.packageName)
          .putExtra("token", token)
          .putExtra("id", UUID.randomUUID().toString())
          .putExtra("endpoint", newUrl)
      )
      settle()

      // The pending endpoint is held sealed: the URL is not readable in the preferences file.
      val pending = context.getSharedPreferences("collie_locked_push", Context.MODE_PRIVATE).getString("pending_endpoint", null)
      assertNotNull("no endpoint stored while locked", pending)
      assertFalse(pending!!.contains("rotated-endpoint"))
    } finally {
      KeyCachingService.setMasterSecret(secret)
    }

    assertEquals("push while locked changed the replay state", replay, values.replayState)
    assertEquals("push while locked took effect", 0L, values.lastReceivedTime)
    assertEquals(endpointUrl, values.endpoint)

    instrumentation.runOnMainSync { ApplicationContext.getInstance(context).onUnlock() }
    val deadline = System.currentTimeMillis() + 10_000
    while (values.endpoint != newUrl && System.currentTimeMillis() < deadline) Thread.sleep(100)
    assertEquals(newUrl, values.endpoint)
    assertEquals("default", values.endpointInstance)
    assertNotNull(values.endpointP256dh)
    assertNull(context.getSharedPreferences("collie_locked_push", Context.MODE_PRIVATE).getString("pending_endpoint", null))
  }

  /**
   * Driven by the host harness (argument `ep_bodies`): exports the enrollment, waits for `inject-wake.bin` and
   * `inject-test.bin` (real CollieSocket pushes, event ids in `inject-ids.txt`), injects them and checks the effects.
   */
  @Test
  fun pushesFromCollieSocketAreAccepted() {
    assumeTrue(args.getString("ep_bodies") != null)
    registerConnector()
    val dir = context.getExternalFilesDir(null)!!
    File(dir, "inject-wake.bin").delete()
    File(dir, "inject-ids.txt").delete()
    export()
    File(dir, "ready.txt").writeText("ready")
    val deadline = System.currentTimeMillis() + 180_000
    while (!File(dir, "inject-ids.txt").exists() && System.currentTimeMillis() < deadline) Thread.sleep(250)
    val ids = File(dir, "inject-ids.txt").readLines()
    accepted(File(dir, "inject-wake.bin").readBytes(), ids[0])
    assertNotEquals(0L, values.lastReceivedTime)
    accepted(File(dir, "inject-test.bin").readBytes(), ids[1])
    File(dir, "result.txt").writeText("ok")
  }
}
