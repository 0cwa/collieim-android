/*
 * Copyright 2026 Collie
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms

import android.app.Activity
import android.view.View
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.thoughtcrime.securesms.components.settings.app.AppSettingsActivity
import org.thoughtcrime.securesms.registration.ui.RegistrationNetworkSettingsActivity
import org.thoughtcrime.securesms.testing.SignalActivityRule
import kotlin.math.abs

/** Screens hosted in edge-to-edge windows must keep their content out of the system bars. */
@RunWith(AndroidJUnit4::class)
class SystemBarClearanceTest {

  @get:Rule
  val harness = SignalActivityRule(othersCount = 0)

  @Test
  fun databaseEncryptionScreenClearsStatusBarAndCentersContent() {
    ActivityScenario.launch(PassphraseCreateActivity::class.java).use { scenario ->
      InstrumentationRegistry.getInstrumentation().waitForIdleSync()
      scenario.onActivity { activity ->
        val title = activity.findViewById<View>(R.id.blurb)
        val lastButton = activity.findViewById<View>(R.id.no_button)
        val bars = systemBars(title)

        assertClearsStatusBar(title, bars)

        val spaceAbove = screenTop(title) - bars.top
        val spaceBelow = activity.window.decorView.height - bars.bottom - (screenTop(lastButton) + lastButton.height)
        val density = activity.resources.displayMetrics.density
        assumeTrue("Content is taller than this screen, so it scrolls instead of centering", spaceBelow >= 20 * density)

        val tolerance = (2 * density).toInt()
        assertTrue("Content is not vertically centered: above=$spaceAbove, below=$spaceBelow", abs(spaceAbove - spaceBelow) <= tolerance)
      }
    }
  }

  @Test
  fun registrationNetworkSettingsToolbarClearsStatusBar() {
    ActivityScenario.launch(RegistrationNetworkSettingsActivity::class.java).use { scenario ->
      assertToolbarClearsStatusBar(scenario)
    }
  }

  @Test
  fun appSettingsNetworkToolbarClearsStatusBar() {
    ActivityScenario.launch<AppSettingsActivity>(AppSettingsActivity.proxy(harness.context)).use { scenario ->
      assertToolbarClearsStatusBar(scenario)
    }
  }

  private fun <T : Activity> assertToolbarClearsStatusBar(scenario: ActivityScenario<T>) {
    InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    scenario.onActivity { activity ->
      val toolbar = activity.findViewById<View>(R.id.toolbar)
      val bars = systemBars(toolbar)
      assertClearsStatusBar(toolbar, bars)
      assertEquals("Toolbar should sit directly below the status bar", bars.top, screenTop(toolbar))
    }
  }

  private fun assertClearsStatusBar(view: View, bars: Insets) {
    assertTrue("The window must report a status bar", bars.top > 0)
    assertTrue("View starts under the status bar: viewTop=${screenTop(view)}, statusBarBottom=${bars.top}", screenTop(view) >= bars.top)
  }

  private fun systemBars(view: View): Insets {
    return ViewCompat.getRootWindowInsets(view)!!.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
  }

  private fun screenTop(view: View): Int {
    val location = IntArray(2)
    view.getLocationOnScreen(location)
    return location[1]
  }
}
