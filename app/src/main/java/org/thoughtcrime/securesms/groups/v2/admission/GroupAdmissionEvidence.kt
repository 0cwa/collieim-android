/*
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.groups.v2.admission

import org.signal.core.models.ServiceId
import org.signal.storageservice.storage.protos.groups.local.DecryptedGroup
import org.signal.storageservice.storage.protos.groups.local.DecryptedGroupChange
import org.thoughtcrime.securesms.groups.v2.processing.GroupStateDiff
import org.whispersystems.signalservice.api.push.ServiceIds

/** Extracts only authenticated group state/history; message sender and imported sharing flags are not consent. */
internal object GroupAdmissionEvidence {
  fun membership(group: DecryptedGroup?, self: ServiceIds): AdmissionMembership {
    val revision = group?.revision ?: -1
    if (group == null || group.terminated) return AdmissionMembership(MembershipKind.ABSENT, "absent", revision)
    group.members.firstOrNull { it.aciBytes == self.aci.toByteString() }?.let {
      return AdmissionMembership(MembershipKind.FULL, "member:${it.joinedAtRevision}", revision, it.joinedAtRevision)
    }
    group.pendingMembers.firstOrNull { self.matches(it.serviceIdBytes) }?.let {
      return AdmissionMembership(MembershipKind.PENDING, "invite:${it.serviceIdBytes.hex()}:${it.addedByAci.hex()}:${it.timestamp}:${it.serviceIdCipherText.hex()}", revision)
    }
    group.requestingMembers.firstOrNull { it.aciBytes == self.aci.toByteString() }?.let {
      return AdmissionMembership(MembershipKind.REQUESTING, "request:${it.timestamp}", revision)
    }
    return AdmissionMembership(MembershipKind.ABSENT, "absent", revision)
  }

  fun inviter(diff: GroupStateDiff, current: DecryptedGroup, self: ServiceIds): ServiceId.ACI? {
    val member = current.members.firstOrNull { it.aciBytes == self.aci.toByteString() }
    return if (member != null) {
      diff.serverHistory.mapNotNull { it.change }
        .firstOrNull { it.revision == member.joinedAtRevision }
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

  fun promotesInvite(change: DecryptedGroupChange, self: ServiceIds): Boolean =
    change.promotePendingMembers.any { it.aciBytes == self.aci.toByteString() } ||
      change.promotePendingPniAciMembers.any { it.aciBytes == self.aci.toByteString() }

  fun matchesIntent(intent: AdmissionIntent, current: AdmissionMembership, changes: List<DecryptedGroupChange>, self: ServiceIds): Boolean {
    val revision = intent.expectedRevision ?: return false
    val change = changes.firstOrNull { it.revision == revision && self.matches(it.editorServiceIdBytes) } ?: return false
    return when (intent.kind) {
      ConsentKind.ACCEPT -> current.kind == MembershipKind.FULL && current.joinedAtRevision == revision && promotesInvite(change, self)
      ConsentKind.JOIN, ConsentKind.CREATE -> current.kind == MembershipKind.FULL && current.joinedAtRevision == revision && change.newMembers.any { it.aciBytes == self.aci.toByteString() }
      ConsentKind.REQUEST -> current.kind == MembershipKind.REQUESTING && change.newRequestingMembers.any { it.aciBytes == self.aci.toByteString() && current.episode == "request:${it.timestamp}" }
    }
  }
}
