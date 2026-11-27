/*
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.components.settings.app.appearance

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.os.Process
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.signal.appsettings.appearance.AppearanceSettingsTestTags
import org.signal.appsettings.routes.AppSettingsRoute
import org.signal.core.ui.util.ThemeUtil
import org.thoughtcrime.securesms.components.settings.app.AppSettingsNavigationActivity
import org.thoughtcrime.securesms.flags.CollieFlags
import org.thoughtcrime.securesms.keyvalue.SettingsValues
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.testing.SignalActivityRule
import org.thoughtcrime.securesms.util.ThemePalettes
import com.google.android.material.R as MaterialR

/** Run first with appearanceColdStartStage=write, then in a fresh process with appearanceColdStartStage=verify. */
@RunWith(AndroidJUnit4::class)
class AppearanceColdStartTest {
  @get:Rule
  val compose = createEmptyComposeRule()

  @Test
  fun restoresDarkPresetAndPureBlackInANewProcess() {
    assumeTrue(CollieFlags.userTheming)
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val stage = InstrumentationRegistry.getArguments().getString("appearanceColdStartStage")
    assumeTrue("Requires two explicit runs that keep package data", stage == "write" || stage == "verify")

    val context = instrumentation.targetContext
    val checkpoint = context.getSharedPreferences("appearance_cold_start_test", Context.MODE_PRIVATE)
    val settings = SignalStore.settings
    val paletteId = "plum_expressive"
    val currentPid = Process.myPid()
    println("Appearance cold start stage=$stage pid=$currentPid")

    if (stage == "write") {
      // Supply a local synthetic account for the real Settings activity.
      val writeSettings = object : Statement() {
        override fun evaluate() {
          assertTrue(
            checkpoint.edit()
              .putInt("pid", currentPid)
              .putString("theme", settings.theme.name)
              .putBoolean("system", settings.isDynamicColorsEnabled)
              .putString("palette", ThemePalettes.storedId(context))
              .putBoolean("pureBlack", settings.isPureBlackEnabled)
              .commit()
          )
          instrumentation.runOnMainSync {
            settings.setTheme(SettingsValues.Theme.DARK, false)
            settings.setColorPalette(paletteId)
            settings.isPureBlackEnabled = true
          }
        }
      }
      SignalActivityRule(othersCount = 0, networkEnabled = false).apply(writeSettings, Description.EMPTY).evaluate()
      return
    }

    assertTrue("Run the write stage before this stage", checkpoint.contains("pid"))
    try {
      assertNotEquals("Expected a new app process", checkpoint.getInt("pid", 0), currentPid)
      assertEquals(SettingsValues.Theme.DARK, settings.theme)
      assertEquals(paletteId, settings.colorPalette)
      assertTrue(settings.isPureBlackEnabled)

      val intent = AppSettingsNavigationActivity.createIntent(context, AppSettingsRoute.AppearanceRoute.Appearance)
      ActivityScenario.launch<AppSettingsNavigationActivity>(intent).use { scenario ->
        scenario.onActivity { activity ->
          assertEquals(Configuration.UI_MODE_NIGHT_YES, activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK)
          assertEquals(Color.BLACK, ThemeUtil.getThemedColor(activity, MaterialR.attr.colorSurface))
        }
        val scroller = compose.onNodeWithTag(AppearanceSettingsTestTags.SCROLLER)
        val preset = AppearanceSettingsTestTags.paletteOption(paletteId)
        scroller.performScrollToNode(hasTestTag(preset))
        compose.onNodeWithTag(preset).assertIsSelected()
        val pureBlack = AppearanceSettingsTestTags.ROW_PURE_BLACK
        scroller.performScrollToNode(hasTestTag(pureBlack))
        compose.onNode(isToggleable() and hasAnyAncestor(hasTestTag(pureBlack)), useUnmergedTree = true).assertIsOn()
      }
    } finally {
      val previousTheme = SettingsValues.Theme.valueOf(checkNotNull(checkpoint.getString("theme", null)))
      val previousSystem = checkpoint.getBoolean("system", false)
      instrumentation.runOnMainSync {
        settings.setTheme(previousTheme, previousSystem)
        ThemePalettes.setStoredId(context, checkpoint.getString("palette", null))
        settings.isPureBlackEnabled = checkpoint.getBoolean("pureBlack", false)
      }
      checkpoint.edit().clear().commit()
    }
  }
}
