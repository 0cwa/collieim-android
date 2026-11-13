/*
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.main.updates

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.view.WindowManager
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.height
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.spyk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.signal.core.util.ServiceUtil
import org.thoughtcrime.securesms.MainActivity
import org.thoughtcrime.securesms.MainNavigator
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.ScreenLockController
import org.thoughtcrime.securesms.database.ThreadTable
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.main.MainListRoute
import org.thoughtcrime.securesms.main.MainNavigationEvents
import org.thoughtcrime.securesms.main.MainNavigationViewModel
import org.thoughtcrime.securesms.main.MainToolbarViewModel
import org.thoughtcrime.securesms.megaphone.Megaphone
import org.thoughtcrime.securesms.megaphone.MegaphoneRepository
import org.thoughtcrime.securesms.net.Networking
import org.thoughtcrime.securesms.testing.DisableAnimationsRule
import org.thoughtcrime.securesms.testing.SignalActivityRule
import org.thoughtcrime.securesms.util.DynamicTheme
import org.thoughtcrime.securesms.util.TextSecurePreferences
import java.io.File

/**
 * Exercises the real Chats host and writes native API 35 screenshots to external files under
 * release-notes-proof. Install app and test APKs from the same build before running this class.
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 35, maxSdkVersion = 35)
class ReleaseNotesUiTest {

  @get:Rule(order = 0)
  val animations = DisableAnimationsRule()

  @get:Rule(order = 1)
  val harness = SignalActivityRule(othersCount = 1)

  @get:Rule(order = 2)
  val compose = createEmptyComposeRule()

  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val context: Context get() = harness.context

  @Test
  fun lightNotesCanBeMinimizedDismissedAndReopenedWithoutResettingTheChoice() = withFixture("light") {
    harness.launchActivity<MainActivity>().use { scenario ->
      awaitTag(UpdateNoticeTags.COLLAPSED)
      compose.onNodeWithTag(UpdateNoticeTags.SUPPORT).assertDoesNotExist()
      saveScreenshot(scenario, "01-light-minimized.png")

      compose.onNodeWithTag(UpdateNoticeTags.COLLAPSED).performClick()
      assertExpandedControls()
      saveScreenshot(scenario, "02-light-expanded.png")

      visibleAction(UpdateNoticeTags.SUPPORT)
      saveScreenshot(scenario, "03-light-support.png")
      compose.onNodeWithTag(UpdateNoticeTags.MINIMIZE).performClick()
      awaitTag(UpdateNoticeTags.COLLAPSED)

      scenario.recreate()
      awaitTag(UpdateNoticeTags.COLLAPSED)
      compose.onNodeWithTag(UpdateNoticeTags.COLLAPSED).performClick()
      scenario.recreate()
      assertExpandedControls()

      // Back is a convenient minimize action, and must not leave the Chats screen.
      scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
      awaitTag(UpdateNoticeTags.COLLAPSED)

      compose.onNodeWithTag(UpdateNoticeTags.COLLAPSED).performClick()
      visibleAction(UpdateNoticeTags.DISMISS).performClick()
      compose.onNodeWithTag(UpdateNoticeTags.NOTICE).assertDoesNotExist()
      assertTrue(SignalStore.updateNotices.isDismissed(CollieReleaseNotes.ID))

      openFromMenu()
      assertExpandedControls()
      assertTrue("Manually reopening notes must not undo Got it", SignalStore.updateNotices.isDismissed(CollieReleaseNotes.ID))
      compose.onNodeWithTag(UpdateNoticeTags.MINIMIZE).performClick()
      awaitTag(UpdateNoticeTags.COLLAPSED)
    }

    // Closing the activity creates a fresh view model, unlike an orientation change.
    harness.launchActivity<MainActivity>().use {
      awaitMainToolbar()
      compose.onNodeWithTag(UpdateNoticeTags.NOTICE).assertDoesNotExist()
      openFromMenu()
      assertExpandedControls()
    }
  }

  @Test
  fun notesStayOutOfSearchSelectionCallsAndAnOpenConversation() = withFixture("light") {
    harness.launchActivity<MainActivity>().use { scenario ->
      awaitTag(UpdateNoticeTags.COLLAPSED)

      compose.onNodeWithContentDescription(context.getString(R.string.conversation_list_search_description)).performClick()
      compose.onNodeWithTag(UpdateNoticeTags.NOTICE).assertDoesNotExist()
      scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
      awaitTag(UpdateNoticeTags.COLLAPSED)

      scenario.onActivity { ViewModelProvider(it)[MainToolbarViewModel::class.java].presentToolbarForMultiselect() }
      compose.onNodeWithTag(UpdateNoticeTags.NOTICE).assertDoesNotExist()
      scenario.onActivity { ViewModelProvider(it)[MainToolbarViewModel::class.java].presentToolbarForCurrentDestination() }
      awaitTag(UpdateNoticeTags.COLLAPSED)

      scenario.navigate(MainNavigationEvents.GoToTab(MainListRoute.Calls))
      compose.onNodeWithTag(UpdateNoticeTags.NOTICE).assertDoesNotExist()
      scenario.navigate(MainNavigationEvents.GoToTab(MainListRoute.Chats))
      awaitTag(UpdateNoticeTags.COLLAPSED)

      scenario.onActivity { activity ->
        MainNavigator.get(activity).goToConversation(harness.others.first(), -1, ThreadTable.DistributionTypes.DEFAULT, -1)
      }
      compose.waitUntil(timeoutMillis = 15_000) {
        var hasDetail = false
        scenario.onActivity { hasDetail = it.navigationViewModel().hasDetailContent.value }
        hasDetail
      }
      compose.onNodeWithTag(UpdateNoticeTags.NOTICE).assertDoesNotExist()
      scenario.navigate(MainNavigationEvents.ExitDetail)
      awaitTag(UpdateNoticeTags.COLLAPSED)
      assertFalse(SignalStore.updateNotices.isDismissed(CollieReleaseNotes.ID))
    }
  }

  @Test
  fun readingNotesTemporarilyHidesTheBatteryPromptWithoutAcknowledgingIt() = withFixture("light") {
    val oldFcmEnabled = SignalStore.account.fcmEnabled
    val oldPromptedDoze = SignalStore.uiHints.hasPromptedOptimizeDoze()
    val powerManager = spyk(ServiceUtil.getPowerManager(context))
    every { powerManager.isIgnoringBatteryOptimizations(context.packageName) } returns false
    val bannerText = context.getString(R.string.DozeReminder_allow_molly_to_deliver_timely_notifications)

    try {
      SignalStore.account.fcmEnabled = false
      SignalStore.uiHints.setHasPromptedOptimizeDoze(false)
      mockkStatic(ServiceUtil::class) {
        every { ServiceUtil.getPowerManager(any()) } returns powerManager
        harness.launchActivity<MainActivity>().use {
          awaitTag(UpdateNoticeTags.COLLAPSED)
          awaitText(bannerText)

          compose.onNodeWithTag(UpdateNoticeTags.COLLAPSED).performClick()
          assertExpandedControls()
          compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodes(hasText(bannerText)).fetchSemanticsNodes().isEmpty()
          }
          compose.onNodeWithText(bannerText).assertDoesNotExist()
          assertFalse("Reading notes must not acknowledge the battery prompt", SignalStore.uiHints.hasPromptedOptimizeDoze())

          compose.onNodeWithTag(UpdateNoticeTags.MINIMIZE).performClick()
          awaitTag(UpdateNoticeTags.COLLAPSED)
          awaitText(bannerText)
          assertFalse("Minimizing must restore the prompt without changing its saved choice", SignalStore.uiHints.hasPromptedOptimizeDoze())
        }
      }
    } finally {
      restoreEverySetting(
        { SignalStore.account.fcmEnabled = oldFcmEnabled },
        { SignalStore.uiHints.setHasPromptedOptimizeDoze(oldPromptedDoze) }
      )
    }
  }

  @Test
  fun darkNotesRenderInTheRealChatsScreen() = withFixture("dark") {
    harness.launchActivity<MainActivity>().use { scenario ->
      awaitTag(UpdateNoticeTags.COLLAPSED)
      compose.onNodeWithTag(UpdateNoticeTags.COLLAPSED).performClick()
      assertExpandedControls()
      saveScreenshot(scenario, "04-dark-expanded.png")
    }
  }

  @Test
  fun largeTextKeepsExitControlsReachableWhileTheBodyScrolls() = withFixture("light", fontScale = 1.5f) {
    harness.launchActivity<MainActivity>().use { scenario ->
      awaitTag(UpdateNoticeTags.COLLAPSED)
      scenario.onActivity { assertEquals(1.5f, it.resources.configuration.fontScale, 0.01f) }
      compose.onNodeWithTag(UpdateNoticeTags.COLLAPSED).performClick()
      assertExpandedControls()
      saveScreenshot(scenario, "05-large-text-expanded.png")

      visibleAction(UpdateNoticeTags.SUPPORT)
      assertExpandedControls()
      saveScreenshot(scenario, "06-large-text-support.png")
      compose.onNodeWithTag(UpdateNoticeTags.MINIMIZE).performClick()
      awaitTag(UpdateNoticeTags.COLLAPSED)
    }
  }

  @Test
  fun landscapeWithLargeTextKeepsTheNotesAndBothExitActionsReachable() = withFixture("light", fontScale = 1.5f) {
    harness.launchActivity<MainActivity>().use { scenario ->
      awaitTag(UpdateNoticeTags.COLLAPSED)
      var oldOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
      scenario.onActivity { activity ->
        oldOrientation = activity.requestedOrientation
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
      }

      try {
        compose.waitUntil(timeoutMillis = 15_000) {
          var isLandscape = false
          scenario.onActivity { isLandscape = it.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
          isLandscape
        }

        compose.onNodeWithTag(UpdateNoticeTags.COLLAPSED).performClick()
        awaitTag(UpdateNoticeTags.EXPANDED)
        compose.onNodeWithTag(UpdateNoticeTags.MINIMIZE).assertIsDisplayed()
        assertChatActionsAccessible(scenario)
        assertReadingAreaFitsTouchTarget(scenario)
        saveScreenshot(scenario, "07-landscape-large-text.png")

        visibleAction(UpdateNoticeTags.SUPPORT)
        // Compact actions are near the beginning of the body; both fixed exits remain reachable.
        visibleAction(UpdateNoticeTags.DISMISS)
        assertExpandedControls()
        assertChatActionsAccessible(scenario)
        saveScreenshot(scenario, "08-landscape-large-text-actions.png")
        compose.onNodeWithTag(UpdateNoticeTags.DISMISS).performClick()
        compose.onNodeWithTag(UpdateNoticeTags.NOTICE).assertDoesNotExist()
      } finally {
        scenario.onActivity { it.requestedOrientation = oldOrientation }
      }
    }
  }

  private fun assertChatActionsAccessible(scenario: ActivityScenario<MainActivity>) {
    var density = 1f
    scenario.onActivity { density = it.resources.displayMetrics.density }
    val minimumTargetSize = 48f * density
    val camera = compose.onNodeWithContentDescription(context.getString(R.string.conversation_list_fragment__open_camera_description))
      .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
    val newChat = compose.onNodeWithContentDescription(context.getString(R.string.conversation_list_fragment__fab_content_description))
      .assertIsDisplayed().fetchSemanticsNode().boundsInRoot

    listOf("Camera" to camera, "New chat" to newChat).forEach { (name, bounds) ->
      assertTrue("$name needs a 48 dp wide touch target, got $bounds", bounds.width + 1f >= minimumTargetSize)
      assertTrue("$name needs a 48 dp high touch target, got $bounds", bounds.height + 1f >= minimumTargetSize)
    }
    assertTrue(
      "Camera and New chat touch targets must not overlap: camera=$camera, newChat=$newChat",
      camera.right <= newChat.left || newChat.right <= camera.left || camera.bottom <= newChat.top || newChat.bottom <= camera.top
    )
  }

  private fun assertReadingAreaFitsTouchTarget(scenario: ActivityScenario<MainActivity>) {
    var density = 1f
    scenario.onActivity { density = it.resources.displayMetrics.density }
    val bounds = compose.onNodeWithTag(UpdateNoticeTags.CONTENT).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
    assertTrue("The reading area must fit a complete 48 dp action, got $bounds", bounds.height + 1f >= 48f * density)
  }

  private fun assertExpandedControls() {
    awaitTag(UpdateNoticeTags.EXPANDED)
    compose.onNodeWithTag(UpdateNoticeTags.MINIMIZE).assertIsDisplayed()
    compose.onNodeWithTag(UpdateNoticeTags.CLOSE).assertIsDisplayed()
  }

  private fun visibleAction(tag: String): SemanticsNodeInteraction {
    val action = compose.onNodeWithTag(tag)
    if (!action.isDisplayed()) {
      compose.onNodeWithTag(UpdateNoticeTags.CONTENT).performScrollToNode(hasTestTag(tag))
    }
    if (compose.onNode(hasTestTag(tag) and hasAnyAncestor(hasTestTag(UpdateNoticeTags.CONTENT))).isDisplayed()) {
      // Bring the button itself fully into view when its lazy item wraps onto several rows.
      action.performScrollTo()
    }
    val density = context.resources.displayMetrics.density
    val fullHeight = action.getUnclippedBoundsInRoot().height.value * density
    assertTrue("$tag must be completely visible", action.fetchSemanticsNode().boundsInRoot.height + 1f >= fullHeight)
    return action.assertIsDisplayed()
  }

  private fun awaitText(text: String) {
    compose.waitUntil(timeoutMillis = 15_000) {
      compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()
    }
    compose.onNodeWithText(text).assertIsDisplayed()
  }

  private fun awaitTag(tag: String) {
    compose.waitUntil(timeoutMillis = 15_000) {
      compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
    }
    compose.onNodeWithTag(tag).assertIsDisplayed()
  }

  private fun awaitMainToolbar() {
    compose.onNodeWithContentDescription(context.getString(R.string.MainToolbar__more_options_content_description)).assertIsDisplayed()
  }

  private fun openFromMenu() {
    compose.onNodeWithContentDescription(context.getString(R.string.MainToolbar__more_options_content_description)).performClick()
    compose.onNodeWithText(context.getString(R.string.CollieUpdates__whats_new)).performClick()
  }

  private fun MainActivity.navigationViewModel(): MainNavigationViewModel {
    return ViewModelProvider(this, MainNavigationViewModel.Factory())[MainNavigationViewModel::class.java]
  }

  private fun ActivityScenario<MainActivity>.navigate(event: MainNavigationEvents) {
    onActivity { it.navigationViewModel().onEvent(event) }
    compose.waitForIdle()
  }

  private fun saveScreenshot(scenario: ActivityScenario<MainActivity>, name: String) {
    compose.waitForIdle()
    scenario.onActivity { activity ->
      assertEquals("Screen security must be disabled only for this fixture", 0, activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
    }

    val screenshot = instrumentation.uiAutomation.takeScreenshot() ?: error("MainActivity screenshot was unavailable")
    try {
      val directory = File(context.getExternalFilesDir(null), "release-notes-proof")
      assertTrue("Could not create screenshot directory", directory.exists() || directory.mkdirs())
      File(directory, name).outputStream().use { output ->
        assertTrue("Could not save $name", screenshot.compress(Bitmap.CompressFormat.PNG, 100, output))
      }
    } finally {
      screenshot.recycle()
    }
  }

  private fun withFixture(theme: String, fontScale: Float = 1f, block: () -> Unit) {
    val oldTheme = TextSecurePreferences.getTheme(context)
    val oldDynamicColors = TextSecurePreferences.isDynamicColorsEnabled(context)
    val oldBiometricLock = TextSecurePreferences.isBiometricScreenLockEnabled(context)
    val oldScreenSecurity = SignalStore.settings.isScreenSecurityEnabled
    val oldAutoLock = ScreenLockController.autoLock
    val oldLockAtStart = ScreenLockController.lockScreenAtStart
    val oldNightMode = AppCompatDelegate.getDefaultNightMode()
    val oldDismissed = SignalStore.updateNotices.isDismissed(CollieReleaseNotes.ID)
    val oldNetworkEnabled = Networking.isEnabled
    val oldFontScale = Settings.System.getString(context.contentResolver, Settings.System.FONT_SCALE)

    try {
      commitThemePreferences(theme, dynamicColors = false, biometricLock = false)
      DynamicTheme.setDefaultDayNightMode(context)
      SignalStore.settings.isScreenSecurityEnabled = false
      ScreenLockController.enableAutoLock(false)
      ScreenLockController.lockScreenAtStart = false
      SignalStore.updateNotices.setDismissed(CollieReleaseNotes.ID, false)
      AppDependencies.networkManager.setNetworkEnabled(false)
      setFontScale(fontScale.toString())
      withoutOtherMegaphones(block)
    } finally {
      restoreEverySetting(
        { commitThemePreferences(oldTheme, oldDynamicColors, oldBiometricLock) },
        { SignalStore.settings.isScreenSecurityEnabled = oldScreenSecurity },
        { ScreenLockController.enableAutoLock(oldAutoLock) },
        { ScreenLockController.lockScreenAtStart = oldLockAtStart },
        { AppCompatDelegate.setDefaultNightMode(oldNightMode) },
        { SignalStore.updateNotices.setDismissed(CollieReleaseNotes.ID, oldDismissed) },
        { AppDependencies.networkManager.setNetworkEnabled(oldNetworkEnabled) },
        { setFontScale(oldFontScale) }
      )
    }
  }

  private fun withoutOtherMegaphones(block: () -> Unit) {
    // SignalActivityRule disables message notifications, which makes its unrelated notification
    // megaphone eligible. Isolate this UI without finishing or snoozing real scheduling records.
    val repository = spyk(AppDependencies.megaphoneRepository)
    every { repository.getNextMegaphone(any()) } answers {
      firstArg<MegaphoneRepository.Callback<Megaphone?>>().onResult(null)
    }

    // The property is @JvmStatic; use the same scoped getter-mocking pattern as other app tests.
    // The block restores the original getter even if an assertion or activity launch fails.
    mockkStatic(AppDependencies::class) {
      every { AppDependencies.megaphoneRepository } returns repository
      block()
    }
  }

  private fun commitThemePreferences(theme: String, dynamicColors: Boolean, biometricLock: Boolean) {
    check(
      TextSecurePreferences.getSharedPreferences(context).edit()
        .putString(TextSecurePreferences.THEME_PREF, theme)
        .putBoolean(TextSecurePreferences.DYNAMIC_COLORS_ENABLED, dynamicColors)
        .putBoolean(TextSecurePreferences.BIOMETRIC_SCREEN_LOCK, biometricLock)
        .commit()
    ) { "Could not persist temporary theme preferences" }
  }

  private fun setFontScale(value: String?) {
    val command = if (value == null) {
      "settings delete system font_scale"
    } else {
      "settings put system font_scale ${value.toFloat()}"
    }
    ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
  }

  private fun restoreEverySetting(vararg steps: () -> Unit) {
    var failure: Throwable? = null
    for (step in steps) {
      try {
        step()
      } catch (error: Throwable) {
        val previous = failure
        if (previous == null) failure = error else previous.addSuppressed(error)
      }
    }
    failure?.let { throw it }
  }
}
