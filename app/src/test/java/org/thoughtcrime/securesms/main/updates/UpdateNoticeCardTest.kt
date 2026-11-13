/*
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.main.updates

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.ui.CoreUiDependenciesRule
import org.signal.core.ui.compose.theme.SignalTheme

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w600dp-h900dp-mdpi")
class UpdateNoticeCardTest {

  @get:Rule
  val compose = createComposeRule()

  @get:Rule
  val coreUiDependencies = CoreUiDependenciesRule(ApplicationProvider.getApplicationContext())

  private val highlights = List(100) { index ->
    UpdateNoticeHighlight(
      title = "Release highlight ${index + 1}",
      description = "This improvement makes a frequently used part of Collie easier to use. ".repeat(4)
    )
  }

  @Test
  fun `a large edition stays bounded and its first middle and last highlights remain reachable`() {
    show()

    assertHeightAtMost(440.dp)
    assertExitControlsVisible()
    listOf(UpdateNoticeTags.READ_MORE, UpdateNoticeTags.SUPPORT, UpdateNoticeTags.DISMISS).forEach { tag ->
      compose.onNodeWithTag(tag).assertIsDisplayed()
    }

    listOf(highlights.first(), highlights[49], highlights.last()).forEach { highlight ->
      compose.onNodeWithTag(UpdateNoticeTags.CONTENT).performScrollToNode(hasText(highlight.title))
      compose.onNodeWithText(highlight.title).assertIsDisplayed()
    }

    // Even at the end of a substantial edition, reading does not move the exits or actions away.
    assertHeightAtMost(440.dp)
    assertExitControlsVisible()
    listOf(UpdateNoticeTags.READ_MORE, UpdateNoticeTags.SUPPORT, UpdateNoticeTags.DISMISS).forEach { tag ->
      compose.onNodeWithTag(tag).assertIsDisplayed()
    }
  }

  @Test
  fun `compact large text wraps actions near the start and keeps exits visible`() {
    var dismissals = 0
    show(width = 320.dp, maxHeight = 220.dp, fontScale = 2f, onDismiss = { dismissals++ })

    assertHeightAtMost(220.dp)
    assertExitControlsVisible()
    assertTrue(
      "The compact reading area must fit at least one complete touch target",
      compose.onNodeWithTag(UpdateNoticeTags.CONTENT).getUnclippedBoundsInRoot().height >= 48.dp
    )

    listOf(UpdateNoticeTags.READ_MORE, UpdateNoticeTags.SUPPORT, UpdateNoticeTags.DISMISS).forEach { tag ->
      visibleAction(tag)
      val scrollRange = compose.onNodeWithTag(UpdateNoticeTags.CONTENT)
        .fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
      assertTrue("$tag must not be buried after the release notes", scrollRange.value() < scrollRange.maxValue() / 10f)
      assertExitControlsVisible()
    }

    val actions = compose.onNodeWithTag(UpdateNoticeTags.ACTIONS).getUnclippedBoundsInRoot()
    val buttonBounds = listOf(UpdateNoticeTags.READ_MORE, UpdateNoticeTags.SUPPORT, UpdateNoticeTags.DISMISS).map { tag ->
      compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()
    }
    assertTrue("Large labels must wrap onto multiple rows", actions.height > 96.dp)
    buttonBounds.forEach { bounds ->
      assertTrue("An action must fit within the card width", bounds.left >= actions.left && bounds.right <= actions.right)
      assertTrue("Actions must retain a 48 dp touch target", bounds.height >= 48.dp)
    }
    buttonBounds.zipWithNext().forEach { (first, second) ->
      assertTrue(
        "Wrapped actions must not overlap",
        first.right <= second.left || second.right <= first.left || first.bottom <= second.top || second.bottom <= first.top
      )
    }

    compose.onNodeWithTag(UpdateNoticeTags.CONTENT).performScrollToNode(hasText(highlights.last().title))
    compose.onNodeWithText(highlights.last().title).assertIsDisplayed()
    assertExitControlsVisible()
    compose.onNodeWithTag(UpdateNoticeTags.CLOSE).performClick()
    compose.runOnIdle { assertEquals(1, dismissals) }
  }

  @Test
  fun `a short landscape card can display a complete action touch target`() {
    // This is the card budget left by the real Chats toolbar, navigation, and compose controls.
    show(width = 600.dp, maxHeight = 108.dp, fontScale = 1.5f)
    val density = ApplicationProvider.getApplicationContext<Application>().resources.displayMetrics.density

    assertHeightAtMost(108.dp)
    assertExitControlsVisible()
    listOf(UpdateNoticeTags.READ_MORE, UpdateNoticeTags.SUPPORT, UpdateNoticeTags.DISMISS).forEach { tag ->
      val bounds = visibleAction(tag).fetchSemanticsNode().boundsInRoot
      assertTrue("$tag must be fully visible, not a clipped sliver", bounds.height + 1f >= 48f * density)
      assertExitControlsVisible()
    }

    compose.onNodeWithTag(UpdateNoticeTags.CONTENT).performScrollToNode(hasText(highlights.last().title))
    compose.onNodeWithText(highlights.last().title).assertIsDisplayed()
    assertExitControlsVisible()
  }

  @Test
  fun `minimizing and reopening preserves the reading position`() {
    show()
    val title = highlights[49].title
    compose.onNodeWithTag(UpdateNoticeTags.CONTENT).performScrollToNode(hasText(title))
    val previousTop = compose.onNodeWithText(title).assertIsDisplayed().getUnclippedBoundsInRoot().top

    compose.onNodeWithTag(UpdateNoticeTags.MINIMIZE).performClick()
    compose.onNodeWithTag(UpdateNoticeTags.COLLAPSED).assertIsDisplayed().performClick()

    val reopenedTop = compose.onNodeWithText(title).assertIsDisplayed().getUnclippedBoundsInRoot().top
    assertEquals("Reopening must not send a long edition back to the top", previousTop.value, reopenedTop.value, 1f)
  }

  @Test
  fun `reopening guidance is available independently of the release highlights`() {
    show(reopenHint = REOPEN_HINT)

    compose.onNodeWithTag(UpdateNoticeTags.REOPEN_HINT).assertIsDisplayed().assertTextEquals(REOPEN_HINT)
    compose.onNodeWithTag(UpdateNoticeTags.MINIMIZE).performClick()
    compose.onNodeWithTag(UpdateNoticeTags.COLLAPSED).performClick()
    compose.onNodeWithTag(UpdateNoticeTags.REOPEN_HINT).assertIsDisplayed().assertTextEquals(REOPEN_HINT)
  }

  private fun show(
    width: Dp = 360.dp,
    maxHeight: Dp = 440.dp,
    fontScale: Float = 1f,
    reopenHint: String? = null,
    onDismiss: () -> Unit = {}
  ) {
    val expanded = mutableStateOf(true)
    compose.setContent {
      val density = LocalDensity.current
      CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
        SignalTheme {
          Box(modifier = Modifier.width(width)) {
            UpdateNoticeCard(
              title = "What’s new in Collie",
              subtitle = "Installed version 1.0",
              highlights = highlights,
              expanded = expanded.value,
              onExpand = { expanded.value = true },
              onMinimize = { expanded.value = false },
              onDismiss = onDismiss,
              onReadMore = {},
              onSupport = {},
              maxExpandedHeight = maxHeight,
              reopenHint = reopenHint
            )
          }
        }
      }
    }
  }

  private fun visibleAction(tag: String): SemanticsNodeInteraction {
    val action = compose.onNodeWithTag(tag)
    if (!action.isDisplayed()) {
      compose.onNodeWithTag(UpdateNoticeTags.CONTENT).performScrollToNode(hasTestTag(tag))
    }
    if (compose.onNode(hasTestTag(tag) and hasAnyAncestor(hasTestTag(UpdateNoticeTags.CONTENT))).isDisplayed()) {
      // Finding a lazy item is not enough when a wrapped action is only partly inside the viewport.
      action.performScrollTo()
    }
    val density = ApplicationProvider.getApplicationContext<Application>().resources.displayMetrics.density
    val fullHeight = action.getUnclippedBoundsInRoot().height.value * density
    assertTrue("$tag must be completely visible", action.fetchSemanticsNode().boundsInRoot.height + 1f >= fullHeight)
    return action.assertIsDisplayed()
  }

  private fun assertExitControlsVisible() {
    compose.onNodeWithTag(UpdateNoticeTags.MINIMIZE).assertIsDisplayed()
    compose.onNodeWithTag(UpdateNoticeTags.CLOSE).assertIsDisplayed()
  }

  private fun assertHeightAtMost(height: Dp) {
    val actualHeight = compose.onNodeWithTag(UpdateNoticeTags.EXPANDED).getUnclippedBoundsInRoot().height
    assertTrue("Release notes exceeded their height budget: $actualHeight > $height", actualHeight <= height + 1.dp)
  }

  companion object {
    private const val REOPEN_HINT = "Find these again in the Chats menu under What’s new."
  }
}
