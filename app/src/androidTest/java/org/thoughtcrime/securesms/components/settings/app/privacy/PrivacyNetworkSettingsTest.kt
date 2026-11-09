/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.components.settings.app.privacy

import android.view.WindowManager
import android.os.SystemClock
import android.graphics.Bitmap
import android.graphics.Color
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.signal.appsettings.R as AppSettingsR
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.components.settings.ClickPreference
import org.thoughtcrime.securesms.components.settings.PreferenceModel
import org.thoughtcrime.securesms.components.settings.SectionHeaderPreference
import org.thoughtcrime.securesms.components.settings.app.AppSettingsActivity
import org.thoughtcrime.securesms.registration.ui.RegistrationNetworkSettingsActivity
import org.thoughtcrime.securesms.testing.DisableAnimationsRule
import org.thoughtcrime.securesms.testing.SignalActivityRule
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.util.TextSecurePreferences
import org.thoughtcrime.securesms.util.adapter.mapping.MappingAdapter
import java.io.File
import java.util.concurrent.TimeUnit
import androidx.test.espresso.NoMatchingViewException
import org.hamcrest.CoreMatchers.anyOf
import org.hamcrest.CoreMatchers.equalTo
import org.junit.Assert.assertNotNull

@RunWith(androidx.test.ext.junit.runners.AndroidJUnit4::class)
class PrivacyNetworkSettingsTest {

  @get:Rule
  val harness = SignalActivityRule(othersCount = 0)

  @get:Rule
  val animationsRule = DisableAnimationsRule()

  private val context = InstrumentationRegistry.getInstrumentation().targetContext

  @Test
  fun privacyNetworkControlsAreFirstAndRunThroughTheExistingProxyEditorAndConnectionCheck() = withScreenSecurityTemporarilyDisabled {
    ActivityScenario.launch<AppSettingsActivity>(AppSettingsActivity.privacy(context)).use { scenario ->
      onView(withText(R.string.network_privacy_inline__section_title)).check(matches(isDisplayed()))
      onView(withText(R.string.preferences_network__check_connection)).check(matches(isDisplayed()))
      onView(withText(R.string.network_privacy_inline__not_checked)).check(matches(isDisplayed()))
      onView(withText(AppSettingsR.string.preferences_use_proxy)).check(matches(isDisplayed()))
      onView(withText(R.string.network_privacy_inline__proxy_not_configured)).check(matches(isDisplayed()))

      scenario.onActivity { activity ->
        val adapter = activity.findViewById<RecyclerView>(R.id.recycler).adapter as MappingAdapter
        val titles = adapter.currentList.mapNotNull { model ->
          (model as? PreferenceModel<*>)?.title?.resolve(activity)?.toString()
        }
        assertEquals(
          listOf(
            context.getString(R.string.network_privacy_inline__section_title),
            context.getString(R.string.preferences_network__check_connection),
            context.getString(AppSettingsR.string.preferences_use_proxy),
            context.getString(AppSettingsR.string.PrivacySettingsFragment__messaging)
          ),
          titles.take(4)
        )
        assertTrue(adapter.currentList[0] is SectionHeaderPreference)
        assertTrue(adapter.currentList[1] is ClickPreference)
        assertTrue(adapter.currentList[2] is ClickPreference)
      }

      capturePrivacyScreenshot(scenario, "privacy-network-idle.png")

      onView(withText(AppSettingsR.string.preferences_use_proxy)).perform(click())
      onView(withText(R.string.preferences_network__connection)).check(matches(isDisplayed()))
      onView(withText(AppSettingsR.string.preferences_use_proxy)).perform(click())
      onView(withText(R.string.arrays__socks5)).inRoot(isDialog()).perform(click())
      onView(withText(R.string.preferences_network__host)).check(matches(isDisplayed()))
      onView(withText(R.string.preferences_network__port)).check(matches(isDisplayed()))

      pressBack()
      onView(withText(R.string.preferences_network__check_connection)).check(matches(isDisplayed()))
      onView(withText(R.string.preferences_network__check_connection)).perform(click())
      awaitConnectionResult()
      capturePrivacyScreenshot(scenario, "privacy-network-result.png")

      onView(withText(AppSettingsR.string.preferences_use_proxy)).perform(click())
      onView(withText(AppSettingsR.string.preferences_use_proxy)).perform(click())
      onView(withText(R.string.arrays__none)).inRoot(isDialog()).perform(click())
      TextSecurePreferences.setStringPreference(context, TextSecurePreferences.PROXY_TYPE, "none")
      pressBack()
    }
  }

  @Test
  fun registrationNetworkSettingsActivityStillShowsTheStandaloneProxyControls() {
    ActivityScenario.launch<RegistrationNetworkSettingsActivity>(RegistrationNetworkSettingsActivity.createIntent(context)).use {
      onView(withText(R.string.preferences_network__check_connection)).check(matches(isDisplayed()))
      onView(withText(AppSettingsR.string.preferences_use_proxy)).check(matches(isDisplayed()))
    }
  }

  private fun awaitConnectionResult() {
    val succeeded = context.getString(R.string.network_privacy_inline__test_succeeded)
    val failed = context.getString(R.string.network_privacy_inline__test_failed)
    val deadline = SystemClock.elapsedRealtime() + TimeUnit.SECONDS.toMillis(15)

    while (SystemClock.elapsedRealtime() < deadline) {
      try {
        onView(withText(anyOf(equalTo(succeeded), equalTo(failed)))).check(matches(isDisplayed()))
        return
      } catch (_: NoMatchingViewException) {
        Thread.sleep(200)
      } catch (_: AssertionError) {
        Thread.sleep(200)
      }
    }

    fail("The connection check did not report a result within 15 seconds")
  }

  private fun capturePrivacyScreenshot(scenario: ActivityScenario<AppSettingsActivity>, name: String) {
    var hadSecureFlag = false
    scenario.onActivity { activity ->
      hadSecureFlag = activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
      if (hadSecureFlag) {
        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
      }
    }

    try {
      InstrumentationRegistry.getInstrumentation().waitForIdleSync()
      InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(500, 5_000)
      val bitmap: Bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
      assertNotNull("Headless screenshot should be captured", bitmap)
      var nonBlackSamples = 0
      for (y in 0 until bitmap.height step 4) {
        for (x in 0 until bitmap.width step 4) {
          if (bitmap.getPixel(x, y) != Color.BLACK) {
            nonBlackSamples++
          }
        }
      }
      assertTrue("Headless screenshot should include rendered app content", nonBlackSamples > 100)
      val screenshot = File(context.getExternalFilesDir(null), name)
      screenshot.outputStream().use { output ->
        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
      }
      bitmap.recycle()
    } finally {
      if (hadSecureFlag) {
        scenario.onActivity { activity -> activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) }
      }
    }
  }

  private fun <T> withScreenSecurityTemporarilyDisabled(block: () -> T): T {
    val wasScreenSecurityEnabled = SignalStore.settings.isScreenSecurityEnabled
    SignalStore.settings.isScreenSecurityEnabled = false

    try {
      return block()
    } finally {
      SignalStore.settings.isScreenSecurityEnabled = wasScreenSecurityEnabled
    }
  }
}
