/*
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.main.updates

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.signal.core.ui.compose.theme.SignalTheme
import org.thoughtcrime.securesms.util.CommunicationActions

@RunWith(AndroidJUnit4::class)
class ReleaseNotesLinksTest {

  @get:Rule
  val compose = createComposeRule()

  @Before
  fun setUp() {
    mockkStatic(CommunicationActions::class)
    every { CommunicationActions.openBrowserLink(any(), any()) } returns Unit
  }

  @After
  fun tearDown() {
    unmockkStatic(CommunicationActions::class)
  }

  @Test
  fun supportAndReleaseNotesOpenCollieDestinations() {
    compose.setContent {
      SignalTheme {
        CollieReleaseNotesCard(
          presentation = UpdateNoticeViewModel.Presentation.EXPANDED,
          onExpand = {},
          onMinimize = {},
          onDismiss = {},
          maxExpandedHeight = 440.dp
        )
      }
    }

    visibleAction(UpdateNoticeTags.SUPPORT).performClick()
    visibleAction(UpdateNoticeTags.READ_MORE).performClick()

    // Guard against accidentally using the upstream Signal or Molly donation resources.
    verify(exactly = 1) { CommunicationActions.openBrowserLink(any(), "https://liberapay.com/Collie") }
    verify(exactly = 1) { CommunicationActions.openBrowserLink(any(), "https://github.com/0cwa/collieim-android/releases") }
    verify(exactly = 2) { CommunicationActions.openBrowserLink(any(), any()) }
  }

  private fun visibleAction(tag: String): SemanticsNodeInteraction {
    val action = compose.onNodeWithTag(tag)
    if (!action.isDisplayed()) {
      compose.onNodeWithTag(UpdateNoticeTags.CONTENT).performScrollToNode(hasTestTag(tag))
    }
    if (compose.onNode(hasTestTag(tag) and hasAnyAncestor(hasTestTag(UpdateNoticeTags.CONTENT))).isDisplayed()) {
      action.performScrollTo()
    }
    val density = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
    val fullHeight = action.getUnclippedBoundsInRoot().height.value * density
    assertTrue("$tag must be completely visible", action.fetchSemanticsNode().boundsInRoot.height + 1f >= fullHeight)
    return action.assertIsDisplayed()
  }
}
