/*
 * Copyright 2026 Collie contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.appsettings.collie.groups

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import assertk.assertThat
import assertk.assertions.containsExactly
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class GroupPrivacySettingsScreenTest {
  @get:Rule val composeTestRule = createComposeRule()
  private val events = mutableListOf<GroupPrivacySettingsEvent>()

  @Test
  fun `the default choice is explicit and tapping another choice does not optimistically select it`() {
    setContent(GroupPrivacySettingsState(isLoading = false))
    scrollTo(GroupPrivacyMode.SIGNAL_DEFAULT)
    composeTestRule.onNodeWithTag(GroupPrivacySettingsTestTags.mode(GroupPrivacyMode.SIGNAL_DEFAULT)).assertIsSelected()
    scrollTo(GroupPrivacyMode.ASK_EVERY_TIME)
    composeTestRule.onNodeWithTag(GroupPrivacySettingsTestTags.mode(GroupPrivacyMode.ASK_EVERY_TIME)).performClick().assertIsNotSelected()
    assertThat(events).containsExactly(GroupPrivacySettingsEvent.ModeSelected(GroupPrivacyMode.ASK_EVERY_TIME))
  }

  @Test
  fun `an empty chosen list clearly requires review`() {
    setContent(GroupPrivacySettingsState(mode = GroupPrivacyMode.SELECTED_PEOPLE, isLoading = false))
    scrollTo(GroupPrivacyMode.SELECTED_PEOPLE)
    composeTestRule.onNodeWithTag(GroupPrivacySettingsTestTags.mode(GroupPrivacyMode.SELECTED_PEOPLE)).assertIsSelected()
    scrollToText("No people selected. New group additions will need your review.")
    composeTestRule.onNodeWithText("No people selected. New group additions will need your review.").assertIsDisplayed()
    composeTestRule.onNodeWithTag(GroupPrivacySettingsTestTags.SCROLLER).performScrollToNode(hasTestTag(GroupPrivacySettingsTestTags.CHOOSE_PEOPLE))
    composeTestRule.onNodeWithTag(GroupPrivacySettingsTestTags.CHOOSE_PEOPLE).performClick()
    assertThat(events).containsExactly(GroupPrivacySettingsEvent.ChoosePeopleClicked)
  }

  @Test
  fun `contact mode explains the effect of missing contacts permission`() {
    setContent(GroupPrivacySettingsState(mode = GroupPrivacyMode.DEVICE_CONTACTS, isLoading = false, hasContactsPermission = false))
    scrollToText("Contacts access is off.")
    composeTestRule.onNodeWithText("Contacts access is off.", substring = true).assertIsDisplayed()
  }

  @Test
  fun `saving disables mode changes while retaining the confirmed choice`() {
    setContent(GroupPrivacySettingsState(mode = GroupPrivacyMode.DEVICE_CONTACTS, isLoading = false, isSaving = true))
    scrollTo(GroupPrivacyMode.DEVICE_CONTACTS)
    composeTestRule.onNodeWithTag(GroupPrivacySettingsTestTags.mode(GroupPrivacyMode.DEVICE_CONTACTS)).assertIsSelected().assertIsNotEnabled()
    scrollTo(GroupPrivacyMode.ASK_EVERY_TIME)
    composeTestRule.onNodeWithTag(GroupPrivacySettingsTestTags.mode(GroupPrivacyMode.ASK_EVERY_TIME)).assertIsNotEnabled()
  }

  @Test
  fun `loading failure does not present a default choice as if it were saved`() {
    setContent(GroupPrivacySettingsState(isLoading = false, error = GroupPrivacySettingsState.Error.LOAD_FAILED))
    composeTestRule.onNodeWithTag(GroupPrivacySettingsTestTags.ERROR).assertIsDisplayed()
    composeTestRule.onNodeWithTag(GroupPrivacySettingsTestTags.mode(GroupPrivacyMode.SIGNAL_DEFAULT)).assertDoesNotExist()
    composeTestRule.onNodeWithText("Try again").performClick()
    assertThat(events).containsExactly(GroupPrivacySettingsEvent.RetryClicked)
  }

  @Test
  fun `chosen people have named removal actions and unavailable entries remain removable`() {
    setContent(
      GroupPrivacySettingsState(
        mode = GroupPrivacyMode.SELECTED_PEOPLE,
        allowedPeople = listOf(GroupAllowedPerson("alice-aci", "Alice", "+12025550123", false)),
        isLoading = false
      )
    )
    composeTestRule.onNodeWithTag(GroupPrivacySettingsTestTags.SCROLLER).performScrollToNode(hasTestTag(GroupPrivacySettingsTestTags.removePerson("alice-aci")))
    composeTestRule.onNodeWithText("Not currently eligible.", substring = true).assertIsDisplayed()
    composeTestRule.onNodeWithContentDescription("Remove Alice from chosen people").performClick()
    assertThat(events).containsExactly(GroupPrivacySettingsEvent.RemovePersonClicked("alice-aci"))
  }

  @Test
  fun `missing contact details do not expose an internal account identifier`() {
    val aci = "56370921-d8a0-401b-b70c-019c25a7a3c8"
    setContent(
      GroupPrivacySettingsState(
        mode = GroupPrivacyMode.SELECTED_PEOPLE,
        allowedPeople = listOf(GroupAllowedPerson(aci, "Alice", null, true)),
        isLoading = false
      )
    )
    composeTestRule.onNodeWithTag(GroupPrivacySettingsTestTags.SCROLLER).performScrollToNode(hasTestTag(GroupPrivacySettingsTestTags.removePerson(aci)))
    composeTestRule.onNodeWithText("Contact details unavailable").assertIsDisplayed()
    composeTestRule.onNodeWithText(aci).assertDoesNotExist()
  }

  @Test
  fun `Signal default does not claim that every unknown inviter requires review`() {
    setContent(GroupPrivacySettingsState(isLoading = false))
    scrollToText("Groups held for review stay held")
    composeTestRule.onNodeWithText("If Collie cannot identify who added you", substring = true).assertDoesNotExist()
  }

  @Test
  fun `review explanation distinguishes held groups from real invitations and linked devices`() {
    setContent(GroupPrivacySettingsState(mode = GroupPrivacyMode.ASK_EVERY_TIME, isLoading = false))
    composeTestRule.onNodeWithText("These controls apply only to this Collie installation.", substring = true).assertIsDisplayed()
    scrollToText("If Collie cannot identify who added you")
    composeTestRule.onNodeWithText("If Collie cannot identify who added you", substring = true).assertIsDisplayed()
    scrollToText("Groups held for review stay held")
    composeTestRule.onNodeWithText("Invitations that already require Accept still need your approval", substring = true).assertIsDisplayed()
  }

  private fun setContent(state: GroupPrivacySettingsState) {
    composeTestRule.setContent { GroupPrivacySettingsScreen(state = state, onEvent = { events += it }) }
  }

  private fun scrollTo(mode: GroupPrivacyMode) {
    composeTestRule.onNodeWithTag(GroupPrivacySettingsTestTags.SCROLLER).performScrollToNode(hasTestTag(GroupPrivacySettingsTestTags.mode(mode)))
  }

  private fun scrollToText(text: String) {
    composeTestRule.onNodeWithTag(GroupPrivacySettingsTestTags.SCROLLER).performScrollToNode(hasText(text, substring = true))
  }
}
