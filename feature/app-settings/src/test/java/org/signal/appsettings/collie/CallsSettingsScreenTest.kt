/*
 * Copyright 2026 Collie contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.appsettings.collie

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CallsSettingsScreenTest {
  @get:Rule
  val composeTestRule = createComposeRule()

  private val events = mutableListOf<CallsSettingsEvent>()
  private val defaults = CallsSettingsState(loading = false, available = true)

  @Test
  fun `ordinary defaults display allowed calls and an off absolute restriction`() {
    setContent(defaults)

    switch().assertIsOff()
    scrollTo(CallsSettingsTestTags.INCOMING)
    composeTestRule.onNodeWithText("Allow incoming calls").assertIsDisplayed()
    scrollTo(CallsSettingsTestTags.GROUPS)
    composeTestRule.onNodeWithText("Allow group calls").assertIsDisplayed()
    composeTestRule.onNodeWithText("Ask first").assertDoesNotExist()
  }

  @Test
  fun `disable-all requests a durable change without an optimistic switch`() {
    setContent(defaults)

    composeTestRule.onNodeWithTag(CallsSettingsTestTags.DISABLE_ALL).performClick()

    assertThat(events).containsExactly(CallsSettingsEvent.DisableAllChanged(true))
    switch().assertIsOff()
  }

  @Test
  fun `direct and group defaults produce distinct events`() {
    setContent(defaults)

    scrollTo(CallsSettingsTestTags.INCOMING)
    composeTestRule.onNodeWithTag(CallsSettingsTestTags.INCOMING).performClick()
    composeTestRule.onNodeWithText("Block incoming calls").performClick()
    scrollTo(CallsSettingsTestTags.GROUPS)
    composeTestRule.onNodeWithTag(CallsSettingsTestTags.GROUPS).performClick()
    composeTestRule.onNodeWithText("Block group calls").performClick()

    assertThat(events).containsExactly(CallsSettingsEvent.IncomingChanged(false), CallsSettingsEvent.GroupsChanged(false))
  }

  @Test
  fun `absolute prohibition prevents default exceptions while allowing explicit reenable`() {
    setContent(defaults.copy(allCallingDisabled = true))

    switch().assertIsOn()
    scrollTo(CallsSettingsTestTags.INCOMING)
    composeTestRule.onNodeWithTag(CallsSettingsTestTags.INCOMING).assertIsNotEnabled()
    scrollTo(CallsSettingsTestTags.GROUPS)
    composeTestRule.onNodeWithTag(CallsSettingsTestTags.GROUPS).assertIsNotEnabled()
    scrollTo(CallsSettingsTestTags.DISABLE_ALL)
    composeTestRule.onNodeWithTag(CallsSettingsTestTags.DISABLE_ALL).performClick()

    assertThat(events).containsExactly(CallsSettingsEvent.DisableAllChanged(false))
  }

  @Test
  fun `unavailable policy does not offer an editable allow setting`() {
    setContent(defaults.copy(available = false))

    composeTestRule.onNodeWithText("Call permissions are unavailable. Calling stays blocked until they can be read.").assertIsDisplayed()
    scrollTo(CallsSettingsTestTags.DISABLE_ALL)
    composeTestRule.onNodeWithTag(CallsSettingsTestTags.DISABLE_ALL).assertIsNotEnabled()
    scrollTo(CallsSettingsTestTags.INCOMING)
    composeTestRule.onNodeWithTag(CallsSettingsTestTags.INCOMING).assertIsNotEnabled()
    assertThat(events).isEmpty()
  }

  @Test
  fun `inherited contact choice stays distinguishable from an explicit allow`() {
    val choices = mutableListOf<CallPermissionChoice>()
    composeTestRule.setContent {
      Column {
        CallPermissionOverrideRow(
          state = CallPermissionOverrideState(loading = false, available = true),
          onSelected = choices::add,
          onErrorDismissed = {}
        )
      }
    }

    composeTestRule.onNodeWithText("Use device default · Allow incoming calls").assertIsDisplayed()
    composeTestRule.onNodeWithTag(CallsSettingsTestTags.OVERRIDE).performClick()
    composeTestRule.onNodeWithText("Allow incoming calls").performClick()

    assertThat(choices).containsExactly(CallPermissionChoice.ALLOW)
    composeTestRule.onNodeWithText("Use device default · Allow incoming calls").assertIsDisplayed()
  }

  @Test
  fun `group override names the group context and never offers private-call permission`() {
    composeTestRule.setContent {
      Column {
        CallPermissionOverrideRow(
          state = CallPermissionOverrideState(loading = false, available = true, group = true),
          onSelected = {},
          onErrorDismissed = {}
        )
      }
    }

    composeTestRule.onNodeWithTag(CallsSettingsTestTags.OVERRIDE).performClick()
    composeTestRule.onNodeWithText("Allow group calls").assertIsDisplayed()
    composeTestRule.onNodeWithText("Allow incoming calls").assertDoesNotExist()
  }

  @Test
  fun `contact override cannot override the device prohibition`() {
    composeTestRule.setContent {
      Column {
        CallPermissionOverrideRow(
          state = CallPermissionOverrideState(loading = false, available = true, allCallingDisabled = true, choice = CallPermissionChoice.ALLOW),
          onSelected = {},
          onErrorDismissed = {}
        )
      }
    }

    composeTestRule.onNodeWithTag(CallsSettingsTestTags.OVERRIDE).assertIsNotEnabled()
    composeTestRule.onNodeWithText("All calling is disabled on this device. Turn off the device restriction in Settings → Collie → Calls to change incoming-call permissions.").assertIsDisplayed()
  }

  private fun setContent(state: CallsSettingsState) {
    composeTestRule.setContent { CallsSettingsScreen(state, events::add) }
  }

  private fun switch() = composeTestRule.onNode(isToggleable() and hasAnyAncestor(hasTestTag(CallsSettingsTestTags.DISABLE_ALL)))

  private fun scrollTo(tag: String) {
    composeTestRule.onNodeWithTag(CallsSettingsTestTags.SCROLLER).performScrollToNode(hasTestTag(tag))
  }
}
