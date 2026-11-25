/*
 * Copyright 2026 Collie contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.components.settings.app.collie.groups

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.signal.appsettings.collie.groups.ChooseGroupPeopleEvent
import org.signal.appsettings.collie.groups.ChooseGroupPeopleState
import org.signal.appsettings.routes.AppSettingsNavigationEvent
import org.signal.core.models.recipients.RecipientId
import org.thoughtcrime.securesms.testing.CoroutineDispatcherRule
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class ChooseGroupPeopleViewModelTest {
  private val dispatcher = UnconfinedTestDispatcher()
  @get:Rule val dispatcherRule = CoroutineDispatcherRule(dispatcher)
  private val navigation = mutableListOf<AppSettingsNavigationEvent>()
  private val alice = RecipientId.from(1L)
  private val aliceAci = "56370921-d8a0-401b-b70c-019c25a7a3c8"

  @Before
  fun setUp() {
    Dispatchers.setMain(dispatcher)
    mockkObject(GroupPrivacySettingsRepository)
    coEvery { GroupPrivacySettingsRepository.resolveSelectableAci(alice) } returns aliceAci
    coEvery { GroupPrivacySettingsRepository.addPeople(any()) } returns Unit
  }

  @After
  fun tearDown() {
    unmockkObject(GroupPrivacySettingsRepository)
    Dispatchers.resetMain()
  }

  @Test
  fun `empty selection cannot persist`() = runTest(dispatcher) {
    val viewModel = createViewModel()
    viewModel.onEvent(ChooseGroupPeopleEvent.AddClicked)
    assertThat(viewModel.state.value.canSave).isFalse()
    coVerify(exactly = 0) { GroupPrivacySettingsRepository.addPeople(any()) }
  }

  @Test
  fun `a phone number without a recipient identity cannot be selected`() = runTest(dispatcher) {
    val viewModel = createViewModel()
    assertThat(viewModel.validateSelection(null)).isFalse()
    assertThat(viewModel.state.value.error).isEqualTo(ChooseGroupPeopleState.Error.INELIGIBLE_PERSON)
    coVerify(exactly = 0) { GroupPrivacySettingsRepository.resolveSelectableAci(any()) }
  }

  @Test
  fun `ineligible or unverified restored selections cannot be saved`() = runTest(dispatcher) {
    coEvery { GroupPrivacySettingsRepository.resolveSelectableAci(alice) } returns null
    val viewModel = createViewModel()
    assertThat(viewModel.validateSelection(alice)).isFalse()
    viewModel.onEvent(ChooseGroupPeopleEvent.SelectionChanged(setOf(alice)))
    viewModel.onEvent(ChooseGroupPeopleEvent.AddClicked)
    assertThat(viewModel.state.value.canSave).isFalse()
    coVerify(exactly = 0) { GroupPrivacySettingsRepository.addPeople(any()) }
  }

  @Test
  fun `saving passes the identity verified when the person was selected`() = runTest(dispatcher) {
    val viewModel = createViewModel()
    assertThat(viewModel.validateSelection(alice)).isTrue()
    viewModel.onEvent(ChooseGroupPeopleEvent.SelectionChanged(setOf(alice)))
    viewModel.onEvent(ChooseGroupPeopleEvent.AddClicked)
    viewModel.onEvent(ChooseGroupPeopleEvent.AddClicked)

    coVerify(exactly = 1) { GroupPrivacySettingsRepository.addPeople(mapOf(alice to aliceAci)) }
    assertThat(navigation).containsExactly(AppSettingsNavigationEvent.NavigateBack())
  }

  @Test
  fun `deselecting everyone disables save`() = runTest(dispatcher) {
    val viewModel = createViewModel()
    viewModel.validateSelection(alice)
    viewModel.onEvent(ChooseGroupPeopleEvent.SelectionChanged(setOf(alice)))
    viewModel.onEvent(ChooseGroupPeopleEvent.SelectionChanged(emptySet()))
    assertThat(viewModel.state.value.selectedCount).isEqualTo(0)
    assertThat(viewModel.state.value.canSave).isFalse()
  }

  @Test
  fun `saving waits for persistence before leaving the picker`() = runTest(dispatcher) {
    val completed = CompletableDeferred<Unit>()
    coEvery { GroupPrivacySettingsRepository.addPeople(any()) } coAnswers { completed.await() }
    val viewModel = createViewModel()
    viewModel.validateSelection(alice)
    viewModel.onEvent(ChooseGroupPeopleEvent.SelectionChanged(setOf(alice)))
    viewModel.onEvent(ChooseGroupPeopleEvent.AddClicked)

    assertThat(viewModel.state.value.isSaving).isTrue()
    assertThat(navigation).isEmpty()
    completed.complete(Unit)
    assertThat(navigation).containsExactly(AppSettingsNavigationEvent.NavigateBack())
  }

  @Test
  fun `identity change is reported without leaving the picker`() = runTest(dispatcher) {
    coEvery { GroupPrivacySettingsRepository.addPeople(any()) } throws GroupPrivacySettingsRepository.SelectionChangedException()
    val viewModel = createViewModel()
    viewModel.validateSelection(alice)
    viewModel.onEvent(ChooseGroupPeopleEvent.SelectionChanged(setOf(alice)))
    viewModel.onEvent(ChooseGroupPeopleEvent.AddClicked)

    assertThat(viewModel.state.value.error).isEqualTo(ChooseGroupPeopleState.Error.IDENTITY_CHANGED)
    assertThat(navigation).isEmpty()
  }

  @Test
  fun `write failure leaves picks available for retry without claiming success`() = runTest(dispatcher) {
    coEvery { GroupPrivacySettingsRepository.addPeople(any()) } throws IOException("disk full")
    val viewModel = createViewModel()
    viewModel.validateSelection(alice)
    viewModel.onEvent(ChooseGroupPeopleEvent.SelectionChanged(setOf(alice)))
    viewModel.onEvent(ChooseGroupPeopleEvent.AddClicked)

    assertThat(viewModel.state.value.error).isEqualTo(ChooseGroupPeopleState.Error.SAVE_FAILED)
    assertThat(viewModel.state.value.canSave).isTrue()
    assertThat(navigation).isEmpty()
    coEvery { GroupPrivacySettingsRepository.addPeople(any()) } returns Unit
    viewModel.onEvent(ChooseGroupPeopleEvent.AddClicked)
    assertThat(navigation).containsExactly(AppSettingsNavigationEvent.NavigateBack())
  }

  private fun createViewModel() = ChooseGroupPeopleViewModel { navigation += it }
}
