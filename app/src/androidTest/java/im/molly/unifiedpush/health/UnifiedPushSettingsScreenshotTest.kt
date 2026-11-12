/*
 * Copyright 2026 Collie
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package im.molly.unifiedpush.health

import android.content.Context
import androidx.test.core.app.ActivityScenario
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
import org.thoughtcrime.securesms.util.TextSecurePreferences
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
  fun settingsScreenShowsTheWarningAndTheTestButton() {
    assumeTrue(args.getString("ep_screenshot") != null)
    val before = TextSecurePreferences.isScreenSecurityEnabled(context)
    TextSecurePreferences.setScreenSecurityEnabled(context, false)
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
        Thread.sleep(3000)
        instrumentation.waitForIdleSync()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        FileOutputStream(File(context.getExternalFilesDir(null), args.getString("ep_screenshot")!!)).use {
          bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, it)
        }
      }
    } finally {
      TextSecurePreferences.setScreenSecurityEnabled(context, before)
      values.health.failures = 0
      values.health.fallback = false
    }
  }
}
