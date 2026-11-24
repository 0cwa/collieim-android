/*
 * Copyright 2026 Collie contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.appsettings.collie

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.appsettings.R
import org.signal.appsettings.labs.LabsSettingsEvent
import org.signal.appsettings.labs.LabsSettingsState
import org.signal.appsettings.labs.LabsSettingsTestTags

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CollieSettingsScreenTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  private val events = mutableListOf<LabsSettingsEvent>()

  @Test
  fun `the Collie screen contains a Labs section`() {
    setContent(LabsSettingsState())

    composeTestRule.onNodeWithText("Collie").assertIsDisplayed()
    composeTestRule.onNodeWithTag(CollieSettingsTestTags.SCROLLER).performScrollToNode(hasText("Labs"))
    composeTestRule.onNodeWithText("Labs").assertIsDisplayed()
  }

  @Test
  fun `Privacy section opens group controls without changing a preference`() {
    var opened = false
    composeTestRule.setContent {
      CollieSettingsScreen(state = LabsSettingsState(), onEvent = { events += it }, onGroupPrivacyClicked = { opened = true })
    }
    composeTestRule.onNodeWithText("Privacy").assertIsDisplayed()
    composeTestRule.onNodeWithTag(CollieSettingsTestTags.ROW_GROUP_PRIVACY).performClick()
    assertThat(opened).isEqualTo(true)
    assertThat(events).isEmpty()
  }

  @Test
  fun `recovery guard defaults off and requests enabling`() {
    val changes = mutableListOf<Boolean>()
    composeTestRule.setContent {
      CollieSettingsScreen(state = LabsSettingsState(), onEvent = {}, onRecoveryPinGuardChanged = { changes += it })
    }
    scrollTo(CollieSettingsTestTags.ROW_RECOVERY_PIN_GUARD)
    switchIn(CollieSettingsTestTags.ROW_RECOVERY_PIN_GUARD).assertIsOff()
    composeTestRule.onNodeWithTag(CollieSettingsTestTags.ROW_RECOVERY_PIN_GUARD).performClick()
    assertThat(changes).containsExactly(true)
    switchIn(CollieSettingsTestTags.ROW_RECOVERY_PIN_GUARD).assertIsOff()
  }

  @Test
  fun `recovery guard requests disabling without changing its own state`() {
    val changes = mutableListOf<Boolean>()
    composeTestRule.setContent {
      CollieSettingsScreen(state = LabsSettingsState(), onEvent = {}, recoveryPinGuardEnabled = true, onRecoveryPinGuardChanged = { changes += it })
    }
    scrollTo(CollieSettingsTestTags.ROW_RECOVERY_PIN_GUARD)
    switchIn(CollieSettingsTestTags.ROW_RECOVERY_PIN_GUARD).assertIsOn()
    composeTestRule.onNodeWithTag(CollieSettingsTestTags.ROW_RECOVERY_PIN_GUARD).performClick()
    assertThat(changes).containsExactly(false)
    switchIn(CollieSettingsTestTags.ROW_RECOVERY_PIN_GUARD).assertIsOn()
  }

  @Test
  fun `clicking back emits NavigateBackClicked`() {
    setContent(LabsSettingsState())
    val description = ApplicationProvider.getApplicationContext<Application>().getString(R.string.AppSettingsScreen__go_back)

    composeTestRule.onNodeWithContentDescription(description).performClick()

    assertThat(events).containsExactly(LabsSettingsEvent.NavigateBackClicked)
  }

  @Test
  fun `the three public features emit their enable events`() {
    setContent(LabsSettingsState())

    val expected = mapOf(
      CollieSettingsTestTags.ROW_STORY_ARCHIVE to LabsSettingsEvent.StoryArchiveToggled(true),
      CollieSettingsTestTags.ROW_BETTER_SEARCH to LabsSettingsEvent.BetterSearchToggled(true),
      CollieSettingsTestTags.ROW_STARRED_MESSAGES to LabsSettingsEvent.StarredMessagesToggled(true)
    )

    expected.forEach { (tag, event) ->
      events.clear()
      scrollTo(tag)
      switchIn(tag).assertIsOff()
      composeTestRule.onNodeWithTag(tag).performClick()

      assertThat(events).containsExactly(event)
    }
  }

  @Test
  fun `enabled public features render as on and can be disabled`() {
    setContent(LabsSettingsState(storyArchive = true, betterSearch = true, starredMessages = true))

    val expected = mapOf(
      CollieSettingsTestTags.ROW_STORY_ARCHIVE to LabsSettingsEvent.StoryArchiveToggled(false),
      CollieSettingsTestTags.ROW_BETTER_SEARCH to LabsSettingsEvent.BetterSearchToggled(false),
      CollieSettingsTestTags.ROW_STARRED_MESSAGES to LabsSettingsEvent.StarredMessagesToggled(false)
    )

    expected.forEach { (tag, event) ->
      events.clear()
      scrollTo(tag)
      switchIn(tag).assertIsOn()
      composeTestRule.onNodeWithTag(tag).performClick()

      assertThat(events).containsExactly(event)
    }
  }

  @Test
  fun `internal experiments are not offered even when their flags are enabled`() {
    setContent(
      LabsSettingsState(
        individualChatPlaintextExport = true,
        incognito = true,
        improvedMessageDeletion = true
      )
    )

    val publicRows = listOf(
      CollieSettingsTestTags.ROW_STORY_ARCHIVE,
      CollieSettingsTestTags.ROW_BETTER_SEARCH,
      CollieSettingsTestTags.ROW_STARRED_MESSAGES
    )
    publicRows.forEach { tag ->
      scrollTo(tag)
      composeTestRule.onNodeWithTag(tag).assertIsDisplayed()
      composeTestRule.onNodeWithTag(LabsSettingsTestTags.ROW_INDIVIDUAL_CHAT_PLAINTEXT_EXPORT).assertDoesNotExist()
      composeTestRule.onNodeWithTag(LabsSettingsTestTags.ROW_INCOGNITO).assertDoesNotExist()
      composeTestRule.onNodeWithTag(LabsSettingsTestTags.ROW_IMPROVED_MESSAGE_DELETION).assertDoesNotExist()
    }
  }

  private fun switchIn(rowTag: String) = composeTestRule.onNode(isToggleable() and hasAnyAncestor(hasTestTag(rowTag)))

  private fun setContent(state: LabsSettingsState) {
    composeTestRule.setContent {
      CollieSettingsScreen(
        state = state,
        onEvent = { events += it }
      )
    }
  }

  private fun scrollTo(testTag: String) {
    composeTestRule.onNodeWithTag(CollieSettingsTestTags.SCROLLER)
      .performScrollToNode(hasTestTag(testTag))
  }
}
