/*
 * Copyright 2026 Collie contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.appsettings.collie.groups

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import assertk.assertThat
import assertk.assertions.containsExactly
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ChooseGroupPeopleScreenTest {
  @get:Rule val composeTestRule = createComposeRule()
  private val events = mutableListOf<ChooseGroupPeopleEvent>()

  @Test
  fun `empty selection cannot be submitted`() {
    setContent(ChooseGroupPeopleState())
    composeTestRule.onNodeWithTag(ChooseGroupPeopleTestTags.ADD).assertIsNotEnabled()
    composeTestRule.onNodeWithText("0 people selected").assertIsDisplayed()
  }

  @Test
  fun `selected people are explicitly added and the selection does not imply a mode change`() {
    setContent(ChooseGroupPeopleState(selectedCount = 2))
    composeTestRule.onNodeWithText("when “Only people I choose” is selected", substring = true).assertIsDisplayed()
    composeTestRule.onNodeWithTag(ChooseGroupPeopleTestTags.ADD).assertIsEnabled().performClick()
    assertThat(events).containsExactly(ChooseGroupPeopleEvent.AddClicked)
  }

  @Test
  fun `save failure is visible without clearing the selection`() {
    setContent(ChooseGroupPeopleState(selectedCount = 1, error = ChooseGroupPeopleState.Error.SAVE_FAILED))
    composeTestRule.onNodeWithText("Your existing list is unchanged.", substring = true).assertIsDisplayed()
    composeTestRule.onNodeWithTag(ChooseGroupPeopleTestTags.ADD).assertIsEnabled()
  }

  private fun setContent(state: ChooseGroupPeopleState) {
    composeTestRule.setContent { ChooseGroupPeopleScreen(state = state, onEvent = { events += it }) }
  }
}
