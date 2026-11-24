/* SPDX-License-Identifier: AGPL-3.0-only */
package org.thoughtcrime.securesms.calls.permissions

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.signal.core.models.ServiceId
import org.signal.core.models.recipients.RecipientId
import org.thoughtcrime.securesms.database.RecipientTable
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.recipients.RecipientIds
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Shared policy/settings boundary for calling. No native dependency and no notification/contact
 * heuristic. A ticket is checked again at serialized dispatch, immediately before remote data is
 * handed to RingRTC. Future capabilities can reuse the local record/INHERIT pattern independently.
 */
object CallPermissions {
  private val lock = Any()
  private val dispatchAuthorization = ThreadLocal<CallAuthorization>()
  private val processId = UUID.randomUUID().toString()
  private val epoch = AtomicLong(1)
  private val writes = AtomicInteger()
  private val unavailable = AtomicBoolean()
  private val changed = MutableStateFlow(1L)
  val changes: StateFlow<Long> = changed
  private val sessions = CallSessionAuthorizer(SystemClock::elapsedRealtime)
  private var observedEpoch = 1L
  @Volatile private var revocationListener: Runnable? = null

  @JvmStatic
  fun settings(): CallPermissionSettings {
    val state = state()
    return CallPermissionSettings(state != null, state?.allCallingDisabled ?: true, state?.directDefault ?: CallPermission.BLOCK, state?.groupDefault ?: CallPermission.BLOCK, epoch.get())
  }

  @JvmStatic
  fun setAllCallingDisabled(disabled: Boolean) = mutate { it.copy(allCallingDisabled = disabled) }

  @JvmStatic
  fun setDefault(context: CallContext, permission: CallPermission) {
    require(permission != CallPermission.INHERIT && context != CallContext.CALL_LINK)
    mutate { if (context == CallContext.DIRECT) it.copy(directDefault = permission) else it.copy(groupDefault = permission) }
  }

  @JvmStatic
  fun getOverride(recipient: Recipient): CallPermission {
    val resolved = Recipient.resolved(recipient.id)
    return state()?.override(context(resolved), resolved.id.toLong()) ?: CallPermission.BLOCK
  }

  @JvmStatic
  fun setOverride(recipient: Recipient, permission: CallPermission) {
    // Serialize identity resolution and the durable write with recipient merges. The live
    // recipient cache may still expose the secondary ID until a merge's transaction commits.
    // Keep the same database -> values lock order as onRecipientMerge, without the session lock.
    SignalDatabase.runInTransaction {
      val record = SignalDatabase.recipients.getRecord(recipient.id)
      val context = when (record.recipientType) {
        RecipientTable.RecipientType.INDIVIDUAL -> CallContext.DIRECT
        RecipientTable.RecipientType.GV2 -> CallContext.GROUP
        else -> throw IllegalArgumentException("Call permission overrides require a contact or v2 group")
      }
      mutate { it.withOverride(context, record.id.toLong(), permission) }
    }
  }

  @JvmStatic
  fun isCallingAllowed(): Boolean {
    val snapshot = policySnapshot() ?: return false
    return !snapshot.state.allCallingDisabled && accountReady() && snapshotCurrent(snapshot)
  }

  private fun accountReady(): Boolean = runCatching {
    SignalStore.isInitialized && SignalStore.account.isRegistered && SignalStore.account.aci != null && !SignalStore.account.isUnauthorizedReceived
  }.getOrDefault(false)

  @JvmStatic
  fun canUseCallLinks(): Boolean = isCallingAllowed()

  @JvmStatic
  fun generation(): Long { state(); return epoch.get() }

  @JvmStatic
  fun isCurrent(generation: Long): Boolean = isCallingAllowed() && writes.get() == 0 && generation == epoch.get()

  @JvmStatic
  fun canStartCall(recipient: Recipient): Boolean = runCatching {
    val resolved = Recipient.resolved(recipient.id)
    !resolved.isBlocked && !resolved.isSelf && isCallingAllowed() && when {
      resolved.isPushV2Group -> SignalDatabase.groups.getGroup(resolved.id).orElse(null)?.isActive == true
      else -> resolved.isIndividual || resolved.isCallLink
    }
  }.getOrDefault(false)

  @JvmStatic
  fun canReceiveGroup(recipientId: RecipientId): Boolean = runCatching {
    val recipient = Recipient.resolved(recipientId)
    val snapshot = policySnapshot() ?: return false
    recipient.isPushV2Group && !recipient.isBlocked && SignalDatabase.groups.getGroup(recipient.id).orElse(null)?.isActive == true &&
      snapshot.state.allowsIncoming(CallContext.GROUP, recipient.id.toLong()) && accountReady() && snapshotCurrent(snapshot)
  }.getOrDefault(false)

  @JvmStatic
  fun canUseGroup(recipientId: RecipientId): Boolean = canReceiveGroup(recipientId) || canContinueCall(recipientId)

  @JvmStatic
  fun canPeekGroup(recipientId: RecipientId): Boolean = canUseGroup(recipientId)

  @JvmStatic
  fun beginOutgoing(recipientId: RecipientId): Boolean = runCatching {
    val recipient = Recipient.resolved(recipientId)
    if (!canStartCall(recipient)) return false
    synchronized(lock) {
      val snapshot = policySnapshot() ?: return false
      syncGeneration()
      if (snapshot.state.allCallingDisabled || !accountReady() || !snapshotCurrent(snapshot)) return false
      val began = sessions.beginOutgoing(context(recipient), recipient.id.toLong())
      if (snapshotCurrent(snapshot)) began else { syncGeneration(); false }
    }
  }.getOrDefault(false)

  @JvmStatic
  fun bindOutgoing(recipientId: RecipientId, callId: Long): Boolean = synchronized(lock) {
    syncGeneration()
    isCallingAllowed() && sessions.bindOutgoing(recipientId.toLong(), callId)
  }

  @JvmStatic
  fun activateCall(recipientId: RecipientId): Boolean = synchronized(lock) {
    syncGeneration()
    isCallingAllowed() && sessions.activate(recipientId.toLong())
  }

  @JvmStatic
  fun activateGroup(recipientId: RecipientId): Boolean = activateCall(recipientId)

  @JvmStatic
  fun canContinueCall(recipientId: RecipientId): Boolean = runCatching {
    val recipient = Recipient.resolved(recipientId)
    if (!canStartCall(recipient)) return false
    synchronized(lock) { syncGeneration(); sessions.hasSession(recipient.id.toLong()) }
  }.getOrDefault(false)

  @JvmStatic
  fun currentCallLinkSession(recipientId: RecipientId): CallSessionKey? = runCatching {
    val recipient = Recipient.resolved(recipientId)
    if (!recipient.isCallLink || recipient.isBlocked) return null
    synchronized(lock) {
      if (!isCallingAllowed()) return null
      syncGeneration()
      sessions.callLinkSession(recipient.id.toLong())
    }
  }.getOrNull()

  /** Called only from the serialized observer of the exact currently authorized GroupCall. */
  @JvmStatic
  fun updateCallLinkMembers(key: CallSessionKey, devices: Map<Long, UUID>, joinedMembers: Collection<UUID>): Boolean = runCatching {
    if (devices.size > 1024 || joinedMembers.size > 1024 || currentCallLinkSession(RecipientId.from(key.recipient)) != key) return false
    // The SFU has authenticated these joined users. Resolve the same local identities used by
    // incoming envelopes, outside the policy lock; no message request/profile is accepted here.
    val recipients = joinedMembers.distinct().mapNotNull { uuid ->
      val aci = ServiceId.ACI.from(uuid)
      if (aci == ServiceId.ACI.UNKNOWN) null else uuid to Recipient.resolved(RecipientIds.from(aci)).id.toLong()
    }.toMap()
    val members = devices.mapNotNull { (demux, uuid) ->
      recipients[uuid]?.takeIf { demux in 1..0xffff_ffffL }?.let { CallLinkMember(it, demux) }
    }.toSet()
    synchronized(lock) {
      if (!isCallingAllowed()) return false
      syncGeneration()
      sessions.updateCallLinkMembers(key, members, recipients.values.toSet())
    }
  }.getOrDefault(false)

  @JvmStatic
  fun bindCallLinkDevice(ticket: CallAuthorization, demuxId: Long): CallAuthorization? {
    if (!identityAllowed(ticket)) return null
    return synchronized(lock) {
      if (!isCallingAllowed()) return null
      syncGeneration()
      sessions.bindCallLinkDevice(ticket, demuxId)
    }
  }

  @JvmStatic
  fun isPendingCallLinkCurrent(ticket: CallAuthorization): Boolean {
    if (!identityAllowed(ticket)) return false
    return synchronized(lock) {
      if (!isCallingAllowed()) return false
      syncGeneration()
      sessions.validPendingLink(ticket)
    }
  }

  @JvmStatic
  fun authorizePendingCallLink(ticket: CallAuthorization): CallAuthorization? {
    if (!identityAllowed(ticket)) return null
    return synchronized(lock) {
      if (!isCallingAllowed()) return null
      syncGeneration()
      sessions.authorizePendingLink(ticket)
    }
  }

  @JvmStatic
  fun isCallLinkParticipant(recipientId: RecipientId): Boolean = runCatching {
    val recipient = Recipient.resolved(recipientId)
    if (recipient.isBlocked) return false
    synchronized(lock) {
      if (!isCallingAllowed()) return false
      syncGeneration()
      sessions.hasCallLinkParticipant(recipient.id.toLong())
    }
  }.getOrDefault(false)

  /** Stamp remote-triggered peeks at message admission, before batching or persisted job queues. */
  @JvmStatic
  fun createGroupPeekPermit(recipientId: RecipientId, senderId: RecipientId): String? = runCatching {
    val generation = generation()
    val recipient = Recipient.resolved(recipientId)
    val sender = Recipient.resolved(senderId)
    if (!recipient.isPushV2Group || !canUseGroup(recipient.id) || !groupIdentityAllowed(recipient, sender, GroupMessageKind.GROUP_DATA) || !isCurrent(generation)) return null
    listOf("peek", processId, generation, recipient.id.toLong(), sender.id.toLong(), SystemClock.elapsedRealtime() + CallSessionAuthorizer.MAX_MESSAGE_AGE_MS).joinToString("|")
  }.getOrNull()

  @JvmStatic
  fun isGroupPeekPermitValid(permit: String?, recipientId: RecipientId, senderId: RecipientId): Boolean = runCatching {
    val parts = permit?.split('|') ?: return false
    if (parts.size != 6 || parts[0] != "peek" || parts[1] != processId || !isCurrent(parts[2].toLong()) || SystemClock.elapsedRealtime() >= parts[5].toLong()) return false
    val recipient = Recipient.resolved(recipientId)
    val sender = Recipient.resolved(senderId)
    parts[3].toLong() == recipient.id.toLong() && parts[4].toLong() == sender.id.toLong() && recipient.isPushV2Group &&
      canUseGroup(recipient.id) && groupIdentityAllowed(recipient, sender, GroupMessageKind.GROUP_DATA) && isCurrent(parts[2].toLong())
  }.getOrDefault(false)

  @JvmStatic
  fun endCall(recipientId: RecipientId, callId: Long) = synchronized(lock) { sessions.end(recipientId.toLong(), callId) }

  @JvmStatic
  fun isIncomingGroupRingCurrent(recipientId: RecipientId, ringId: Long, senderId: RecipientId): Boolean = runCatching {
    // Ring-specific authority can change while credentials are being fetched without a policy
    // write (for example, an announcement-group admin is demoted). Resolve outside the lock.
    val recipient = Recipient.resolved(recipientId)
    val sender = Recipient.resolved(senderId)
    if (recipient.id != recipientId || sender.id != senderId || !groupIdentityAllowed(recipient, sender, GroupMessageKind.RING)) return false
    synchronized(lock) {
      if (!isCallingAllowed()) return false
      syncGeneration()
      sessions.incomingGroupRingCurrent(recipientId.toLong(), ringId, senderId.toLong())
    }
  }.getOrDefault(false)

  @JvmStatic
  fun endIncomingGroupRing(recipientId: RecipientId, ringId: Long) = synchronized(lock) {
    sessions.endIncomingGroupRing(recipientId.toLong(), ringId)
  }

  @JvmStatic
  fun endAllSessions() {
    synchronized(lock) { sessions.clear() }
    GroupCallAdmission.clearPending()
  }

  @JvmStatic
  fun setRevocationListener(listener: Runnable) { revocationListener = listener }

  /** A deliberate contact grant can accept a call without accepting a message request or sharing a profile. */
  @JvmStatic
  fun hasExplicitIncomingAllow(recipientId: RecipientId): Boolean = runCatching {
    val recipient = Recipient.resolved(recipientId)
    val snapshot = policySnapshot() ?: return false
    recipient.isIndividual && !recipient.isBlocked && !snapshot.state.allCallingDisabled && accountReady() &&
      snapshot.state.override(CallContext.DIRECT, recipient.id.toLong()) == CallPermission.ALLOW && snapshotCurrent(snapshot)
  }.getOrDefault(false)

  @JvmStatic
  fun shouldSendBusy(recipientId: RecipientId): Boolean = runCatching {
    val recipient = Recipient.resolved(recipientId)
    if (!recipient.isIndividual || recipient.isBlocked || !isCallingAllowed()) return false
    synchronized(lock) {
      val snapshot = policySnapshot() ?: return false
      syncGeneration()
      snapshot.state.allowsIncoming(CallContext.DIRECT, recipient.id.toLong()) && sessions.hasSession() && snapshotCurrent(snapshot)
    }
  }.getOrDefault(false)

  @JvmStatic
  @JvmOverloads
  fun admitDirect(recipientId: RecipientId, callId: Long, sourceDeviceId: Int, kind: DirectMessageKind, messageAgeMillis: Long, expectedGeneration: Long? = null): CallAuthorization? {
    val recipient = runCatching { Recipient.resolved(recipientId) }.getOrNull() ?: return null
    if (!recipient.isIndividual || recipient.isBlocked || recipient.isSelf) return null
    return synchronized(lock) {
      val snapshot = policySnapshot() ?: return null
      syncGeneration()
      if (expectedGeneration != null && snapshot.generation != expectedGeneration) return null
      if (snapshot.state.allCallingDisabled || !accountReady() || !snapshotCurrent(snapshot)) return null
      val ticket = sessions.direct(recipient.id.toLong(), callId, sourceDeviceId, kind, messageAgeMillis, snapshot.state.allowsIncoming(CallContext.DIRECT, recipient.id.toLong())) ?: return null
      // Security writes deliberately do not take this lock (recipient merges hold a DB
      // transaction). Never stamp an old permission decision with a newer session generation.
      if (snapshotCurrent(snapshot)) ticket else { sessions.discard(ticket); null }
    }
  }

  @JvmStatic
  @JvmOverloads
  fun authorizeGroup(recipientId: RecipientId, senderId: RecipientId, ringId: Long?, sourceDeviceId: Int, kind: GroupMessageKind, messageAgeMillis: Long, expectedGeneration: Long? = null): CallAuthorization? {
    val recipient = runCatching { Recipient.resolved(recipientId) }.getOrNull() ?: return null
    val sender = runCatching { Recipient.resolved(senderId) }.getOrNull() ?: return null
    val context = context(recipient)
    if (!runCatching { groupIdentityAllowed(recipient, sender, kind) }.getOrDefault(false)) return null
    return synchronized(lock) {
      val snapshot = policySnapshot() ?: return null
      syncGeneration()
      if (expectedGeneration != null && snapshot.generation != expectedGeneration) return null
      if (snapshot.state.allCallingDisabled || !accountReady() || !snapshotCurrent(snapshot)) return null
      val ticket = sessions.group(context, recipient.id.toLong(), sender.id.toLong(), ringId, sourceDeviceId, kind, messageAgeMillis, snapshot.state.allowsIncoming(context, recipient.id.toLong())) ?: return null
      if (snapshotCurrent(snapshot)) ticket else { sessions.discard(ticket); null }
    }
  }

  @JvmStatic
  fun isAuthorized(authorization: CallAuthorization): Boolean {
    if (!identityAllowed(authorization)) {
      // A changed direct recipient invalidates that call. A removed/blocked group member's
      // queued packet must not terminate the otherwise authorized group or link session.
      if (authorization.context == CallContext.DIRECT) synchronized(lock) { sessions.discard(authorization) }
      return false
    }
    return synchronized(lock) { valid(authorization) }
  }

  @JvmStatic
  fun runAuthorized(authorization: CallAuthorization, action: Runnable): Boolean {
    // Resolve identities outside the policy lock. Recipient merges hold the database transaction.
    if (!identityAllowed(authorization)) {
      if (authorization.context == CallContext.DIRECT) synchronized(lock) { sessions.discard(authorization) }
      return false
    }
    if (!synchronized(lock) { valid(authorization) }) return false
    val previous = dispatchAuthorization.get()
    dispatchAuthorization.set(authorization)
    return try {
      if (!isDispatchAuthorized()) false else { action.run(); true }
    } finally {
      if (previous == null) dispatchAuthorization.remove() else dispatchAuthorization.set(previous)
    }
  }

  /** Final managed check adjacent to JNI, after action processors' existing trust/identity checks. */
  @JvmStatic
  fun isDispatchAuthorized(): Boolean {
    val ticket = dispatchAuthorization.get() ?: return false
    // Blocking and group membership can change independently of the call-policy generation
    // while an action is running. Recheck them here too, outside the session lock: recipient
    // merges hold the database writer and must never wait for a lock that encloses a DB read.
    return isAuthorized(ticket)
  }

  @JvmStatic
  fun runIfCurrent(generation: Long, action: Runnable): Boolean {
    if (!synchronized(lock) { isCurrent(generation) }) return false
    action.run()
    return true
  }

  /** Transport-only decline may run while calling is disabled; account/generation still must match. */
  @JvmStatic
  fun runForGeneration(generation: Long, action: Runnable): Boolean {
    if (!synchronized(lock) { state() != null && accountReady() && writes.get() == 0 && generation == epoch.get() }) return false
    action.run()
    return true
  }

  /** Only call-specific profile jobs use this ephemeral, exact-session permit. */
  @JvmStatic
  fun createSharingPermit(context: CallContext, recipientId: RecipientId?): String? = synchronized(lock) {
    if (!isCallingAllowed()) return null
    syncGeneration()
    val session = sessions.sharingSession(context, recipientId?.toLong()) ?: return null
    listOf(processId, epoch.get(), session, context.name, recipientId?.toLong() ?: 0L, SystemClock.elapsedRealtime() + CallSessionAuthorizer.SIGNALING_LEASE_MS).joinToString("|")
  }

  @JvmStatic
  fun isSharingPermitValid(permit: String): Boolean = runCatching {
    val parts = permit.split('|')
    if (parts.size != 6 || parts[0] != processId || !isCallingAllowed()) return false
    synchronized(lock) {
      syncGeneration()
      val recipient = parts[4].toLong().takeUnless { it == 0L }
      val context = CallContext.valueOf(parts[3])
      // The trusted PNI handshake ends its pseudo-call before its profile job runs. Its exact
      // recipient grant survives normal teardown, but never a policy/account change or restart.
      parts[1].toLong() == epoch.get() && writes.get() == 0 && SystemClock.elapsedRealtime() < parts[5].toLong() &&
        if (context == CallContext.DIRECT) recipient != null else sessions.sharingSession(context, recipient) == parts[2].toLong()
    }
  }.getOrDefault(false)

  @JvmStatic
  fun isSharingPermitValid(permit: String, recipientId: RecipientId): Boolean {
    if (!isSharingPermitValid(permit)) return false
    return runCatching {
      val parts = permit.split('|')
      when (CallContext.valueOf(parts[3])) {
        CallContext.DIRECT -> parts[4].toLong() == Recipient.resolved(recipientId).id.toLong()
        CallContext.CALL_LINK -> isCallLinkParticipant(recipientId)
        CallContext.GROUP -> true
      }
    }.getOrDefault(false)
  }

  /** A final group presence update may finish after normal teardown, never after revocation. */
  @JvmStatic
  fun createGroupCleanupPermit(recipientId: RecipientId): String? = createSharingPermit(CallContext.GROUP, recipientId)

  @JvmStatic
  fun isGroupCleanupPermitValid(permit: String, recipientId: RecipientId): Boolean = runCatching {
    val parts = permit.split('|')
    val group = Recipient.resolved(recipientId)
    group.isPushV2Group && !group.isBlocked && SignalDatabase.groups.getGroup(group.id).orElse(null)?.isActive == true &&
      parts.size == 6 && parts[0] == processId && parts[3] == CallContext.GROUP.name &&
      parts[4].toLong() == group.id.toLong() && isCallingAllowed() && writes.get() == 0 &&
      parts[1].toLong() == epoch.get() && SystemClock.elapsedRealtime() < parts[5].toLong()
  }.getOrDefault(false)

  /** May be called inside a recipient DB transaction. It never waits for the dispatch lock. */
  @JvmStatic
  fun onRecipientMerge(primary: RecipientId, secondary: RecipientId) {
    val before = SignalStore.callPermissions.read()
    val merged = before.merge(primary.toLong(), secondary.toLong())
    if (merged != before) {
      mutate { it.merge(primary.toLong(), secondary.toLong()) }
    } else if (sessions.involvesRecipient(primary.toLong()) || sessions.involvesRecipient(secondary.toLong())) {
      invalidate(true)
    }
  }

  @JvmStatic
  fun onAccountChanged(account: String?) = mutate { if (it.account == account) it else it.resetRecipients(account) }

  @JvmStatic
  fun onAccountRegistrationChanged() { invalidate(true) }

  @JvmStatic
  fun onRestore() = mutate { it.resetRecipients(SignalStore.account.aci?.toString()) }

  private fun valid(ticket: CallAuthorization): Boolean {
    if (!isCallingAllowed() || writes.get() != 0) return false
    syncGeneration()
    return sessions.valid(ticket)
  }

  private fun identityAllowed(ticket: CallAuthorization): Boolean = runCatching {
    val recipient = Recipient.resolved(RecipientId.from(ticket.recipient))
    val sender = Recipient.resolved(RecipientId.from(ticket.sender))
    recipient.id.toLong() == ticket.recipient && sender.id.toLong() == ticket.sender && !recipient.isBlocked && !sender.isBlocked &&
      if (ticket.context == CallContext.DIRECT) recipient.isIndividual && !recipient.isSelf else groupIdentityAllowed(recipient, sender, ticket.groupKind!!)
  }.getOrDefault(false)

  private fun groupIdentityAllowed(recipient: Recipient, sender: Recipient, kind: GroupMessageKind): Boolean {
    if (recipient.isBlocked || sender.isBlocked || !sender.isIndividual) return false
    if (recipient.isCallLink) return kind == GroupMessageKind.GROUP_DATA
    if (!recipient.isPushV2Group) return false
    if (kind == GroupMessageKind.RING_RESPONSE && !sender.isSelf) return false
    val group = SignalDatabase.groups.getGroup(recipient.id).orElse(null) ?: return false
    if (!group.isActive || !group.memberLevel(sender).isInGroup) return false
    return kind !in listOf(GroupMessageKind.RING, GroupMessageKind.CANCEL) || !group.isAnnouncementGroup || group.isAdmin(sender)
  }

  private fun state(): CallPolicyState? = try {
    if (!SignalStore.isInitialized || writes.get() != 0) null else {
      val state = SignalStore.callPermissions.read()
      unavailable.set(false)
      val account = SignalStore.account.aci?.toString()
      if (state.account != null && state.account != account) {
        onAccountChanged(account)
        SignalStore.callPermissions.read()
      } else state
    }
  } catch (_: Exception) {
    if (unavailable.compareAndSet(false, true)) invalidate(true)
    // A temporarily locked/unavailable/corrupt store is never an implicit fresh installation.
    null
  }

  private data class PolicySnapshot(val state: CallPolicyState, val generation: Long)

  private fun policySnapshot(): PolicySnapshot? {
    val generation = epoch.get()
    val state = state() ?: return null
    val snapshot = PolicySnapshot(state, generation)
    return snapshot.takeIf(::snapshotCurrent)
  }

  private fun snapshotCurrent(snapshot: PolicySnapshot): Boolean = writes.get() == 0 && epoch.get() == snapshot.generation

  private fun mutate(transform: (CallPolicyState) -> CallPolicyState) {
    writes.incrementAndGet()
    invalidate(true)
    try {
      check(SignalStore.isInitialized) { "Call permissions are unavailable" }
      SignalStore.callPermissions.update { transform(it).copy(account = SignalStore.account.aci?.toString()) }
    } finally {
      writes.decrementAndGet()
      invalidate(true)
    }
  }

  private fun invalidate(notify: Boolean) {
    val generation = epoch.incrementAndGet()
    GroupCallAdmission.clearPending()
    // Concurrent settings/account writes may finish publishing in the opposite order.
    changed.update { maxOf(it, generation) }
    // Listener only schedules shutdown on an already-created manager. It must not wait for it.
    if (notify) revocationListener?.run()
  }

  private fun syncGeneration() {
    val current = epoch.get()
    if (current != observedEpoch) { sessions.revoke(); observedEpoch = current }
  }

  private fun context(recipient: Recipient): CallContext = when {
    recipient.isCallLink -> CallContext.CALL_LINK
    recipient.isPushV2Group -> CallContext.GROUP
    else -> CallContext.DIRECT
  }
}
