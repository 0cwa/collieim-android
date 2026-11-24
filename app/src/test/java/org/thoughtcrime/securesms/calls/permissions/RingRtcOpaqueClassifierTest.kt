package org.thoughtcrime.securesms.calls.permissions

import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.thoughtcrime.securesms.calls.permissions.proto.GroupDeviceMessage
import org.thoughtcrime.securesms.calls.permissions.proto.OpaqueCallMessage

class RingRtcOpaqueClassifierTest {
  private val groupId = ByteArray(32) { 7 }.toByteString()

  @Test
  fun `ring is identified without native decoding`() {
    val message = OpaqueCallMessage(ringIntention = ring())
    val result = RingRtcOpaqueClassifier.classify(message.encode())!!

    assertEquals(GroupMessageKind.RING, result.kind)
    assertEquals(17L, result.ringId)
    assertArrayEquals(groupId.toByteArray(), result.groupId)
    assertArrayEquals(message.encode(), result.opaque)
  }

  @Test
  fun `cancel and linked device responses remain distinct`() {
    assertEquals(
      GroupMessageKind.CANCEL,
      RingRtcOpaqueClassifier.classify(OpaqueCallMessage(ringIntention = ring().copy(type = OpaqueCallMessage.RingIntention.Type.CANCELLED)).encode())!!.kind
    )
    for (type in listOf(OpaqueCallMessage.RingResponse.Type.ACCEPTED, OpaqueCallMessage.RingResponse.Type.DECLINED, OpaqueCallMessage.RingResponse.Type.BUSY)) {
      val response = OpaqueCallMessage(ringResponse = OpaqueCallMessage.RingResponse(groupId, type, 17L))
      assertEquals(GroupMessageKind.RING_RESPONSE, RingRtcOpaqueClassifier.classify(response.encode())!!.kind)
    }
    assertNull(RingRtcOpaqueClassifier.classify(OpaqueCallMessage(ringResponse = OpaqueCallMessage.RingResponse(groupId, OpaqueCallMessage.RingResponse.Type.RINGING, 17L)).encode()))
  }

  @Test
  fun `multiple outer fields never hide a second context`() {
    val otherGroup = ByteArray(32) { 3 }.toByteString()
    val data = GroupDeviceMessage(groupId = otherGroup, leaving = GroupDeviceMessage.Leaving(16))
    val response = OpaqueCallMessage.RingResponse(otherGroup, OpaqueCallMessage.RingResponse.Type.ACCEPTED, 9L)
    assertNull(RingRtcOpaqueClassifier.classify(OpaqueCallMessage(groupCallMessage = data, ringIntention = ring()).encode()))
    assertNull(RingRtcOpaqueClassifier.classify(OpaqueCallMessage(ringIntention = ring(), ringResponse = response).encode()))
    assertNull(RingRtcOpaqueClassifier.classify(OpaqueCallMessage(groupCallMessage = data, ringResponse = response).encode()))
  }

  @Test
  fun `group media and leaving have context but no invented call ID`() {
    val media = GroupDeviceMessage(groupId = groupId, mediaKey = GroupDeviceMessage.MediaKey(0, ByteArray(32).toByteString(), 16))
    val leaving = GroupDeviceMessage(groupId = groupId, leaving = GroupDeviceMessage.Leaving(16))
    for (data in listOf(media, leaving)) {
      val result = RingRtcOpaqueClassifier.classify(OpaqueCallMessage(groupCallMessage = data).encode())!!
      assertEquals(GroupMessageKind.GROUP_DATA, result.kind)
      assertNull(result.ringId)
    }
  }

  @Test
  fun `group variants cannot be combined or sent over the wrong transport`() {
    val media = GroupDeviceMessage.MediaKey(0, ByteArray(32).toByteString(), 16)
    val leaving = GroupDeviceMessage.Leaving(16)
    assertNull(RingRtcOpaqueClassifier.classify(OpaqueCallMessage(groupCallMessage = GroupDeviceMessage(groupId, mediaKey = media, leaving = leaving)).encode()))
    assertNull(RingRtcOpaqueClassifier.classify(OpaqueCallMessage(groupCallMessage = GroupDeviceMessage(groupId, heartbeat = GroupDeviceMessage.Heartbeat(audioMuted = true))).encode()))
    assertNull(RingRtcOpaqueClassifier.classify(OpaqueCallMessage(groupCallMessage = GroupDeviceMessage(groupId, reaction = GroupDeviceMessage.Reaction("x"))).encode()))
    assertNull(RingRtcOpaqueClassifier.classify(OpaqueCallMessage(groupCallMessage = GroupDeviceMessage(groupId, remoteMuteRequest = GroupDeviceMessage.RemoteMuteRequest(16))).encode()))
  }

  @Test
  fun `missing or malformed required classification fields fail closed`() {
    for (invalid in listOf(ring().copy(groupId = null), ring().copy(groupId = ByteArray(31).toByteString()), ring().copy(type = null), ring().copy(ringId = null))) {
      assertNull(RingRtcOpaqueClassifier.classify(OpaqueCallMessage(ringIntention = invalid).encode()))
    }
    for (invalid in listOf(
      GroupDeviceMessage.MediaKey(null, ByteArray(32).toByteString(), 16),
      GroupDeviceMessage.MediaKey(256, ByteArray(32).toByteString(), 16),
      GroupDeviceMessage.MediaKey(-1, ByteArray(32).toByteString(), 16),
      GroupDeviceMessage.MediaKey(0, ByteArray(31).toByteString(), 16),
      GroupDeviceMessage.MediaKey(0, ByteArray(32).toByteString(), null)
    )) {
      assertNull(RingRtcOpaqueClassifier.classify(OpaqueCallMessage(groupCallMessage = GroupDeviceMessage(groupId, mediaKey = invalid)).encode()))
    }
  }

  @Test
  fun `empty truncated oversized and unknown fields never reach dispatch`() {
    assertNull(RingRtcOpaqueClassifier.classify(byteArrayOf()))
    assertNull(RingRtcOpaqueClassifier.classify(byteArrayOf(0x12, 0x7f)))
    assertNull(RingRtcOpaqueClassifier.classify(ByteArray(RingRtcOpaqueClassifier.MAX_MESSAGE_BYTES + 1)))
    val valid = OpaqueCallMessage(ringIntention = ring()).encode()
    assertNull(RingRtcOpaqueClassifier.classify(valid + byteArrayOf(0x20, 1)))
    assertNull(RingRtcOpaqueClassifier.classify(OpaqueCallMessage(ringIntention = ring().copy(unknownFields = byteArrayOf(0x20, 1).toByteString())).encode()))
  }

  @Test
  fun `duplicate message fields are normalized before native sees them`() {
    val first = OpaqueCallMessage(ringIntention = ring()).encode()
    val second = OpaqueCallMessage(ringIntention = ring().copy(groupId = ByteArray(32) { 3 }.toByteString(), ringId = 29)).encode()
    val result = RingRtcOpaqueClassifier.classify(first + second)
    assertNotNull(result)
    assertEquals(29L, result!!.ringId)
    assertTrue(result.opaque.size < first.size + second.size)
    assertArrayEquals(second, result.opaque)
    assertArrayEquals(result.groupId, RingRtcOpaqueClassifier.classify(result.opaque)!!.groupId)
  }

  private fun ring() = OpaqueCallMessage.RingIntention(groupId, OpaqueCallMessage.RingIntention.Type.RING, 17L)
}
