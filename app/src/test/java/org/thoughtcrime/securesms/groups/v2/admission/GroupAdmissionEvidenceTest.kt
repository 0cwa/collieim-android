/*
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.groups.v2.admission

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotEqualTo
import assertk.assertions.isNull
import assertk.assertions.isTrue
import okio.ByteString.Companion.encodeUtf8
import org.junit.Test
import org.signal.core.models.ServiceId.ACI
import org.signal.core.models.ServiceId.PNI
import org.signal.storageservice.storage.protos.groups.local.DecryptedGroup
import org.signal.storageservice.storage.protos.groups.local.DecryptedGroupChange
import org.signal.storageservice.storage.protos.groups.local.DecryptedMember
import org.signal.storageservice.storage.protos.groups.local.DecryptedPendingMember
import org.signal.storageservice.storage.protos.groups.local.DecryptedPendingMemberRemoval
import org.signal.storageservice.storage.protos.groups.local.DecryptedRequestingMember
import org.thoughtcrime.securesms.groups.v2.processing.GroupStateDiff
import org.whispersystems.signalservice.api.groupsv2.DecryptedGroupChangeLog
import org.whispersystems.signalservice.api.push.ServiceIds
import java.util.UUID

class GroupAdmissionEvidenceTest {
  private val selfAci = ACI.from(UUID.fromString("11111111-1111-4111-8111-111111111111"))
  private val selfPni = PNI.from(UUID.fromString("22222222-2222-4222-8222-222222222222"))
  private val alice = ACI.from(UUID.fromString("33333333-3333-4333-8333-333333333333"))
  private val bob = ACI.from(UUID.fromString("44444444-4444-4444-8444-444444444444"))
  private val self = ServiceIds(selfAci, selfPni)

  @Test
  fun `ordinary updates preserve a membership episode but removal and re-add changes it`() {
    val original = fullGroup(joined = 5, revision = 5)
    val renamed = original.copy(title = "New name", revision = 10)
    val readded = fullGroup(joined = 12, revision = 12)

    assertThat(membership(original).episode).isEqualTo(membership(renamed).episode)
    assertThat(membership(original).episode).isNotEqualTo(membership(readded).episode)
    assertThat(membership(original.copy(terminated = true)).kind).isEqualTo(MembershipKind.ABSENT)
    assertThat(membership(original.copy(members = emptyList())).kind).isEqualTo(MembershipKind.ABSENT)
    assertThat(membership(null).kind).isEqualTo(MembershipKind.ABSENT)
  }

  @Test
  fun `pending PNI invitations use invitation evidence rather than latest group revision`() {
    val pending = DecryptedPendingMember(serviceIdBytes = selfPni.toByteString(), addedByAci = alice.toByteString(), timestamp = 100, serviceIdCipherText = "ciphertext".encodeUtf8())
    val group = DecryptedGroup(revision = 5, pendingMembers = listOf(pending))

    assertThat(membership(group).kind).isEqualTo(MembershipKind.PENDING)
    assertThat(membership(group).episode).isEqualTo(membership(group.copy(revision = 9)).episode)
    assertThat(membership(group).episode).isNotEqualTo(membership(group.copy(pendingMembers = listOf(pending.copy(timestamp = 200)))).episode)
    assertThat(membership(group).episode).isNotEqualTo(membership(group.copy(pendingMembers = listOf(pending.copy(addedByAci = bob.toByteString())))).episode)
  }

  @Test
  fun `inviter is the authenticated editor of the joining revision rather than the latest editor`() {
    val current = fullGroup(joined = 5, revision = 10)
    val joined = DecryptedGroupChange(revision = 5, editorServiceIdBytes = alice.toByteString(), newMembers = current.members)
    val later = DecryptedGroupChange(revision = 10, editorServiceIdBytes = bob.toByteString())
    val diff = GroupStateDiff(null, listOf(DecryptedGroupChangeLog(null, joined), DecryptedGroupChangeLog(current, later)), null)

    assertThat(GroupAdmissionEvidence.inviter(diff, current, self)).isEqualTo(alice)
    assertThat(GroupAdmissionEvidence.inviter(GroupStateDiff(null, current, later), current, self)).isNull()
    assertThat(GroupAdmissionEvidence.inviter(GroupStateDiff(null, current, joined.copy(editorServiceIdBytes = "invalid".encodeUtf8())), current, self)).isNull()
  }

  @Test
  fun `pending invitation attributes the original inviter even with no change history`() {
    val pending = DecryptedPendingMember(serviceIdBytes = selfPni.toByteString(), addedByAci = alice.toByteString(), timestamp = 100)
    val group = DecryptedGroup(revision = 5, pendingMembers = listOf(pending))
    assertThat(GroupAdmissionEvidence.inviter(GroupStateDiff(null, group, null), group, self)).isEqualTo(alice)
  }

  @Test
  fun `membership removal recognizes full ACI and pending or requesting self identities`() {
    assertThat(GroupAdmissionEvidence.removedSelf(DecryptedGroupChange(deleteMembers = listOf(selfAci.toByteString())), self)).isTrue()
    assertThat(GroupAdmissionEvidence.removedSelf(DecryptedGroupChange(deletePendingMembers = listOf(DecryptedPendingMemberRemoval(serviceIdBytes = selfPni.toByteString()))), self)).isTrue()
    assertThat(GroupAdmissionEvidence.removedSelf(DecryptedGroupChange(deleteRequestingMembers = listOf(selfAci.toByteString())), self)).isTrue()
    assertThat(GroupAdmissionEvidence.removedSelf(DecryptedGroupChange(deleteMembers = listOf(alice.toByteString())), self)).isFalse()
  }

  @Test
  fun `acceptance needs its exact mutation revision and self-authored promotion`() {
    val current = fullGroup(joined = 5, revision = 8)
    val intent = AdmissionIntent("accept-attempt", ConsentKind.ACCEPT, "reviewed-invite", expectedRevision = 5)
    val accepted = DecryptedGroupChange(revision = 5, editorServiceIdBytes = selfAci.toByteString(), promotePendingMembers = current.members)

    assertThat(matches(intent, current, accepted)).isTrue()
    assertThat(matches(intent.copy(expectedRevision = null), current, accepted)).isFalse()
    assertThat(matches(intent.copy(expectedRevision = 6), current, accepted)).isFalse()
    assertThat(matches(intent, current, accepted.copy(editorServiceIdBytes = alice.toByteString()))).isFalse()
    assertThat(matches(intent, fullGroup(joined = 7, revision = 8), accepted)).isFalse()
    assertThat(matches(intent, current, accepted.copy(promotePendingMembers = listOf(DecryptedMember(aciBytes = alice.toByteString()))))).isFalse()
  }

  @Test
  fun `accepting a PNI invitation recognizes the self editor and resulting ACI promotion`() {
    val current = fullGroup(joined = 5, revision = 5)
    val intent = AdmissionIntent("accept-pni", ConsentKind.ACCEPT, "reviewed-pni-invite", expectedRevision = 5)
    val accepted = DecryptedGroupChange(revision = 5, editorServiceIdBytes = selfPni.toByteString(), promotePendingPniAciMembers = current.members)

    assertThat(matches(intent, current, accepted)).isTrue()
  }

  @Test
  fun `join and create intents cannot authorize another editor or a later re-add`() {
    val current = fullGroup(joined = 5, revision = 8)
    val joined = DecryptedGroupChange(revision = 5, editorServiceIdBytes = selfAci.toByteString(), newMembers = current.members)
    listOf(ConsentKind.JOIN, ConsentKind.CREATE).forEach { kind ->
      val intent = AdmissionIntent("local-operation", kind, null, expectedRevision = 5)
      assertThat(matches(intent, current, joined)).isTrue()
      assertThat(matches(intent, current, joined.copy(editorServiceIdBytes = alice.toByteString()))).isFalse()
      assertThat(matches(intent, fullGroup(joined = 7, revision = 8), joined)).isFalse()
    }
  }

  @Test
  fun `an older request intent cannot authorize a later requesting episode`() {
    val request = DecryptedRequestingMember(aciBytes = selfAci.toByteString(), timestamp = 100)
    val current = DecryptedGroup(revision = 5, requestingMembers = listOf(request))
    val intent = AdmissionIntent("request-attempt", ConsentKind.REQUEST, null, expectedRevision = 5)
    val requested = DecryptedGroupChange(revision = 5, editorServiceIdBytes = selfAci.toByteString(), newRequestingMembers = listOf(request))

    assertThat(matches(intent, current, requested)).isTrue()
    assertThat(matches(intent, current.copy(revision = 8, requestingMembers = listOf(request.copy(timestamp = 200))), requested)).isFalse()
    assertThat(matches(intent, current, requested.copy(editorServiceIdBytes = alice.toByteString()))).isFalse()
    assertThat(matches(intent, fullGroup(joined = 6, revision = 6), requested)).isFalse()
  }

  private fun fullGroup(joined: Int, revision: Int) = DecryptedGroup(revision = revision, members = listOf(DecryptedMember(aciBytes = selfAci.toByteString(), joinedAtRevision = joined)))
  private fun membership(group: DecryptedGroup?) = GroupAdmissionEvidence.membership(group, self)
  private fun matches(intent: AdmissionIntent, group: DecryptedGroup, change: DecryptedGroupChange) = GroupAdmissionEvidence.matchesIntent(intent, membership(group), listOf(change), self)
}
