/*
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.calls.permissions

enum class DirectMessageKind { OFFER, ANSWER, ICE, HANGUP, BUSY }
enum class GroupMessageKind { RING, CANCEL, RING_RESPONSE, GROUP_DATA }

/** Tickets are process-local, short lived, and cannot be constructed by a remote payload. */
class CallAuthorization internal constructor(
  internal val generation: Long,
  internal val session: Long,
  internal val context: CallContext,
  internal val recipient: Long,
  internal val sender: Long,
  internal val sourceDevice: Int,
  internal val callId: Long?,
  internal val directKind: DirectMessageKind? = null,
  internal val groupKind: GroupMessageKind? = null,
  internal val expiresAt: Long
)

/** Pure managed admission state. All production access is serialized by CallPermissions' lock. */
internal class CallSessionAuthorizer(private val clock: () -> Long) {
  companion object {
    const val SIGNALING_LEASE_MS = 120_000L
    const val ACTIVE_LEASE_MS = 86_400_000L
    const val MAX_MESSAGE_AGE_MS = 120_000L
  }

  private data class Session(
    val serial: Long,
    val context: CallContext,
    val recipient: Long,
    val outgoing: Boolean,
    var callId: Long?,
    val sourceDevice: Int?,
    val initiator: Long,
    var expiresAt: Long,
    var active: Boolean = false
  )

  private var serial = 0L
  private var current: Session? = null
  private var glare: Session? = null
  var generation: Long = 1L
    private set

  fun revoke() {
    generation++
    current = null
    glare = null
  }

  fun beginOutgoing(context: CallContext, recipient: Long): Boolean {
    val existing = live()
    if (existing != null) return existing.outgoing && existing.context == context && existing.recipient == recipient
    current = Session(++serial, context, recipient, true, null, null, recipient, clock() + SIGNALING_LEASE_MS, context != CallContext.DIRECT)
    return true
  }

  fun bindOutgoing(recipient: Long, callId: Long): Boolean {
    val s = live() ?: return false
    if (!s.outgoing || s.context != CallContext.DIRECT || s.recipient != recipient || (s.callId != null && s.callId != callId)) return false
    s.callId = callId
    return true
  }

  fun activate(recipient: Long): Boolean {
    val s = live() ?: return false
    if (s.recipient != recipient) return false
    s.active = true
    s.expiresAt = clock() + ACTIVE_LEASE_MS
    glare?.takeIf { it.recipient == recipient }?.let { it.active = true; it.expiresAt = clock() + ACTIVE_LEASE_MS }
    return true
  }

  fun end(recipient: Long, callId: Long) {
    current?.let { if (it.recipient == recipient && (it.callId == callId || it.callId == null)) current = null }
    glare?.let { if (it.recipient == recipient && it.callId == callId) glare = null }
    live()
  }

  fun hasSession(recipient: Long): Boolean = live()?.recipient == recipient

  fun clear() { current = null; glare = null }

  fun sharingSession(context: CallContext, recipient: Long?): Long? = live()?.takeIf {
    it.context == context && (recipient == null || it.recipient == recipient) && (context == CallContext.DIRECT || it.active)
  }?.serial

  fun direct(recipient: Long, callId: Long, device: Int, kind: DirectMessageKind, age: Long, incomingAllowed: Boolean): CallAuthorization? {
    if (!fresh(device, age)) return null
    val primary = live()
    var s = primary?.takeIf { it.callId == callId } ?: glare?.takeIf { it.callId == callId }
    if (kind == DirectMessageKind.OFFER) {
      if (!incomingAllowed) return null
      if (primary == null) {
        s = Session(++serial, CallContext.DIRECT, recipient, false, callId, device, recipient, clock() + SIGNALING_LEASE_MS)
        current = s
      } else if (s == null && primary.context == CallContext.DIRECT && primary.outgoing && primary.recipient == recipient && glare == null) {
        // Let RingRTC resolve simultaneous calls from the SAME permitted peer. This does not
        // authorize an unrelated caller or turn an incoming BLOCK into an outgoing exception.
        s = Session(++serial, CallContext.DIRECT, recipient, false, callId, device, recipient, clock() + SIGNALING_LEASE_MS)
        glare = s
      }
    }
    s ?: return null
    if (s.context != CallContext.DIRECT || s.recipient != recipient || s.callId != callId) return null
    if (kind == DirectMessageKind.OFFER && (s.outgoing || s.sourceDevice != device)) return null
    if ((kind == DirectMessageKind.ANSWER || kind == DirectMessageKind.BUSY) && !s.outgoing) return null
    // An outgoing offer is broadcast to the peer's authenticated devices. All can answer or send
    // ICE/busy/hangup for that exact call; incoming calls stay bound to their offering device.
    if (!s.outgoing && s.sourceDevice != device) return null
    return ticket(s, recipient, device, kind, null)
  }

  fun group(context: CallContext, recipient: Long, sender: Long, ringId: Long?, device: Int, kind: GroupMessageKind, age: Long, incomingAllowed: Boolean): CallAuthorization? {
    if (!fresh(device, age) || context == CallContext.DIRECT) return null
    var s = live()
    if (kind == GroupMessageKind.RING) {
      if (context != CallContext.GROUP || !incomingAllowed || ringId == null) return null
      if (s == null) {
        s = Session(++serial, context, recipient, false, ringId, device, sender, clock() + SIGNALING_LEASE_MS)
        current = s
      }
    }
    s ?: return null
    if (s.context != context || s.recipient != recipient) return null
    when (kind) {
      GroupMessageKind.RING -> if (s.outgoing || s.callId != ringId || s.sourceDevice != device || s.initiator != sender) return null
      GroupMessageKind.CANCEL -> if (ringId == null || s.callId != ringId || s.sourceDevice != device || s.initiator != sender) return null
      GroupMessageKind.RING_RESPONSE -> if (ringId == null || s.callId != ringId) return null
      GroupMessageKind.GROUP_DATA -> if (!s.active) return null
    }
    return ticket(s, sender, device, null, kind)
  }

  fun valid(ticket: CallAuthorization): Boolean {
    val primary = live()
    val s = primary?.takeIf { it.serial == ticket.session } ?: glare?.takeIf { it.serial == ticket.session } ?: return false
    return ticket.generation == generation && ticket.session == s.serial && ticket.recipient == s.recipient &&
      ticket.context == s.context && clock() < ticket.expiresAt
  }

  private fun ticket(s: Session, sender: Long, device: Int, direct: DirectMessageKind?, group: GroupMessageKind?) = CallAuthorization(
    generation, s.serial, s.context, s.recipient, sender, device, s.callId, direct, group, minOf(s.expiresAt, clock() + SIGNALING_LEASE_MS)
  )

  private fun fresh(device: Int, age: Long): Boolean = device > 0 && age in 0..MAX_MESSAGE_AGE_MS

  private fun live(): Session? {
    if (current?.let { clock() >= it.expiresAt } == true) current = null
    if (glare?.let { clock() >= it.expiresAt } == true) glare = null
    if (current == null) { current = glare; glare = null }
    return current
  }
}
