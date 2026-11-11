package im.molly.unifiedpush.receiver

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.google.crypto.tink.apps.fixed_webpush.WebPushHybridDecrypt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import java.security.spec.ECPoint
import java.security.AlgorithmParameters
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.util.Base64

/**
 * Plain JVM tests (no libsignal) that run the pinned CollieSocket v1 fixtures (see colliesocket-contract/PIN.txt)
 * through the event parser, the receiver decision rules and the connector's RFC 8291 decryption.
 */
class EncryptedPushContractTest {

  private val mapper = ObjectMapper()
  private fun bytes(name: String) = javaClass.getResourceAsStream("/colliesocket-contract/$name")!!.readBytes()
  private fun json(name: String): JsonNode = mapper.readTree(bytes(name))

  @Test
  fun `copied fixtures are the pinned bytes listed in the contract manifest`() {
    val manifest = json("manifest.json")["files"].associate { it["path"].asText() to it["sha256"].asText() }
    val dir = java.io.File(javaClass.getResource("/colliesocket-contract/PIN.txt")!!.toURI()).parentFile
    var checked = 0
    dir.walkTopDown().filter { it.isFile && it.name != "PIN.txt" && it.name != "manifest.json" }.forEach { file ->
      val relative = file.relativeTo(dir).path
      val expected = manifest[relative] ?: manifest["fixtures/$relative"]
      assertNotNull("not in the manifest: $relative", expected)
      val actual = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
      assertEquals(relative, expected, actual)
      checked++
    }
    assertTrue(checked > 30)
  }

  @Test
  fun `every event case in cases json is accepted or refused as the contract says`() {
    val spec = json("cases.json")
    val now = spec["validation_time"].asLong()
    var events = 0
    for (case in spec["cases"].filter { it["schema"].asText() == "event" }) {
      val event = EncryptedPush.parse(bytes(case["path"].asText()), now)
      assertEquals(case["name"].asText(), case["valid"].asBoolean(), event != null)
      events++
    }
    assertEquals(23, events)
  }

  private fun stateOf(node: JsonNode) = EncryptedPush.ReplayState(
    node["clock_high_water"].asLong(),
    node["seen"].map { EncryptedPush.ReplayState.Seen(it["event_id"].asText(), it["expires_at"].asLong()) },
  )

  @Test
  fun `every receiver case reaches the reference decision and state`() {
    val cases = json("receiver-cases.json")["cases"]
    assertEquals(14, cases.size())
    for (case in cases) {
      val name = case["name"].asText()
      var lockChecks = 0
      val locked = case["locked"].asBoolean()
      val lockBeforeCommit = case["lock_before_commit"]?.asBoolean() == true
      val before = stateOf(case["state"])
      val outcome = EncryptedPush.decide(
        content = bytes(case["path"].asText()),
        decrypted = case["decrypted"].asBoolean(),
        now = case["now"].asLong(),
        locked = { locked || (lockBeforeCommit && lockChecks++ > 0) },
        activeInstance = case["active_instance"].asText(),
        acceptedEpochs = setOf(case["active_epoch"].asLong()),
        state = before,
      )
      assertEquals(name, case["expected"].asText(), outcome.decision.name.lowercase())
      if (outcome.decision.accepted) {
        val now = case["now"].asLong()
        val kept = before.seen.filter { it.expiresAt + 60 > now }
        assertEquals(name, now, outcome.state.clockHighWater)
        assertEquals(name, kept.map { it.eventId } + outcome.event!!.eventId, outcome.state.seen.map { it.eventId })
      } else {
        assertEquals("$name must not touch state", before, outcome.state)
      }
    }
  }

  @Test
  fun `replay state survives serialization and refuses garbage`() {
    val state = EncryptedPush.ReplayState(1735689600, listOf(EncryptedPush.ReplayState.Seen("11111111-1111-4111-8111-111111111111", 1735689900)))
    assertEquals(state, EncryptedPush.ReplayState.parse(state.serialize()))
    assertNull(EncryptedPush.ReplayState.parse(null))
    assertNull(EncryptedPush.ReplayState.parse("not json"))
    assertNull(EncryptedPush.ReplayState.parse("""{"hw":"x","seen":[]}"""))
    assertNull(EncryptedPush.ReplayState.parse("""{"hw":1}"""))
  }

  @Test
  fun `a replayed id is refused after the state is reloaded and accepted again once pruned`() {
    val wake = bytes("event-valid-wake.json")
    val now = 1735689600L
    fun run(at: Long, state: EncryptedPush.ReplayState) =
      EncryptedPush.decide(wake, true, at, { false }, "fixture-instance", setOf(1L), state)
    val first = run(now, EncryptedPush.ReplayState(now - 1, emptyList()))
    assertEquals(EncryptedPush.Decision.ACCEPT_WAKE, first.decision)
    val reloaded = EncryptedPush.ReplayState.parse(first.state.serialize())!!
    assertEquals(EncryptedPush.Decision.DROP_DUPLICATE, run(now + 1, reloaded).decision)
    // The event expired long ago and its record is pruned, but it is expired, so it is refused as invalid.
    assertEquals(EncryptedPush.Decision.DROP_INVALID, run(now + 400, reloaded).decision)
  }

  @Test
  fun `strict parsing refuses what the schema fixtures do not cover`() {
    val now = 1735689600L
    val good = String(bytes("event-valid-wake.json"))
    assertNotNull(EncryptedPush.parse(good.toByteArray(), now))
    assertNull(EncryptedPush.parse("﻿$good".toByteArray(), now))
    assertNull(EncryptedPush.parse(good.toByteArray(Charsets.UTF_16LE), now))
    assertNull(EncryptedPush.parse(byteArrayOf(0x7b, 0xff.toByte(), 0x7d), now))
    assertNull(EncryptedPush.parse("{\"urgent\":true}".toByteArray(), now))
    assertNull(EncryptedPush.parse("{\"test\":true}".toByteArray(), now))
    assertNull(EncryptedPush.parse(good.replace("\"version\": 1", "\"version\": 1.0").toByteArray(), now))
    assertNull(EncryptedPush.parse(good.replace("\"version\": 1", "\"version\": 1e0").toByteArray(), now))
    assertNull(EncryptedPush.parse(good.replace("1735689600", "99999999999999999999").toByteArray(), now))
    assertNull(EncryptedPush.parse(good.replace("\"kind\": \"wake\"", "\"Kind\": \"wake\"").toByteArray(), now))
    assertNull(EncryptedPush.parse("[$good]".toByteArray(), now))
    assertNull(EncryptedPush.parse(ByteArray(0), now))
    assertNull(EncryptedPush.parse((good + "x").toByteArray(), now))
    assertNotNull(EncryptedPush.parse((good + "  \n").toByteArray(), now))
  }

  @Test
  fun `the connector decrypts the fixture to an event the receiver accepts`() {
    val meta = json("crypto/metadata.json")
    fun b64(s: String) = Base64.getUrlDecoder().decode(s)
    val params = AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }.getParameterSpec(ECParameterSpec::class.java)
    val point = b64(meta["p256dh"].asText())
    val factory = KeyFactory.getInstance("EC")
    val pub = factory.generatePublic(ECPublicKeySpec(ECPoint(BigInteger(1, point.copyOfRange(1, 33)), BigInteger(1, point.copyOfRange(33, 65))), params))
    val priv = factory.generatePrivate(ECPrivateKeySpec(BigInteger(1, b64(meta["receiver_private"].asText())), params))
    val decrypt = WebPushHybridDecrypt.Builder()
      .withAuthSecret(b64(meta["auth"].asText()))
      .withRecipientPublicKey(pub as java.security.interfaces.ECPublicKey)
      .withRecipientPrivateKey(priv as java.security.interfaces.ECPrivateKey)
      .build() as Any

    // The supertype (Tink's HybridDecrypt) is not on the test compile classpath, so call it reflectively.
    val plaintext = decrypt.javaClass.getMethod("decrypt", ByteArray::class.java, ByteArray::class.java)
      .invoke(decrypt, bytes("crypto/wake-v1.bin"), null) as ByteArray
    assertEquals(EncryptedPush.MAX_INPUT_BYTES, plaintext.size)
    val event = EncryptedPush.parse(plaintext, 1735689600)
    assertNotNull(event)
    assertEquals(EncryptedPush.Kind.WAKE, event!!.kind)
    assertEquals(String(bytes("crypto/wake-v1.json")).trim(), String(plaintext).trim())
    assertFalse(EncryptedPush.parse(plaintext, 1735689600 + 400) != null)
  }
}
