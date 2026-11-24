/*
 * Copyright 2026 Collie
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package im.molly.unifiedpush.health

import android.content.Context
import android.view.WindowManager
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModelProvider
import im.molly.unifiedpush.components.settings.app.notifications.UnifiedPushSettingsFragment
import im.molly.unifiedpush.components.settings.app.notifications.UnifiedPushSettingsViewModel
import im.molly.unifiedpush.enrollment.EnrollmentExporter
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.components.settings.app.AppSettingsActivity
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.testing.SignalActivityRule
import java.io.File
import java.io.FileOutputStream

/**
 * Opens the UnifiedPush settings screen in the warning state and saves a screenshot to the app's external files
 * directory (argument `ep_screenshot`). Screen security is turned off for this test only and restored after.
 */
@RunWith(AndroidJUnit4::class)
class UnifiedPushSettingsScreenshotTest {

  @get:Rule
  val harness = SignalActivityRule(othersCount = 0)

  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val context: Context = instrumentation.targetContext
  private val args = InstrumentationRegistry.getArguments()

  @Test
  fun settingsOffersExplicitImportAndRecoveryModes() {
    val screenshotName = args.getString("ep_screenshot")
    assumeTrue(screenshotName != null)
    val before = SignalStore.settings.isScreenSecurityEnabled
    SignalStore.settings.isScreenSecurityEnabled = false
    val values = SignalStore.unifiedpush
    values.enabled = true
    values.health.failures = 2
    values.health.fallback = true
    values.health.lastResult = PushHealth.Hop.COLLIESOCKET_OR_PROVIDER.code
    values.health.lastResultAt = System.currentTimeMillis() / 1000
    try {
      ActivityScenario.launch<AppSettingsActivity>(AppSettingsActivity.notifications(context)).use { scenario ->
        scenario.onActivity {
          (it.supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as androidx.navigation.fragment.NavHostFragment)
            .navController.navigate(R.id.notificationUnifiedPushFragment)
        }
        instrumentation.waitForIdleSync()
        onView(withText(R.string.UnifiedPushSettingsFragment__export_enrollment))
          .check(matches(isDisplayed()))
          .perform(click())
        onView(withText(R.string.UnifiedPushSettingsFragment__export_new_intent)).check(matches(isDisplayed()))
        onView(withText(R.string.UnifiedPushSettingsFragment__export_recovery_intent)).check(matches(isDisplayed()))

        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        try {
          FileOutputStream(File(context.getExternalFilesDir(null), screenshotName!!)).use {
            check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
          }
        } finally {
          bitmap.recycle()
        }

        onView(withText(R.string.UnifiedPushSettingsFragment__export_recovery_intent)).perform(click())
        onView(withText(R.string.UnifiedPushSettingsFragment__export_recovery_title)).check(matches(isDisplayed()))
        onView(withText(R.string.UnifiedPushSettingsFragment__export_recovery_message)).check(matches(isDisplayed()))
      }
    } finally {
      SignalStore.settings.isScreenSecurityEnabled = before
      values.health.failures = 0
      values.health.fallback = false
    }
  }

  @Test
  fun exportResultShowsFullCleanupAndCollisionWarnings() {
    val screenshotPrefix = args.getString("ep_export_screenshot_prefix")
    assumeTrue(screenshotPrefix != null)
    val before = SignalStore.settings.isScreenSecurityEnabled
    SignalStore.settings.isScreenSecurityEnabled = false
    try {
      ActivityScenario.launch<AppSettingsActivity>(AppSettingsActivity.notifications(context)).use { scenario ->
        scenario.onActivity { activity ->
          val host = activity.supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as androidx.navigation.fragment.NavHostFragment
          host.navController.navigate(R.id.notificationUnifiedPushFragment)
        }
        instrumentation.waitForIdleSync()
        onView(withText(R.string.UnifiedPushSettingsFragment__export_enrollment)).check(matches(isDisplayed()))

        val file = File(context.getExternalFilesDir(null), EnrollmentExporter.FILE_NAME).absolutePath
        val messages = mapOf(
          "success" to context.getString(R.string.UnifiedPushSettingsFragment__export_enrollment_done, file),
          "collision" to context.getString(R.string.UnifiedPushSettingsFragment__export_enrollment_done_collides, file, 2L, 3L),
        )
        val secureFlag = WindowManager.LayoutParams.FLAG_SECURE
        for ((name, message) in messages) {
          // Check restoration with capture protection enabled, then recreate for the evidence.
          SignalStore.settings.isScreenSecurityEnabled = true
          scenario.onActivity { activity ->
            val host = activity.supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as androidx.navigation.fragment.NavHostFragment
            val fragment = host.childFragmentManager.primaryNavigationFragment as UnifiedPushSettingsFragment
            val model = ViewModelProvider(fragment)[UnifiedPushSettingsViewModel::class.java]
            // Exercise the real settings presentation without provisioning a distributor or a relay.
            val field = model.javaClass.getDeclaredField("exportSuccess").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val result = field.get(model) as MutableLiveData<String?>
            result.value = message
          }
          onView(androidx.test.espresso.matcher.ViewMatchers.withId(android.R.id.message))
            .check(matches(withText(message)))
            .check(matches(isDisplayed()))
          scenario.recreate()
          onView(androidx.test.espresso.matcher.ViewMatchers.withId(android.R.id.message))
            .check(matches(withText(message)))
            .check(matches(isDisplayed()))
          scenario.onActivity { activity ->
            val host = activity.supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as androidx.navigation.fragment.NavHostFragment
            val fragment = host.childFragmentManager.primaryNavigationFragment as UnifiedPushSettingsFragment
            val result = fragment.childFragmentManager.findFragmentByTag(UnifiedPushSettingsFragment.ExportResultDialog.TAG)
              as UnifiedPushSettingsFragment.ExportResultDialog
            check(requireNotNull(result.requireDialog().window).attributes.flags and secureFlag != 0)
          }
          SignalStore.settings.isScreenSecurityEnabled = false
          scenario.recreate()
          onView(androidx.test.espresso.matcher.ViewMatchers.withId(android.R.id.message))
            .check(matches(withText(message)))
            .check(matches(isDisplayed()))
          val image = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
          try {
            FileOutputStream(File(context.getExternalFilesDir(null), "$screenshotPrefix-$name.png")).use { output ->
              check(image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output))
            }
          } finally {
            image.recycle()
          }
          onView(androidx.test.espresso.matcher.ViewMatchers.withId(android.R.id.button1)).perform(click())
        }
      }
    } finally {
      SignalStore.settings.isScreenSecurityEnabled = before
    }
  }
}
