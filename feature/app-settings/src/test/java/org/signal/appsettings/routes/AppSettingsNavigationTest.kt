/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.appsettings.routes

import android.app.Application
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.SavedStateHandle
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import assertk.assertions.isTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.ui.compose.split.ListDetailEvents
import org.signal.core.ui.compose.split.detailEntry
import org.signal.core.ui.compose.theme.SignalTheme

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AppSettingsNavigationTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  private val router = AppSettingsRouter(SavedStateHandle())

  private var closed = 0
  private val launched = mutableListOf<AppSettingsRoute>()

  @Test
  @Config(qualifiers = "w800dp-h700dp")
  fun `a split-pane window opens the default detail`() {
    setContent(defaultDetailRoute = AppSettingsRoute.ChatsRoute.Chats)

    assertThat(router.navigator.detail.value).isEqualTo(AppSettingsRoute.ChatsRoute.Chats)
  }

  @Test
  @Config(qualifiers = "w800dp-h700dp")
  fun `a split-pane window keeps the detail it was opened with`() {
    router.navigator.processEvent(ListDetailEvents.Push(AppSettingsRoute.AppearanceRoute.Appearance))

    setContent(defaultDetailRoute = AppSettingsRoute.ChatsRoute.Chats)

    assertThat(router.navigator.detail.value).isEqualTo(AppSettingsRoute.AppearanceRoute.Appearance)
  }

  @Test
  @Config(qualifiers = "w360dp-h640dp")
  fun `a phone stays on the settings list`() {
    setContent(defaultDetailRoute = AppSettingsRoute.ChatsRoute.Chats)

    assertThat(router.navigator.detail.value).isNull()
  }

  @Test
  @Config(qualifiers = "w800dp-h700dp")
  fun `a split-pane window leaves settings rather than emptying the detail pane`() {
    setContent(defaultDetailRoute = AppSettingsRoute.ChatsRoute.Chats)

    router.onEvent(AppSettingsNavigationEvent.NavigateBack())
    composeTestRule.waitForIdle()

    assertThat(closed).isEqualTo(1)
    assertThat(router.navigator.detail.value).isEqualTo(AppSettingsRoute.ChatsRoute.Chats)
  }

  @Test
  @Config(qualifiers = "w800dp-h700dp")
  fun `a split-pane window pops stacked detail`() {
    setContent(defaultDetailRoute = AppSettingsRoute.ChatsRoute.Chats)
    router.navigator.processEvent(ListDetailEvents.Push(AppSettingsRoute.ChatFoldersRoute.ChatFolders))
    composeTestRule.waitForIdle()

    router.onEvent(AppSettingsNavigationEvent.NavigateBack())
    composeTestRule.waitForIdle()

    assertThat(closed).isEqualTo(0)
    assertThat(router.navigator.detail.value).isEqualTo(AppSettingsRoute.ChatsRoute.Chats)
  }

  @Test
  @Config(qualifiers = "w360dp-h640dp")
  fun `a phone pops its only detail`() {
    router.navigator.processEvent(ListDetailEvents.Push(AppSettingsRoute.ChatsRoute.Chats))
    setContent(defaultDetailRoute = AppSettingsRoute.ChatsRoute.Chats)

    router.onEvent(AppSettingsNavigationEvent.NavigateBack())
    composeTestRule.waitForIdle()

    assertThat(closed).isEqualTo(0)
    assertThat(router.navigator.detail.value).isNull()
  }

  @Test
  @Config(qualifiers = "w800dp-h700dp")
  fun `a route the host opens as an activity stays off the back stack`() {
    setContent(defaultDetailRoute = AppSettingsRoute.ChatsRoute.Chats)

    router.onEvent(AppSettingsNavigationEvent.NavigateToScreen(AppSettingsRoute.Payments, replacesDetail = true))
    composeTestRule.waitForIdle()

    assertThat(launched).containsExactly(AppSettingsRoute.Payments)
    assertThat(router.navigator.detail.value).isEqualTo(AppSettingsRoute.ChatsRoute.Chats)
    assertThat(closed).isEqualTo(0)
  }

  @Test
  @Config(qualifiers = "w800dp-h700dp")
  fun `home selection follows a replaced section`() {
    setContent(defaultDetailRoute = AppSettingsRoute.ChatsRoute.Chats) { selected ->
      Text(if (selected == AppSettingsRoute.ChatsRoute.Chats) "selected chats" else if (selected == AppSettingsRoute.AppearanceRoute.Appearance) "selected appearance" else "no selection")
    }
    composeTestRule.waitUntil(10_000) { router.selectedRoute.value == AppSettingsRoute.ChatsRoute.Chats }
    composeTestRule.onNodeWithText("selected chats").assertIsDisplayed()

    composeTestRule.runOnIdle {
      router.onEvent(AppSettingsNavigationEvent.NavigateToScreen(AppSettingsRoute.AppearanceRoute.Appearance, replacesDetail = true))
    }
    composeTestRule.waitForIdle()
    composeTestRule.waitUntil(10_000) { router.selectedRoute.value == AppSettingsRoute.AppearanceRoute.Appearance }
    composeTestRule.onNodeWithText("selected appearance").assertIsDisplayed()
  }

  @Test
  @Config(qualifiers = "w800dp-h700dp")
  fun `discarding edits carries out the section change they held`() {
    setContent(defaultDetailRoute = AppSettingsRoute.ChatsRoute.Chats)
    router.onEvent(AppSettingsNavigationEvent.BeginEditSession)
    router.onEvent(AppSettingsNavigationEvent.NavigateToScreen(AppSettingsRoute.AppearanceRoute.Appearance, replacesDetail = true))
    composeTestRule.waitForIdle()
    assertThat(router.navigator.detail.value).isEqualTo(AppSettingsRoute.ChatsRoute.Chats)
    composeTestRule.onNodeWithText("Discard").performClick()
    composeTestRule.waitForIdle()
    assertThat(router.navigator.detail.value).isEqualTo(AppSettingsRoute.AppearanceRoute.Appearance)
  }

  @Test
  @Config(qualifiers = "w800dp-h700dp")
  fun `cancelling keeps the edited screen open`() {
    setContent(defaultDetailRoute = AppSettingsRoute.ChatsRoute.Chats)
    router.onEvent(AppSettingsNavigationEvent.BeginEditSession)
    router.onEvent(AppSettingsNavigationEvent.Close)
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithText("Cancel").performClick()
    composeTestRule.waitForIdle()
    assertThat(closed).isEqualTo(0)
    assertThat(router.navigator.detail.value).isEqualTo(AppSettingsRoute.ChatsRoute.Chats)
    assertThat(router.losesEdits(AppSettingsNavigationEvent.Close)).isTrue()
  }

  private fun setContent(defaultDetailRoute: AppSettingsRoute, home: @Composable (AppSettingsRoute?) -> Unit = { Text("Home") }) {
    composeTestRule.setContent {
      SignalTheme(incognitoKeyboardEnabled = false) {
        AppSettingsNavigation(
          router = router,
          onClose = { closed++ },
          home = home,
          defaultDetailRoute = defaultDetailRoute,
          tryLaunchActivity = { route ->
            (route == AppSettingsRoute.Payments).also { if (it) launched += route }
          },
          detailEntries = {
            detailEntry<AppSettingsRoute.ChatsRoute.Chats> { Text("Chats") }
            detailEntry<AppSettingsRoute.AppearanceRoute.Appearance> { Text("Appearance") }
            detailEntry<AppSettingsRoute.ChatFoldersRoute.ChatFolders> { Text("Chat folders") }
          }
        )
      }
    }
    composeTestRule.waitForIdle()
  }
}
