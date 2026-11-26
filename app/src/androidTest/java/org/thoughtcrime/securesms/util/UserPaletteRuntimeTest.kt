/*
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.util

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Process
import android.util.TypedValue
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.signal.appsettings.home.AppSettingsTestTags
import org.signal.core.ui.compose.theme.UserPalettes
import org.signal.core.ui.util.ThemeUtil
import org.signal.remoteconfig.RemoteConfig
import org.signal.storage.keyvalue.PlainTextKeyValueStore
import org.thoughtcrime.securesms.MainActivity
import org.thoughtcrime.securesms.ScreenLockController
import org.thoughtcrime.securesms.components.settings.app.AppSettingsActivity
import org.thoughtcrime.securesms.keyvalue.SettingsValues
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.testing.DisableAnimationsRule
import org.thoughtcrime.securesms.testing.RawFlag
import org.thoughtcrime.securesms.testing.RemoteConfigForTest
import org.thoughtcrime.securesms.testing.SignalActivityRule
import java.io.File
import com.google.android.material.R as MaterialR
import org.signal.core.ui.R as CoreUiR

/**
 * Stores the pilot palette, then checks the overview and Compose settings on a device before returning to Collie
 * in the same process. Runs on API 27 and later; screenshots go to additionalTestOutputDir.
 */
@RunWith(AndroidJUnit4::class)
@RemoteConfigForTest(rawFlags = [RawFlag(RemoteConfig.APP_SETTINGS_NAVIGATION_KEY, "false")])
class UserPaletteRuntimeTest {

  @get:Rule
  val harness = SignalActivityRule(othersCount = 0)

  @get:Rule
  val animations = DisableAnimationsRule()

  @get:Rule
  val compose = createEmptyComposeRule()

  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val context get() = harness.context
  private val pilot = UserPalettes.byId(PILOT)!!
  private lateinit var previousTheme: SettingsValues.Theme
  private var previousDynamicColors = false
  private var previousPalette: String? = null
  private var previousScreenSecurity = true
  private var previousBiometricLock = false
  private var previousAutoLock = false
  private var previousLockAtStart = false
  private var previousNightMode = AppCompatDelegate.MODE_NIGHT_UNSPECIFIED

  @Before
  fun savePreferences() {
    previousTheme = SignalStore.settings.theme
    previousDynamicColors = PlainTextKeyValueStore.isDynamicColorsEnabled
    previousPalette = PlainTextKeyValueStore.themePalette
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
    instrumentation.runOnMainSync {
      ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).toList().forEach(Activity::finish)
      SignalStore.settings.setTheme(previousTheme, previousDynamicColors)
      PlainTextKeyValueStore.setPalette(previousDynamicColors, previousPalette)
      SignalStore.settings.isScreenSecurityEnabled = previousScreenSecurity
      PlainTextKeyValueStore.biometricScreenLockEnabled = previousBiometricLock
      ScreenLockController.enableAutoLock(previousAutoLock)
      ScreenLockController.lockScreenAtStart = previousLockAtStart
      AppCompatDelegate.setDefaultNightMode(previousNightMode)
    }
  }

  @Test
  fun lightPilotReachesOverviewAndSettingsThenReturnsToCollie() {
    assertPilotRoundTrip(SettingsValues.Theme.LIGHT)
  }

  @Test
  fun darkPilotReachesOverviewAndSettingsThenReturnsToCollie() {
    assertPilotRoundTrip(SettingsValues.Theme.DARK)
  }

  private fun assertPilotRoundTrip(theme: SettingsValues.Theme) {
    val dark = theme == SettingsValues.Theme.DARK
    val nightMode = if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
    val pilotSurface = pilot.colorScheme(dark).surface.toArgb()
    val collieSurface = Color.parseColor(if (dark) "#24130D" else "#FFF8F0")
    val name = theme.name.lowercase()
    val processId = Process.myPid()

    instrumentation.runOnMainSync {
      SignalStore.settings.setTheme(theme, false)
      SignalStore.settings.setColorPalette(PILOT)
      DynamicTheme.setDefaultDayNightMode(context)
    }

    ActivityScenario.launch(MainActivity::class.java).use { main ->
      val pilotOverview = awaitResumed<MainActivity>(PILOT, nightMode)
      assertEquals(pilotSurface, ThemeUtil.getThemedColor(pilotOverview, MaterialR.attr.colorSurface))
      assertEquals(pilot.colorScheme(dark).primary.toArgb(), ThemeUtil.getThemedColor(pilotOverview, MaterialR.attr.colorPrimary))
      assertScreenshot("pilot-$name-overview", pilotSurface)

      main.onActivity { it.startActivity(AppSettingsActivity.home(it)) }
      val settings = awaitResumed<AppSettingsActivity>(PILOT, nightMode)
      awaitComposeNode(AppSettingsTestTags.SCROLLER)
      assertScreenshot("pilot-$name-settings", pilotSurface)

      // The settings host recreates on the theme event; the retained overview recreates when it resumes.
      instrumentation.runOnMainSync { SignalStore.settings.setColorPalette(ThemePalettes.COLLIE) }
      val collieSettings = awaitResumed<AppSettingsActivity>(null, nightMode, previous = settings)
      assertNull(PlainTextKeyValueStore.themePalette)
      instrumentation.runOnMainSync { collieSettings.finish() }

      val collieOverview = awaitResumed<MainActivity>(null, nightMode, previous = pilotOverview)
      assertNotSame(pilotOverview, collieOverview)
      assertEquals(collieSurface, ThemeUtil.getThemedColor(collieOverview, MaterialR.attr.colorSurface))
      assertEquals(processId, Process.myPid())
      assertScreenshot("collie-$name-overview", collieSurface)
    }
  }

  private inline fun <reified T : Activity> awaitResumed(palette: String?, nightMode: Int, previous: Activity? = null): T {
    var result: T? = null
    compose.waitUntil(15_000) {
      instrumentation.runOnMainSync {
        result = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
          .filterIsInstance<T>()
          .singleOrNull { it !== previous && !it.isFinishing && paletteMarker(it) == palette }
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

  /** Waits for a frame where at least a tenth of sampled pixels show [surface], then keeps that screenshot. */
  private fun assertScreenshot(name: String, surface: Int) {
    val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
      ?.let(::File) ?: File(context.getExternalFilesDir(null), "theme-regression")
    assertTrue(output.isDirectory || output.mkdirs())

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
          File(output, "$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
          return
        }
      } finally {
        bitmap.recycle()
      }
      Thread.sleep(250)
    }
    throw AssertionError("$name never rendered the expected surface ${Integer.toHexString(surface)}; best $best% of sampled pixels")
  }

  private fun paletteMarker(activity: Activity): String? {
    val value = TypedValue()
    return if (activity.theme.resolveAttribute(CoreUiR.attr.collie_palette, value, true)) value.string?.toString() else null
  }

  companion object {
    private const val PILOT = "teal_vibrant"
  }
}
