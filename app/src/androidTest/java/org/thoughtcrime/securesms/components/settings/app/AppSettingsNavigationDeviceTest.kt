/*
 * Copyright 2026 Collie
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package org.thoughtcrime.securesms.components.settings.app

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.signal.appsettings.routes.AppSettingsNavigation
import org.signal.appsettings.routes.AppSettingsNavigationEvent
import org.signal.appsettings.routes.AppSettingsRoute
import org.signal.appsettings.routes.AppSettingsRouter
import org.signal.core.ui.compose.split.detailEntry
import org.signal.core.ui.compose.theme.SignalTheme

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 35, maxSdkVersion = 35)
class AppSettingsNavigationDeviceTest {
  @get:Rule val compose = createComposeRule()

  @Test
  fun splitPaneHomeSelectionFollowsSectionChange() {
    val router = AppSettingsRouter(SavedStateHandle())
    compose.setContent {
      SignalTheme {
        AppSettingsNavigation(
          router = router,
          onClose = {},
          home = { selected ->
            Text(if (selected == AppSettingsRoute.ChatsRoute.Chats) "selected chats" else if (selected == AppSettingsRoute.AppearanceRoute.Appearance) "selected appearance" else "no selection")
          },
          defaultDetailRoute = AppSettingsRoute.ChatsRoute.Chats,
          detailEntries = {
            detailEntry<AppSettingsRoute.ChatsRoute.Chats> { Text("Chats detail") }
            detailEntry<AppSettingsRoute.AppearanceRoute.Appearance> { Text("Appearance detail") }
          }
        )
      }
    }
    compose.waitForIdle()
    compose.waitUntil(10_000) { router.selectedRoute.value == AppSettingsRoute.ChatsRoute.Chats }
    compose.onNodeWithText("selected chats").assertIsDisplayed()
    compose.runOnIdle {
      router.onEvent(AppSettingsNavigationEvent.NavigateToScreen(AppSettingsRoute.AppearanceRoute.Appearance, replacesDetail = true))
    }
    compose.waitForIdle()
    compose.waitUntil(10_000) { router.selectedRoute.value == AppSettingsRoute.AppearanceRoute.Appearance }
    compose.onNodeWithText("selected appearance").assertIsDisplayed()
  }
}
