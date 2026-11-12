package org.whispersystems.signalservice.api.messages

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.whispersystems.signalservice.internal.push.OutgoingPushMessage
import java.util.Base64

/** Plain JVM test (no libsignal native code) of the per-device request built for an endpoint-update frame. */
class ControlMessageListTest {

  private val frame = ByteArray(200) { (it * 7).toByte() }
  private val encrypted = mutableListOf<Int>()
  private fun ordinary(id: Int): OutgoingPushMessage {
    encrypted += id
    return OutgoingPushMessage(if (id == 3) 3 else 1, id, 1000 + id, "ORDINARY-$id")
  }

  private fun build(others: Collection<Int>, controlId: Int = 2, registrationId: Int = 4242) =
    ControlMessageList.build("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", 1234L, controlId, registrationId, frame, others, ::ordinary)

  @Test
  fun `only the CollieSocket entry carries the frame as padded base64 with type 1 and its registration ID`() {
    val list = build(listOf(1, 3))
    val control = list.messages.single { it.destinationDeviceId == 2 }
    assertEquals(1, control.type)
    assertEquals(4242, control.destinationRegistrationId)
    assertTrue(control.content.endsWith("=") || frame.size % 3 == 0)
    assertArrayEquals(frame, Base64.getDecoder().decode(control.content))
    assertEquals(listOf(1, 3), list.messages.filter { it.destinationDeviceId != 2 }.map { it.destinationDeviceId })
    assertTrue(list.messages.filter { it.destinationDeviceId != 2 }.all { it.content.startsWith("ORDINARY-") })
  }

  @Test
  fun `the request is non urgent and not online and addressed to the account`() {
    val list = build(listOf(1))
    assertFalse(list.urgent)
    assertFalse(list.online)
    assertEquals("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", list.destination)
    assertEquals(1234L, list.timestamp)
    val json = ObjectMapper().readTree(ObjectMapper().writeValueAsString(list))
    assertFalse(json["urgent"].asBoolean())
    assertEquals(2, json["messages"].size())
  }

  @Test
  fun `the CollieSocket device is never encrypted to even when it is listed among the others`() {
    val list = build(listOf(2, 1, 2, 3))
    assertEquals(listOf(1, 3), encrypted)
    assertEquals(3, list.messages.size)
    assertEquals(1, list.messages.count { it.content != "ORDINARY-1" && it.content != "ORDINARY-3" })
  }

  @Test
  fun `with no other device the request still holds the CollieSocket entry`() {
    val list = build(emptyList())
    assertEquals(listOf(2), list.devices)
    assertTrue(encrypted.isEmpty())
  }

  @Test
  fun `a rebuild after a stale device keeps the identical frame and only the registration ID changes`() {
    val before = build(listOf(1, 3), registrationId = 4242).messages.single { it.destinationDeviceId == 2 }
    val after = build(listOf(1, 3, 4), registrationId = 5151).messages.single { it.destinationDeviceId == 2 }
    assertEquals(before.content, after.content)
    assertEquals(before.type, after.type)
    assertEquals(5151, after.destinationRegistrationId)
  }
}
