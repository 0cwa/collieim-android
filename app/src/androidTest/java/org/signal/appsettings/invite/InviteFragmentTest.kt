/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.appsettings.invite

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import assertk.assertThat
import assertk.assertions.isTrue
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.thoughtcrime.securesms.components.settings.app.AppSettingsActivity
import org.thoughtcrime.securesms.testing.DisableAnimationsRule
import org.thoughtcrime.securesms.testing.SignalActivityRule
import org.signal.appsettings.R as AppSettingsR
import org.signal.core.ui.R as CoreUiR

@RunWith(AndroidJUnit4::class)
class InviteFragmentTest {

  @get:Rule
  val harness = SignalActivityRule(othersCount = 0)

  @get:Rule
  val composeRule = createEmptyComposeRule()

  @get:Rule
  val animationsRule = DisableAnimationsRule()

  private val context = InstrumentationRegistry.getInstrumentation().targetContext

  /**
   * When launched directly (e.g. "Invite to Signal" from New Message), the invite screen is the only entry
   * on the back stack, so the navigation icon has to finish the activity rather than just pop the stack.
   */
  @Test
  fun givenDirectLaunch_whenNavigationIconClicked_thenActivityFinishes() {
    ActivityScenario.launch<AppSettingsActivity>(AppSettingsActivity.invite(context)).use { scenario ->
      awaitInviteScreen()
      lateinit var activity: AppSettingsActivity
      scenario.onActivity { activity = it }

      clickNavigationIcon()

      assertThat(activity.isFinishing).isTrue()
    }
  }

  @Test
  fun givenAppSettings_thenInviteShortcutIsAbsent() {
    ActivityScenario.launch<AppSettingsActivity>(AppSettingsActivity.home(context)).use {
      val inviteRowText = context.getString(AppSettingsR.string.AppSettingsFragment__invite_your_friends)
      val error = assertThrows(AssertionError::class.java) {
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText(inviteRowText))
      }
      assertTrue(error.message.orEmpty().contains("No node found that matches"))
      composeRule.onNodeWithText(context.getString(AppSettingsR.string.AppSettingsScreen__settings)).assertIsDisplayed()
    }
  }

  private fun awaitInviteScreen() {
    val title = hasText(context.getString(AppSettingsR.string.InviteScreen__invite_friends))
    composeRule.waitUntil { composeRule.onAllNodes(title).fetchSemanticsNodes().isNotEmpty() }
  }

  private fun clickNavigationIcon() {
    composeRule.onNodeWithContentDescription(context.getString(CoreUiR.string.CloseButton__content_description)).performClick()
    composeRule.waitForIdle()
  }
}
