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
import org.signal.appsettings.collie.groups.GroupAllowedPerson
import org.signal.appsettings.collie.groups.GroupPrivacyMode
import org.signal.appsettings.collie.groups.GroupPrivacySettingsEvent
import org.signal.appsettings.collie.groups.GroupPrivacySettingsState
import org.signal.appsettings.routes.AppSettingsNavigationEvent
import org.signal.appsettings.routes.AppSettingsRoute
import org.thoughtcrime.securesms.testing.CoroutineDispatcherRule
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class GroupPrivacySettingsViewModelTest {
  private val dispatcher = UnconfinedTestDispatcher()
  @get:Rule val dispatcherRule = CoroutineDispatcherRule(dispatcher)
  private val navigation = mutableListOf<AppSettingsNavigationEvent>()
  private var saved = GroupPrivacySettingsState(isLoading = false)

  @Before
  fun setUp() {
    Dispatchers.setMain(dispatcher)
    mockkObject(GroupPrivacySettingsRepository)
    coEvery { GroupPrivacySettingsRepository.load() } answers { saved }
    coEvery { GroupPrivacySettingsRepository.setMode(any()) } answers { saved = saved.copy(mode = firstArg()) }
    coEvery { GroupPrivacySettingsRepository.removePerson(any()) } answers { saved = saved.copy(allowedPeople = saved.allowedPeople.filterNot { it.aci == firstArg<String>() }) }
  }

  @After
  fun tearDown() {
    unmockkObject(GroupPrivacySettingsRepository)
    Dispatchers.resetMain()
  }

  @Test
  fun `all modes load from successful persistence and changing mode keeps the people list`() = runTest(dispatcher) {
    val person = GroupAllowedPerson("alice-aci", "Alice", null, true)
    saved = saved.copy(allowedPeople = listOf(person))
    val viewModel = createViewModel()

    (GroupPrivacyMode.entries.drop(1) + GroupPrivacyMode.SIGNAL_DEFAULT).forEach { mode ->
      viewModel.onEvent(GroupPrivacySettingsEvent.ModeSelected(mode))
      assertThat(viewModel.state.value.mode).isEqualTo(mode)
      assertThat(viewModel.state.value.allowedPeople).containsExactly(person)
      coVerify(exactly = 1) { GroupPrivacySettingsRepository.setMode(mode) }
    }
  }

  @Test
  fun `mode does not change while a durable write is unfinished`() = runTest(dispatcher) {
    val completed = CompletableDeferred<Unit>()
    coEvery { GroupPrivacySettingsRepository.setMode(GroupPrivacyMode.ASK_EVERY_TIME) } coAnswers {
      completed.await()
      saved = saved.copy(mode = GroupPrivacyMode.ASK_EVERY_TIME)
    }
    val viewModel = createViewModel()
    viewModel.onEvent(GroupPrivacySettingsEvent.ModeSelected(GroupPrivacyMode.ASK_EVERY_TIME))

    assertThat(viewModel.state.value.mode).isEqualTo(GroupPrivacyMode.SIGNAL_DEFAULT)
    assertThat(viewModel.state.value.isSaving).isTrue()
    assertThat(viewModel.state.value.canChange).isFalse()
    completed.complete(Unit)
    assertThat(viewModel.state.value.mode).isEqualTo(GroupPrivacyMode.ASK_EVERY_TIME)
  }

  @Test
  fun `failed mode write leaves the confirmed selection visible`() = runTest(dispatcher) {
    coEvery { GroupPrivacySettingsRepository.setMode(any()) } throws IOException("disk full")
    val viewModel = createViewModel()
    viewModel.onEvent(GroupPrivacySettingsEvent.ModeSelected(GroupPrivacyMode.DEVICE_CONTACTS))

    assertThat(viewModel.state.value.mode).isEqualTo(GroupPrivacyMode.SIGNAL_DEFAULT)
    assertThat(viewModel.state.value.error).isEqualTo(GroupPrivacySettingsState.Error.SAVE_FAILED)
    assertThat(viewModel.state.value.isSaving).isFalse()
  }

  @Test
  fun `a read failure after a completed write asks for retry without claiming the old setting is saved`() = runTest(dispatcher) {
    val viewModel = createViewModel()
    coEvery { GroupPrivacySettingsRepository.load() } throws IOException("read unavailable")
    viewModel.onEvent(GroupPrivacySettingsEvent.ModeSelected(GroupPrivacyMode.ASK_EVERY_TIME))

    assertThat(saved.mode).isEqualTo(GroupPrivacyMode.ASK_EVERY_TIME)
    assertThat(viewModel.state.value.error).isEqualTo(GroupPrivacySettingsState.Error.LOAD_FAILED)
    assertThat(viewModel.state.value.canChange).isFalse()

    coEvery { GroupPrivacySettingsRepository.load() } returns saved
    viewModel.onEvent(GroupPrivacySettingsEvent.RetryClicked)
    assertThat(viewModel.state.value.mode).isEqualTo(GroupPrivacyMode.ASK_EVERY_TIME)
    assertThat(viewModel.state.value.canChange).isTrue()
  }

  @Test
  fun `selected people mode with an empty list stays selected and allows opening the picker`() = runTest(dispatcher) {
    saved = saved.copy(mode = GroupPrivacyMode.SELECTED_PEOPLE)
    val viewModel = createViewModel()

    assertThat(viewModel.state.value.mode).isEqualTo(GroupPrivacyMode.SELECTED_PEOPLE)
    assertThat(viewModel.state.value.allowedPeople).isEmpty()
    coVerify(exactly = 0) { GroupPrivacySettingsRepository.setMode(any()) }
    viewModel.onEvent(GroupPrivacySettingsEvent.ChoosePeopleClicked)
    assertThat(navigation).containsExactly(AppSettingsNavigationEvent.NavigateToScreen(AppSettingsRoute.CollieRoute.ChooseGroupPeople))
  }

  @Test
  fun `removing the last person does not relax the mode`() = runTest(dispatcher) {
    saved = saved.copy(mode = GroupPrivacyMode.SELECTED_PEOPLE, allowedPeople = listOf(GroupAllowedPerson("alice-aci", "Alice", null, true)))
    val viewModel = createViewModel()
    viewModel.onEvent(GroupPrivacySettingsEvent.RemovePersonClicked("alice-aci"))

    assertThat(viewModel.state.value.allowedPeople).isEmpty()
    assertThat(viewModel.state.value.mode).isEqualTo(GroupPrivacyMode.SELECTED_PEOPLE)
    coVerify(exactly = 0) { GroupPrivacySettingsRepository.setMode(any()) }
  }

  @Test
  fun `failed removal leaves the existing person visible`() = runTest(dispatcher) {
    val person = GroupAllowedPerson("alice-aci", "Alice", null, true)
    saved = saved.copy(mode = GroupPrivacyMode.SELECTED_PEOPLE, allowedPeople = listOf(person))
    coEvery { GroupPrivacySettingsRepository.removePerson(any()) } throws IOException("disk full")
    val viewModel = createViewModel()
    viewModel.onEvent(GroupPrivacySettingsEvent.RemovePersonClicked(person.aci))

    assertThat(viewModel.state.value.allowedPeople).containsExactly(person)
    assertThat(viewModel.state.value.error).isEqualTo(GroupPrivacySettingsState.Error.SAVE_FAILED)
  }

  @Test
  fun `failed initial read disables changes until retry succeeds`() = runTest(dispatcher) {
    coEvery { GroupPrivacySettingsRepository.load() } throws IOException("unavailable")
    val viewModel = createViewModel()
    viewModel.onEvent(GroupPrivacySettingsEvent.ModeSelected(GroupPrivacyMode.SIGNAL_DEFAULT))

    assertThat(viewModel.state.value.canChange).isFalse()
    coVerify(exactly = 0) { GroupPrivacySettingsRepository.setMode(any()) }
    coEvery { GroupPrivacySettingsRepository.load() } returns saved.copy(mode = GroupPrivacyMode.ASK_EVERY_TIME)
    viewModel.onEvent(GroupPrivacySettingsEvent.RetryClicked)
    assertThat(viewModel.state.value.mode).isEqualTo(GroupPrivacyMode.ASK_EVERY_TIME)
    assertThat(viewModel.state.value.canChange).isTrue()
  }

  @Test
  fun `resuming reloads changed contact permission and persisted mode`() = runTest(dispatcher) {
    val viewModel = createViewModel()
    saved = saved.copy(hasContactsPermission = true, mode = GroupPrivacyMode.DEVICE_CONTACTS)
    viewModel.onEvent(GroupPrivacySettingsEvent.ScreenResumed)

    assertThat(viewModel.state.value.hasContactsPermission).isTrue()
    assertThat(viewModel.state.value.mode).isEqualTo(GroupPrivacyMode.DEVICE_CONTACTS)
    coVerify(exactly = 0) { GroupPrivacySettingsRepository.setMode(any()) }
  }

  private fun createViewModel() = GroupPrivacySettingsViewModel { navigation += it }
}
