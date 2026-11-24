package org.thoughtcrime.securesms.calls.permissions

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallLinkRosterTest {
  private var now = 0L
  private val sessions = CallSessionAuthorizer { now }
  private val room = 9L

  @Test
  fun `an explicit link session alone never authorizes an unrelated sender`() {
    assertTrue(sessions.beginOutgoing(CallContext.CALL_LINK, room))
    val key = sessions.callLinkSession(room)!!
    sessions.updateCallLinkMembers(key, setOf(CallLinkMember(1, 16)))

    assertTrue(sessions.valid(candidate(1, 16)))
    val unrelated = candidate(2, 32)
    assertFalse(sessions.valid(unrelated))
    assertTrue(sessions.validPendingLink(unrelated))
    assertNull(sessions.authorizePendingLink(unrelated))
    assertTrue(sessions.valid(candidate(1, 16)))
  }

  @Test
  fun `sender and SFU device must both match while legitimate linked devices remain possible`() {
    sessions.beginOutgoing(CallContext.CALL_LINK, room)
    sessions.updateCallLinkMembers(sessions.callLinkSession(room)!!, setOf(CallLinkMember(1, 16), CallLinkMember(1, 32), CallLinkMember(2, 48)))
    assertTrue(sessions.valid(candidate(1, 16, sourceDevice = 2)))
    assertTrue(sessions.valid(candidate(1, 32, sourceDevice = 3)))
    assertFalse(sessions.valid(candidate(1, 48)))
    assertFalse(sessions.valid(candidate(2, 16)))
    assertNull(sessions.bindCallLinkDevice(unbound(1), 0))
    assertNull(sessions.bindCallLinkDevice(unbound(1), 0x1_0000_0000L))
  }

  @Test
  fun `early key can be promoted only after its exact authenticated SFU pair joins`() {
    sessions.beginOutgoing(CallContext.CALL_LINK, room)
    val pending = candidate(1, 16)
    val key = sessions.callLinkSession(room)!!
    sessions.updateCallLinkMembers(key, setOf(CallLinkMember(1, 32)))
    assertNull(sessions.authorizePendingLink(pending))
    sessions.updateCallLinkMembers(key, setOf(CallLinkMember(1, 16), CallLinkMember(1, 32)))
    val promoted = sessions.authorizePendingLink(pending)
    assertNotNull(promoted)
    assertTrue(sessions.valid(promoted!!))
    assertFalse(sessions.valid(pending)) // The candidate itself is never dispatchable.
    now = CallSessionAuthorizer.SIGNALING_LEASE_MS
    assertFalse(sessions.valid(promoted))
  }

  @Test
  fun `departure and rejoin invalidate queued tickets without cancelling other participants`() {
    sessions.beginOutgoing(CallContext.CALL_LINK, room)
    val key = sessions.callLinkSession(room)!!
    val first = CallLinkMember(1, 16)
    val second = CallLinkMember(2, 32)
    sessions.updateCallLinkMembers(key, setOf(first, second))
    val firstTicket = candidate(1, 16)
    val secondTicket = candidate(2, 32)

    sessions.updateCallLinkMembers(key, setOf(second))
    assertFalse(sessions.valid(firstTicket))
    assertTrue(sessions.valid(secondTicket))
    assertFalse(sessions.hasCallLinkParticipant(1))
    assertTrue(sessions.hasCallLinkParticipant(2))
    sessions.updateCallLinkMembers(key, setOf(first, second))
    assertFalse(sessions.valid(firstTicket))
    assertNull(sessions.bindCallLinkDevice(firstTicket, 16))
    assertTrue(sessions.valid(secondTicket))
    assertTrue(sessions.valid(candidate(1, 16)))
  }

  @Test
  fun `old callback and pending keys cannot cross hangup or revoke into a new same-room call`() {
    for (revoke in listOf(false, true)) {
      sessions.beginOutgoing(CallContext.CALL_LINK, room)
      val oldKey = sessions.callLinkSession(room)!!
      val oldPending = candidate(1, 16)
      if (revoke) sessions.revoke() else sessions.clear()
      sessions.beginOutgoing(CallContext.CALL_LINK, room)
      assertFalse(sessions.updateCallLinkMembers(oldKey, setOf(CallLinkMember(1, 16))))
      val newKey = sessions.callLinkSession(room)!!
      sessions.updateCallLinkMembers(newKey, setOf(CallLinkMember(1, 16)))
      assertFalse(sessions.validPendingLink(oldPending))
      assertNull(sessions.authorizePendingLink(oldPending))
      assertTrue(sessions.valid(candidate(1, 16)))
      sessions.clear()
    }
  }

  @Test
  fun `joined participant can receive profile response before its media device is ready`() {
    sessions.beginOutgoing(CallContext.CALL_LINK, room)
    val key = sessions.callLinkSession(room)!!
    sessions.updateCallLinkMembers(key, emptySet(), setOf(1))
    assertTrue(sessions.hasCallLinkParticipant(1))
    assertFalse(sessions.valid(candidate(1, 16)))
    sessions.updateCallLinkMembers(key, emptySet(), emptySet())
    assertFalse(sessions.hasCallLinkParticipant(1))
  }

  private fun unbound(sender: Long, sourceDevice: Int = 2) = sessions.group(CallContext.CALL_LINK, room, sender, null, sourceDevice, GroupMessageKind.GROUP_DATA, 0, false)!!

  private fun candidate(sender: Long, demux: Long, sourceDevice: Int = 2) = sessions.bindCallLinkDevice(unbound(sender, sourceDevice), demux)!!
}
