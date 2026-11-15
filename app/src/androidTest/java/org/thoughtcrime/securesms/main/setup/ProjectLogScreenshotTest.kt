/*
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.main.setup

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.view.WindowManager
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.spyk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Writes API 35 screenshots of What's new with its two tabs and of Settings → Help → What's new to external files
 * under Downloads/project-log-proof. Install the app and test APKs from the same build before running it.
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
      val log = compose.onNode(hasTestTag("project-log-list"))
      log.performScrollToNode(hasText("8.33.2-1 RC1"))
      compose.onNode(hasText("8.33.2-1 RC1") and isClickable()).performClick()
      log.performScrollToNode(hasText("The first signed Collie test build", substring = true))
      saveScreenshot(scenario, "04-dark-project-log-from-help.png")
    }
  }

  @Test
  fun projectLogDisclosureOpensPr156InTheExternalBrowser() = withFixture("light") {
    SignalStore.collieSetup.notesSeenId = null
    harness.launchActivity<MainActivity>().use { scenario ->
      awaitText(context.getString(R.string.CollieStatus__updated_long))
      compose.onNode(hasText(context.getString(R.string.CollieStatus__updated_long)) and isClickable()).performClick()
      awaitText(context.getString(R.string.CollieWhatsNew__heading))
      compose.onNode(hasText(context.getString(R.string.CollieWhatsNew__tab_project_log)) and isClickable()).performClick()
      awaitText(context.getString(R.string.CollieLog__this_build))

      val log = compose.onNode(hasTestTag("project-log-list"))
      val disclosure = context.getString(R.string.CollieLog__links_note)
      val entry = "What’s new has a Project log of every change, also under Settings, Help."
      log.performScrollToNode(hasText(disclosure))
      awaitText(disclosure)
      saveScreenshot(scenario, "05-light-pr156-disclosure.png")

      log.performScrollToNode(hasText(entry, substring = true))
      awaitText(entry, substring = true)
      saveScreenshot(scenario, "06-light-pr156-entry.png")

      val targetUrl = "https://github.com/0cwa/collieim-android/pull/156"
      compose.onNode(hasText(entry, substring = true) and isClickable()).performClick()
      val device = UiDevice.getInstance(instrumentation)
      assertTrue(
        "The PR156 handoff did not leave Collie",
        device.wait(Until.gone(By.pkg(context.packageName)), 30_000)
      )
      continueBrowserWithoutAccount(device)

      val renderedTitle = "Add a project log to What's new, and What's new to Settings"
      assertTrue(
        "The external browser did not render the public PR156 page",
        device.wait(Until.hasObject(By.textContains(renderedTitle)), 90_000)
      )
      assertTrue(
        "The browser page did not render the PR156 number",
        device.wait(Until.hasObject(By.textContains("#156")), 15_000)
      )
      val browserPackage = requireNotNull(device.currentPackageName) { "No foreground package after the PR156 page rendered" }
      assertTrue("The PR156 handoff must leave Collie", browserPackage != context.packageName)
      val addressBar = device.wait(Until.findObject(By.res(browserPackage, "url_bar")), 10_000)
      assertNotNull("Could not inspect the external browser URL", addressBar)
      val address = addressBar?.text.orEmpty()
      val observedUrl = Uri.parse(if (address.contains("://")) address else "https://$address")
      val expectedUrl = Uri.parse(targetUrl)
      assertEquals("Unexpected browser URL scheme: $address", expectedUrl.scheme, observedUrl.scheme)
      assertEquals("Unexpected browser URL host: $address", expectedUrl.host, observedUrl.host)
      assertEquals("Unexpected browser repository or PR: $address", expectedUrl.path, observedUrl.path?.trimEnd('/'))

      saveTextEvidence("browser-handoff.txt", "url=$targetUrl\nbrowser=$browserPackage\nrendered_title=$renderedTitle\nrendered_pr=#156\naddress=$address\n")
      saveScreenshot("07-light-pr156-browser.png")
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

    saveScreenshot(name)
  }

  private fun saveScreenshot(name: String) {
    Thread.sleep(500)
    val screenshot = instrumentation.uiAutomation.takeScreenshot() ?: error("Screenshot was unavailable")
    try {
      writeMediaStoreFile(name, "image/png") { output ->
        requireNotNull(output) { "Could not open $name" }
        assertTrue("Could not save $name", screenshot.compress(Bitmap.CompressFormat.PNG, 100, output))
      }
    } finally {
      screenshot.recycle()
    }
  }

  private fun saveTextEvidence(name: String, contents: String) {
    writeMediaStoreFile(name, "text/plain") { output ->
      requireNotNull(output) { "Could not open $name" }
      output.write(contents.toByteArray(Charsets.UTF_8))
    }
  }

  private fun writeMediaStoreFile(name: String, mimeType: String, write: (java.io.OutputStream?) -> Unit) {
    val values = ContentValues().apply {
      put(MediaStore.MediaColumns.DISPLAY_NAME, name)
      put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
      put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/project-log-proof")
    }
    val resolver = context.contentResolver
    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("Could not create $name")
    resolver.openOutputStream(uri).use(write)
  }

  private fun continueBrowserWithoutAccount(device: UiDevice) {
    // Chrome's first-run screen can offer this explicit no-account path. Do not sign in or enable sync.
    val noAccount = device.wait(Until.findObject(By.text("Use without an account")), 3_000)
    if (noAccount != null) {
      noAccount.click()
      device.waitForIdle()
    }
    val noThanks = device.wait(Until.findObject(By.text("No thanks")), 3_000)
    if (noThanks != null) {
      noThanks.click()
      device.waitForIdle()
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
    } catch (failure: Throwable) {
      captureFailureContext(failure)
      throw failure
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

  private fun captureFailureContext(failure: Throwable) {
    val details = StringWriter().also { failure.printStackTrace(PrintWriter(it)) }
    val hierarchy = File(context.cacheDir, "project-log-failure-window.xml")
    runCatching {
      UiDevice.getInstance(instrumentation).dumpWindowHierarchy(hierarchy)
      details.append("\nAccessibility window hierarchy:\n").append(hierarchy.readText())
    }.onFailure { details.append("\nCould not capture window hierarchy: ").append(it.toString()) }
    runCatching {
      val semantics = compose.onAllNodes(SemanticsMatcher("all semantics nodes") { true }, useUnmergedTree = true).fetchSemanticsNodes()
      details.append("\nCompose semantics nodes:\n").append(semantics.joinToString("\n"))
    }.onFailure { details.append("\nCould not capture Compose semantics: ").append(it.toString()) }
    android.util.Log.e("ProjectLogScreenshotTest", "Instrumented project-log flow failed", failure)
    runCatching { saveTextEvidence("failure-context-${System.currentTimeMillis()}.txt", details.toString()) }
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
