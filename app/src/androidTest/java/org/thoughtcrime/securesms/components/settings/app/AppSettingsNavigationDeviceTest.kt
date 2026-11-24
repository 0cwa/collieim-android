/*
 * Copyright 2026 Collie
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package org.thoughtcrime.securesms.components.settings.app

import android.content.ContentValues
import android.graphics.Bitmap
import android.view.WindowManager
import android.provider.MediaStore
import android.os.Environment
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.signal.appsettings.account.AccountSettingsTestTags
import org.signal.appsettings.appearance.AppearanceSettingsTestTags
import org.signal.appsettings.collie.CollieSettingsTestTags
import org.signal.appsettings.home.AppSettingsTestTags
import org.signal.appsettings.routes.AppSettingsNavigation
import org.signal.appsettings.routes.AppSettingsNavigationEvent
import org.signal.appsettings.routes.AppSettingsRoute
import org.signal.appsettings.routes.AppSettingsRouter
import org.signal.appsettings.updates.AppUpdatesSettingsTestTags
import org.signal.core.ui.compose.split.detailEntry
import org.signal.core.ui.compose.theme.SignalTheme
import org.signal.core.ui.isSplitPane
import org.signal.remoteconfig.RemoteConfig
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.testing.RawFlag
import org.thoughtcrime.securesms.testing.RemoteConfigForTest
import org.thoughtcrime.securesms.testing.SignalActivityRule
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.Espresso.pressBackUnconditionally
import org.junit.Assert.assertTrue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 35, maxSdkVersion = 35)
@RemoteConfigForTest(rawFlags = [RawFlag(RemoteConfig.APP_SETTINGS_NAVIGATION_KEY, "true")])
class AppSettingsNavigationDeviceTest {
  @get:Rule val harness = SignalActivityRule(othersCount = 0, networkEnabled = false)
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

  @Test
  fun settingsHomeOpensAppearanceAndBackMatchesLayout() {
    assertTrue("The navigation feature must be enabled for this test", RemoteConfig.useAppSettingsNavigation)
    val context = harness.context
    val intent = AppSettingsActivity.home(context)
    assertEquals(AppSettingsNavigationActivity::class.java.name, intent.component?.className)

    ActivityScenario.launch<AppSettingsNavigationActivity>(intent).use { scenario ->
      awaitNode(AppSettingsTestTags.ROW_APPEARANCE)
      compose.onNodeWithTag(AppSettingsTestTags.ROW_APPEARANCE).assertIsDisplayed()

      val splitPane = AtomicBoolean()
      scenario.onActivity { splitPane.set(it.resources.isSplitPane()) }
      withScreenSecurityTemporarilyDisabled(scenario) {
        captureScreenshot(scenario, "settings-home")
        compose.onNodeWithTag(AppSettingsTestTags.SCROLLER).performScrollToNode(hasTestTag(AppSettingsTestTags.ROW_APPEARANCE))
        compose.onNodeWithTag(AppSettingsTestTags.ROW_APPEARANCE).performClick()
        awaitNode(AppearanceSettingsTestTags.ROW_THEME)
        compose.onNodeWithTag(AppearanceSettingsTestTags.ROW_THEME).assertIsDisplayed()
        captureScreenshot(scenario, "appearance-settings")
      }

      if (splitPane.get()) {
        pressBackUnconditionally()
        compose.waitUntil(10_000) { scenario.state == Lifecycle.State.DESTROYED }
      } else {
        pressBack()
        compose.waitUntil(10_000) {
          compose.onAllNodesWithTag(AppearanceSettingsTestTags.ROW_THEME)
            .fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty()
        }
        compose.onNodeWithTag(AppSettingsTestTags.ROW_APPEARANCE).assertIsDisplayed()
      }
    }
  }

  @Test
  fun accountAndSupportRemainAvailableWithoutSignalCheckout() {
    val context = harness.context
    val collieSupport = context.getString(R.string.AppSettingsFragment__donate_to_collie)
    val mollySupport = context.getString(R.string.AppSettingsFragment__donate_to_molly)
    ActivityScenario.launch<AppSettingsNavigationActivity>(AppSettingsActivity.home(context)).use { scenario ->
      awaitNode(AppSettingsTestTags.ROW_ACCOUNT)
      val home = compose.onNodeWithTag(AppSettingsTestTags.SCROLLER)

      withScreenSecurityTemporarilyDisabled(scenario) {
        home.performScrollToNode(hasText(collieSupport))
        compose.onNodeWithText(collieSupport).assertIsDisplayed()
        compose.onNodeWithText(mollySupport).assertExists()
        captureScreenshot(scenario, "settings-support")

        home.performScrollToNode(hasTestTag(AppSettingsTestTags.ROW_ACCOUNT))
        compose.onNodeWithTag(AppSettingsTestTags.ROW_ACCOUNT).performClick()
        awaitNode(AccountSettingsTestTags.ROW_LINKED_DEVICES)
        compose.onNodeWithText("Manage existing Signal donations").assertDoesNotExist()
        captureScreenshot(scenario, "account-settings")
      }
    }
  }

  @Test
  fun collieOpensAppUpdatesAndBackReturnsToCollie() {
    val context = harness.context
    ActivityScenario.launch<AppSettingsNavigationActivity>(AppSettingsActivity.home(context)).use { scenario ->
      awaitNode(AppSettingsTestTags.ROW_ACCOUNT)
      compose.onNodeWithTag(AppSettingsTestTags.SCROLLER)
        .performScrollToNode(hasTestTag(AppSettingsTestTags.ROW_COLLIE))
      compose.onNodeWithTag(AppSettingsTestTags.ROW_COLLIE).performClick()
      awaitNode(CollieSettingsTestTags.ROW_APP_UPDATES)
      compose.onNodeWithTag(CollieSettingsTestTags.ROW_APP_UPDATES).assertIsDisplayed()

      withScreenSecurityTemporarilyDisabled(scenario) {
        captureScreenshot(scenario, "collie-app-updates-entry")
        compose.onNodeWithTag(CollieSettingsTestTags.ROW_APP_UPDATES).performClick()
        awaitNode(AppUpdatesSettingsTestTags.ROW_AUTO_UPDATE)
        compose.onNodeWithTag(AppUpdatesSettingsTestTags.ROW_AUTO_UPDATE).assertIsDisplayed()
        captureScreenshot(scenario, "collie-app-updates-settings")
        pressBack()
        awaitNode(CollieSettingsTestTags.ROW_APP_UPDATES)
        compose.onNodeWithTag(CollieSettingsTestTags.ROW_APP_UPDATES).assertIsDisplayed()
      }
    }
  }

  private fun awaitNode(tag: String) {
    compose.waitUntil(15_000) {
      compose.onAllNodesWithTag(tag).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
    }
  }

  private fun withScreenSecurityTemporarilyDisabled(
    scenario: ActivityScenario<AppSettingsNavigationActivity>,
    block: () -> Unit
  ) {
    val hadSecureFlag = AtomicBoolean()
    scenario.onActivity { activity ->
      hadSecureFlag.set(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
      activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
    try {
      block()
    } finally {
      scenario.onActivity { activity ->
        if (hadSecureFlag.get()) {
          activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
          activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
      }
    }
  }

  private fun awaitScreenshotFrame(scenario: ActivityScenario<AppSettingsNavigationActivity>) {
    compose.waitForIdle()

    // Compose can report idle before the window submits a frame with the new content or security flags.
    val frameCommitted = CountDownLatch(1)
    val callback = Runnable { frameCommitted.countDown() }
    scenario.onActivity { activity ->
      val decor = activity.window.decorView
      assertTrue("Settings must use hardware rendering for frame capture", decor.isHardwareAccelerated)
      decor.viewTreeObserver.registerFrameCommitCallback(callback)
      decor.postInvalidateOnAnimation()
    }
    try {
      assertTrue("Settings did not submit its rendered frame", frameCommitted.await(15, TimeUnit.SECONDS))
    } finally {
      scenario.onActivity { activity ->
        val observer = activity.window.decorView.viewTreeObserver
        if (observer.isAlive) observer.unregisterFrameCommitCallback(callback)
      }
    }
    InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(500, 5_000)
  }

  private fun captureScreenshot(
    scenario: ActivityScenario<AppSettingsNavigationActivity>,
    name: String
  ) {
    awaitScreenshotFrame(scenario)
    val bitmap = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()) {
      "Could not capture Settings screenshot"
    }
    val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/collie-settings-navigation/"
    try {
      scenario.onActivity { activity ->
        val resolver = activity.contentResolver
        resolver.delete(
          MediaStore.Downloads.EXTERNAL_CONTENT_URI,
          "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
          arrayOf("$name.png", relativePath)
        )
        val uri = checkNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
          put(MediaStore.MediaColumns.DISPLAY_NAME, "$name.png")
          put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
          put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
          put(MediaStore.MediaColumns.IS_PENDING, 1)
        }))
        checkNotNull(resolver.openOutputStream(uri)).use {
          assertTrue("Could not encode Settings screenshot", bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
      }
    } finally {
      bitmap.recycle()
    }
  }
}
