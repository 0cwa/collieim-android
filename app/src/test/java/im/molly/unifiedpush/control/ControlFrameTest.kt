package im.molly.unifiedpush.control

import com.fasterxml.jackson.databind.ObjectMapper
import im.molly.unifiedpush.enrollment.EnrollmentFile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Plain JVM tests (no libsignal) against the pinned CollieSocket v2 control vector (colliesocket-contract-v2/PIN.txt). */
class ControlFrameTest {

  private val mapper = ObjectMapper()
  private val fixture = mapper.readTree(javaClass.getResourceAsStream("/colliesocket-contract-v2/fixtures/control-v2.json")!!.readBytes())
  private val url = Base64.getUrlDecoder()
  private val plain = mapper.readTree(fixture["plaintext_utf8"].asText())
  private val subscription = ControlFrame.Subscription(plain["endpoint"].asText(), plain["p256dh"].asText(), plain["auth"].asText())
  private val nonce = url.decode(fixture["nonce"].asText())
  private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

  private fun build(epoch: Long = fixture["subscription_epoch"].asLong(), device: Int = fixture["device_id"].asInt(), nonce: ByteArray = this.nonce) =
    ControlFrame.build(fixture["control_key"].asText(), fixture["account_id"].asText(), device, epoch, subscription, nonce)

  @Test
  fun `frame equals the contract vector byte for byte`() {
    assertArrayEquals(url.decode(fixture["frame_base64url"].asText()), build())
  }

  @Test
  fun `plaintext equals the vector's string and the header equals its additional data`() {
    assertEquals(fixture["plaintext_utf8"].asText(), String(ControlFrame.plaintext(subscription), Charsets.UTF_8))
    val header = ControlFrame.header(fixture["account_id"].asText(), fixture["device_id"].asInt(), fixture["subscription_epoch"].asLong())
    assertEquals(fixture["aad_hex"].asText(), hex(header))
    assertEquals(46, header.size)
    assertEquals("COLLIESOCKET-CTRL", String(header, 0, 17, Charsets.US_ASCII))
  }

  @Test
  fun `an independent decryption of the frame with the header as additional data gives the plaintext`() {
    val frame = build()
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(url.decode(fixture["control_key"].asText()), "AES"), GCMParameterSpec(128, frame, 46, 12))
    cipher.updateAAD(frame, 0, 46)
    assertEquals(fixture["plaintext_utf8"].asText(), String(cipher.doFinal(frame, 58, frame.size - 58), Charsets.UTF_8))
  }

  @Test
  fun `epoch and device are big endian in the header and change the frame`() {
    val frame = build(epoch = 0x0001020304050607L, device = 0x0A0B0C0D)
    assertEquals("0a0b0c0d", hex(frame.copyOfRange(34, 38)))
    assertEquals("0001020304050607", hex(frame.copyOfRange(38, 46)))
    assertEquals(17, "COLLIESOCKET-CTRL".length)
    assertEquals(2, frame[17].toInt())
    assertEquals("aaaaaaaaaaaa4aaa8aaaaaaaaaaaaaaa", hex(frame.copyOfRange(18, 34)))
  }

  @Test
  fun `a fresh nonce is drawn when none is given and two builds differ`() {
    val a = ControlFrame.build(fixture["control_key"].asText(), fixture["account_id"].asText(), 2, 5, subscription)
    val b = ControlFrame.build(fixture["control_key"].asText(), fixture["account_id"].asText(), 2, 5, subscription)
    assertFalse(a.contentEquals(b))
    assertEquals(ControlFrame.HEADER_SIZE + ControlFrame.NONCE_SIZE + fixture["plaintext_utf8"].asText().length + 16, a.size)
  }

  @Test
  fun `a frame tampered with anywhere fails to authenticate`() {
    val frame = build()
    for (index in listOf(0, 17, 20, 36, 40, 46, 58, frame.size - 1)) {
      val bad = frame.clone().also { it[index] = (it[index].toInt() xor 1).toByte() }
      val cipher = Cipher.getInstance("AES/GCM/NoPadding")
      cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(url.decode(fixture["control_key"].asText()), "AES"), GCMParameterSpec(128, bad, 46, 12))
      cipher.updateAAD(bad, 0, 46)
      try {
        cipher.doFinal(bad, 58, bad.size - 58)
        fail("byte $index was not authenticated")
      } catch (e: java.security.GeneralSecurityException) {
        // expected
      }
    }
  }

  @Test
  fun `inputs outside the contract are refused`() {
    fun refused(block: () -> Unit) = try { block(); fail("expected refusal") } catch (e: EnrollmentFile.Invalid) { /* expected */ }
    refused { build(epoch = 0) }
    refused { build(epoch = 9007199254740992L) }
    refused { build(device = 1) }
    refused { ControlFrame.build(fixture["control_key"].asText(), "AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA", 2, 2, subscription, nonce) }
    refused { ControlFrame.build(fixture["control_key"].asText().dropLast(1), fixture["account_id"].asText(), 2, 2, subscription, nonce) }
    refused { ControlFrame.Subscription("https://host/a b", subscription.p256dh, subscription.auth) }
    refused { ControlFrame.Subscription("https://" + "h".repeat(2048), subscription.p256dh, subscription.auth) }
    refused { ControlFrame.Subscription(subscription.endpoint, subscription.p256dh.dropLast(1), subscription.auth) }
    refused { ControlFrame.Subscription(subscription.endpoint, subscription.p256dh, "") }
  }

  @Test
  fun `a frame of the largest allowed endpoint stays within the contract bound`() {
    val big = ControlFrame.Subscription("https://h/" + "a".repeat(2048 - 10), subscription.p256dh, subscription.auth)
    assertTrue(ControlFrame.build(fixture["control_key"].asText(), fixture["account_id"].asText(), 2, 3, big).size <= ControlFrame.MAX_FRAME)
  }

  @Test
  fun `subscription never prints its secrets`() {
    assertFalse(subscription.toString().contains(subscription.auth))
    assertFalse(subscription.toString().contains(subscription.endpoint))
  }
}
