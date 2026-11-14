/*
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.main.setup

import android.content.Context
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.view.WindowManager
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.performClick
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
import org.thoughtcrime.securesms.MainActivity
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.ScreenLockController
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.keyvalue.SettingsValues.NotificationDeliveryMethod
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.megaphone.Megaphone
import org.thoughtcrime.securesms.megaphone.MegaphoneRepository
import org.thoughtcrime.securesms.testing.DisableAnimationsRule
import org.thoughtcrime.securesms.testing.SignalActivityRule
import org.thoughtcrime.securesms.util.DynamicTheme
import org.thoughtcrime.securesms.util.TextSecurePreferences
import java.io.File

/**
 * Opens the real Chats screen with a synthetic local account and writes API 35 screenshots of the status line, the
 * problem banner, the setup sheet, the empty-list checklist and What's new to external files under
 * finish-setup-proof. Install the app and test APKs from the same build before running it.
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 35, maxSdkVersion = 35)
class FinishSetupScreenshotTest {

  @get:Rule(order = 0)
  val animations = DisableAnimationsRule()

  @get:Rule(order = 1)
  val harness = SignalActivityRule(othersCount = 1, networkEnabled = false)

  @get:Rule(order = 2)
  val compose = createEmptyComposeRule()

  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val context: Context get() = harness.context

  @Test
  fun setupShowsInTheStatusLineTheEmptyListAndTheSheet() = withFixture("light") {
    SignalStore.collieSetup.notesSeenId = CollieReleaseNotes.ID
    harness.launchActivity<MainActivity>().use { scenario ->
      awaitText(context.getString(R.string.CollieSetup__empty_title))
      awaitText(context.getString(R.string.CollieStatus__setup_short), substring = true)
      saveScreenshot(scenario, "01-light-setup-empty-list.png")

      compose.onNode(hasText(context.getString(R.string.CollieStatus__setup_short), substring = true) and isClickable()).performClick()
      awaitText(context.getString(R.string.CollieSetup__hide_hint))
      saveScreenshot(scenario, "02-light-setup-sheet.png")
    }
  }

  @Test
  fun updatedNotesOutrankSetupAndOpenWhatsNew() = withFixture("light") {
    SignalStore.collieSetup.notesSeenId = null
    SignalStore.collieSetup.notesPendingId = null
    harness.launchActivity<MainActivity>().use { scenario ->
      awaitText(context.getString(R.string.CollieStatus__updated_long))
      saveScreenshot(scenario, "03-light-updated.png")

      compose.onNode(hasText(context.getString(R.string.CollieStatus__updated_long)) and isClickable()).performClick()
      awaitText(context.getString(R.string.CollieWhatsNew__heading))
      saveScreenshot(scenario, "04-light-whats-new.png")
    }
    assertEquals(CollieReleaseNotes.ID, SignalStore.collieSetup.notesSeenId)
  }

  @Test
  fun aMissingPushAppGetsTheRedBannerInDarkTheme() = withFixture("dark") {
    SignalStore.collieSetup.notesSeenId = CollieReleaseNotes.ID
    // UnifiedPush is selected and enabled, but this emulator has no distributor app to acknowledge it.
    SignalStore.settings.preferredNotificationMethod = NotificationDeliveryMethod.UNIFIEDPUSH
    SignalStore.unifiedpush.enabled = true
    harness.launchActivity<MainActivity>().use { scenario ->
      awaitText(context.getString(R.string.CollieProblem__push_app_missing_title))
      awaitText(context.getString(R.string.CollieStatus__push_app_missing_long))
      saveScreenshot(scenario, "05-dark-push-app-missing.png")
    }
  }

  @Test
  fun largeTextChoosesShorterWordsInsteadOfCuttingThemOff() = withFixture("light", fontScale = 2f) {
    SignalStore.collieSetup.notesSeenId = null
    SignalStore.collieSetup.notesPendingId = null
    harness.launchActivity<MainActivity>().use { scenario ->
      compose.waitUntil(timeoutMillis = 15_000) {
        listOf(R.string.CollieStatus__updated_long, R.string.CollieStatus__updated_short, R.string.CollieStatus__updated_tiny).any { res ->
          compose.onAllNodes(hasText(context.getString(res)), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
      }
      saveScreenshot(scenario, "06-large-text-updated.png")
    }
  }

  private fun isClickable(): SemanticsMatcher = SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick)

  private fun awaitText(text: String, substring: Boolean = false) {
    compose.waitUntil(timeoutMillis = 15_000) {
      compose.onAllNodes(hasText(text, substring = substring), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    }
  }

  private fun saveScreenshot(scenario: ActivityScenario<MainActivity>, name: String) {
    compose.waitForIdle()
    Thread.sleep(500)
    scenario.onActivity { activity ->
      assertEquals("Screen security must be off only for this fixture", 0, activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
    }

    val screenshot = instrumentation.uiAutomation.takeScreenshot() ?: error("Screenshot was unavailable")
    try {
      val directory = File(context.getExternalFilesDir(null), "finish-setup-proof")
      assertTrue("Could not create the screenshot directory", directory.exists() || directory.mkdirs())
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
    val oldFontScale = Settings.System.getString(context.contentResolver, Settings.System.FONT_SCALE)
    val oldMethod = SignalStore.settings.preferredNotificationMethod
    val oldUnifiedPush = SignalStore.unifiedpush.enabled
    val oldSeen = SignalStore.collieSetup.notesSeenId
    val oldPending = SignalStore.collieSetup.notesPendingId
    val oldPendingSince = SignalStore.collieSetup.notesPendingSince
    val oldHidden = SignalStore.collieSetup.hiddenItems

    try {
      commitThemePreferences(theme, dynamicColors = false, biometricLock = false)
      DynamicTheme.setDefaultDayNightMode(context)
      SignalStore.settings.isScreenSecurityEnabled = false
      ScreenLockController.enableAutoLock(false)
      ScreenLockController.lockScreenAtStart = false
      SignalStore.collieSetup.hiddenItems = emptySet()
      setFontScale(fontScale.toString())
      withoutMegaphones(block)
    } finally {
      restoreEverySetting(
        { commitThemePreferences(oldTheme, oldDynamicColors, oldBiometricLock) },
        { SignalStore.settings.isScreenSecurityEnabled = oldScreenSecurity },
        { ScreenLockController.enableAutoLock(oldAutoLock) },
        { ScreenLockController.lockScreenAtStart = oldLockAtStart },
        { AppCompatDelegate.setDefaultNightMode(oldNightMode) },
        { setFontScale(oldFontScale) },
        { SignalStore.settings.preferredNotificationMethod = oldMethod },
        { SignalStore.unifiedpush.enabled = oldUnifiedPush },
        { SignalStore.collieSetup.notesSeenId = oldSeen },
        { SignalStore.collieSetup.notesPendingId = oldPending },
        { SignalStore.collieSetup.notesPendingSince = oldPendingSince },
        { SignalStore.collieSetup.hiddenItems = oldHidden }
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

  private fun setFontScale(value: String?) {
    val command = if (value == null) "settings delete system font_scale" else "settings put system font_scale ${value.toFloat()}"
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
