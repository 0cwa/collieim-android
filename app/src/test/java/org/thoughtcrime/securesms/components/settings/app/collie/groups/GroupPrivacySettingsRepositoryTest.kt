/*
 * Copyright 2026 Collie contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.components.settings.app.collie.groups

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.signal.core.models.ServiceId.ACI
import org.signal.core.models.recipients.RecipientId
import org.thoughtcrime.securesms.groups.v2.admission.GroupAdmission
import org.thoughtcrime.securesms.groups.v2.admission.GroupAdmissionMode
import org.thoughtcrime.securesms.groups.v2.admission.GroupAdmissionSettings
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.testing.CoroutineDispatcherRule
import java.util.Optional

@OptIn(ExperimentalCoroutinesApi::class)
class GroupPrivacySettingsRepositoryTest {
  private val dispatcher = UnconfinedTestDispatcher()
  @get:Rule val dispatcherRule = CoroutineDispatcherRule(dispatcher)
  private val alice = RecipientId.from(1L)
  private val aliceAci = ACI.parseOrThrow("56370921-d8a0-401b-b70c-019c25a7a3c8")
  private val existingAci = "e015bc46-4653-48d4-8951-471e82ff204e"

  @Before
  fun setUp() {
    mockkObject(Recipient)
    mockkStatic(Recipient::class)
    mockkStatic(GroupAdmission::class)
    every { GroupAdmission.getSettings() } returns GroupAdmissionSettings(GroupAdmissionMode.SELECTED_PEOPLE, setOf(existingAci))
    every { GroupAdmission.setAllowedPeople(any()) } returns Unit
  }

  @After
  fun tearDown() {
    unmockkStatic(GroupAdmission::class)
    unmockkStatic(Recipient::class)
    unmockkObject(Recipient)
  }

  @Test
  fun `only registered visible unblocked individuals with a valid ACI are selectable`() {
    assertThat(GroupPrivacySettingsRepository.selectableAci(recipient())).isEqualTo(aliceAci.toString())
    assertThat(GroupPrivacySettingsRepository.selectableAci(recipient(registered = false))).isNull()
    assertThat(GroupPrivacySettingsRepository.selectableAci(recipient(individual = false))).isNull()
    assertThat(GroupPrivacySettingsRepository.selectableAci(recipient(blocked = true))).isNull()
    assertThat(GroupPrivacySettingsRepository.selectableAci(recipient(hidden = true))).isNull()
    assertThat(GroupPrivacySettingsRepository.selectableAci(recipient(self = true))).isNull()
    assertThat(GroupPrivacySettingsRepository.selectableAci(recipient(aci = null))).isNull()
    assertThat(GroupPrivacySettingsRepository.selectableAci(recipient(aci = ACI.UNKNOWN))).isNull()
  }

  @Test
  fun `adding stores the verified ACI and preserves existing entries without changing mode`() = runTest(dispatcher) {
    every { Recipient.resolved(alice) } returns recipient()
    GroupPrivacySettingsRepository.addPeople(mapOf(alice to aliceAci.toString()))

    verify(exactly = 1) { GroupAdmission.setAllowedPeople(setOf(existingAci, aliceAci.toString())) }
    verify(exactly = 0) { GroupAdmission.setMode(any()) }
  }

  @Test
  fun `identity change rejects the entire addition before persistence`() = runTest(dispatcher) {
    every { Recipient.resolved(alice) } returns recipient(aci = ACI.parseOrThrow(existingAci))
    var rejected = false
    try {
      GroupPrivacySettingsRepository.addPeople(mapOf(alice to aliceAci.toString()))
    } catch (e: GroupPrivacySettingsRepository.SelectionChangedException) {
      rejected = true
    }

    assertThat(rejected).isEqualTo(true)
    verify(exactly = 0) { GroupAdmission.setAllowedPeople(any()) }
  }

  @Test
  fun `newly blocked person is rejected before persistence`() = runTest(dispatcher) {
    every { Recipient.resolved(alice) } returns recipient(blocked = true)
    var rejected = false
    try {
      GroupPrivacySettingsRepository.addPeople(mapOf(alice to aliceAci.toString()))
    } catch (e: GroupPrivacySettingsRepository.SelectionChangedException) {
      rejected = true
    }

    assertThat(rejected).isEqualTo(true)
    verify(exactly = 0) { GroupAdmission.setAllowedPeople(any()) }
  }

  @Test
  fun `removing the last saved entry persists an empty set without relaxing the mode`() = runTest(dispatcher) {
    GroupPrivacySettingsRepository.removePerson(existingAci)
    verify(exactly = 1) { GroupAdmission.setAllowedPeople(emptySet()) }
    verify(exactly = 0) { GroupAdmission.setMode(any()) }
  }

  private fun recipient(
    aci: ACI? = aliceAci,
    registered: Boolean = true,
    individual: Boolean = true,
    blocked: Boolean = false,
    hidden: Boolean = false,
    self: Boolean = false
  ): Recipient {
    val recipient = mockk<Recipient>()
    every { recipient.fresh() } returns recipient
    every { recipient.isIndividual } returns individual
    every { recipient.isRegistered } returns registered
    every { recipient.isBlocked } returns blocked
    every { recipient.isHidden } returns hidden
    every { recipient.isSelf } returns self
    every { recipient.aci } returns Optional.ofNullable(aci)
    return recipient
  }
}
