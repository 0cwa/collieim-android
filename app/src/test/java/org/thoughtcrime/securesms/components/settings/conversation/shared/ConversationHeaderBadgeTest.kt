/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.components.settings.conversation.shared

import android.app.Application
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.models.badges.Badge
import org.signal.core.models.recipients.RecipientId
import org.signal.core.ui.CoreUiDependenciesRule
import org.signal.core.ui.compose.theme.SignalTheme
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.recipients.Recipient

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ConversationHeaderBadgeTest {
  @get:Rule
  val composeTestRule = createComposeRule()

  @get:Rule
  val coreUiDependenciesRule = CoreUiDependenciesRule(ApplicationProvider.getApplicationContext())

  private val description: String
    get() = ApplicationProvider.getApplicationContext<Application>().getString(R.string.ImageView__badge)

  @Test
  fun `non-self featured badge has Molly avatar position and semantics`() {
    setHeader(isSelf = false, badge = mockk())

    val badge = composeTestRule.onNodeWithContentDescription(description)
    badge.assertIsDisplayed().assertHasNoClickAction()
    val bounds = badge.getUnclippedBoundsInRoot()
    val header = composeTestRule.onNodeWithTag("header").getUnclippedBoundsInRoot()
    assertEquals(36.dp, bounds.right - bounds.left)
    assertEquals(36.dp, bounds.bottom - bounds.top)
    assertEquals(44.dp, bounds.left - header.left)
    // The header adds 12dp above Molly's 52dp avatar-relative badge position.
    assertEquals(64.dp, bounds.top - header.top)
  }

  @Test
  fun `self recipient does not display a featured badge`() {
    setHeader(isSelf = true, badge = mockk())
    composeTestRule.onNodeWithContentDescription(description).assertDoesNotExist()
  }

  @Test
  fun `recipient without a featured badge displays no badge`() {
    setHeader(isSelf = false, badge = null)
    composeTestRule.onNodeWithContentDescription(description).assertDoesNotExist()
  }

  private fun setHeader(isSelf: Boolean, badge: Badge?) {
    val recipient = mockk<Recipient> {
      every { id } returns RecipientId.from(1)
      every { this@mockk.isSelf } returns isSelf
      every { featuredBadge } returns badge
    }
    composeTestRule.setContent {
      // Inspection mode avoids loading avatar and badge images through the app dependency graph.
      CompositionLocalProvider(LocalInspectionMode provides true) {
        SignalTheme {
          ConversationHeader(recipient = recipient, name = "Contact", modifier = Modifier.width(80.dp).testTag("header"))
        }
      }
    }
  }
}
