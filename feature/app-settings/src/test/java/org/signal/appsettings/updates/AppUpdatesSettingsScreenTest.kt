/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.appsettings.updates

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AppUpdatesSettingsScreenTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  private val events = mutableListOf<AppUpdatesSettingsEvent>()

  @Test
  fun `periodic update row is shown for a managed build`() {
    setContent(createState(isAutoUpdateSupported = true))

    composeTestRule.onNodeWithTag(AppUpdatesSettingsTestTags.ROW_AUTO_UPDATE).assertIsDisplayed()
  }

  @Test
  fun `update actions are hidden for an externally managed build`() {
    setContent(createState(isAutoUpdateSupported = false))

    composeTestRule.onNodeWithTag(AppUpdatesSettingsTestTags.ROW_AUTO_UPDATE).assertDoesNotExist()
    composeTestRule.onNodeWithTag(AppUpdatesSettingsTestTags.ROW_CHECK_FOR_UPDATES).assertDoesNotExist()
  }

  @Test
  fun `clicking auto update when off emits AutoUpdateToggled true`() {
    setContent(createState(autoUpdateEnabled = false))

    composeTestRule.onNodeWithTag(AppUpdatesSettingsTestTags.ROW_AUTO_UPDATE).performClick()

    assertThat(events).contains(AppUpdatesSettingsEvent.AutoUpdateToggled(true))
  }

  @Test
  fun `clicking auto update when on emits AutoUpdateToggled false`() {
    setContent(createState(autoUpdateEnabled = true))

    composeTestRule.onNodeWithTag(AppUpdatesSettingsTestTags.ROW_AUTO_UPDATE).performClick()

    assertThat(events).contains(AppUpdatesSettingsEvent.AutoUpdateToggled(false))
  }

  @Test
  fun `clicking check for updates emits CheckForUpdatesClicked`() {
    setContent(createState(autoUpdateEnabled = true))

    composeTestRule.onNodeWithTag(AppUpdatesSettingsTestTags.ROW_CHECK_FOR_UPDATES).performClick()

    assertThat(events).contains(AppUpdatesSettingsEvent.CheckForUpdatesClicked)
  }

  @Test
  fun `check for updates says never when no check has succeeded`() {
    setContent(createState(lastCheckedTime = Duration.ZERO))

    composeTestRule.onNodeWithText("Last checked: Never").assertIsDisplayed()
  }

  @Test
  fun `check for updates shows when the last check succeeded`() {
    setContent(createState(lastCheckedTime = 1_700_000_000_000L.milliseconds))

    composeTestRule.onNodeWithText("Last checked: Never").assertDoesNotExist()
    composeTestRule.onNodeWithText("2023", substring = true).assertIsDisplayed()
  }

  @Test
  fun `manual check is disabled when periodic update checks are off`() {
    setContent(createState(autoUpdateEnabled = false))

    composeTestRule.onNodeWithTag(AppUpdatesSettingsTestTags.ROW_CHECK_FOR_UPDATES).assertIsNotEnabled()
  }

  @Test
  fun `clicking beta updates emits the selected channel`() {
    setContent(createState())

    composeTestRule.onNodeWithTag(AppUpdatesSettingsTestTags.ROW_INCLUDE_BETA).performClick()

    assertThat(events).contains(AppUpdatesSettingsEvent.IncludeBetaToggled(true))
  }

  @Test
  fun `resuming the screen emits ScreenResumed and nothing else`() {
    setContent(createState())

    assertThat(events).containsExactly(AppUpdatesSettingsEvent.ScreenResumed)
  }

  private fun setContent(state: AppUpdatesSettingsState) {
    composeTestRule.setContent {
      AppUpdatesSettingsScreen(
        state = state,
        onEvent = { events += it }
      )
    }
  }

  private fun createState(
    lastCheckedTime: Duration = 1_700_000_000_000L.milliseconds,
    autoUpdateEnabled: Boolean = false,
    isAutoUpdateSupported: Boolean = true
  ): AppUpdatesSettingsState {
    return AppUpdatesSettingsState(
      lastCheckedTime = lastCheckedTime,
      autoUpdateEnabled = autoUpdateEnabled,
      isAutoUpdateSupported = isAutoUpdateSupported
    )
  }
}
