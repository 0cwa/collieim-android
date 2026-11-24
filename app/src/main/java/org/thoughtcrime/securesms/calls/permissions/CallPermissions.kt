/* SPDX-License-Identifier: AGPL-3.0-only */
package org.thoughtcrime.securesms.calls.permissions

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.signal.core.models.recipients.RecipientId
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.recipients.Recipient
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
    val resolved = Recipient.resolved(recipient.id)
    require(resolved.isIndividual || resolved.isPushV2Group)
    mutate { it.withOverride(context(resolved), resolved.id.toLong(), permission) }
  }

  @JvmStatic
  fun isCallingAllowed(): Boolean = runCatching {
    state()?.allCallingDisabled == false && SignalStore.account.isRegistered && SignalStore.account.aci != null && !SignalStore.account.isUnauthorizedReceived
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
    recipient.isPushV2Group && !recipient.isBlocked && SignalDatabase.groups.getGroup(recipient.id).orElse(null)?.isActive == true &&
      state()?.allowsIncoming(CallContext.GROUP, recipient.id.toLong()) == true
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
      syncGeneration()
      isCallingAllowed() && writes.get() == 0 && sessions.beginOutgoing(context(recipient), recipient.id.toLong())
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
  fun endCall(recipientId: RecipientId, callId: Long) = synchronized(lock) { sessions.end(recipientId.toLong(), callId) }

  @JvmStatic
  fun endAllSessions() = synchronized(lock) { sessions.clear() }

  @JvmStatic
  fun setRevocationListener(listener: Runnable) { revocationListener = listener }

  @JvmStatic
  fun admitDirect(recipientId: RecipientId, callId: Long, sourceDeviceId: Int, kind: DirectMessageKind, messageAgeMillis: Long): CallAuthorization? {
    val recipient = runCatching { Recipient.resolved(recipientId) }.getOrNull() ?: return null
    if (!recipient.isIndividual || recipient.isBlocked || recipient.isSelf) return null
    return synchronized(lock) {
      val state = state() ?: return null
      syncGeneration()
      if (state.allCallingDisabled || writes.get() != 0 || !isCallingAllowed()) return null
      sessions.direct(recipient.id.toLong(), callId, sourceDeviceId, kind, messageAgeMillis, state.allowsIncoming(CallContext.DIRECT, recipient.id.toLong()))
    }
  }

  @JvmStatic
  fun authorizeGroup(recipientId: RecipientId, senderId: RecipientId, ringId: Long?, sourceDeviceId: Int, kind: GroupMessageKind, messageAgeMillis: Long): CallAuthorization? {
    val recipient = runCatching { Recipient.resolved(recipientId) }.getOrNull() ?: return null
    val sender = runCatching { Recipient.resolved(senderId) }.getOrNull() ?: return null
    val context = context(recipient)
    if (!runCatching { groupIdentityAllowed(recipient, sender, kind) }.getOrDefault(false)) return null
    return synchronized(lock) {
      val state = state() ?: return null
      syncGeneration()
      if (state.allCallingDisabled || writes.get() != 0 || !isCallingAllowed()) return null
      sessions.group(context, recipient.id.toLong(), sender.id.toLong(), ringId, sourceDeviceId, kind, messageAgeMillis, state.allowsIncoming(context, recipient.id.toLong()))
    }
  }

  @JvmStatic
  fun isAuthorized(authorization: CallAuthorization): Boolean {
    if (!identityAllowed(authorization)) return false
    return synchronized(lock) { valid(authorization) }
  }

  @JvmStatic
  fun runAuthorized(authorization: CallAuthorization, action: Runnable): Boolean {
    // Resolve identities outside the policy lock. Recipient merges hold the database transaction.
    if (!identityAllowed(authorization)) return false
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
  fun isDispatchAuthorized(): Boolean = synchronized(lock) {
    val ticket = dispatchAuthorization.get() ?: return false
    valid(ticket)
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
    if (!synchronized(lock) { state() != null && writes.get() == 0 && generation == epoch.get() }) return false
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
      parts[3] != CallContext.DIRECT.name || parts[4].toLong() == Recipient.resolved(recipientId).id.toLong()
    }.getOrDefault(false)
  }

  /** May be called inside a recipient DB transaction. It never waits for the dispatch lock. */
  @JvmStatic
  fun onRecipientMerge(primary: RecipientId, secondary: RecipientId) = mutate { it.merge(primary.toLong(), secondary.toLong()) }

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
    changed.value = generation
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
