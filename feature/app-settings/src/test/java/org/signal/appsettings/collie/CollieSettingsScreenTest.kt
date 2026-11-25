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
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.core.app.ApplicationProvider
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isLessThanOrEqualTo
import assertk.assertions.isTrue
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
    composeTestRule.onNodeWithText("Labs").assertIsDisplayed()
  }

  @Test
  fun `Hardening is hidden by default`() {
    setContent(LabsSettingsState())

    composeTestRule.onNodeWithText("Hardening").assertDoesNotExist()
    composeTestRule.onNodeWithTag(CollieSettingsTestTags.ROW_PROTECT_BACKUP_KEY).assertDoesNotExist()
    composeTestRule.onNodeWithTag(CollieSettingsTestTags.ROW_RECOVERY_PIN_GUARD).assertDoesNotExist()
  }

  @Test
  fun `Hardening shows both controls and lets the caller authorize updates`() {
    val pinChanges = mutableListOf<Boolean>()
    val backupChanges = mutableListOf<Boolean>()
    composeTestRule.setContent {
      CollieSettingsScreen(
        state = LabsSettingsState(),
        onEvent = {},
        showHardening = true,
        onRecoveryPinGuardChanged = { pinChanges += it },
        protectBackupKey = true,
        onProtectBackupKeyChanged = { backupChanges += it }
      )
    }

    composeTestRule.onNodeWithText("Hardening").assertIsDisplayed()
    val pin = CollieSettingsTestTags.ROW_RECOVERY_PIN_GUARD
    scrollTo(pin)
    switchIn(pin).assertIsOff()
    composeTestRule.onNodeWithTag(pin).performClick()
    switchIn(pin).assertIsOff()
    assertThat(pinChanges).containsExactly(true)

    val backup = CollieSettingsTestTags.ROW_PROTECT_BACKUP_KEY
    scrollTo(backup)
    switchIn(backup).assertIsOn()
    composeTestRule.onNodeWithTag(backup).performClick()
    switchIn(backup).assertIsOn()
    assertThat(backupChanges).containsExactly(false)
  }

  @Test
  fun `clicking back emits NavigateBackClicked`() {
    setContent(LabsSettingsState())
    val description = ApplicationProvider.getApplicationContext<Application>().getString(R.string.AppSettingsScreen__go_back)

    composeTestRule.onNodeWithContentDescription(description).performClick()

    assertThat(events).containsExactly(LabsSettingsEvent.NavigateBackClicked)
  }

  @Test
  fun `App updates is hidden when disabled`() {
    setContent(LabsSettingsState())
    composeTestRule.onNodeWithTag(CollieSettingsTestTags.ROW_APP_UPDATES).assertDoesNotExist()
  }

  @Test
  fun `App updates invokes its destination callback when enabled`() {
    var clicked = false
    setContent(LabsSettingsState(), showAppUpdates = true) { clicked = true }
    val tag = CollieSettingsTestTags.ROW_APP_UPDATES
    composeTestRule.onNodeWithTag(CollieSettingsTestTags.SCROLLER)
      .performScrollToNode(hasTestTag(tag))
    composeTestRule.onNodeWithTag(tag).assertIsDisplayed().performClick()

    assertThat(clicked).isTrue()
  }

  @Test
  fun `group privacy is hidden when feature entry point is disabled`() {
    composeTestRule.setContent {
      CollieSettingsScreen(state = LabsSettingsState(), onEvent = {}, showGroupPrivacy = false)
    }

    composeTestRule.onNodeWithTag(CollieSettingsTestTags.ROW_GROUP_PRIVACY).assertDoesNotExist()
  }

  @Test
  fun `group privacy appears after app updates and before Hardening and Labs when enabled`() {
    var opened = false
    composeTestRule.setContent {
      CollieSettingsScreen(
        state = LabsSettingsState(),
        onEvent = {},
        showAppUpdates = true,
        showGroupPrivacy = true,
        showHardening = true,
        onGroupPrivacyClicked = { opened = true }
      )
    }

    scrollTo(CollieSettingsTestTags.ROW_APP_UPDATES)
    composeTestRule.onNodeWithTag(CollieSettingsTestTags.ROW_APP_UPDATES).assertIsDisplayed()
    val updatesScroll = scrollPosition()

    scrollTo(CollieSettingsTestTags.ROW_GROUP_PRIVACY)
    composeTestRule.onNodeWithTag(CollieSettingsTestTags.ROW_GROUP_PRIVACY).assertIsDisplayed()
    val privacyScroll = scrollPosition()

    scrollTo(CollieSettingsTestTags.SECTION_HARDENING)
    composeTestRule.onNodeWithText("Hardening").assertIsDisplayed()
    val hardeningScroll = scrollPosition()

    scrollTo(CollieSettingsTestTags.SECTION_LABS)
    composeTestRule.onNodeWithText("Labs").assertIsDisplayed()
    val labsScroll = scrollPosition()

    assertThat(updatesScroll).isLessThanOrEqualTo(privacyScroll)
    assertThat(privacyScroll).isLessThanOrEqualTo(hardeningScroll)
    assertThat(hardeningScroll).isLessThanOrEqualTo(labsScroll)

    scrollTo(CollieSettingsTestTags.ROW_GROUP_PRIVACY)
    composeTestRule.onNodeWithTag(CollieSettingsTestTags.ROW_GROUP_PRIVACY).performClick()
    assertThat(opened).isTrue()
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

  private fun setContent(state: LabsSettingsState, showAppUpdates: Boolean = false, onAppUpdatesClick: () -> Unit = {}) {
    composeTestRule.setContent {
      CollieSettingsScreen(
        state = state,
        onEvent = { events += it },
        showAppUpdates = showAppUpdates,
        onAppUpdatesClick = onAppUpdatesClick
      )
    }
  }

  private fun scrollTo(testTag: String) {
    composeTestRule.onNodeWithTag(CollieSettingsTestTags.SCROLLER)
      .performScrollToNode(hasTestTag(testTag))
  }

  private fun scrollPosition(): Float = composeTestRule.onNodeWithTag(CollieSettingsTestTags.SCROLLER)
    .fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
}
