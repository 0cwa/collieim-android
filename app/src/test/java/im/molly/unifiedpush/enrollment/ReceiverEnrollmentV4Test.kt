package im.molly.unifiedpush.enrollment

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiverEnrollmentV4Test {
  private val mapper = ObjectMapper()
  private val receiver by lazy { mapper.readTree(resource("fixtures/receiver.json")) }
  private fun resource(path: String) = checkNotNull(javaClass.getResourceAsStream("/colliesocket-encrypted-push-v4/$path")).use { it.readBytes().toString(Charsets.UTF_8) }

  @Test fun `receiver import matches pinned shape and never contains bridge password`() {
    val subscription = receiver["subscription"]
    val result = ReceiverEnrollmentV4.render(ReceiverEnrollmentV4.Input(
      receiver["request_id"].asText(), receiver["account_id"].asText(), receiver["device_id"].intValue(),
      subscription["endpoint"].asText(), subscription["p256dh"].asText(), subscription["auth"].asText(),
      subscription["instance"].asText(), receiver["receiver_id"].asText(), receiver["control_key"].asText(),
      receiver["label"].asText(),
    ))
    assertEquals(receiver, mapper.readTree(result))
    assertFalse(result.contains("password"))
    ReceiverEnrollmentV4.validate(result)
  }

  @Test fun `generated identities are fresh canonical uuid v4 and label is printable ascii`() {
    val id1 = ReceiverEnrollmentV4.newUuid()
    val id2 = ReceiverEnrollmentV4.newUuid()
    assertNotEquals(id1, id2)
    assertTrue(Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}").matches(id1))
    assertEquals("device-name", ReceiverEnrollmentV4.sanitizedLabel("device\nname"))
  }

  @Test fun `receiver enrollment rejects bridge id below two and cleartext endpoints`() {
    val subscription = receiver["subscription"]
    fun input(device: Int, endpoint: String) = ReceiverEnrollmentV4.Input(
      receiver["request_id"].asText(), receiver["account_id"].asText(), device, endpoint,
      subscription["p256dh"].asText(), subscription["auth"].asText(), subscription["instance"].asText(),
      receiver["receiver_id"].asText(), receiver["control_key"].asText(),
    )
    try { ReceiverEnrollmentV4.render(input(1, subscription["endpoint"].asText())); throw AssertionError("accepted primary device id") }
    catch (e: EnrollmentFile.Invalid) { assertEquals("invalid_enrollment", e.code) }
    try { ReceiverEnrollmentV4.render(input(2, "http://push.example.test/second")); throw AssertionError("accepted HTTP endpoint") }
    catch (e: EnrollmentFile.Invalid) { assertEquals("invalid_endpoint", e.code) }
  }
}
