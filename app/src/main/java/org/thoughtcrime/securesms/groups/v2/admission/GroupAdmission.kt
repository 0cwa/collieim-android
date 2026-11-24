/*
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.groups.v2.admission

import android.Manifest
import androidx.annotation.WorkerThread
import org.signal.core.models.ServiceId.ACI
import org.signal.core.models.recipients.RecipientId
import org.signal.core.ui.permissions.Permissions
import org.signal.core.util.groups.GroupChangeFailedException
import org.signal.storageservice.storage.protos.groups.local.DecryptedGroup
import org.signal.storageservice.storage.protos.groups.local.DecryptedGroupChange
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.groups.GroupId
import org.thoughtcrime.securesms.groups.GroupsV2ProcessingLock
import org.thoughtcrime.securesms.groups.v2.processing.GroupStateDiff
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.recipients.RecipientUtil
import org.whispersystems.signalservice.api.push.ServiceIds
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

/**
 * Local permission to use a group, separate from its verified Signal membership and synced profile-sharing flag.
 * Membership and consent mutations run under GroupsV2ProcessingLock. Reads only inspect the encrypted ledger.
 */
object GroupAdmission {
  data class PendingUpdate internal constructor(val account: String, val group: String, val token: String)
  data class ConsentAttempt internal constructor(val account: String, val group: String, val id: String)

  @JvmStatic
  fun isHeld(recipient: Recipient): Boolean = recipient.isPushV2Group && isHeld(recipient.requireGroupId().requireV2())

  @JvmStatic
  fun isHeld(recipientId: RecipientId): Boolean = isHeld(Recipient.resolved(recipientId))

  @JvmStatic
  fun isHeld(groupId: GroupId.V2): Boolean {
    val account = account() ?: return false
    return SignalStore.groupAdmission.read(account).isHeld(groupId.toString())
  }

  @JvmStatic
  fun getSettings(): GroupAdmissionSettings {
    val account = account() ?: return GroupAdmissionSettings()
    return SignalStore.groupAdmission.read(account).settings
  }

  @JvmStatic
  fun getAllowedPeople(): Set<String> = getSettings().allowedPeople

  @JvmStatic
  @WorkerThread
  fun setMode(mode: GroupAdmissionMode) {
    val account = requireNotNull(account()) { "No registered account" }
    GroupsV2ProcessingLock.acquireAllGroupsProcessingLock(5.seconds).use {
      val before = SignalStore.groupAdmission.read(account)
      // Take DB snapshots before entering the values monitor. No values lock ever waits for a group lock or DB.
      val baseline = if (before.settings.mode == GroupAdmissionMode.SIGNAL_DEFAULT && mode != GroupAdmissionMode.SIGNAL_DEFAULT) {
        existingAdmissions()
      } else {
        emptyMap()
      }
      SignalStore.groupAdmission.update(account) { state ->
        val records = state.records.toMutableMap()
        if (state.settings.mode == GroupAdmissionMode.SIGNAL_DEFAULT && mode != GroupAdmissionMode.SIGNAL_DEFAULT) {
          records.replaceAll { _, record ->
            if (record.status == AdmissionStatus.SIGNAL_DEFAULT) record.copy(status = AdmissionStatus.HELD) else record
          }
          baseline.forEach { (group, record) ->
            if (state.records[group]?.status != AdmissionStatus.HELD && state.records[group]?.pendingWrite == null) records[group] = record
          }
        }
        state.copy(settings = state.settings.copy(mode = mode), records = records)
      }
    }
  }

  @JvmStatic
  @WorkerThread
  fun setAllowedPeople(acis: Set<String>) {
    val account = requireNotNull(account()) { "No registered account" }
    val normalized = acis.map { raw -> ACI.parseOrThrow(raw).also { require(it.isValid) }.toString() }.toSet()
    GroupsV2ProcessingLock.acquireAllGroupsProcessingLock(5.seconds).use {
      val before = SignalStore.groupAdmission.read(account).settings.allowedPeople
      for (raw in normalized - before) {
        val person = Recipient.externalPush(ACI.parseOrThrow(raw)).resolve()
        require(person.isRegistered && !person.isSelf && !person.isBlocked && !person.isHidden) { "Choose a registered, unblocked person" }
      }
      SignalStore.groupAdmission.update(account) { state -> state.copy(settings = state.settings.copy(allowedPeople = normalized)) }
    }
  }

  /** Captured by the displayed request, then checked again under the group lock when Accept is processed. */
  @JvmStatic
  @WorkerThread
  fun consentToken(groupId: GroupId.V2): String? {
    val account = account() ?: return null
    val membership = currentMembership(groupId)
    if (membership.kind != MembershipKind.PENDING && membership.kind != MembershipKind.FULL) return null
    val record = SignalStore.groupAdmission.read(account).records[groupId.toString()]
    return token(account, record?.takeIf { it.membership.episode == membership.episode }, membership)
  }

  /** Must precede the actual GroupTable write. An interrupted cross-store update remains held. */
  @JvmStatic
  @WorkerThread
  fun prepareStateUpdate(groupId: GroupId.V2, self: ServiceIds, diff: GroupStateDiff, updated: DecryptedGroup): PendingUpdate? {
    val account = account() ?: return null
    if (account != self.aci.toString()) return null
    val group = groupId.toString()
    val snapshot = SignalStore.groupAdmission.read(account)
    if (snapshot.settings.mode == GroupAdmissionMode.SIGNAL_DEFAULT && group !in snapshot.records && group !in snapshot.intents) return null

    val current = GroupAdmissionEvidence.membership(updated, self)
    val previous = GroupAdmissionEvidence.membership(diff.previousGroupState, self)
    val changes = diff.serverHistory.mapNotNull { it.change }
    val prior = snapshot.records[group]
    val removed = changes.any { it.revision > (diff.previousGroupState?.revision ?: -1) && GroupAdmissionEvidence.removedSelf(it, self) }
    val sameEpisode = prior?.membership?.episode == current.episode && !removed
    val intent = snapshot.intents[group]
    val intentMatches = intent != null && GroupAdmissionEvidence.matchesIntent(intent, current, changes, self)
    val joiningChange = changes.firstOrNull { it.revision == current.joinedAtRevision }
    val continuingRequest = prior?.status == AdmissionStatus.LOCAL_CONSENT && prior.membership.kind == MembershipKind.REQUESTING &&
      prior.membership.episode == previous.episode && !removed && joiningChange?.let { GroupAdmissionEvidence.promotesRequest(it, self) } == true
    val continuingInvite = prior != null && prior.membership.kind == MembershipKind.PENDING && prior.membership.episode == previous.episode &&
      !removed && joiningChange?.let { GroupAdmissionEvidence.promotesInvite(it, self) } == true
    val inviter = GroupAdmissionEvidence.inviter(diff, updated, self)?.let { aci ->
      val recipient = Recipient.externalPush(aci).resolve()
      AdmissionInviter(
        aci = aci.toString(),
        blocked = recipient.isBlocked,
        hidden = recipient.isHidden,
        deviceContact = recipient.isSystemContact && Permissions.hasAll(AppDependencies.application, Manifest.permission.READ_CONTACTS)
      )
    }
    val status = when {
      updated.terminated || current.kind == MembershipKind.ABSENT -> if (snapshot.settings.mode == GroupAdmissionMode.SIGNAL_DEFAULT) AdmissionStatus.SIGNAL_DEFAULT else AdmissionStatus.HELD
      updated.needsServerSnapshot -> AdmissionStatus.HELD
      intentMatches || continuingRequest -> AdmissionStatus.LOCAL_CONSENT
      sameEpisode -> prior!!.status
      continuingInvite -> prior!!.status
      current.kind == MembershipKind.PENDING && snapshot.settings.mode != GroupAdmissionMode.SIGNAL_DEFAULT -> AdmissionStatus.HELD
      else -> GroupAdmissionPolicy.evaluate(snapshot.settings, inviter)
    }
    val pendingToken = UUID.randomUUID().toString()
    val record = AdmissionRecord(
      membership = current,
      status = status,
      generation = if (sameEpisode) prior!!.generation else UUID.randomUUID().toString(),
      pendingWrite = pendingToken
    )
    SignalStore.groupAdmission.update(account) { state ->
      state.copy(records = state.records + (group to record), intents = if (intentMatches || current.kind == MembershipKind.ABSENT) state.intents - group else state.intents)
    }
    return PendingUpdate(account, group, pendingToken)
  }

  @JvmStatic
  @WorkerThread
  fun completeStateUpdate(update: PendingUpdate?) {
    if (update == null || account() != update.account) return
    SignalStore.groupAdmission.update(update.account) { state ->
      val record = state.records[update.group]
      if (record?.pendingWrite != update.token) state else state.copy(records = state.records + (update.group to record.copy(pendingWrite = null)))
    }
    notifyGroupChanged(update.group)
  }

  /** Only a configured automatic decision or actual local consent may replace the legacy trust predicate. */
  @JvmStatic
  fun shouldEnableProfileSharing(groupId: GroupId.V2): Boolean {
    val account = account() ?: return false
    val record = SignalStore.groupAdmission.read(account).records[groupId.toString()] ?: return false
    return record.pendingWrite == null && record.membership.kind == MembershipKind.FULL &&
      (record.status == AdmissionStatus.AUTOMATIC || record.status == AdmissionStatus.LOCAL_CONSENT)
  }

  @JvmStatic
  @WorkerThread
  @Throws(GroupChangeFailedException::class)
  fun beginAccept(groupId: GroupId.V2, expectedToken: String?): ConsentAttempt? {
    val account = account() ?: return null
    val group = groupId.toString()
    if (Recipient.externalGroupExact(groupId).resolve().isBlocked) throw GroupChangeFailedException("Unblock this group before accepting")
    val membership = currentMembership(groupId)
    val state = SignalStore.groupAdmission.read(account)
    val prior = state.records[group]?.takeIf { it.membership.episode == membership.episode }
    val actualToken = token(account, prior, membership)
    if (membership.kind != MembershipKind.FULL && membership.kind != MembershipKind.PENDING) throw GroupChangeFailedException("This group invitation is no longer current")
    if (expectedToken != null && expectedToken != actualToken) throw GroupChangeFailedException("This group invitation changed; review it again")
    if (expectedToken == null && state.isHeld(group)) throw GroupChangeFailedException("Review this group invitation before accepting")
    if (state.settings.mode == GroupAdmissionMode.SIGNAL_DEFAULT && group !in state.records) return null

    val intent = AdmissionIntent(UUID.randomUUID().toString(), ConsentKind.ACCEPT, actualToken)
    val record = (prior ?: AdmissionRecord(membership, AdmissionStatus.HELD)).copy(
      status = if (membership.kind == MembershipKind.FULL) AdmissionStatus.LOCAL_CONSENT else AdmissionStatus.HELD,
      pendingWrite = null
    )
    SignalStore.groupAdmission.update(account) {
      it.copy(records = it.records + (group to record), intents = it.intents + (group to intent))
    }
    return ConsentAttempt(account, group, intent.id)
  }

  @JvmStatic
  @WorkerThread
  fun beginJoin(groupId: GroupId.V2, request: Boolean): ConsentAttempt? = beginOperation(groupId, if (request) ConsentKind.REQUEST else ConsentKind.JOIN)

  @JvmStatic
  @WorkerThread
  fun beginCreate(groupId: GroupId.V2): ConsentAttempt? = beginOperation(groupId, ConsentKind.CREATE)

  private fun beginOperation(groupId: GroupId.V2, kind: ConsentKind): ConsentAttempt? {
    val account = account() ?: return null
    val group = groupId.toString()
    val state = SignalStore.groupAdmission.read(account)
    if (state.settings.mode == GroupAdmissionMode.SIGNAL_DEFAULT && group !in state.records) return null
    val intent = AdmissionIntent(UUID.randomUUID().toString(), kind, null)
    SignalStore.groupAdmission.update(account) { state -> state.copy(intents = state.intents + (group to intent)) }
    return ConsentAttempt(account, group, intent.id)
  }

  /** Called immediately before each local mutation attempt, including a retry at a new group revision. */
  @JvmStatic
  @WorkerThread
  @Throws(GroupChangeFailedException::class)
  fun beforeMutation(groupId: GroupId.V2, nextRevision: Int) {
    val account = account() ?: return
    val group = groupId.toString()
    val state = SignalStore.groupAdmission.read(account)
    val intent = state.intents[group] ?: return
    if (intent.kind == ConsentKind.ACCEPT) {
      val membership = currentMembership(groupId)
      val record = state.records[group]?.takeIf { it.membership.episode == membership.episode }
      if (intent.originEpisode != token(account, record, membership)) throw GroupChangeFailedException("The reviewed group membership changed")
    }
    SignalStore.groupAdmission.update(account) { latest ->
      if (latest.intents[group]?.id != intent.id) latest else latest.copy(intents = latest.intents + (group to intent.copy(expectedRevision = nextRevision)))
    }
  }

  /** A successful local response binds consent to the membership in that exact authenticated mutation. */
  @JvmStatic
  @JvmOverloads
  @WorkerThread
  fun confirmLocalMembership(attempt: ConsentAttempt?, group: DecryptedGroup, change: DecryptedGroupChange? = null) {
    if (attempt == null || account() != attempt.account) return
    val self = SignalStore.account.getServiceIds()
    val membership = GroupAdmissionEvidence.membership(group, self)
    SignalStore.groupAdmission.update(attempt.account) { state ->
      val intent = state.intents[attempt.group]?.takeIf { it.id == attempt.id } ?: return@update state
      val created = intent.kind == ConsentKind.CREATE && intent.expectedRevision == 0 && !group.isPlaceholderGroup && membership.kind == MembershipKind.FULL && membership.joinedAtRevision == 0
      if (!created && !GroupAdmissionEvidence.matchesIntent(intent, membership, listOfNotNull(change), self)) return@update state
      grant(state, attempt.group, membership)
    }
    notifyGroupChanged(attempt.group)
  }

  /** Used only after a local Join got an already-member response and fetched current, non-placeholder server state. */
  @JvmStatic
  @WorkerThread
  fun confirmExistingMembership(attempt: ConsentAttempt?, group: DecryptedGroup) {
    if (attempt == null || account() != attempt.account || group.isPlaceholderGroup || group.needsServerSnapshot) return
    val membership = GroupAdmissionEvidence.membership(group, SignalStore.account.getServiceIds())
    if (membership.kind != MembershipKind.FULL) return
    SignalStore.groupAdmission.update(attempt.account) { state ->
      val intent = state.intents[attempt.group]?.takeIf { it.id == attempt.id } ?: return@update state
      if (intent.kind != ConsentKind.JOIN && intent.kind != ConsentKind.REQUEST) state else grant(state, attempt.group, membership)
    }
    notifyGroupChanged(attempt.group)
  }

  @JvmStatic
  @WorkerThread
  fun endOperation(attempt: ConsentAttempt?) {
    if (attempt == null || account() != attempt.account) return
    SignalStore.groupAdmission.update(attempt.account) { state ->
      if (state.intents[attempt.group]?.id != attempt.id) state else state.copy(intents = state.intents - attempt.group)
    }
  }

  private fun account(): String? = if (SignalStore.isInitialized) SignalStore.account.aci?.toString() else null

  private fun currentMembership(groupId: GroupId.V2): AdmissionMembership = GroupAdmissionEvidence.membership(
    SignalDatabase.groups.getGroup(groupId).orElse(null)?.takeIf { it.hasV2GroupProperties }?.requireV2GroupProperties()?.decryptedGroup,
    SignalStore.account.getServiceIds()
  )

  private fun token(account: String, record: AdmissionRecord?, membership: AdmissionMembership): String = "$account/${record?.generation ?: membership.episode}/${membership.episode}"

  private fun grant(state: AdmissionAccountState, group: String, membership: AdmissionMembership): AdmissionAccountState {
    val prior = state.records[group]
    val record = AdmissionRecord(membership, AdmissionStatus.LOCAL_CONSENT, if (prior?.membership?.episode == membership.episode) prior.generation else UUID.randomUUID().toString())
    return state.copy(records = state.records + (group to record))
  }

  private fun notifyGroupChanged(group: String) {
    SignalDatabase.recipients.getByGroupId(GroupId.parseOrThrow(group)).ifPresent {
      AppDependencies.databaseObserver.notifyRecipientChanged(it)
    }
  }

  private fun existingAdmissions(): Map<String, AdmissionRecord> {
    return SignalDatabase.groups.getAllGroupV2Ids().mapNotNull { groupId ->
      val membership = currentMembership(groupId)
      val recipient = Recipient.externalGroupExact(groupId).resolve()
      if (membership.kind == MembershipKind.FULL && !recipient.isBlocked && RecipientUtil.isMessageRequestAccepted(recipient)) {
        groupId.toString() to AdmissionRecord(membership, AdmissionStatus.EXISTING_GROUP, UUID.randomUUID().toString())
      } else {
        null
      }
    }.toMap()
  }
}
