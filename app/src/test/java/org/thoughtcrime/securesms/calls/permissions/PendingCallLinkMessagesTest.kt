package org.thoughtcrime.securesms.calls.permissions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class PendingCallLinkMessagesTest {
  private var now = 0L
  private val sessions = CallSessionAuthorizer { now }
  private val pending = PendingCallLinkMessages { now }
  private val sender = UUID(0, 1)

  @Test
  fun `retained early keys keep source device original age and strict short expiry`() {
    val ticket = candidate()
    assertTrue(pending.offer(ticket, sender, byteArrayOf(1), 4_000))
    now = 29_999
    val message = pending.take(key()).single()
    assertEquals(33_999L, message.ageAt(now))
    assertEquals(2, message.authorization.sourceDevice)
    assertTrue(pending.put(message))
    now = 30_000
    assertTrue(pending.take(key()).isEmpty())
  }

  @Test
  fun `an already old envelope expires before the buffer lease`() {
    assertTrue(pending.offer(candidate(), sender, byteArrayOf(1), 119_000))
    now = 1_000
    assertTrue(pending.take(key()).isEmpty())
  }

  @Test
  fun `a later session can never receive an old frame or be erased by an old callback`() {
    val old = candidate()
    val oldKey = key()
    assertTrue(pending.offer(old, sender, byteArrayOf(1), 0))
    sessions.clear()
    val current = candidate()
    assertTrue(pending.offer(current, sender, byteArrayOf(2), 0))
    assertFalse(pending.offer(old, sender, byteArrayOf(3), 0))
    assertTrue(pending.take(oldKey).isEmpty())
    assertEquals(2.toByte(), pending.take(key()).single().opaque.single())
  }

  @Test
  fun `deduplication and per-sender bound leave capacity for other legitimate callers`() {
    val ticket = candidate()
    assertTrue(pending.offer(ticket, sender, byteArrayOf(1), 0))
    assertFalse(pending.offer(ticket, sender, byteArrayOf(1), 0))
    for (index in 2..PendingCallLinkMessages.MAX_PER_SENDER) assertTrue(pending.offer(ticket, sender, byteArrayOf(index.toByte()), 0))
    assertFalse(pending.offer(ticket, sender, byteArrayOf(9), 0))
    assertTrue(pending.offer(candidate(senderId = 2), UUID(0, 2), byteArrayOf(9), 0))
    assertEquals(PendingCallLinkMessages.MAX_PER_SENDER + 1, pending.take(key()).size)
  }

  @Test
  fun `total message and byte budgets bound untrusted pending input`() {
    for (senderId in 1..PendingCallLinkMessages.MAX_MESSAGES) {
      assertTrue(pending.offer(candidate(senderId.toLong()), UUID(0, senderId.toLong()), byteArrayOf(1), 0))
    }
    assertFalse(pending.offer(candidate(100), UUID(0, 100), byteArrayOf(1), 0))
    pending.clear()
    for (senderId in 1..4) {
      assertTrue(pending.offer(candidate(senderId.toLong()), UUID(0, senderId.toLong()), ByteArray(64 * 1024), 0))
    }
    assertFalse(pending.offer(candidate(100), UUID(0, 100), byteArrayOf(1), 0))
    assertEquals(4, pending.take(key()).size)
  }

  @Test
  fun `clearing removes all queued keys and already admitted tickets cannot be buffered`() {
    val candidate = candidate()
    pending.offer(candidate, sender, byteArrayOf(1), 0)
    pending.clear()
    assertTrue(pending.take(key()).isEmpty())
    sessions.updateCallLinkMembers(key(), setOf(CallLinkMember(1, 16)))
    val admitted = sessions.authorizePendingLink(candidate)!!
    assertFalse(pending.offer(admitted, sender, byteArrayOf(1), 0))
  }

  @Test
  fun `enqueue race can claim its frame exactly once without duplicating roster replay`() {
    val ticket = candidate()
    pending.offer(ticket, sender, byteArrayOf(1), 0)
    assertTrue(pending.remove(ticket))
    assertTrue(pending.take(key()).isEmpty())
    pending.offer(ticket, sender, byteArrayOf(1), 0)
    assertEquals(1, pending.take(key()).size)
    assertFalse(pending.remove(ticket))
  }

  private fun key() = sessions.callLinkSession(9)!!

  private fun candidate(senderId: Long = 1): CallAuthorization {
    sessions.beginOutgoing(CallContext.CALL_LINK, 9)
    val ticket = sessions.group(CallContext.CALL_LINK, 9, senderId, null, 2, GroupMessageKind.GROUP_DATA, 0, false)!!
    return sessions.bindCallLinkDevice(ticket, 16)!!
  }
}
