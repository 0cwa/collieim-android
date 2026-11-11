package im.molly.unifiedpush.enrollment

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test

/** Plain JVM tests (no libsignal): the output must satisfy the pinned CollieSocket v1 enrollment contract. */
class EnrollmentFileTest {

  private val mapper = ObjectMapper()
  private fun resource(name: String) = javaClass.getResourceAsStream("/colliesocket-contract/$name")!!.readBytes().toString(Charsets.UTF_8)
  private val schema = mapper.readTree(resource("enrollment.schema.json"))
  private val fixture = mapper.readTree(resource("enrollment-valid.json"))

  private val sub = fixture["subscription"]
  private val valid = EnrollmentFile.Input(
    requestId = fixture["request_id"].asText(),
    accountId = fixture["account_id"].asText(),
    deviceId = fixture["device_id"].asInt(),
    password = fixture["password"].asText(),
    endpoint = sub["endpoint"].asText(),
    p256dh = sub["p256dh"].asText(),
    auth = sub["auth"].asText(),
    instance = sub["instance"].asText(),
    epoch = sub["subscription_epoch"].asLong(),
  )

  /** Just the keywords enrollment.schema.json uses. Returns the first violation or null. */
  private fun violation(schema: JsonNode, value: JsonNode, path: String = "$"): String? {
    schema["const"]?.let { if (it != value) return "$path const" }
    schema["type"]?.asText()?.let {
      val ok = when (it) {
        "object" -> value.isObject
        "string" -> value.isTextual
        "integer" -> value.isIntegralNumber
        "boolean" -> value.isBoolean
        "array" -> value.isArray
        else -> false
      }
      if (!ok) return "$path type"
    }
    if (value.isTextual) {
      val text = value.asText()
      schema["minLength"]?.let { if (text.length < it.asInt()) return "$path minLength" }
      schema["maxLength"]?.let { if (text.length > it.asInt()) return "$path maxLength" }
      schema["pattern"]?.let { if (!Regex(it.asText()).containsMatchIn(text)) return "$path pattern" }
    }
    if (value.isIntegralNumber) {
      schema["minimum"]?.let { if (value.asLong() < it.asLong()) return "$path minimum" }
      schema["maximum"]?.let { if (value.asLong() > it.asLong()) return "$path maximum" }
    }
    if (value.isObject) {
      schema["required"]?.forEach { if (!value.has(it.asText())) return "$path missing ${it.asText()}" }
      val props = schema["properties"]
      if (schema["additionalProperties"]?.asBoolean(true) == false) {
        value.fieldNames().forEach { if (props?.has(it) != true) return "$path unknown $it" }
      }
      props?.fields()?.forEach { (name, sub) -> value[name]?.let { v -> violation(sub, v, "$path.$name")?.let { return it } } }
    }
    return null
  }

  private fun refused(input: EnrollmentFile.Input, code: String? = null) {
    try {
      EnrollmentFile.render(input)
      fail("expected refusal")
    } catch (e: EnrollmentFile.Invalid) {
      if (code != null) assertEquals(code, e.code)
      assertFalse(e.message!!.contains(input.password))
    }
  }

  @Test
  fun `output equals the contract fixture and satisfies the schema`() {
    val out = mapper.readTree(EnrollmentFile.render(valid))
    assertEquals(fixture, out)
    assertEquals(null, violation(schema, out))
  }

  @Test
  fun `generated values with quotes in the password and a query endpoint stay valid json and schema`() {
    val input = valid.copy(password = "pa\"ss\\word-with-quotes-1234", endpoint = "https://ntfy.example/up?x=1&y=\"2\"", epoch = 7, deviceId = Int.MAX_VALUE)
    val out = mapper.readTree(EnrollmentFile.render(input))
    assertEquals(input.password, out["password"].asText())
    assertEquals(input.endpoint, out["subscription"]["endpoint"].asText())
    assertEquals(null, violation(schema, out))
  }

  @Test
  fun `connector keys with padding or the standard alphabet are written canonical`() {
    val out = mapper.readTree(EnrollmentFile.render(valid.copy(auth = valid.auth + "==", p256dh = valid.p256dh + "=")))
    assertEquals(valid.auth, out["subscription"]["auth"].asText())
    assertEquals(valid.p256dh, out["subscription"]["p256dh"].asText())
  }

  @Test
  fun `missing or malformed keys refuse to export`() {
    refused(valid.copy(p256dh = ""), "invalid_key")
    refused(valid.copy(auth = ""), "invalid_key")
    refused(valid.copy(auth = valid.auth.dropLast(2)), "invalid_key")
    refused(valid.copy(p256dh = valid.p256dh.dropLast(1)), "invalid_key")
    refused(valid.copy(auth = "!!!!!!!!!!!!!!!!!!!!!!"), "invalid_key")
    // last character carries non-zero trailing bits, so it is not canonical
    refused(valid.copy(auth = valid.auth.dropLast(1) + "h"), "invalid_key")
  }

  @Test
  fun `point that is not on P-256 or lacks the uncompressed prefix is refused`() {
    val bytes = java.util.Base64.getUrlDecoder().decode(valid.p256dh)
    val offCurve = bytes.clone().also { it[64] = (it[64] + 1).toByte() }
    val badPrefix = bytes.clone().also { it[0] = 3 }
    val enc = java.util.Base64.getUrlEncoder().withoutPadding()
    refused(valid.copy(p256dh = enc.encodeToString(offCurve)), "invalid_key")
    refused(valid.copy(p256dh = enc.encodeToString(badPrefix)), "invalid_key")
  }

  @Test
  fun `identifiers credentials and instance outside the contract are refused`() {
    refused(valid.copy(deviceId = 1))
    refused(valid.copy(deviceId = 0))
    refused(valid.copy(password = "short"))
    refused(valid.copy(password = "x".repeat(257)))
    refused(valid.copy(password = "good-password-1234\n"))
    refused(valid.copy(password = "good-password-12é"))
    refused(valid.copy(requestId = "22222222-2222-1222-8222-222222222222"))
    refused(valid.copy(requestId = fixture["request_id"].asText() + "\n"))
    refused(valid.copy(accountId = "AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA"))
    refused(valid.copy(instance = "bad\ninstance"))
    refused(valid.copy(instance = ""))
    refused(valid.copy(epoch = 0))
    refused(valid.copy(epoch = 9007199254740992L))
  }

  @Test
  fun `endpoints the contract forbids are refused`() {
    listOf(
      "", "ftp://host/x", "https:///x", "https://user@host/x", "https://@host/x", "https://host/x#frag", "https://host/x#",
      "https://host/a b", "https://host\\x", "https://host:0/x", "https://host:70000/x", "https://host:abc/x", "https://:443/x",
      "https://host/é", "https://" + "h".repeat(2048),
    ).forEach { refused(valid.copy(endpoint = it), "invalid_endpoint") }
  }

  @Test
  fun `http and bracketed ipv6 endpoints are left for the server's allowed_endpoints decision`() {
    EnrollmentFile.render(valid.copy(endpoint = "http://192.168.1.2:8080/up"))
    EnrollmentFile.render(valid.copy(endpoint = "https://[::1]:8443/up?a=b"))
  }

  @Test
  fun `epoch is the server's epoch plus one, or 1 when not enrolled`() {
    assertEquals(1, EnrollmentFile.nextEpoch(null))
    assertEquals(1, EnrollmentFile.nextEpoch(0))
    assertEquals(2, EnrollmentFile.nextEpoch(1))
    assertEquals(9007199254740991L, EnrollmentFile.nextEpoch(9007199254740990L))
  }

  @Test
  fun `a server epoch outside the contract range is refused`() {
    listOf(-1L, 9007199254740991L, Long.MAX_VALUE).forEach {
      try {
        EnrollmentFile.nextEpoch(it)
        fail("expected refusal")
      } catch (e: EnrollmentFile.Invalid) {
        assertEquals("invalid_epoch", e.code)
      }
    }
  }

  @Test
  fun `input never prints its secrets`() {
    assertFalse(valid.toString().contains(valid.password))
    assertFalse(valid.toString().contains(valid.auth))
  }
}
