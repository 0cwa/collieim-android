/*
 * Copyright 2026 Collie
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.components.settings.app.appearance

import android.app.Activity
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Process
import android.util.TypedValue
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.signal.appsettings.appearance.AppearanceSettingsTestTags
import org.signal.appsettings.home.AppSettingsTestTags
import org.signal.appsettings.routes.AppSettingsRoute
import org.signal.core.ui.util.ThemeUtil
import org.signal.remoteconfig.RemoteConfig
import org.thoughtcrime.securesms.MainActivity
import org.thoughtcrime.securesms.ScreenLockController
import org.thoughtcrime.securesms.components.settings.app.AppSettingsActivity
import org.thoughtcrime.securesms.components.settings.app.AppSettingsNavigationActivity
import org.thoughtcrime.securesms.keyvalue.SettingsValues
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.testing.DisableAnimationsRule
import org.thoughtcrime.securesms.testing.RawFlag
import org.thoughtcrime.securesms.testing.RemoteConfigForTest
import org.thoughtcrime.securesms.testing.SignalActivityRule
import org.thoughtcrime.securesms.util.DynamicTheme
import org.thoughtcrime.securesms.util.TextSecurePreferences
import java.io.File
import org.signal.appsettings.R as AppSettingsR
import org.signal.core.ui.R as CoreUiR

/** Exercises the real settings controls and a retained conversation overview in the same process. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 31)
@RemoteConfigForTest(rawFlags = [RawFlag(RemoteConfig.APP_SETTINGS_NAVIGATION_KEY, "false")])
class AppearanceThemePropagationTest {

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
  private var previousScreenSecurity = true
  private var previousBiometricLock = false
  private var previousAutoLock = false
  private var previousLockAtStart = false
  private var previousNightMode = AppCompatDelegate.MODE_NIGHT_UNSPECIFIED

  @Before
  fun savePreferences() {
    previousTheme = SignalStore.settings.theme
    previousDynamicColors = SignalStore.settings.isDynamicColorsEnabled
    previousScreenSecurity = SignalStore.settings.isScreenSecurityEnabled
    previousBiometricLock = TextSecurePreferences.isBiometricScreenLockEnabled(context)
    previousAutoLock = ScreenLockController.autoLock
    previousLockAtStart = ScreenLockController.lockScreenAtStart
    previousNightMode = AppCompatDelegate.getDefaultNightMode()

    // Screenshots are part of the regression evidence; restore security in tearDown.
    SignalStore.settings.isScreenSecurityEnabled = false
    TextSecurePreferences.setBiometricScreenLockEnabled(context, false)
    ScreenLockController.enableAutoLock(false)
    ScreenLockController.lockScreenAtStart = false
  }

  @After
  fun restorePreferences() {
    instrumentation.runOnMainSync {
      ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).toList().forEach(Activity::finish)
      SignalStore.settings.setTheme(previousTheme, previousDynamicColors)
      SignalStore.settings.isScreenSecurityEnabled = previousScreenSecurity
      TextSecurePreferences.setBiometricScreenLockEnabled(context, previousBiometricLock)
      ScreenLockController.enableAutoLock(previousAutoLock)
      ScreenLockController.lockScreenAtStart = previousLockAtStart
      AppCompatDelegate.setDefaultNightMode(previousNightMode)
    }
  }

  @Test
  fun dynamicColorsRoundTripFromLegacySettingsInLightMode() {
    assertTrue("Run dynamic-color coverage on a supported Android device", DynamicTheme.isDynamicColorsAvailable())
    assertDynamicColorsRoundTrip(SettingsValues.Theme.LIGHT, navigationSettings = false)
  }

  @Test
  fun dynamicColorsRoundTripFromNavigationSettingsInDarkMode() {
    assertTrue("Run dynamic-color coverage on a supported Android device", DynamicTheme.isDynamicColorsAvailable())
    assertDynamicColorsRoundTrip(SettingsValues.Theme.DARK, navigationSettings = true)
  }

  @Test
  fun lightDarkAndSystemSelectionsReachTheOverviewWithoutRestarting() {
    setInitialTheme(SettingsValues.Theme.LIGHT)
    val processId = Process.myPid()
    val application = context.applicationContext

    ActivityScenario.launch(MainActivity::class.java).use { main ->
      for (theme in listOf(SettingsValues.Theme.DARK, SettingsValues.Theme.LIGHT, SettingsValues.Theme.SYSTEM)) {
        openAppearance(main, navigationSettings = false)
        val previousSettings = awaitSettings()
        compose.onNodeWithTag(AppearanceSettingsTestTags.ROW_THEME).performClick()
        val label = when (theme) {
          SettingsValues.Theme.DARK -> AppSettingsR.string.preferences__dark_theme
          SettingsValues.Theme.LIGHT -> AppSettingsR.string.preferences__light_theme
          SettingsValues.Theme.SYSTEM -> AppSettingsR.string.preferences__system_default
        }
        compose.onNode(hasText(context.getString(label)) and hasAnyAncestor(isDialog())).performClick()
        compose.waitUntil(15_000) { SignalStore.settings.theme == theme }
        closeSettings(previousSettings)

        val nightMode = when (theme) {
          SettingsValues.Theme.DARK -> Configuration.UI_MODE_NIGHT_YES
          SettingsValues.Theme.LIGHT -> Configuration.UI_MODE_NIGHT_NO
          SettingsValues.Theme.SYSTEM -> Resources.getSystem().configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        }
        val activity = awaitOverview(dynamicColors = false, nightMode = nightMode)
        assertSame(application, activity.application)
        assertEquals(processId, Process.myPid())
        saveOverviewScreenshot(activity, "overview-${theme.name.lowercase()}", dynamicColors = false)
      }
      assertEquals(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM, AppCompatDelegate.getDefaultNightMode())
    }
  }

  private fun assertDynamicColorsRoundTrip(theme: SettingsValues.Theme, navigationSettings: Boolean) {
    setInitialTheme(theme)
    val processId = Process.myPid()
    val application = context.applicationContext
    val nightMode = if (theme == SettingsValues.Theme.DARK) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO

    ActivityScenario.launch(MainActivity::class.java).use { main ->
      var previousActivity = awaitOverview(dynamicColors = false, nightMode = nightMode)
      for (enabled in listOf(true, false)) {
        openAppearance(main, navigationSettings)
        val previousSettings = awaitSettings()
        compose.onNodeWithText(context.getString(AppSettingsR.string.preferences_appearance__dynamic_colors)).performClick()
        compose.waitUntil(15_000) { SignalStore.settings.isDynamicColorsEnabled == enabled }
        closeSettings(previousSettings)

        val activity = awaitOverview(dynamicColors = enabled, nightMode = nightMode)
        assertNotSame("Returning from Settings should rebuild the retained activity's palette", previousActivity, activity)
        assertSame(application, activity.application)
        assertEquals(processId, Process.myPid())
        saveOverviewScreenshot(activity, "overview-${theme.name.lowercase()}-dynamic-$enabled", enabled)
        previousActivity = activity
      }
    }
  }

  private fun setInitialTheme(theme: SettingsValues.Theme) {
    instrumentation.runOnMainSync {
      SignalStore.settings.setTheme(theme, false)
      DynamicTheme.setDefaultDayNightMode(context)
    }
  }

  private fun openAppearance(main: ActivityScenario<MainActivity>, navigationSettings: Boolean) {
    main.onActivity { activity ->
      activity.startActivity(
        if (navigationSettings) {
          AppSettingsNavigationActivity.createIntent(activity, AppSettingsRoute.AppearanceRoute.Appearance)
        } else {
          AppSettingsActivity.home(activity)
        }
      )
    }

    awaitSettings()
    if (!navigationSettings) {
      awaitComposeNode(AppSettingsTestTags.SCROLLER)
      compose.onNodeWithTag(AppSettingsTestTags.SCROLLER).performScrollToNode(hasTestTag(AppSettingsTestTags.ROW_APPEARANCE))
      compose.onNodeWithTag(AppSettingsTestTags.ROW_APPEARANCE).performClick()
    }
    awaitComposeNode(AppearanceSettingsTestTags.ROW_THEME)
    compose.onNodeWithTag(AppearanceSettingsTestTags.ROW_THEME).assertExists()
  }

  private fun awaitComposeNode(tag: String) {
    // Activity transitions can briefly leave no registered Compose root, especially after recreation.
    compose.waitUntil(15_000) {
      compose.onAllNodesWithTag(tag).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
    }
  }

  private fun awaitSettings(previous: Activity? = null): Activity {
    var result: Activity? = null
    compose.waitUntil(15_000) {
      instrumentation.runOnMainSync {
        result = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).singleOrNull {
          (it is AppSettingsActivity || it is AppSettingsNavigationActivity) && it !== previous && !it.isFinishing
        }
      }
      result != null
    }
    return checkNotNull(result)
  }

  private fun closeSettings(previous: Activity) {
    // Preference writes precede the host's asynchronous recreation. Return only after its replacement resumes.
    awaitSettings(previous)
    compose.waitForIdle()
    val settings = awaitSettings(previous)
    instrumentation.runOnMainSync { settings.finish() }
  }

  private fun awaitOverview(dynamicColors: Boolean, nightMode: Int): MainActivity {
    var result: MainActivity? = null
    compose.waitUntil(15_000) {
      instrumentation.runOnMainSync {
        result = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
          .filterIsInstance<MainActivity>()
          .singleOrNull()
          ?.takeIf { activity ->
            val value = TypedValue()
            activity.theme.resolveAttribute(CoreUiR.attr.dynamic_colors, value, true) &&
              (value.data != 0) == dynamicColors &&
              activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == nightMode
          }
      }
      result != null
    }
    return checkNotNull(result)
  }

  private fun saveOverviewScreenshot(activity: MainActivity, name: String, dynamicColors: Boolean) {
    compose.waitForIdle()
    val dark = activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    val expected = if (dynamicColors) {
      ThemeUtil.getThemedColor(activity, com.google.android.material.R.attr.colorSurface)
    } else {
      Color.parseColor(if (dark) "#24130D" else "#FFF8F0")
    }
    val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot()) { "Could not capture the overview" }
    try {
      val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
        ?.let(::File) ?: File(context.getExternalFilesDir(null), "theme-regression")
      assertTrue(output.isDirectory || output.mkdirs())
      File(output, "$name.png").outputStream().use { stream ->
        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
      }
      var matching = 0
      var sampled = 0
      for (y in 0 until bitmap.height step 2) {
        for (x in 0 until bitmap.width step 2) {
          sampled++
          if (bitmap.getPixel(x, y) == expected) matching++
        }
      }
      assertTrue("The overview should render the selected surface: $matching/$sampled matching pixels", matching > sampled / 10)
    } finally {
      bitmap.recycle()
    }
  }
}
