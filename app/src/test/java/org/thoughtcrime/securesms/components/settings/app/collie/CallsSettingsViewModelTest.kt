/*
 * Copyright 2026 Collie contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.components.settings.app.collie

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.signal.appsettings.collie.CallPermissionChoice
import org.signal.appsettings.collie.CallPermissionOverrideState
import org.signal.appsettings.collie.CallsSettingsEvent
import org.signal.appsettings.collie.CallsSettingsState
import org.signal.appsettings.routes.AppSettingsNavigationEvent
import org.thoughtcrime.securesms.recipients.Recipient
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class CallsSettingsViewModelTest {
  private val dispatcher = UnconfinedTestDispatcher()
  private val access = FakeAccess()
  private val navigation = mutableListOf<AppSettingsNavigationEvent>()

  @Before
  fun setUp() {
    Dispatchers.setMain(dispatcher)
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  @Test
  fun `failed permission grant leaves the persisted restriction visible`() = runTest(dispatcher) {
    access.device = access.device.copy(allowIncoming = false)
    val model = CallsSettingsViewModel(navigation::add, access, dispatcher)
    access.beforeWrite = {
      assertThat(model.state.value.allowIncoming).isFalse()
      assertThat(model.state.value.saving).isTrue()
      throw IOException("disk full")
    }

    model.onEvent(CallsSettingsEvent.IncomingChanged(true))

    assertThat(model.state.value.allowIncoming).isFalse()
    assertThat(model.state.value.saving).isFalse()
    assertThat(model.state.value.saveFailed).isTrue()
  }

  @Test
  fun `absolute restriction ignores individual and group edits`() = runTest(dispatcher) {
    access.device = access.device.copy(allCallingDisabled = true)
    val model = CallsSettingsViewModel(navigation::add, access, dispatcher)

    model.onEvent(CallsSettingsEvent.IncomingChanged(false))
    model.onEvent(CallsSettingsEvent.GroupsChanged(false))

    assertThat(access.writeCount).isEqualTo(0)
    model.onEvent(CallsSettingsEvent.DisableAllChanged(false))
    assertThat(model.state.value.allCallingDisabled).isFalse()
    assertThat(access.writeCount).isEqualTo(1)
  }

  @Test
  fun `unavailable policy does not allow settings writes`() = runTest(dispatcher) {
    access.readFails = true
    val model = CallsSettingsViewModel(navigation::add, access, dispatcher)

    model.onEvent(CallsSettingsEvent.DisableAllChanged(false))
    model.onEvent(CallsSettingsEvent.IncomingChanged(true))

    assertThat(model.state.value.available).isFalse()
    assertThat(access.writeCount).isEqualTo(0)
  }

  @Test
  fun `policy changes refresh the screen and group defaults stay separate`() = runTest(dispatcher) {
    val model = CallsSettingsViewModel(navigation::add, access, dispatcher)

    model.onEvent(CallsSettingsEvent.GroupsChanged(false))

    assertThat(model.state.value.allowIncoming).isTrue()
    assertThat(model.state.value.allowGroups).isFalse()
    access.device = access.device.copy(allCallingDisabled = true)
    access.changes.value++
    assertThat(model.state.value.allCallingDisabled).isTrue()
  }

  @Test
  fun `navigation returns to Collie without mutating permissions`() = runTest(dispatcher) {
    CallsSettingsViewModel(navigation::add, access, dispatcher).onEvent(CallsSettingsEvent.NavigateBack)
    assertThat(navigation).containsExactly(AppSettingsNavigationEvent.NavigateBack())
    assertThat(access.writeCount).isEqualTo(0)
  }

  @Test
  fun `selecting an unchanged permission does not revoke the current call`() = runTest(dispatcher) {
    val model = CallsSettingsViewModel(navigation::add, access, dispatcher)
    val recipient = mockk<Recipient> { every { isGroup } returns false }
    val contactModel = CallPermissionOverrideViewModel(recipient, access, dispatcher)

    model.onEvent(CallsSettingsEvent.DisableAllChanged(false))
    model.onEvent(CallsSettingsEvent.IncomingChanged(true))
    model.onEvent(CallsSettingsEvent.GroupsChanged(true))
    contactModel.select(CallPermissionChoice.INHERIT)

    assertThat(access.writeCount).isEqualTo(0)
  }

  @Test
  fun `contact inherit stays inherit when the effective device default changes`() = runTest(dispatcher) {
    val recipient = mockk<Recipient> { every { isGroup } returns false }
    val model = CallPermissionOverrideViewModel(recipient, access, dispatcher)

    access.recipient = access.recipient.copy(defaultAllowsCalls = false)
    access.changes.value++

    assertThat(model.state.value.choice).isEqualTo(CallPermissionChoice.INHERIT)
    assertThat(model.state.value.defaultAllowsCalls).isFalse()
    assertThat(access.writeCount).isEqualTo(0)
  }

  @Test
  fun `failed contact grant never replaces blocked state in the UI`() = runTest(dispatcher) {
    val recipient = mockk<Recipient> { every { isGroup } returns false }
    access.recipient = access.recipient.copy(choice = CallPermissionChoice.BLOCK)
    val model = CallPermissionOverrideViewModel(recipient, access, dispatcher)
    access.beforeWrite = { throw IOException("disk full") }

    model.select(CallPermissionChoice.ALLOW)

    assertThat(model.state.value.choice).isEqualTo(CallPermissionChoice.BLOCK)
    assertThat(model.state.value.saveFailed).isTrue()
  }

  @Test
  fun `retrying an unavailable contact policy reads its saved restriction without writing`() = runTest(dispatcher) {
    val recipient = mockk<Recipient> { every { isGroup } returns false }
    access.recipient = access.recipient.copy(choice = CallPermissionChoice.BLOCK, defaultAllowsCalls = false)
    access.readFails = true
    val model = CallPermissionOverrideViewModel(recipient, access, dispatcher)
    assertThat(model.state.value.available).isFalse()

    access.readFails = false
    model.retry()

    assertThat(model.state.value.available).isTrue()
    assertThat(model.state.value.loading).isFalse()
    assertThat(model.state.value.choice).isEqualTo(CallPermissionChoice.BLOCK)
    assertThat(model.state.value.defaultAllowsCalls).isFalse()
    assertThat(access.writeCount).isEqualTo(0)
  }

  @Test
  fun `failed recipient policy retry stays unavailable and cannot grant permission`() = runTest(dispatcher) {
    val recipient = mockk<Recipient> { every { isGroup } returns true }
    access.readFails = true
    val model = CallPermissionOverrideViewModel(recipient, access, dispatcher)

    model.retry()
    model.select(CallPermissionChoice.ALLOW)

    assertThat(model.state.value.available).isFalse()
    assertThat(model.state.value.loading).isFalse()
    assertThat(model.state.value.group).isTrue()
    assertThat(access.writeCount).isEqualTo(0)
  }

  private class FakeAccess : CallPermissionsSettingsAccess {
    override val changes = MutableStateFlow(0L)
    var device = CallsSettingsState(loading = false, available = true)
    var recipient = CallPermissionOverrideState(loading = false, available = true)
    var readFails = false
    var writeCount = 0
    var beforeWrite: () -> Unit = {}

    override fun readDevice(): CallsSettingsState {
      if (readFails) throw IOException("unreadable")
      return device
    }

    override fun readRecipient(recipient: Recipient): CallPermissionOverrideState {
      if (readFails) throw IOException("unreadable")
      return this.recipient
    }

    override fun setAllCallingDisabled(disabled: Boolean) = write { device = device.copy(allCallingDisabled = disabled) }

    override fun setIncomingDefault(allow: Boolean) = write { device = device.copy(allowIncoming = allow) }

    override fun setGroupDefault(allow: Boolean) = write { device = device.copy(allowGroups = allow) }

    override fun setOverride(recipient: Recipient, choice: CallPermissionChoice) = write { this.recipient = this.recipient.copy(choice = choice) }

    private fun write(change: () -> Unit) {
      writeCount++
      beforeWrite()
      change()
      changes.value++
    }
  }
}
