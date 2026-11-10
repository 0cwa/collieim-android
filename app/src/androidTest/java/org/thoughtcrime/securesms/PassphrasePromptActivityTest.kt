/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms

import android.view.View
import androidx.appcompat.R as AppCompatR
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class PassphrasePromptActivityTest {

  @Test
  fun promptContentClearsActionBarWithoutAccumulatingMargin() {
    ActivityScenario.launch(PassphrasePromptActivity::class.java).use { scenario ->
      val originalClearance = assertPromptClearsActionBar(scenario)

      scenario.moveToState(Lifecycle.State.CREATED)
      scenario.moveToState(Lifecycle.State.RESUMED)
      assertEquals("Resuming must not move the form", originalClearance, assertPromptClearsActionBar(scenario))

      scenario.recreate()
      assertEquals("Recreating must not move the form", originalClearance, assertPromptClearsActionBar(scenario))
    }
  }

  private fun assertPromptClearsActionBar(scenario: ActivityScenario<PassphrasePromptActivity>): Int {
    InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    var clearance = 0
    scenario.onActivity { activity ->
      val header = activity.findViewById<View>(R.id.header_text)
      val actionBar = activity.findViewById<View>(AppCompatR.id.action_bar_container)
      val root = activity.findViewById<View>(R.id.password_auth_container)

      val headerLocation = IntArray(2)
      val actionBarLocation = IntArray(2)
      val rootLocation = IntArray(2)
      header.getLocationOnScreen(headerLocation)
      actionBar.getLocationOnScreen(actionBarLocation)
      root.getLocationOnScreen(rootLocation)

      val actionBarBottom = actionBarLocation[1] + actionBar.height
      clearance = rootLocation[1] - actionBarBottom
      assertTrue("Action bar must be laid out", actionBar.height > 0)
      assertTrue(
        "Unlock form starts behind the action bar: headerTop=${headerLocation[1]}, actionBarBottom=$actionBarBottom, rootTop=${rootLocation[1]}",
        clearance >= 0
      )
      val expectedClearance = (8 * activity.resources.displayMetrics.density).roundToInt()
      assertEquals("Unlock form should preserve its 8dp XML spacing below the action bar", expectedClearance, clearance)
      assertTrue(
        "Unlock form header overlaps the action bar: headerTop=${headerLocation[1]}, actionBarBottom=$actionBarBottom",
        headerLocation[1] >= actionBarBottom
      )
    }
    return clearance
  }
}
