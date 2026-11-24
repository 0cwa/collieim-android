/*
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.groups.v2.admission

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import org.junit.Test

class GroupAdmissionPolicyTest {
  private val alice = "11111111-1111-4111-8111-111111111111"
  private val bob = "22222222-2222-4222-8222-222222222222"

  @Test
  fun `default delegates to Signal instead of broadening or replacing its trust checks`() {
    listOf(null, AdmissionInviter(null), AdmissionInviter(alice, blocked = true), AdmissionInviter(alice, deviceContact = true)).forEach { inviter ->
      assertThat(GroupAdmissionPolicy.evaluate(GroupAdmissionSettings(), inviter)).isEqualTo(AdmissionStatus.SIGNAL_DEFAULT)
    }
  }

  @Test
  fun `every restrictive mode holds missing attribution and blocked or hidden inviters`() {
    GroupAdmissionMode.entries.filter { it != GroupAdmissionMode.SIGNAL_DEFAULT }.forEach { mode ->
      val settings = GroupAdmissionSettings(mode, setOf(alice))
      listOf(null, AdmissionInviter(null, deviceContact = true), AdmissionInviter(alice, blocked = true, deviceContact = true), AdmissionInviter(alice, hidden = true, deviceContact = true)).forEach { inviter ->
        assertThat(GroupAdmissionPolicy.evaluate(settings, inviter)).isEqualTo(AdmissionStatus.HELD)
      }
    }
  }

  @Test
  fun `contacts mode requires a verified device contact and ignores the selected list`() {
    val settings = GroupAdmissionSettings(GroupAdmissionMode.DEVICE_CONTACTS, setOf(alice))
    assertThat(GroupAdmissionPolicy.evaluate(settings, AdmissionInviter(alice))).isEqualTo(AdmissionStatus.HELD)
    assertThat(GroupAdmissionPolicy.evaluate(settings, AdmissionInviter(bob, deviceContact = true))).isEqualTo(AdmissionStatus.AUTOMATIC)
  }

  @Test
  fun `selected people mode matches exact stable identity rather than contact status`() {
    val settings = GroupAdmissionSettings(GroupAdmissionMode.SELECTED_PEOPLE, setOf(alice))
    assertThat(GroupAdmissionPolicy.evaluate(settings, AdmissionInviter(alice))).isEqualTo(AdmissionStatus.AUTOMATIC)
    assertThat(GroupAdmissionPolicy.evaluate(settings, AdmissionInviter(bob, deviceContact = true))).isEqualTo(AdmissionStatus.HELD)
    assertThat(GroupAdmissionPolicy.evaluate(settings.copy(allowedPeople = emptySet()), AdmissionInviter(alice, deviceContact = true))).isEqualTo(AdmissionStatus.HELD)
  }

  @Test
  fun `ask every time holds even a selected contact`() {
    val settings = GroupAdmissionSettings(GroupAdmissionMode.ASK_EVERY_TIME, setOf(alice))
    assertThat(GroupAdmissionPolicy.evaluate(settings, AdmissionInviter(alice, deviceContact = true))).isEqualTo(AdmissionStatus.HELD)
  }

  @Test
  fun `unseen group is held in restrictive modes but an existing hold survives a return to default`() {
    val held = AdmissionRecord(AdmissionMembership(MembershipKind.FULL, "member:5", 5, 5), AdmissionStatus.HELD)
    GroupAdmissionMode.entries.forEach { mode ->
      val state = AdmissionAccountState(alice, GroupAdmissionSettings(mode), records = mapOf("held" to held))
      assertThat(state.isHeld("new")).isEqualTo(mode != GroupAdmissionMode.SIGNAL_DEFAULT)
      assertThat(state.isHeld("held")).isTrue()
    }
  }

  @Test
  fun `unfinished cross-store update cannot publish any otherwise permitted decision`() {
    val membership = AdmissionMembership(MembershipKind.FULL, "member:5", 5, 5)
    AdmissionStatus.entries.filter { it != AdmissionStatus.HELD }.forEach { status ->
      val record = AdmissionRecord(membership, status, pendingWrite = "write-in-progress")
      val state = AdmissionAccountState(alice, records = mapOf("group" to record))
      assertThat(state.isHeld("group")).isTrue()
      assertThat(state.copy(records = mapOf("group" to record.copy(pendingWrite = null))).isHeld("group")).isFalse()
    }
  }
}
