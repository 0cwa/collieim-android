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
import org.signal.core.util.orNull
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
 * Membership and consent mutations run under GroupsV2ProcessingLock.
 */
object GroupAdmission {
  data class PendingUpdate internal constructor(val account: String, val group: String, val token: String, val intentToClear: String?)
  class SendEpisode internal constructor(
    private val account: String?,
    private val generation: String?,
    private val episode: String?,
    val held: Boolean,
    val joinedAtRevision: Int? = null
  ) {
    fun key(): String = "$account/$generation/$episode/$held"
  }
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
  fun sendEpisode(groupId: GroupId.V2): SendEpisode {
    val account = account() ?: return SendEpisode(null, null, null, false)
    val group = groupId.toString()
    val membership = currentMembership(groupId)
    val state = SignalStore.groupAdmission.read(account)
    val record = state.records[group]
    val held = state.isHeld(group) || (record != null && record.membership.episode != membership.episode)
    return SendEpisode(account, record?.generation, membership.episode, held, membership.joinedAtRevision)
  }

  /** Shared by the picker and the persistence boundary. */
  internal fun selectableAci(recipient: Recipient): String? {
    if (!recipient.isIndividual || recipient.isSelf || recipient.isBlocked || recipient.isHidden || !recipient.isRegistered) return null
    return recipient.aci.orNull()?.takeIf { it.isValid }?.toString()
  }

  private fun currentPerson(aci: ACI): Recipient? =
    SignalDatabase.recipients.getByAci(aci).orNull()?.let { Recipient.resolved(it).fresh() }?.takeIf { it.aci.orNull() == aci }

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
            if (record.status == AdmissionStatus.SIGNAL_DEFAULT) record.copy(status = AdmissionStatus.HELD, generation = UUID.randomUUID().toString()) else record
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
        val person = currentPerson(ACI.parseOrThrow(raw))
        require(person?.let(::selectableAci) == raw) { "Choose a registered, unblocked person" }
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
    val changes = diff.serverHistory.mapNotNull { it.change }.filter { it.revision in 0..updated.revision }
    val prior = snapshot.records[group]
    // A pending decision may be carried across writes in the same batch, provided the prior write
    // actually produced the previous state. A rolled-back write cannot authorize a different state.
    val priorApplies = prior != null && (prior.pendingWrite == null || prior.membership == previous)
    val removed = changes.any { it.revision > previous.revision && GroupAdmissionEvidence.removedSelf(it, self) }
    val sameEpisode = priorApplies && prior?.membership?.episode == current.episode && !removed
    val intent = snapshot.intents[group]
    val joiningChange = changes.singleOrNull { it.revision == current.joinedAtRevision }?.takeIf { joining ->
      priorApplies && prior?.membership?.episode == previous.episode && !removed &&
        GroupAdmissionEvidence.hasUnbrokenHistory(previous.revision, joining.revision, changes, self)
    }
    val continuingRequest = prior?.status == AdmissionStatus.LOCAL_CONSENT && previous.kind == MembershipKind.REQUESTING &&
      joiningChange?.let { ACI.parseOrNull(it.editorServiceIdBytes)?.isValid == true && GroupAdmissionEvidence.promotesRequest(it, self) } == true
    val pendingId = diff.previousGroupState?.pendingMembers?.firstOrNull { self.matches(it.serviceIdBytes) }?.serviceIdBytes
    val continuingInvite = previous.kind == MembershipKind.PENDING && pendingId != null &&
      joiningChange?.let { GroupAdmissionEvidence.promotesInvite(it, self, pendingId) } == true
    val intentMatches = intent != null && GroupAdmissionEvidence.matchesIntent(intent, current, changes, self) &&
      (intent.kind != ConsentKind.ACCEPT || (continuingInvite && intent.originEpisode == token(account, prior, previous)))
    val inviter = GroupAdmissionEvidence.inviter(diff, updated, self)?.let { aci ->
      val recipient = currentPerson(aci)
      AdmissionInviter(
        aci = recipient?.let(::selectableAci),
        blocked = recipient?.isBlocked == true,
        hidden = recipient?.isHidden == true,
        deviceContact = recipient?.isSystemContact == true && Permissions.hasAll(AppDependencies.application, Manifest.permission.READ_CONTACTS)
      )
    }
    val status = when {
      updated.terminated || current.kind == MembershipKind.ABSENT -> if (snapshot.settings.mode == GroupAdmissionMode.SIGNAL_DEFAULT) AdmissionStatus.SIGNAL_DEFAULT else AdmissionStatus.HELD
      updated.needsServerSnapshot || current.kind == MembershipKind.UNKNOWN -> AdmissionStatus.HELD
      intentMatches || continuingRequest -> AdmissionStatus.LOCAL_CONSENT
      sameEpisode -> prior!!.status
      continuingInvite -> prior!!.status
      snapshot.settings.mode != GroupAdmissionMode.SIGNAL_DEFAULT && (previous.kind == MembershipKind.PENDING || current.kind == MembershipKind.PENDING) -> AdmissionStatus.HELD
      else -> GroupAdmissionPolicy.evaluate(snapshot.settings, inviter)
    }
    val pendingToken = UUID.randomUUID().toString()
    val record = AdmissionRecord(
      membership = current,
      status = status,
      generation = if (sameEpisode && status == prior!!.status) prior.generation else UUID.randomUUID().toString(),
      pendingWrite = pendingToken
    )
    val intentToClear = intent?.id?.takeIf {
      (intentMatches && status == AdmissionStatus.LOCAL_CONSENT) || current.kind == MembershipKind.ABSENT ||
        (sameEpisode && prior?.pendingWrite != null && status == AdmissionStatus.LOCAL_CONSENT)
    }
    SignalStore.groupAdmission.update(account) { state -> state.copy(records = state.records + (group to record)) }
    return PendingUpdate(account, group, pendingToken, intentToClear)
  }

  @JvmStatic
  @WorkerThread
  fun completeStateUpdate(update: PendingUpdate?): Boolean {
    if (update == null) return true
    if (account() != update.account) return false
    val committed = currentMembership(GroupId.parseOrThrow(update.group).requireV2())
    var completed = false
    SignalStore.groupAdmission.update(update.account) { state ->
      val record = state.records[update.group]
      if (record?.pendingWrite != update.token || record.membership != committed) {
        state
      } else {
        completed = true
        state.copy(
          records = state.records + (update.group to record.copy(pendingWrite = null)),
          intents = if (state.intents[update.group]?.id == update.intentToClear) state.intents - update.group else state.intents
        )
      }
    }
    if (completed) notifyGroupChanged(update.group)
    return completed
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

    if (membership.kind == MembershipKind.FULL) {
      SignalStore.groupAdmission.update(account) { grant(it, group, membership) }
      return null
    }
    val intent = AdmissionIntent(UUID.randomUUID().toString(), ConsentKind.ACCEPT, actualToken)
    val record = (prior ?: AdmissionRecord(membership, AdmissionStatus.HELD)).copy(status = AdmissionStatus.HELD, pendingWrite = null)
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
    if (nextRevision < 0) throw GroupChangeFailedException("Invalid group mutation revision")
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
    if (group.needsServerSnapshot || (group.isPlaceholderGroup && membership.kind != MembershipKind.REQUESTING)) return
    if (currentMembership(GroupId.parseOrThrow(attempt.group).requireV2()) != membership) return
    SignalStore.groupAdmission.update(attempt.account) { state ->
      val intent = state.intents[attempt.group]?.takeIf { it.id == attempt.id } ?: return@update state
      if (state.records[attempt.group]?.pendingWrite != null) return@update state
      val created = intent.kind == ConsentKind.CREATE && intent.expectedRevision == 0 && !group.isPlaceholderGroup && membership.kind == MembershipKind.FULL && membership.joinedAtRevision == 0
      if (!created && (intent.kind == ConsentKind.ACCEPT || !GroupAdmissionEvidence.matchesIntent(intent, membership, listOfNotNull(change), self))) return@update state
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
    if (membership.kind != MembershipKind.FULL || currentMembership(GroupId.parseOrThrow(attempt.group).requireV2()) != membership) return
    SignalStore.groupAdmission.update(attempt.account) { state ->
      val intent = state.intents[attempt.group]?.takeIf { it.id == attempt.id } ?: return@update state
      if (state.records[attempt.group]?.pendingWrite != null || (intent.kind != ConsentKind.JOIN && intent.kind != ConsentKind.REQUEST)) state else grant(state, attempt.group, membership)
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

  private fun currentMembership(groupId: GroupId.V2): AdmissionMembership {
    val state = SignalDatabase.groups.getGroupAdmissionState(groupId)
    val membership = GroupAdmissionEvidence.membership(state?.first, SignalStore.account.getServiceIds())
    // A local leave can clear the active flag before there is a new decrypted group snapshot.
    return if (membership.kind == MembershipKind.FULL && state?.second == false) {
      AdmissionMembership(MembershipKind.ABSENT, "absent", membership.revision)
    } else {
      membership
    }
  }

  private fun token(account: String, record: AdmissionRecord?, membership: AdmissionMembership): String = "$account/${record?.generation ?: membership.episode}/${membership.episode}"

  private fun grant(state: AdmissionAccountState, group: String, membership: AdmissionMembership): AdmissionAccountState {
    val prior = state.records[group]
    val generation = if (prior?.membership?.episode == membership.episode && prior.status == AdmissionStatus.LOCAL_CONSENT && prior.pendingWrite == null) {
      prior.generation
    } else {
      UUID.randomUUID().toString()
    }
    val record = AdmissionRecord(membership, AdmissionStatus.LOCAL_CONSENT, generation)
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
