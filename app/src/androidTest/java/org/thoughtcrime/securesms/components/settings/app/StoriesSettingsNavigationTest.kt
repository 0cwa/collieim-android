package org.thoughtcrime.securesms.components.settings.app

import androidx.navigation.fragment.NavHostFragment
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.signal.appsettings.home.AppSettingsTestTags
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.testing.DisableAnimationsRule
import org.thoughtcrime.securesms.testing.SignalActivityRule

@RunWith(androidx.test.ext.junit.runners.AndroidJUnit4::class)
class StoriesSettingsNavigationTest {

  @get:Rule
  val harness = SignalActivityRule(othersCount = 0)

  @get:Rule
  val animations = DisableAnimationsRule()

  @get:Rule
  val compose = createEmptyComposeRule()

  @Test
  fun storiesFollowsChatsAndOpensFromSettingsHome() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    AppDependencies.networkManager.setNetworkEnabled(false)

    ActivityScenario.launch<AppSettingsActivity>(AppSettingsActivity.home(context)).use { scenario ->
      scrollTo(AppSettingsTestTags.ROW_STORIES)
      val appearance = compose.onNodeWithTag(AppSettingsTestTags.ROW_APPEARANCE).fetchSemanticsNode().boundsInRoot
      val chats = compose.onNodeWithTag(AppSettingsTestTags.ROW_CHATS).fetchSemanticsNode().boundsInRoot
      val stories = compose.onNodeWithTag(AppSettingsTestTags.ROW_STORIES).fetchSemanticsNode().boundsInRoot
      assertTrue("Appearance precedes Chats", appearance.bottom <= chats.top)
      assertEquals("Stories immediately follows Chats", chats.bottom, stories.top, 1f)

      compose.onNodeWithTag(AppSettingsTestTags.ROW_STORIES).performClick()
      compose.waitForIdle()
      scenario.onActivity { activity ->
        val host = activity.supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        assertEquals(R.id.storiesPrivacySettingsFragment, host.navController.currentDestination?.id)
      }
      onView(withText(R.string.preferences__stories)).check(matches(isDisplayed()))

      pressBack()
      scrollTo(AppSettingsTestTags.ROW_STORIES)
      compose.onNodeWithTag(AppSettingsTestTags.ROW_CHATS).performClick()
      compose.waitForIdle()
      scenario.onActivity { activity ->
        val host = activity.supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        assertEquals(R.id.chatsSettingsFragment, host.navController.currentDestination?.id)
      }
    }
  }

  private fun scrollTo(tag: String) {
    compose.onNodeWithTag(AppSettingsTestTags.SCROLLER).performScrollToNode(hasTestTag(tag))
  }
}
