/* SPDX-License-Identifier: AGPL-3.0-only */
package org.thoughtcrime.securesms.calls.permissions

import org.junit.Assert.*
import org.junit.Test

class CallSessionAuthorizerTest {
  private var now = 1_000L
  private val sessions = CallSessionAuthorizer { now }

  @Test
  fun `all denied direct categories have no dispatch ticket`() {
    for (kind in DirectMessageKind.entries) assertNull(sessions.direct(1, 9, 1, kind, 0, false))
  }

  @Test
  fun `another allowed call cannot authorize any denied peer category`() {
    assertNotNull(sessions.direct(1, 9, 1, DirectMessageKind.OFFER, 0, true))
    for (kind in DirectMessageKind.entries) assertNull(sessions.direct(2, 9, 1, kind, 0, false))
    assertNull(sessions.direct(2, 10, 1, DirectMessageKind.OFFER, 0, true))
  }

  @Test
  fun `explicit outgoing call authorizes only its continuation from linked peer devices`() {
    assertTrue(sessions.beginOutgoing(CallContext.DIRECT, 1))
    assertTrue(sessions.bindOutgoing(1, 9))
    for (device in listOf(1, 2, 7)) {
      for (kind in listOf(DirectMessageKind.ANSWER, DirectMessageKind.ICE, DirectMessageKind.HANGUP, DirectMessageKind.BUSY)) {
        assertNotNull(sessions.direct(1, 9, device, kind, 0, false))
        assertNull(sessions.direct(1, 10, device, kind, 0, false))
        assertNull(sessions.direct(2, 9, device, kind, 0, false))
      }
    }
    assertNull(sessions.direct(1, 9, 2, DirectMessageKind.OFFER, 0, false))
  }

  @Test
  fun `incoming continuation is bound to offering device and direction`() {
    assertNotNull(sessions.direct(1, 9, 2, DirectMessageKind.OFFER, 0, true))
    assertNotNull(sessions.direct(1, 9, 2, DirectMessageKind.ICE, 0, true))
    assertNull(sessions.direct(1, 9, 3, DirectMessageKind.ICE, 0, true))
    assertNull(sessions.direct(1, 9, 2, DirectMessageKind.ANSWER, 0, true))
  }

  @Test
  fun `revocation invalidates queued work even after same call is admitted again`() {
    val old = sessions.direct(1, 9, 1, DirectMessageKind.OFFER, 0, true)!!
    sessions.revoke()
    assertFalse(sessions.valid(old))
    assertNotNull(sessions.direct(1, 9, 1, DirectMessageKind.OFFER, 0, true))
    assertFalse(sessions.valid(old))
  }

  @Test
  fun `stale future invalid device and expired work are denied`() {
    assertNull(sessions.direct(1, 9, 1, DirectMessageKind.OFFER, -1, true))
    assertNull(sessions.direct(1, 9, 0, DirectMessageKind.OFFER, 0, true))
    assertNull(sessions.direct(1, 9, 1, DirectMessageKind.OFFER, 120_001, true))
    val ticket = sessions.direct(1, 9, 1, DirectMessageKind.OFFER, 0, true)!!
    now += 120_001
    assertFalse(sessions.valid(ticket))
    assertNull(sessions.direct(1, 9, 1, DirectMessageKind.ICE, 0, true))
  }

  @Test
  fun `group data requires the precise active group and never a direct permission`() {
    assertNotNull(sessions.direct(1, 9, 1, DirectMessageKind.OFFER, 0, true))
    assertNull(sessions.group(CallContext.GROUP, 2, 1, null, 1, GroupMessageKind.GROUP_DATA, 0, true))
    sessions.revoke()
    assertNotNull(sessions.group(CallContext.GROUP, 2, 1, 10, 1, GroupMessageKind.RING, 0, true))
    assertNull(sessions.group(CallContext.GROUP, 2, 1, null, 1, GroupMessageKind.GROUP_DATA, 0, true))
    assertTrue(sessions.activate(2))
    assertNotNull(sessions.group(CallContext.GROUP, 2, 1, null, 2, GroupMessageKind.GROUP_DATA, 0, true))
    assertNull(sessions.group(CallContext.GROUP, 3, 1, null, 2, GroupMessageKind.GROUP_DATA, 0, true))
    assertNull(sessions.group(CallContext.GROUP, 2, 1, 11, 1, GroupMessageKind.CANCEL, 0, true))
  }

  @Test
  fun `call links require explicit session and cannot receive rings`() {
    assertNull(sessions.group(CallContext.CALL_LINK, 4, 1, null, 1, GroupMessageKind.GROUP_DATA, 0, true))
    assertTrue(sessions.beginOutgoing(CallContext.CALL_LINK, 4))
    assertNotNull(sessions.group(CallContext.CALL_LINK, 4, 1, null, 1, GroupMessageKind.GROUP_DATA, 0, false))
    assertNull(sessions.group(CallContext.CALL_LINK, 4, 1, 1, 1, GroupMessageKind.RING, 0, true))
  }

  @Test
  fun `ending a call invalidates its tickets`() {
    val ticket = sessions.direct(1, 9, 1, DirectMessageKind.OFFER, 0, true)!!
    sessions.end(1, 9)
    assertFalse(sessions.valid(ticket))
  }
  @Test
  fun `same peer glare preserves native resolution without admitting unrelated callers`() {
    assertTrue(sessions.beginOutgoing(CallContext.DIRECT, 1))
    assertTrue(sessions.bindOutgoing(1, 9))
    val incoming = sessions.direct(1, 10, 2, DirectMessageKind.OFFER, 0, true)!!
    assertNull(sessions.direct(2, 11, 1, DirectMessageKind.OFFER, 0, true))
    assertNotNull(sessions.direct(1, 10, 2, DirectMessageKind.ICE, 0, true))
    assertNull(sessions.direct(1, 10, 3, DirectMessageKind.ICE, 0, true))
    sessions.end(1, 9)
    assertTrue(sessions.valid(incoming))
    sessions.end(1, 10)
    assertFalse(sessions.valid(incoming))
  }

  @Test
  fun `outgoing exception does not authorize blocked same peer glare`() {
    sessions.beginOutgoing(CallContext.DIRECT, 1)
    sessions.bindOutgoing(1, 9)
    assertNull(sessions.direct(1, 10, 2, DirectMessageKind.OFFER, 0, false))
  }

  @Test
  fun `group ring and cancellation bind authenticated ringer and device`() {
    assertNotNull(sessions.group(CallContext.GROUP, 2, 1, 10, 1, GroupMessageKind.RING, 0, true))
    assertNull(sessions.group(CallContext.GROUP, 2, 3, 10, 1, GroupMessageKind.RING, 0, true))
    assertNull(sessions.group(CallContext.GROUP, 2, 3, 10, 1, GroupMessageKind.CANCEL, 0, true))
    assertNull(sessions.group(CallContext.GROUP, 2, 1, 10, 2, GroupMessageKind.CANCEL, 0, true))
    assertNotNull(sessions.group(CallContext.GROUP, 2, 1, 10, 1, GroupMessageKind.CANCEL, 0, true))
  }

}
