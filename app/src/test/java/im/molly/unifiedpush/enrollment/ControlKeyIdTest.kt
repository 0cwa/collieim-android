package im.molly.unifiedpush.enrollment

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlKeyIdTest {
  private val vector: com.fasterxml.jackson.databind.JsonNode by lazy {
    ObjectMapper().readTree(javaClass.getResourceAsStream("/colliesocket-contract-v3/fixtures/key-id-v3.json")!!)
  }

  @Test
  fun `key id matches full v3 HMAC vector`() {
    val actual = ControlKeyId.derive(
      vector["control_key"].asText(), vector["account_id"].asText(), vector["device_id"].asInt(), vector["instance"].asText(),
    )
    assertEquals("mXw0XDWJSi-VIzvypI0-jVN2rgJWeIX4G6N60XnCvWM", vector["key_id"].asText())
    assertEquals(vector["key_id"].asText(), actual)
    assertTrue(ControlKeyId.isCanonical(actual))
  }

  @Test
  fun `key identity changes with account device or instance context`() {
    val expected = ControlKeyId.derive("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8", "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", 2, "fixture-instance")
    assertNotEquals(expected, ControlKeyId.derive("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8", "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", 2, "fixture-instance"))
    assertNotEquals(expected, ControlKeyId.derive("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8", "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", 3, "fixture-instance"))
    assertNotEquals(expected, ControlKeyId.derive("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8", "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", 2, "other-instance"))
  }

  @Test
  fun `noncanonical key ids are rejected`() {
    assertFalse(ControlKeyId.isCanonical("mXw0XDWJSi-VIzvypI0-jVN2rgJWeIX4G6N60XnCvW="))
    assertFalse(ControlKeyId.isCanonical("mXw0XDWJSi-VIzvypI0-jVN2rgJWeIX4G6N60XnCvWB"))
  }
}
