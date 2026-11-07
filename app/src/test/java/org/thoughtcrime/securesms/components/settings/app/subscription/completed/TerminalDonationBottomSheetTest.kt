/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.components.settings.app.subscription.completed

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.models.badges.Badge
import org.signal.core.ui.CoreUiDependenciesRule
import org.signal.core.ui.compose.theme.SignalTheme
import org.thoughtcrime.securesms.R

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class TerminalDonationBottomSheetTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  @get:Rule
  val coreUiDependenciesRule = CoreUiDependenciesRule(ApplicationProvider.getApplicationContext())

  @Test
  fun `loaded badge without profile toggle renders completion instead of loading`() {
    setContent(badge = testBadge())

    composeTestRule.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).assertDoesNotExist()
    composeTestRule.onNodeWithText(
      ApplicationProvider.getApplicationContext<Application>().getString(R.string.DonationCompletedBottomSheet__donation_complete)
    ).assertIsDisplayed()
    composeTestRule.onNodeWithText(
      ApplicationProvider.getApplicationContext<Application>().getString(R.string.SubscribeThanksForYourSupportBottomSheetDialogFragment__display_on_profile)
    ).assertDoesNotExist()
  }

  @Test
  fun `missing badge keeps the loading indicator`() {
    setContent(badge = null)

    composeTestRule.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).assertIsDisplayed()
  }

  private fun setContent(badge: Badge?) {
    composeTestRule.setContent {
      SignalTheme {
        CompositionLocalProvider(LocalInspectionMode provides true) {
          DonationCompletedSheetContent(
            badge = badge,
            isToggleChecked = false,
            toggleType = TerminalDonationViewModel.ToggleType.NONE,
            onCheckChanged = {},
            onDoneClick = {}
          )
        }
      }
    }
  }

  private fun testBadge(): Badge {
    return Badge(
      id = Badge.BOOST_BADGE_ID,
      category = Badge.Category.Donor,
      name = "Boost",
      description = "Support Signal",
      imageUrl = Uri.EMPTY,
      imageDensity = "1",
      expirationTimestamp = 0,
      visible = false,
      duration = 30
    )
  }
}
