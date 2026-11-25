package im.molly.unifiedpush.enrollment

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.Assert.*
import org.junit.Assume.assumeNoException
import org.junit.Test
import java.util.Base64

class PairingV1Test {
  private val mapper = ObjectMapper()
  private val vectors = mapper.readTree(resource("fixtures/sealed-vectors.json"))
  private fun resource(name: String) = (javaClass.getResourceAsStream("/colliesocket-pairing-v1/$name") ?: error(name)).bufferedReader().readText()
  private fun offer(v: com.fasterxml.jackson.databind.JsonNode, now: Long = 1_799_999_999L) = PairingV1.parseOffer(v.toString(), now)
  private fun bad(block: () -> Unit) { try { block(); fail("expected invalid pairing data") } catch (_: EnrollmentFile.Invalid) { } }
  private fun b64(s: String) = Base64.getUrlDecoder().decode(s)
  private fun enc(b: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(b)
  private fun vectorSealed(v: com.fasterxml.jackson.databind.JsonNode, o: PairingV1.Offer) = PairingV1.sealedFromVectorForTest(
    o,
    v["enrollment_json"].asText(),
    mapper.readTree(v["enrollment_json"].asText())["subscription"]["endpoint"].asText(),
    v["request"]["client_public_key"].asText(),
    v["shared_secret_hex"].asText(),
    v["request"]["nonce"].asText(),
  )

  @Test fun `pinned sealed vectors authenticate and decrypt byte for byte`() {
    for (v in vectors) {
      val o = offer(v["offer"])
      assertEquals(v["transcript_hex"].asText(), PairingV1.transcriptForTest(o, v["request"]["client_public_key"].asText()).toHex())
      val sealed = vectorSealed(v, o)
      assertEquals(v["request"].toString(), sealed.json)
      assertEquals(v["request"], mapper.readTree(sealed.json))
      assertEquals(v["receipt_json"].asText(), PairingV1.openReceipt(o, sealed, v["response"].toString()))
    }
  }

  @Test fun `sealing uses canonical envelope and selected endpoint origin`() {
    val v = vectors[0]; val o = offer(v["offer"])
    val first = try {
      PairingV1.seal(o, v["enrollment_json"].asText(), "https://push.example.test/path")
    } catch (error: UnsatisfiedLinkError) {
      if (error.message?.contains("no signal_jni in java.library.path") != true) throw error
      assumeNoException("Host libsignal JNI is unavailable", error)
      throw error
    }
    val second = PairingV1.seal(o, v["enrollment_json"].asText(), "https://push.example.test/path")
    assertEquals(1, mapper.readTree(first.json)["pairing_version"].asInt())
    assertEquals(12, first.requestNonce.size)
    assertEquals(32, b64(mapper.readTree(first.json)["client_public_key"].asText()).size)
    assertNotEquals(mapper.readTree(first.json)["client_public_key"], mapper.readTree(second.json)["client_public_key"])
    assertNotEquals(mapper.readTree(first.json)["nonce"], mapper.readTree(second.json)["nonce"])
    bad { PairingV1.seal(o, v["enrollment_json"].asText(), "https://other.example.test/path") }
  }

  @Test fun `expiry provider mismatch duplicate and unknown fields and noncanonical coordinate are rejected`() {
    val v = vectors[0]; val json = v["offer"].toString()
    bad { PairingV1.parseOffer(json, 1_800_000_000L) }
    bad { PairingV1.parseOffer(json.replace("https://push.example.test", "https://Push.example.test"), 1_799_999_999L) }
    bad { PairingV1.parseOffer(json.dropLast(1) + ",\"unexpected\":1}", 1_799_999_999L) }
    bad { PairingV1.parseOffer(json.replace("\"pairing_version\":1", "\"pairing_version\":1,\"pairing_version\":1"), 1_799_999_999L) }
    val raw = b64(v["offer"]["server_public_key"].asText()).also { it[31] = 0x80.toByte() }
    bad { PairingV1.parseOffer(json.replace(v["offer"]["server_public_key"].asText(), enc(raw)), 1_799_999_999L) }
    val offer = v["offer"].deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>().put("expires_at", 1_800_000_601L)
    assertEquals(1_800_000_601L, PairingV1.parseOffer(offer.toString(), 1_799_999_999L).expiresAt)
  }

  @Test fun `canonical low-order X25519 coordinates are rejected before approval`() {
    val offer = vectors[0]["offer"].deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>()
    val keys = listOf(
      "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
      "AQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
      "7P_______________________________________38",
      "4Ot6fDtBuK4WVuP68Z_EatoJjeucMrH9hmIFFl9JuAA",
      "X5yVvKNQjCSx0LFVnIPvWwREXMRYHI6G2CJO3dCfEVc",
    )
    for (key in keys) {
      offer.put("server_public_key", key)
      bad { PairingV1.parseOffer(offer.toString(), 1_799_999_999L) }
    }
  }

  @Test fun `malformed and out of range offers are rejected`() {
    val json = vectors[0]["offer"].toString()
    bad { PairingV1.parseOffer("\uFEFF$json", 1_799_999_999L) }
    bad { PairingV1.parseOffer("\uD800", 1_799_999_999L) }
    bad { PairingV1.parseOffer(" ".repeat(1025), 1_799_999_999L) }
    bad { PairingV1.parseOffer(json.replace("pair_bridge", "unknown_intent"), 1_799_999_999L) }
    val beyondSafeInteger = vectors[0]["offer"].deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>()
      .put("expires_at", 9_007_199_254_740_992L)
    bad { PairingV1.parseOffer(beyondSafeInteger.toString(), 1_799_999_999L) }
    val negativeExpiry = vectors[0]["offer"].deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>()
      .put("expires_at", -20)
    bad { PairingV1.parseOffer(negativeExpiry.toString(), -100) }
    val brokenHost = vectors[0]["offer"].deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>()
      .put("host_label", " leading space")
    bad { PairingV1.parseOffer(brokenHost.toString(), 1_799_999_999L) }
  }

  @Test fun `sealing rejects invalid enrollment and endpoint binding`() {
    val o = offer(vectors[0]["offer"])
    bad { PairingV1.seal(o, "not json", "https://push.example.test/path") }
    bad { PairingV1.seal(o, "{}", "https://other.example.test/path") }
    bad { PairingV1.seal(o, " ".repeat(8193), "https://push.example.test/path") }
  }

  @Test fun `tampered receipt and transcript binding are rejected`() {
    val v = vectors[0]; val o = offer(v["offer"])
    val sealed = vectorSealed(v, o)
    val resp = v["response"].deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>()
    val c = b64(resp["ciphertext"].asText()).also { it[0] = (it[0].toInt() xor 1).toByte() }
    resp.put("ciphertext", enc(c)); bad { PairingV1.openReceipt(o, sealed, resp.toString()) }
    val changed = sealed.copy(transcript = sealed.transcript.clone().also { it[0] = (it[0].toInt() xor 1).toByte() })
    bad { PairingV1.openReceipt(o, changed, v["response"].toString()) }
    val wrongSession = v["response"].deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>()
      .put("session_id", "00000000-0000-4000-8000-000000000000")
    bad { PairingV1.openReceipt(o, sealed, wrongSession.toString()) }
    val extraField = v["response"].deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>().put("extra", true)
    bad { PairingV1.openReceipt(o, sealed, extraField.toString()) }
    bad { PairingV1.openReceipt(o, sealed, "\uFEFF${v["response"]}") }
    val nonUtf8 = v["response"].toString().toByteArray(Charsets.UTF_16LE).toString(Charsets.UTF_8)
    bad { PairingV1.openReceipt(o, sealed, nonUtf8) }
    val wrongDetails = mapper.readTree(v["receipt_json"].asText()).deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>()
      .put("request_id", "33333333-3333-4333-8333-333333333333")
    val authenticatedWrongDetails = PairingV1.responseForTest(o, sealed, wrongDetails.toString())
    bad { PairingV1.openReceipt(o, sealed, authenticatedWrongDetails) }
    val unknownReceiptField = mapper.readTree(v["receipt_json"].asText()).deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>()
      .put("unexpected", true)
    bad { PairingV1.openReceipt(o, sealed, PairingV1.responseForTest(o, sealed, unknownReceiptField.toString())) }
  }

  @Test fun `canonical origins normalize paths and enforce the exact IPv6 spelling`() {
    assertEquals("https://push.example.test", PairingV1.canonicalOrigin("https://Push.Example.Test/path"))
    val mapped = PairingV1.canonicalOrigin("https://[::ffff:192.0.2.1]/push")
    assertEquals("https://[::ffff:c000:201]", mapped)
    assertTrue(PairingV1.isCanonicalOrigin(mapped!!))
    assertFalse(PairingV1.isCanonicalOrigin("https://[::FFFF:192.0.2.1]"))
  }

  @Test fun `pinned origin spelling cases match the upstream contract`() {
    val cases = listOf(
      "https://push.example.test" to true,
      "https://[2001:db8::1]:8443" to true,
      "http://127.0.0.1:8020" to true,
      "https://[::ffff:c000:201]" to true,
      "https://xn--fa-hia.example" to true,
      "https://site.xn--4db.example" to true,
      "https://push.example.test/path?token=synthetic" to false,
      "https://push.example.test:443" to false,
      "https://[2001:0db8:0:0:0:0:0:1]" to false,
      "https://push.example.test:08443" to false,
      "https://push.example.test:" to false,
      "https://p\u00fcsh.example.test" to false,
      "https://[::ffff:192.0.2.1]" to false,
      "https://push\u007f.example.test" to false,
      "https://1.xn--4db.example" to false,
      "https://xn--a.example" to false,
      "https://push.example.test:0" to false,
    )
    for ((origin, valid) in cases) {
      assertEquals(origin, valid, PairingV1.isCanonicalOrigin(origin))
    }
  }

  @Test fun `reserved add receiver is rejected at app boundary`() {
    val v = vectors[0]["offer"].deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>().put("intent", "add_receiver")
    val reserved = PairingV1.parseOffer(v.toString(), 1_799_999_999L)
    bad { PairingV1.seal(reserved, vectors[0]["enrollment_json"].asText(), "https://push.example.test/path") }
  }

  private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
}
