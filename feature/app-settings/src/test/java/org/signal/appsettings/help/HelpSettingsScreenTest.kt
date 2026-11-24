/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.appsettings.help

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import assertk.assertThat
import assertk.assertions.containsExactly
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class HelpSettingsScreenTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  private val events = mutableListOf<HelpSettingsEvent>()

  @Test
  fun `the version name is shown`() {
    setContent(HelpSettingsState(versionName = "8.33.0"))

    scrollTo(HelpSettingsTestTags.ROW_VERSION)
    composeTestRule.onNodeWithText("8.33.0").assertIsDisplayed()
  }

  @Test
  fun `Help displays the Collie website and copyright brand`() {
    setContent(HelpSettingsState(versionName = "8.33.0"))

    composeTestRule.onNodeWithText("Collie.im website").assertIsDisplayed()
    composeTestRule.onNodeWithTag(HelpSettingsTestTags.SCROLLER)
      .performScrollToNode(hasText("Copyright Collie Messenger", substring = true))
    composeTestRule.onNodeWithText("Copyright Collie Messenger", substring = true).assertIsDisplayed()
  }

  @Test
  fun `clicking the website emits WebsiteClicked`() = assertClickEmits(HelpSettingsTestTags.ROW_WEBSITE, HelpSettingsEvent.WebsiteClicked)

  @Test
  fun `clicking support center emits SupportCenterClicked`() = assertClickEmits(HelpSettingsTestTags.ROW_SUPPORT_CENTER, HelpSettingsEvent.SupportCenterClicked)

  @Test
  fun `clicking contact us emits ContactUsClicked`() = assertClickEmits(HelpSettingsTestTags.ROW_CONTACT_US, HelpSettingsEvent.ContactUsClicked)

  @Test
  fun `clicking the enabled debug log emits DebugLogClicked`() {
    setContent(HelpSettingsState(versionName = "8.33.0", logEnabled = true))

    scrollTo(HelpSettingsTestTags.ROW_DEBUG_LOG)
    composeTestRule.onNodeWithTag(HelpSettingsTestTags.ROW_DEBUG_LOG).performClick()

    assertThat(events).containsExactly(HelpSettingsEvent.DebugLogClicked)
  }

  @Test
  fun `toggling debug logging emits the requested choice`() = assertClickEmits(HelpSettingsTestTags.ROW_DEBUG_LOGGING, HelpSettingsEvent.DebugLoggingChanged(true))

  @Test
  fun `clicking licenses emits LicensesClicked`() = assertClickEmits(HelpSettingsTestTags.ROW_LICENSES, HelpSettingsEvent.LicensesClicked)

  @Test
  fun `clicking terms and privacy policy emits TermsAndPrivacyPolicyClicked`() = assertClickEmits(HelpSettingsTestTags.ROW_TERMS_AND_PRIVACY_POLICY, HelpSettingsEvent.TermsAndPrivacyPolicyClicked)

  @Test
  fun `long-pressing the version emits VersionLongClicked`() {
    setContent(HelpSettingsState(versionName = "8.33.0"))

    scrollTo(HelpSettingsTestTags.ROW_VERSION)
    composeTestRule.onNodeWithTag(HelpSettingsTestTags.ROW_VERSION).performTouchInput { longClick() }

    assertThat(events).containsExactly(HelpSettingsEvent.VersionLongClicked)
  }

  @Test
  fun `tapping the version opens app updates`() {
    setContent(HelpSettingsState(versionName = "8.33.0"))

    scrollTo(HelpSettingsTestTags.ROW_VERSION)
    composeTestRule.onNodeWithTag(HelpSettingsTestTags.ROW_VERSION).performClick()

    assertThat(events).containsExactly(HelpSettingsEvent.VersionClicked)
  }

  @Test
  fun `app updates are shown in Help only when enabled and use the existing route`() {
    setContent(HelpSettingsState(versionName = "8.33.0", showAppUpdates = true))

    scrollTo(HelpSettingsTestTags.ROW_APP_UPDATES)
    composeTestRule.onNodeWithTag(HelpSettingsTestTags.ROW_APP_UPDATES).performClick()

    assertThat(events).containsExactly(HelpSettingsEvent.VersionClicked)
  }

  @Test
  fun `app updates are hidden when disabled`() {
    setContent(HelpSettingsState(versionName = "8.33.0"))

    composeTestRule.onNodeWithTag(HelpSettingsTestTags.ROW_APP_UPDATES).assertDoesNotExist()
  }

  @Test
  fun `the version copied snackbar is shown and reports when it is dismissed`() {
    setContent(HelpSettingsState(versionName = "8.33.0", snackbar = HelpSettingsState.Snackbar.VersionCopied))

    composeTestRule.onNodeWithText("Copied to clipboard").assertIsDisplayed()

    composeTestRule.mainClock.advanceTimeBy(10_000)
    composeTestRule.waitForIdle()

    assertThat(events).containsExactly(HelpSettingsEvent.SnackbarDismissed)
  }

  private fun assertClickEmits(tag: String, expected: HelpSettingsEvent) {
    setContent(HelpSettingsState(versionName = "8.33.0"))

    scrollTo(tag)
    composeTestRule.onNodeWithTag(tag).performClick()

    assertThat(events).containsExactly(expected)
  }

  @Test
  fun `moving the app updates row preserves the version shortcut`() {
    setContent(HelpSettingsState(versionName = "8.33.0", showAppUpdates = true), showAppUpdatesRow = false)

    composeTestRule.onNodeWithTag(HelpSettingsTestTags.ROW_APP_UPDATES).assertDoesNotExist()
    scrollTo(HelpSettingsTestTags.ROW_VERSION)
    composeTestRule.onNodeWithTag(HelpSettingsTestTags.ROW_VERSION).performClick()
    assertThat(events).containsExactly(HelpSettingsEvent.VersionClicked)
  }

  private fun setContent(state: HelpSettingsState, showAppUpdatesRow: Boolean = true) {
    composeTestRule.setContent {
      HelpSettingsScreen(
        state = state,
        onEvent = { events += it },
        showAppUpdatesRow = showAppUpdatesRow
      )
    }
  }

  private fun scrollTo(testTag: String) {
    composeTestRule.onNodeWithTag(HelpSettingsTestTags.SCROLLER)
      .performScrollToNode(hasTestTag(testTag))
  }
}
