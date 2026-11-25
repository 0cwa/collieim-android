/*
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.groups.v2.admission

import okio.ByteString
import org.signal.core.models.ServiceId
import org.signal.storageservice.storage.protos.groups.local.DecryptedGroup
import org.signal.storageservice.storage.protos.groups.local.DecryptedGroupChange
import org.thoughtcrime.securesms.groups.v2.processing.GroupStateDiff
import org.whispersystems.signalservice.api.push.ServiceIds

/** Extracts only authenticated group state/history; message sender and imported sharing flags are not consent. */
internal object GroupAdmissionEvidence {
  fun membership(group: DecryptedGroup?, self: ServiceIds): AdmissionMembership {
    val revision = group?.revision ?: -1
    if (group == null || group.terminated) return AdmissionMembership(MembershipKind.ABSENT, "absent", revision.coerceAtLeast(-1))
    // Restore placeholders have negative revisions and can only provide an invite or request.
    val localRevision = revision.coerceAtLeast(-1)
    group.members.firstOrNull { it.aciBytes == self.aci.toByteString() }?.let {
      if (group.isPlaceholderGroup || group.needsServerSnapshot || it.joinedAtRevision !in 0..revision) return unknownMembership(revision)
      return AdmissionMembership(MembershipKind.FULL, "member:${it.joinedAtRevision}", revision, it.joinedAtRevision)
    }
    group.pendingMembers.firstOrNull { self.matches(it.serviceIdBytes) }?.let {
      if (it.timestamp < 0) return unknownMembership(revision)
      return AdmissionMembership(MembershipKind.PENDING, "invite:${it.serviceIdBytes.hex()}:${it.addedByAci.hex()}:${it.timestamp}:${it.serviceIdCipherText.hex()}", localRevision)
    }
    group.requestingMembers.firstOrNull { it.aciBytes == self.aci.toByteString() }?.let {
      if (it.timestamp < 0) return unknownMembership(revision)
      return AdmissionMembership(MembershipKind.REQUESTING, "request:${it.timestamp}", localRevision)
    }
    return AdmissionMembership(MembershipKind.ABSENT, "absent", revision.coerceAtLeast(-1))
  }

  fun unknownMembership(revision: Int) = AdmissionMembership(MembershipKind.UNKNOWN, "unknown", revision.coerceAtLeast(-1))

  fun inviter(diff: GroupStateDiff, current: DecryptedGroup, self: ServiceIds): ServiceId.ACI? {
    val member = current.members.firstOrNull { it.aciBytes == self.aci.toByteString() }
    return if (member != null) {
      diff.serverHistory.mapNotNull { it.change }
        .singleOrNull { it.revision == member.joinedAtRevision && (it.newMembers.any { added -> added.aciBytes == member.aciBytes } || promotesRequest(it, self)) }
        ?.let { ServiceId.ACI.parseOrNull(it.editorServiceIdBytes) }
        ?.takeIf { it.isValid }
    } else {
      current.pendingMembers.firstOrNull { self.matches(it.serviceIdBytes) }
        ?.let { ServiceId.ACI.parseOrNull(it.addedByAci) }
        ?.takeIf { it.isValid }
    }
  }

  fun removedSelf(change: DecryptedGroupChange, self: ServiceIds): Boolean =
    change.deleteMembers.any { self.matches(it) } ||
      change.deletePendingMembers.any { self.matches(it.serviceIdBytes) } ||
      change.deleteRequestingMembers.any { self.matches(it) }

  fun promotesRequest(change: DecryptedGroupChange, self: ServiceIds): Boolean =
    change.promoteRequestingMembers.any { it.aciBytes == self.aci.toByteString() }

  fun promotesInvite(change: DecryptedGroupChange, self: ServiceIds, pendingId: ByteString? = null): Boolean =
    ((pendingId == null || pendingId == self.aci.toByteString()) && change.promotePendingMembers.any { it.aciBytes == self.aci.toByteString() }) ||
      change.promotePendingPniAciMembers.any { it.aciBytes == self.aci.toByteString() && (pendingId == null || it.pniBytes == pendingId) }

  fun hasUnbrokenHistory(previousRevision: Int, joiningRevision: Int, changes: List<DecryptedGroupChange>, self: ServiceIds): Boolean {
    if (previousRevision < 0 || joiningRevision <= previousRevision) return false
    val between = changes.filter { it.revision > previousRevision && it.revision <= joiningRevision }
    if (between.size != joiningRevision - previousRevision || between.map { it.revision }.toSet().size != between.size) return false
    return between.none { change ->
      change.newPendingMembers.any { self.matches(it.serviceIdBytes) } ||
        change.newRequestingMembers.any { self.matches(it.aciBytes) }
    }
  }

  fun matchesIntent(intent: AdmissionIntent, current: AdmissionMembership, changes: List<DecryptedGroupChange>, self: ServiceIds): Boolean {
    val revision = intent.expectedRevision ?: return false
    if (revision !in 0..current.revision || changes.any { it.revision > revision && it.revision <= current.revision && removedSelf(it, self) }) return false
    val change = changes.singleOrNull { it.revision == revision } ?: return false
    if (!self.matches(change.editorServiceIdBytes)) return false
    return when (intent.kind) {
      ConsentKind.ACCEPT -> current.kind == MembershipKind.FULL && current.joinedAtRevision == revision && promotesInvite(change, self)
      ConsentKind.JOIN, ConsentKind.CREATE -> current.kind == MembershipKind.FULL && current.joinedAtRevision == revision && change.newMembers.any { it.aciBytes == self.aci.toByteString() }
      ConsentKind.REQUEST -> current.kind == MembershipKind.REQUESTING && change.newRequestingMembers.any { it.aciBytes == self.aci.toByteString() && current.episode == "request:${it.timestamp}" }
    }
  }
}
