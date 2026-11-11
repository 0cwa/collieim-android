/*
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.main.updates

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.After
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

    compose.onNodeWithTag(UpdateNoticeTags.SUPPORT).performScrollTo().performClick()
    compose.onNodeWithTag(UpdateNoticeTags.READ_MORE).performScrollTo().performClick()

    // Guard against accidentally using the upstream Signal or Molly donation resources.
    verify(exactly = 1) { CommunicationActions.openBrowserLink(any(), "https://liberapay.com/Collie") }
    verify(exactly = 1) { CommunicationActions.openBrowserLink(any(), "https://github.com/0cwa/collieim-android/releases") }
    verify(exactly = 2) { CommunicationActions.openBrowserLink(any(), any()) }
  }
}
