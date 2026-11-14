/*
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.main.setup

import android.content.Context
import android.graphics.Bitmap
import android.view.WindowManager
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.filterToOne
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.spyk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.signal.appsettings.R as AppSettingsR
import org.signal.appsettings.home.AppSettingsTestTags
import org.thoughtcrime.securesms.MainActivity
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.ScreenLockController
import org.thoughtcrime.securesms.components.settings.app.AppSettingsActivity
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.megaphone.Megaphone
import org.thoughtcrime.securesms.megaphone.MegaphoneRepository
import org.thoughtcrime.securesms.testing.DisableAnimationsRule
import org.thoughtcrime.securesms.testing.SignalActivityRule
import org.thoughtcrime.securesms.util.DynamicTheme
import org.thoughtcrime.securesms.util.TextSecurePreferences
import java.io.File

/**
 * Writes API 35 screenshots of What's new with its two tabs and of Settings → Help → What's new to external files
 * under project-log-proof. Install the app and test APKs from the same build before running it.
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 35, maxSdkVersion = 35)
class ProjectLogScreenshotTest {

  @get:Rule(order = 0)
  val animations = DisableAnimationsRule()

  @get:Rule(order = 1)
  val harness = SignalActivityRule(othersCount = 1, networkEnabled = false)

  @get:Rule(order = 2)
  val compose = createEmptyComposeRule()

  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val context: Context get() = harness.context

  @Test
  fun whatsNewShowsThisUpdateAndTheProjectLog() = withFixture("light") {
    SignalStore.collieSetup.notesSeenId = null
    SignalStore.collieSetup.notesPendingId = null
    harness.launchActivity<MainActivity>().use { scenario ->
      awaitText(context.getString(R.string.CollieStatus__updated_long))
      compose.onNode(hasText(context.getString(R.string.CollieStatus__updated_long)) and isClickable()).performClick()
      awaitText(context.getString(R.string.CollieWhatsNew__heading))
      saveScreenshot(scenario, "01-light-this-update.png")

      compose.onNode(hasText(context.getString(R.string.CollieWhatsNew__tab_project_log)) and isClickable()).performClick()
      awaitText(context.getString(R.string.CollieLog__this_build))
      saveScreenshot(scenario, "02-light-project-log.png")
    }
  }

  @Test
  fun helpOpensTheProjectLogAndOlderReleasesExpand() = withFixture("dark") {
    SignalStore.collieSetup.notesSeenId = CollieReleaseNotes.ID
    // AppSettingsActivity.help() starts on Contact us, so go through the Settings list the way people do.
    ActivityScenario.launch<AppSettingsActivity>(AppSettingsActivity.home(context)).use { scenario ->
      val help = context.getString(AppSettingsR.string.preferences__help)
      compose.waitUntil(timeoutMillis = 15_000) { compose.onAllNodes(hasTestTag(AppSettingsTestTags.SCROLLER)).fetchSemanticsNodes().isNotEmpty() }
      compose.onNode(hasTestTag(AppSettingsTestTags.SCROLLER)).performScrollToNode(hasText(help))
      compose.onNode(hasText(help) and isClickable()).performClick()
      awaitText(context.getString(AppSettingsR.string.HelpSettingsFragment__whats_new))
      saveScreenshot(scenario, "03-dark-help.png")

      compose.onNode(hasText(context.getString(AppSettingsR.string.HelpSettingsFragment__whats_new)) and isClickable()).performClick()
      awaitText(context.getString(R.string.CollieLog__this_build))

      // Collapse this build's changes and open the oldest release, so both header states show.
      compose.onNode(hasText(context.getString(R.string.CollieLog__this_build)) and isClickable()).performClick()
      val log = compose.onAllNodes(hasScrollAction()).filterToOne(hasAnyDescendant(hasText(context.getString(R.string.CollieLog__intro))))
      log.performScrollToNode(hasText("8.33.2-1 RC1"))
      compose.onNode(hasText("8.33.2-1 RC1") and isClickable()).performClick()
      log.performScrollToNode(hasText("The first signed Collie test build", substring = true))
      saveScreenshot(scenario, "04-dark-project-log-from-help.png")
    }
  }

  private fun isClickable(): SemanticsMatcher = SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick)

  private fun awaitText(text: String, substring: Boolean = false) {
    compose.waitUntil(timeoutMillis = 15_000) {
      compose.onAllNodes(hasText(text, substring = substring), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    }
  }

  private fun <T : android.app.Activity> saveScreenshot(scenario: ActivityScenario<T>, name: String) {
    compose.waitForIdle()
    Thread.sleep(500)
    scenario.onActivity { activity ->
      assertEquals("Screen security must be off only for this fixture", 0, activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
    }

    val screenshot = instrumentation.uiAutomation.takeScreenshot() ?: error("Screenshot was unavailable")
    try {
      val directory = File(context.getExternalFilesDir(null), "project-log-proof")
      assertTrue("Could not create the screenshot directory", directory.exists() || directory.mkdirs())
      File(directory, name).outputStream().use { output ->
        assertTrue("Could not save $name", screenshot.compress(Bitmap.CompressFormat.PNG, 100, output))
      }
    } finally {
      screenshot.recycle()
    }
  }

  private fun withFixture(theme: String, block: () -> Unit) {
    val oldTheme = TextSecurePreferences.getTheme(context)
    val oldDynamicColors = TextSecurePreferences.isDynamicColorsEnabled(context)
    val oldBiometricLock = TextSecurePreferences.isBiometricScreenLockEnabled(context)
    val oldScreenSecurity = SignalStore.settings.isScreenSecurityEnabled
    val oldAutoLock = ScreenLockController.autoLock
    val oldLockAtStart = ScreenLockController.lockScreenAtStart
    val oldNightMode = AppCompatDelegate.getDefaultNightMode()
    val oldSeen = SignalStore.collieSetup.notesSeenId
    val oldPending = SignalStore.collieSetup.notesPendingId
    val oldPendingSince = SignalStore.collieSetup.notesPendingSince

    try {
      commitThemePreferences(theme, dynamicColors = false, biometricLock = false)
      DynamicTheme.setDefaultDayNightMode(context)
      SignalStore.settings.isScreenSecurityEnabled = false
      ScreenLockController.enableAutoLock(false)
      ScreenLockController.lockScreenAtStart = false
      withoutMegaphones(block)
    } finally {
      restoreEverySetting(
        { commitThemePreferences(oldTheme, oldDynamicColors, oldBiometricLock) },
        { SignalStore.settings.isScreenSecurityEnabled = oldScreenSecurity },
        { ScreenLockController.enableAutoLock(oldAutoLock) },
        { ScreenLockController.lockScreenAtStart = oldLockAtStart },
        { AppCompatDelegate.setDefaultNightMode(oldNightMode) },
        { SignalStore.collieSetup.notesSeenId = oldSeen },
        { SignalStore.collieSetup.notesPendingId = oldPending },
        { SignalStore.collieSetup.notesPendingSince = oldPendingSince }
      )
    }
  }

  /** Keeps unrelated bottom prompts out of the captures without touching their saved schedules. */
  private fun withoutMegaphones(block: () -> Unit) {
    val repository = spyk(AppDependencies.megaphoneRepository)
    every { repository.getNextMegaphone(any()) } answers {
      firstArg<MegaphoneRepository.Callback<Megaphone?>>().onResult(null)
    }
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
    ) { "Could not save the temporary theme preferences" }
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
