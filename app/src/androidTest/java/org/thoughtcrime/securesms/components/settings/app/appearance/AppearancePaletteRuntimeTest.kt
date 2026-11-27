/*
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.components.settings.app.appearance

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.util.TypedValue
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.signal.appsettings.appearance.AppearanceSettingsTestTags
import org.signal.appsettings.home.AppSettingsTestTags
import org.signal.appsettings.routes.AppSettingsRoute
import org.signal.core.ui.compose.theme.UserPalettes
import org.signal.core.ui.util.ThemeUtil
import org.signal.remoteconfig.RemoteConfig
import org.signal.storage.keyvalue.PlainTextKeyValueStore
import org.thoughtcrime.securesms.MainActivity
import org.thoughtcrime.securesms.ScreenLockController
import org.thoughtcrime.securesms.components.settings.app.AppSettingsActivity
import org.thoughtcrime.securesms.components.settings.app.AppSettingsNavigationActivity
import org.thoughtcrime.securesms.flags.CollieFlags
import org.thoughtcrime.securesms.keyvalue.SettingsValues
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.testing.DisableAnimationsRule
import org.thoughtcrime.securesms.testing.RawFlag
import org.thoughtcrime.securesms.testing.RemoteConfigForTest
import org.thoughtcrime.securesms.testing.SignalActivityRule
import org.thoughtcrime.securesms.util.DynamicTheme
import org.thoughtcrime.securesms.util.ThemePalettes
import java.io.File
import com.google.android.material.R as MaterialR
import org.signal.core.ui.R as CoreUiR

/**
 * Drives the real Appearance color picker and Pure black switch in both Settings hosts: the fragment host
 * ([AppSettingsActivity]) and the navigation host ([AppSettingsNavigationActivity]). Each choice recreates the host on
 * the same Appearance screen, and the retained overview follows on return. Runs on API 27 and later; screenshots go to
 * additionalTestOutputDir.
 */
@RunWith(AndroidJUnit4::class)
@RemoteConfigForTest(rawFlags = [RawFlag(RemoteConfig.APP_SETTINGS_NAVIGATION_KEY, "false")])
class AppearancePaletteRuntimeTest {

  @get:Rule
  val harness = SignalActivityRule(othersCount = 0)

  @get:Rule
  val animations = DisableAnimationsRule()

  @get:Rule
  val compose = createEmptyComposeRule()

  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val context get() = harness.context
  private lateinit var previousTheme: SettingsValues.Theme
  private var previousDynamicColors = false
  private var previousPalette: String? = null
  private var previousPureBlack = false
  private var previousScreenSecurity = true
  private var previousBiometricLock = false
  private var previousAutoLock = false
  private var previousLockAtStart = false
  private var previousNightMode = AppCompatDelegate.MODE_NIGHT_UNSPECIFIED

  @Before
  fun savePreferences() {
    assumeTrue(CollieFlags.userTheming)
    previousTheme = SignalStore.settings.theme
    previousDynamicColors = SignalStore.settings.isDynamicColorsEnabled
    previousPalette = ThemePalettes.storedId(context)
    previousPureBlack = ThemePalettes.isPureBlackEnabled(context)
    previousScreenSecurity = SignalStore.settings.isScreenSecurityEnabled
    previousBiometricLock = PlainTextKeyValueStore.biometricScreenLockEnabled
    previousAutoLock = ScreenLockController.autoLock
    previousLockAtStart = ScreenLockController.lockScreenAtStart
    previousNightMode = AppCompatDelegate.getDefaultNightMode()

    // Screenshots are part of the evidence; restore screen security in tearDown.
    SignalStore.settings.isScreenSecurityEnabled = false
    PlainTextKeyValueStore.biometricScreenLockEnabled = false
    ScreenLockController.enableAutoLock(false)
    ScreenLockController.lockScreenAtStart = false
  }

  @After
  fun restorePreferences() {
    if (!CollieFlags.userTheming) return
    instrumentation.runOnMainSync {
      ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).toList().forEach(Activity::finish)
      SignalStore.settings.setTheme(previousTheme, previousDynamicColors)
      ThemePalettes.setStoredId(context, previousPalette)
      ThemePalettes.setPureBlack(context, previousPureBlack)
      SignalStore.settings.isScreenSecurityEnabled = previousScreenSecurity
      PlainTextKeyValueStore.biometricScreenLockEnabled = previousBiometricLock
      ScreenLockController.enableAutoLock(previousAutoLock)
      ScreenLockController.lockScreenAtStart = previousLockAtStart
      AppCompatDelegate.setDefaultNightMode(previousNightMode)
    }
  }

  @Test
  fun darkPalettesAndPureBlackFromFragmentSettings() {
    assertPickerFlow(SettingsValues.Theme.DARK, navigationSettings = false, palettes = listOf("plum_expressive", "slate_neutral"))
  }

  @Test
  fun lightPalettesAndPureBlackFromNavigationSettings() {
    assertPickerFlow(SettingsValues.Theme.LIGHT, navigationSettings = true, palettes = listOf("blue_tonal_spot", "teal_vibrant"))
  }

  @Test
  fun darkPalettesAndPureBlackFromNavigationSettings() {
    assertPickerFlow(SettingsValues.Theme.DARK, navigationSettings = true, palettes = listOf("teal_vibrant", "blue_tonal_spot"))
  }

  @Test
  fun lightPalettesFromFragmentSettings() {
    assertPickerFlow(SettingsValues.Theme.LIGHT, navigationSettings = false, palettes = listOf("slate_neutral", "plum_expressive"))
  }

  /** Captures the picker at the largest standard font scale and checks every option keeps its full name on screen. */
  @Test
  fun pickerKeepsFullNamesAtLargeFontScale() {
    val previousScale = shell("settings get system font_scale").trim().ifEmpty { "1.0" }
    try {
      shell("settings put system font_scale 2.0")
      setInitial(SettingsValues.Theme.LIGHT, "plum_expressive", pureBlack = false)
      ActivityScenario.launch(MainActivity::class.java).use { main ->
        openAppearance(main, navigationSettings = true)
        val names = mapOf(
          "collie" to "Collie",
          "blue_tonal_spot" to "Blue",
          "teal_vibrant" to "Teal",
          "plum_expressive" to "Plum",
          "slate_neutral" to "Slate"
        )
        for ((id, name) in names) {
          scrollTo(AppearanceSettingsTestTags.paletteOption(id))
          compose.onNodeWithTag(AppearanceSettingsTestTags.paletteOption(id)).assertIsDisplayed().assertTextEquals(name)
        }
        scrollTo(AppearanceSettingsTestTags.ROW_COLOR)
        compose.onNodeWithTag(AppearanceSettingsTestTags.paletteOption("plum_expressive")).assertIsSelected()
        saveScreenshot("appearance-large-font-light-plum")
      }
    } finally {
      shell("settings put system font_scale $previousScale")
    }
  }

  private fun assertPickerFlow(theme: SettingsValues.Theme, navigationSettings: Boolean, palettes: List<String>) {
    val dark = theme == SettingsValues.Theme.DARK
    val nightMode = if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
    val host = if (navigationSettings) "navigation" else "fragment"
    val mode = theme.name.lowercase()
    setInitial(theme, null, pureBlack = false)

    ActivityScenario.launch(MainActivity::class.java).use { main ->
      val collieOverview = awaitResumed<MainActivity>(nightMode) { paletteMarker(it) == null }
      var settings = openAppearance(main, navigationSettings)
      assertSelected(ThemePalettes.COLLIE)
      assertScreenshot("appearance-$host-$mode-collie", collieSurface(dark))

      // Collie, then straight from one built-in palette to another.
      for (id in palettes) {
        val palette = UserPalettes.byId(id)!!
        settings = pick(settings, nightMode, AppearanceSettingsTestTags.paletteOption(id)) { paletteMarker(it) == id }
        assertEquals(id, ThemePalettes.storedId(context))
        assertFalse(SignalStore.settings.isDynamicColorsEnabled)
        assertEquals(palette.colorScheme(dark).surface.toArgb(), ThemeUtil.getThemedColor(settings, MaterialR.attr.colorSurface))
        assertSelected(id)
        assertScreenshot("appearance-$host-$mode-$id", palette.colorScheme(dark).surface.toArgb())
      }

      // Pure black: only dark surfaces turn black, and elevated containers keep the palette. Light keeps its palette.
      val last = UserPalettes.byId(palettes.last())!!
      val expectedSurface = if (dark) Color.BLACK else last.colorScheme(false).surface.toArgb()
      settings = pick(settings, nightMode, AppearanceSettingsTestTags.ROW_PURE_BLACK) { pureBlackMarker(it) == dark && paletteMarker(it) == last.id }
      assertEquals(last.colorScheme(dark).surfaceContainer.toArgb(), ThemeUtil.getThemedColor(settings, MaterialR.attr.colorSurfaceContainer))
      assertTrue(ThemePalettes.isPureBlackEnabled(context))
      assertEquals(expectedSurface, ThemeUtil.getThemedColor(settings, MaterialR.attr.colorSurface))
      assertSelected(last.id)
      assertScreenshot("appearance-$host-$mode-${last.id}-pure-black", expectedSurface)

      // Back to the retained overview, which recreates with the same identity.
      instrumentation.runOnMainSync { settings.finish() }
      val overview = awaitResumed<MainActivity>(nightMode, previous = collieOverview) { paletteMarker(it) == last.id && pureBlackMarker(it) == dark }
      assertEquals(expectedSurface, ThemeUtil.getThemedColor(overview, MaterialR.attr.colorSurface))
      assertScreenshot("overview-$host-$mode-${last.id}-pure-black", expectedSurface)

      // Return to Collie without Pure black from the same picker.
      settings = openAppearance(main, navigationSettings)
      settings = pick(settings, nightMode, AppearanceSettingsTestTags.paletteOption(ThemePalettes.COLLIE)) { paletteMarker(it) == null }
      settings = pick(settings, nightMode, AppearanceSettingsTestTags.ROW_PURE_BLACK) { !pureBlackMarker(it) && paletteMarker(it) == null }
      assertEquals(collieSurface(dark), ThemeUtil.getThemedColor(settings, MaterialR.attr.colorSurface))
      assertEquals(ThemePalettes.COLLIE, ThemePalettes.selectedId(context))
      assertFalse(ThemePalettes.isPureBlackEnabled(context))
    }
  }

  private fun setInitial(theme: SettingsValues.Theme, palette: String?, pureBlack: Boolean) {
    instrumentation.runOnMainSync {
      SignalStore.settings.setTheme(theme, false)
      SignalStore.settings.setColorPalette(palette ?: ThemePalettes.COLLIE)
      ThemePalettes.setPureBlack(context, pureBlack)
      DynamicTheme.setDefaultDayNightMode(context)
    }
  }

  /** Opens Appearance from the overview through the given host and returns that host once the picker shows. */
  private fun openAppearance(main: ActivityScenario<MainActivity>, navigationSettings: Boolean): Activity {
    main.onActivity { activity ->
      activity.startActivity(
        if (navigationSettings) {
          AppSettingsNavigationActivity.createIntent(activity, AppSettingsRoute.AppearanceRoute.Appearance)
        } else {
          AppSettingsActivity.home(activity)
        }
      )
    }

    if (!navigationSettings) {
      awaitComposeNode(AppSettingsTestTags.SCROLLER)
      compose.onNodeWithTag(AppSettingsTestTags.SCROLLER).performScrollToNode(hasTestTag(AppSettingsTestTags.ROW_APPEARANCE))
      compose.onNodeWithTag(AppSettingsTestTags.ROW_APPEARANCE).performClick()
    }
    awaitComposeNode(AppearanceSettingsTestTags.ROW_COLOR)
    var host: Activity? = null
    instrumentation.runOnMainSync {
      host = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
        .single { (it is AppSettingsActivity || it is AppSettingsNavigationActivity) && !it.isFinishing }
    }
    assertEquals(navigationSettings, host is AppSettingsNavigationActivity)
    return checkNotNull(host)
  }

  /** Taps [tag] and returns the recreated host, which must still show Appearance rather than the Settings home. */
  private fun pick(settings: Activity, nightMode: Int, tag: String, expected: (Activity) -> Boolean): Activity {
    scrollTo(tag)
    compose.onNodeWithTag(tag).performClick()
    val recreated = awaitResumed<Activity>(nightMode, previous = settings) {
      (it is AppSettingsActivity || it is AppSettingsNavigationActivity) && it.javaClass == settings.javaClass && expected(it)
    }
    awaitComposeNode(AppearanceSettingsTestTags.ROW_COLOR)
    assertTrue(
      "Settings home replaced Appearance after recreation",
      compose.onAllNodesWithTag(AppSettingsTestTags.ROW_APPEARANCE).fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty()
    )
    return recreated
  }

  private fun assertSelected(id: String) {
    scrollTo(AppearanceSettingsTestTags.paletteOption(id))
    compose.onNodeWithTag(AppearanceSettingsTestTags.paletteOption(id)).assertIsSelected()
  }

  private fun scrollTo(tag: String) {
    awaitComposeNode(AppearanceSettingsTestTags.SCROLLER)
    compose.onNodeWithTag(AppearanceSettingsTestTags.SCROLLER).performScrollToNode(hasTestTag(tag))
  }

  private inline fun <reified T : Activity> awaitResumed(nightMode: Int, previous: Activity? = null, crossinline matches: (T) -> Boolean): T {
    var result: T? = null
    compose.waitUntil(15_000) {
      instrumentation.runOnMainSync {
        result = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
          .filterIsInstance<T>()
          .singleOrNull { it !== previous && !it.isFinishing && matches(it) }
          ?.takeIf { it.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == nightMode }
      }
      result != null
    }
    return checkNotNull(result)
  }

  private fun awaitComposeNode(tag: String) {
    compose.waitUntil(15_000) {
      compose.onAllNodesWithTag(tag).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
    }
  }

  private fun outputDir(): File {
    val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
      ?.let(::File) ?: File(context.getExternalFilesDir(null), "theme-regression")
    assertTrue(output.isDirectory || output.mkdirs())
    return output
  }

  private fun saveScreenshot(name: String) {
    instrumentation.waitForIdleSync()
    instrumentation.uiAutomation.waitForIdle(300, 5_000)
    val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot()) { "Could not capture $name" }
    try {
      File(outputDir(), "$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    } finally {
      bitmap.recycle()
    }
  }

  /** Waits for a frame where at least a tenth of sampled pixels show [surface], then keeps that screenshot. */
  private fun assertScreenshot(name: String, surface: Int) {
    var best = 0
    val deadline = System.currentTimeMillis() + 15_000
    while (System.currentTimeMillis() < deadline) {
      instrumentation.waitForIdleSync()
      instrumentation.uiAutomation.waitForIdle(300, 5_000)
      val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot()) { "Could not capture $name" }
      try {
        var matching = 0
        var sampled = 0
        for (y in 0 until bitmap.height step 4) {
          for (x in 0 until bitmap.width step 4) {
            sampled++
            if (bitmap.getPixel(x, y) == surface) matching++
          }
        }
        best = maxOf(best, matching * 100 / sampled)
        if (matching > sampled / 10) {
          File(outputDir(), "$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
          return
        }
      } finally {
        bitmap.recycle()
      }
      Thread.sleep(250)
    }
    throw AssertionError("$name never rendered the expected surface ${Integer.toHexString(surface)}; best $best% of sampled pixels")
  }

  private fun shell(command: String): String {
    val descriptor = instrumentation.uiAutomation.executeShellCommand(command)
    return android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
  }

  private fun collieSurface(dark: Boolean): Int = Color.parseColor(if (dark) "#24130D" else "#FFF8F0")

  private fun paletteMarker(activity: Activity): String? {
    val value = TypedValue()
    return if (activity.theme.resolveAttribute(CoreUiR.attr.collie_palette, value, true)) value.string?.toString() else null
  }

  private fun pureBlackMarker(activity: Activity): Boolean {
    val value = TypedValue()
    return activity.theme.resolveAttribute(CoreUiR.attr.collie_pure_black, value, true) && value.data != 0
  }
}
