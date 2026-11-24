/*
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.main.setup

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.provider.MediaStore
import android.view.WindowManager
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import im.molly.app.base.ApkInfo
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
import org.signal.core.ui.compose.ComposeFullScreenDialogFragment
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
import org.signal.storage.keyvalue.PlainTextKeyValueStore
import org.thoughtcrime.securesms.util.SecurePreferenceManager
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.util.concurrent.atomic.AtomicReference

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
    val notes = preparedBetaNotes()
    val editionId = requireNotNull(notes.current.highlightsId)
    val openedRelease = AtomicReference<String?>(null)
    SignalStore.collieSetup.notesSeenId = null
    SignalStore.collieSetup.notesPendingId = null

    harness.launchActivity<MainActivity>().use { scenario ->
      // A real app dialog renders the prepared record; the production repository and build flags stay untouched.
      showNotes(scenario, notes, WhatsNewTab.PROJECT_LOG, onFullNotes = openedRelease::set)
      assertCurrentSection(notes)
      assertEquals("Reading history must not consume the highlights", null, SignalStore.collieSetup.notesSeenId)
      compose.onAllNodes(hasText(context.getString(R.string.CollieLog__development_build))).assertCountEquals(0)
      compose.onAllNodes(hasText(context.getString(R.string.CollieLog__unavailable))).assertCountEquals(0)

      compose.onNode(hasText(context.getString(R.string.CollieWhatsNew__tab_this_update)) and isClickable()).performClick()
      awaitText(context.getString(R.string.CollieWhatsNew__heading))
      compose.waitUntil(timeoutMillis = 15_000) { SignalStore.collieSetup.notesSeenId == editionId }
      val highlights = compose.onNode(hasTestTag("whats-new-highlights"))
      val upstream = context.getString(R.string.CollieWhatsNew__signal_title, ApkInfo.signalCanonicalVersionName)
      highlights.performScrollToNode(hasText(upstream))
      awaitText(upstream)
      val fullNotes = hasText(context.getString(R.string.CollieWhatsNew__full_release_notes)) and isClickable()
      highlights.performScrollToNode(fullNotes)
      compose.onNode(fullNotes).performClick()
      assertEquals("Full notes must target this release, not the mixed release list", notes.current.releaseUrl, openedRelease.get())
      assertEquals("https://github.com/0cwa/collieim-android/releases/tag/trailblazer-v8.36.3-1-beta1", openedRelease.get())
      val support = hasText(context.getString(R.string.CollieSetup__support)) and isClickable()
      highlights.performScrollToNode(support)
      compose.onNode(support).assertExists()
      highlights.performScrollToIndex(0)
      saveScreenshot(scenario, "01-light-this-update.png")

      compose.onNode(hasText(context.getString(R.string.CollieWhatsNew__tab_project_log)) and isClickable()).performClick()
      assertCurrentSection(notes)
      compose.onNode(hasTestTag("project-log-list")).performScrollToIndex(0)
      saveScreenshot(scenario, "02-light-project-log.png")
      saveTextEvidence(
        "release-fixture.txt",
        "fixture=prepared record rendered by the app\nproduct=beta\nversion=${notes.current.version}\nasset=collie-log/releases/beta/8.36.3-1-beta1.txt\nhighlights=$editionId\nrelease_url=${openedRelease.get()}\nentries=${notes.current.entries.size}\n"
      )

      // An empty finalized maintenance record stays current and cannot borrow the prior release's highlights.
      val maintenanceVersion = "99.0.0-1"
      val maintenance = ProjectLog.notes(
        releaseFiles = bundledReleases("stable") + "version: $maintenanceVersion\ndate: 2026-11-22\n",
        unreleasedFiles = emptyList(),
        installedVersion = maintenanceVersion,
        prepared = true
      )
      assertEquals(ProjectLog.CurrentKind.RELEASE, maintenance.current.kind)
      showNotes(scenario, maintenance, WhatsNewTab.PROJECT_LOG)
      assertCurrentSection(maintenance)
      awaitText(context.getString(R.string.CollieLog__no_changes))
      compose.onNode(hasText(context.getString(R.string.CollieWhatsNew__tab_this_update)) and isClickable()).performClick()
      awaitText(context.getString(R.string.CollieWhatsNew__no_highlights))
      compose.onAllNodes(hasText(context.getString(R.string.CollieWhatsNew__setup_title))).assertCountEquals(0)

      val unavailable = ProjectLog.notes(
        releaseFiles = bundledReleases("beta"),
        unreleasedFiles = listOf("topic: fixes\nA change left outside the release."),
        installedVersion = notes.current.version,
        prepared = true
      )
      assertEquals(ProjectLog.CurrentKind.UNAVAILABLE, unavailable.current.kind)
      showNotes(scenario, unavailable, WhatsNewTab.PROJECT_LOG)
      assertCurrentSection(unavailable)
      awaitText(context.getString(R.string.CollieLog__unavailable))
      assertTrue("The invalid current record must not appear again in history", unavailable.history.none { it.version == unavailable.current.version })
    }
  }

  @Test
  fun helpOpensTheProjectLogAndOlderReleasesExpand() = withFixture("dark") {
    SignalStore.collieSetup.notesSeenId = null
    val notes = ProjectLogRepository.load(context)
    assertEquals("This navigation fixture uses the ordinary development APK", ProjectLog.CurrentKind.DEVELOPMENT, notes.current.kind)
    assertTrue("Stable development must not show TrailBlazer release history", notes.history.none { it.version == "8.36.3-1-beta1" })
    // AppSettingsActivity.help() starts on Contact us, so go through the Settings list the way people do.
    ActivityScenario.launch<AppSettingsActivity>(AppSettingsActivity.home(context)).use { scenario ->
      val help = context.getString(AppSettingsR.string.preferences__help)
      compose.waitUntil(timeoutMillis = 15_000) { compose.onAllNodes(hasTestTag(AppSettingsTestTags.SCROLLER)).fetchSemanticsNodes().isNotEmpty() }
      compose.onNode(hasTestTag(AppSettingsTestTags.SCROLLER)).performScrollToNode(hasText(help))
      compose.onNode(hasText(help) and isClickable()).performClick()
      awaitText(context.getString(AppSettingsR.string.HelpSettingsFragment__whats_new))
      saveScreenshot(scenario, "03-dark-help.png")

      compose.onNode(hasText(context.getString(AppSettingsR.string.HelpSettingsFragment__whats_new)) and isClickable()).performClick()
      assertCurrentSection(notes)
      awaitText(context.getString(R.string.CollieLog__development_build), substring = true)
      assertEquals("Help history must not mark highlights seen", null, SignalStore.collieSetup.notesSeenId)

      if (notes.current.entries.isNotEmpty()) {
        compose.onNode(hasTestTag("project-log-current")).performClick()
      }
      val oldest = notes.history.last()
      val log = compose.onNode(hasTestTag("project-log-list"))
      log.performScrollToNode(hasText(ProjectLog.displayVersion(oldest.version)))
      compose.onNode(hasText(ProjectLog.displayVersion(oldest.version)) and isClickable()).performClick()
      log.performScrollToNode(hasText(oldest.entries.first().text))
      saveScreenshot(scenario, "04-dark-project-log-from-help.png")
    }
  }

  @Test
  fun projectLogDisclosureOpensPr156InTheExternalBrowser() = withFixture("light") {
    val notes = preparedBetaNotes()
    val entry = notes.current.entries.single { it.pr == 156 }.text
    SignalStore.collieSetup.notesSeenId = null
    harness.launchActivity<MainActivity>().use { scenario ->
      showNotes(scenario, notes, WhatsNewTab.PROJECT_LOG)
      assertCurrentSection(notes)
      val log = compose.onNode(hasTestTag("project-log-list"))
      val disclosure = context.getString(R.string.CollieLog__links_note)
      log.performScrollToNode(hasText(disclosure))
      awaitText(disclosure)
      saveScreenshot(scenario, "05-light-pr156-disclosure.png")

      log.performScrollToNode(hasText(entry))
      awaitText(entry)
      saveScreenshot(scenario, "06-light-pr156-entry.png")

      val targetUrl = "https://github.com/0cwa/collieim-android/pull/156"
      compose.onNode(hasText(entry) and isClickable()).performClick()
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
      val (address, titleBounds) = saveBrowserScreenshot(device, browserPackage, renderedTitle, targetUrl)
      saveTextEvidence("browser-handoff.txt", "url=$targetUrl\nbrowser=$browserPackage\nrendered_title=$renderedTitle\nrendered_pr=#156\naddress=$address\ntitle_bounds=${titleBounds.left},${titleBounds.top},${titleBounds.right},${titleBounds.bottom}\n")
    }
  }

  private fun bundledReleases(product: String): List<String> {
    val directory = "${ProjectLog.ASSET_DIR}/${ProjectLog.RELEASES_DIR}/$product"
    return context.assets.list(directory).orEmpty().sorted().map { name ->
      context.assets.open("$directory/$name").bufferedReader().use { it.readText() }
    }
  }

  private fun preparedBetaNotes(): ProjectLog.Notes = ProjectLog.notes(
    releaseFiles = bundledReleases("beta"),
    unreleasedFiles = emptyList(),
    installedVersion = "8.36.3-1-beta1",
    prepared = true
  ).also {
    assertEquals(ProjectLog.CurrentKind.RELEASE, it.current.kind)
    assertTrue("A Beta release must not inherit Stable history", it.history.none { release -> release.version == "8.35.1-3-rc2" })
  }

  private fun showNotes(
    scenario: ActivityScenario<MainActivity>,
    notes: ProjectLog.Notes,
    startTab: WhatsNewTab,
    onFullNotes: (String) -> Unit = {}
  ) {
    scenario.onActivity { activity ->
      (activity.supportFragmentManager.findFragmentByTag("project-log-proof") as? ProjectLogProofDialog)?.dismissNow()
      ProjectLogProofDialog().apply {
        this.notes = notes
        this.startTab = startTab
        this.onFullNotes = onFullNotes
      }.showNow(activity.supportFragmentManager, "project-log-proof")
    }
    compose.waitForIdle()
  }

  private fun assertCurrentSection(notes: ProjectLog.Notes) {
    compose.waitUntil(timeoutMillis = 15_000) { compose.onAllNodes(hasTestTag("project-log-current")).fetchSemanticsNodes().isNotEmpty() }
    compose.onAllNodes(hasText(context.getString(R.string.CollieLog__your_version))).assertCountEquals(1)
    compose.onNode(hasTestTag("project-log-current") and hasText(ProjectLog.displayVersion(notes.current.version))).assertExists()
  }

  /** Test-only host for the production composable, using release records from this APK's assets. */
  class ProjectLogProofDialog : ComposeFullScreenDialogFragment() {
    lateinit var notes: ProjectLog.Notes
    var startTab: WhatsNewTab = WhatsNewTab.THIS_UPDATE
    var onFullNotes: (String) -> Unit = {}

    @Composable
    override fun DialogContent() {
      WhatsNewContent(
        notes = notes,
        startTab = startTab,
        onBack = { dismissAllowingStateLoss() },
        onReleaseAction = { SetupActions.actOnReleaseNotice(requireActivity(), it) },
        onFullNotes = onFullNotes,
        onSupport = { SetupActions.openSupport(requireActivity()) },
        onOpenPullRequest = { SetupActions.openPullRequest(requireActivity(), it) },
        onHighlightsSeen = SetupStatusRepository::markNotesSeen
      )
    }
  }

  private fun verifyBrowserPage(device: UiDevice, browserPackage: String, title: String, targetUrl: String): String {
    assertEquals("The browser must remain foreground", browserPackage, device.currentPackageName)
    assertTrue("The PR156 title must remain visible", device.hasObject(By.textContains(title)))
    assertTrue("The PR156 number must remain visible", device.hasObject(By.textContains("#156")))
    val addressBar = device.findObject(By.res(browserPackage, "url_bar"))
    assertNotNull("Could not inspect the external browser URL", addressBar)
    val address = addressBar?.text.orEmpty()
    val observedUrl = Uri.parse(if (address.contains("://")) address else "https://$address")
    val expectedUrl = Uri.parse(targetUrl)
    assertEquals("Unexpected browser URL scheme: $address", expectedUrl.scheme, observedUrl.scheme)
    assertEquals("Unexpected browser URL host: $address", expectedUrl.host, observedUrl.host)
    assertEquals("Unexpected browser repository or PR: $address", expectedUrl.path, observedUrl.path?.trimEnd('/'))
    return address
  }

  private fun browserTitleSample(bitmap: Bitmap, bounds: Rect): IntArray {
    if (bounds.width() < 8 || bounds.height() < 8 || bounds.left < 0 || bounds.top < 0 || bounds.right > bitmap.width || bounds.bottom > bitmap.height) return IntArray(0)
    val xs = (bounds.left until bounds.right step maxOf(1, bounds.width() / 80)).toList()
    val ys = (bounds.top until bounds.bottom step maxOf(1, bounds.height() / 20)).toList()
    return IntArray(xs.size * ys.size) { index -> bitmap.getPixel(xs[index % xs.size], ys[index / xs.size]) and 0x00F0F0F0 }
  }

  private fun browserTitleHasContent(sample: IntArray): Boolean {
    if (sample.isEmpty()) return false
    return (0..2).any { channel ->
      val shift = channel * 8
      val mean = sample.sumOf { (it ushr shift) and 255 }.toDouble() / sample.size
      sample.sumOf { val delta = ((it ushr shift) and 255) - mean; delta * delta } / sample.size >= 1.0
    }
  }

  private fun saveBrowserScreenshot(device: UiDevice, browserPackage: String, title: String, targetUrl: String): Pair<String, Rect> {
    val deadline = android.os.SystemClock.uptimeMillis() + 30_000
    var previous: IntArray? = null
    var previousBounds: Rect? = null
    android.util.Log.i("ProjectLogScreenshotTest", "Starting browser capture at ${android.os.SystemClock.uptimeMillis()}")
    while (android.os.SystemClock.uptimeMillis() < deadline) {
      verifyBrowserPage(device, browserPackage, title, targetUrl)
      val titleBounds = device.findObject(By.textContains(title))?.visibleBounds ?: Rect()
      val screenshot = instrumentation.uiAutomation.takeScreenshot() ?: error("Browser screenshot was unavailable")
      try {
        val sample = browserTitleSample(screenshot, titleBounds)
        val contentful = browserTitleHasContent(sample)
        if (contentful && titleBounds == previousBounds && previous?.contentEquals(sample) == true) {
          val address = verifyBrowserPage(device, browserPackage, title, targetUrl)
          if (android.os.SystemClock.uptimeMillis() >= deadline) break
          if (device.findObject(By.textContains(title))?.visibleBounds != titleBounds) continue
          writeMediaStoreFile("07-light-pr156-browser.png", "image/png") { output ->
            assertTrue("Could not save browser capture", screenshot.compress(Bitmap.CompressFormat.PNG, 100, requireNotNull(output)))
          }
          android.util.Log.i("ProjectLogScreenshotTest", "Saved browser capture at ${android.os.SystemClock.uptimeMillis()}")
          return address to titleBounds
        }
        previous = if (contentful) sample else null
        previousBounds = if (contentful) titleBounds else null
      } finally {
        screenshot.recycle()
      }
      val remaining = deadline - android.os.SystemClock.uptimeMillis()
      if (remaining > 0) android.os.SystemClock.sleep(minOf(250L, remaining))
    }
    throw AssertionError("Browser PR title did not become nonblank and stable within 30 seconds")
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
    // Chrome can show these accountless first-run choices after its initial loading screen.
    val deadline = android.os.SystemClock.uptimeMillis() + 60_000
    val addressBar = By.res("com.android.chrome", "url_bar")
    while (android.os.SystemClock.uptimeMillis() < deadline) {
      val browserReady = device.hasObject(addressBar)
      if (android.os.SystemClock.uptimeMillis() >= deadline) break
      if (browserReady) return
      for (label in listOf("Use without an account", "No thanks")) {
        val remaining = deadline - android.os.SystemClock.uptimeMillis()
        if (remaining <= 0) break
        val choice = device.wait(Until.findObject(By.text(label)), minOf(remaining, 1_000L))
        if (android.os.SystemClock.uptimeMillis() >= deadline) break
        choice?.click()
      }
    }
    throw AssertionError("Chrome onboarding did not reach browser content within 60 seconds")
  }

  private fun withFixture(theme: String, block: () -> Unit) {
    val oldTheme = PlainTextKeyValueStore.theme
    val oldDynamicColors = PlainTextKeyValueStore.isDynamicColorsEnabled
    val oldBiometricLock = PlainTextKeyValueStore.biometricScreenLockEnabled
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
      SecurePreferenceManager.getSecurePreferences(context).edit()
        .putString(PlainTextKeyValueStore.THEME, theme)
        .putBoolean(PlainTextKeyValueStore.DYNAMIC_COLORS_ENABLED, dynamicColors)
        .putBoolean(PlainTextKeyValueStore.BIOMETRIC_SCREEN_LOCK_ENABLED, biometricLock)
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
