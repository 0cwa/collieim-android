package org.thoughtcrime.securesms.apkupdate

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.ParcelFileDescriptor
import android.os.Process
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.navigation.fragment.NavHostFragment
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import im.molly.app.base.ApkInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.signal.appsettings.updates.AppUpdatesSettingsTestTags
import org.signal.core.util.eventbus.EventBus
import org.signal.core.ui.util.ThemeUtil
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.components.settings.app.AppSettingsActivity
import org.thoughtcrime.securesms.events.ApkUpdateEvent
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.testing.DisableAnimationsRule
import org.thoughtcrime.securesms.testing.SignalActivityRule
import org.thoughtcrime.securesms.util.DynamicTheme
import org.thoughtcrime.securesms.util.TextSecurePreferences
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import org.signal.core.ui.R as CoreUiR

@RunWith(AndroidJUnit4::class)
class CollieUpdateSettingsTest {
  @get:Rule val harness = SignalActivityRule(othersCount = 0)
  @get:Rule val animations = DisableAnimationsRule()
  @get:Rule val compose = createEmptyComposeRule()

  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val context = instrumentation.targetContext

  @Before
  fun stopIdleLauncherWhenRequestedByTheEmulatorFixture() {
    if (InstrumentationRegistry.getArguments().getString("collieTestStopLauncher") != "true") return
    val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
    val launcher = context.packageManager.resolveActivity(home, 0)?.activityInfo?.packageName ?: return
    if (launcher == "android" || launcher == context.packageName) return
    // The isolated emulator's idle launcher can leave an ANR dialog over the test app.
    ParcelFileDescriptor.AutoCloseInputStream(
      instrumentation.uiAutomation.executeShellCommand("am force-stop $launcher")
    ).use { it.readBytes() }
  }

  @Test
  fun androidVerifiesThePinnedRepositorySigner() {
    val descriptor = instrumentation.context.assets.open("apkupdate/collie-entry.jar").use {
      CollieUpdateIndex.readEntry(it, context.cacheDir, 0)
    }
    val result = instrumentation.context.assets.open("apkupdate/collie-index-v2.json").use {
      CollieUpdateIndex.readIndex(it, descriptor, 177704, 35, setOf("arm64-v8a"))
    }
    assertEquals(CollieUpdateIndex.Update(177705, "8.35.1-3-rc2"), result.update)
    assertThrows(IOException::class.java) {
      instrumentation.context.assets.open("apkupdate/collie-entry.jar").use {
        CollieUpdateIndex.verifyArchive(it, context.cacheDir, "00".repeat(32))
      }
    }
  }

  @Test
  fun updateSettingsShowTheReleaseAndOpenOnlyTheOfficialPage() {
    val values = SignalStore.apkUpdate
    val previousTheme = SignalStore.settings.theme
    val previousDynamicColors = SignalStore.settings.isDynamicColorsEnabled
    val previousSecurity = TextSecurePreferences.isScreenSecurityEnabled(context)
    val previousEnabled = TextSecurePreferences.isUpdateApkEnabled(context)
    val previousTimestamp = values.lastIndexTimestamp
    val previousCode = values.availableVersionCode
    val previousName = values.availableVersionName
    val previousFailed = values.lastCheckFailed
    val previousCheck = values.lastSuccessfulCheck
    val opened = AtomicReference<Intent>()
    val monitor = object : Instrumentation.ActivityMonitor() {
      override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
        if (intent.action == Intent.ACTION_VIEW) {
          opened.set(Intent(intent))
          return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
        }
        return null
      }
    }
    instrumentation.addMonitor(monitor)
    TextSecurePreferences.setScreenSecurityEnabled(context, false)
    TextSecurePreferences.setUpdateApkEnabled(context, true)
    values.setAvailableUpdate(previousTimestamp, ApkInfo.versionCode.toLong() + 1, "8.35.3")
    values.lastCheckFailed = false
    values.lastSuccessfulCheck = System.currentTimeMillis()
    try {
      assertTrue("Dynamic colors must be available on the test device", DynamicTheme.isDynamicColorsAvailable())
      instrumentation.runOnMainSync { SignalStore.settings.setTheme(previousTheme, false) }
      val processId = Process.myPid()
      ActivityScenario.launch<AppSettingsActivity>(AppSettingsActivity.home(context)).use { scenario ->
        scenario.onActivity { activity ->
          val host = activity.supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
          host.navController.navigate(R.id.appUpdatesFragment)
        }
        scrollTo(AppUpdatesSettingsTestTags.ROW_RELEASE)
        compose.onNodeWithText("Collie 8.35.3 is available").assertIsDisplayed()
        capture("collie-updates-available.png")

        var originalActivity: AppSettingsActivity? = null
        scenario.onActivity { activity ->
          originalActivity = activity
          assertFalse(ThemeUtil.getThemedBoolean(activity, CoreUiR.attr.dynamic_colors))
        }
        scenario.moveToState(Lifecycle.State.CREATED)
        instrumentation.runOnMainSync { SignalStore.settings.setTheme(previousTheme, true) }
        scenario.moveToState(Lifecycle.State.RESUMED)
        var recreatedActivity: AppSettingsActivity? = null
        compose.waitUntil(10_000) {
          scenario.onActivity { activity ->
            if (activity !== originalActivity && ThemeUtil.getThemedBoolean(activity, CoreUiR.attr.dynamic_colors)) {
              recreatedActivity = activity
            }
          }
          recreatedActivity != null
        }
        assertNotSame(originalActivity, recreatedActivity)
        assertEquals(processId, Process.myPid())
        scenario.onActivity { activity ->
          val host = activity.supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
          assertEquals(R.id.appUpdatesFragment, host.navController.currentDestination?.id)
          assertTrue(ThemeUtil.getThemedBoolean(activity, CoreUiR.attr.dynamic_colors))
        }
        scrollTo(AppUpdatesSettingsTestTags.ROW_RELEASE)
        compose.onNodeWithText("Collie 8.35.3 is available").assertIsDisplayed()
        compose.onNodeWithTag(AppUpdatesSettingsTestTags.ROW_RELEASE).performClick()
        compose.waitUntil(5000) { opened.get() != null }
        assertEquals("https://github.com/0cwa/collieim-android/releases/tag/v8.35.3", opened.get().dataString)
        assertEquals(Intent.ACTION_VIEW, opened.get().action)

        scrollTo(AppUpdatesSettingsTestTags.ROW_CHECK_FOR_UPDATES)
        // JVM coverage verifies enqueueing; this isolated native test does not start network work.
        compose.onNodeWithTag(AppUpdatesSettingsTestTags.ROW_CHECK_FOR_UPDATES).assertIsEnabled()
        values.lastCheckFailed = true
        EventBus.default.post(ApkUpdateEvent)
        compose.waitUntil(5000) {
          compose.onAllNodes(androidx.compose.ui.test.hasText("The last update check failed. Try again when you can connect.")).fetchSemanticsNodes().isNotEmpty()
        }
        scrollTo(AppUpdatesSettingsTestTags.ROW_CHECK_FOR_UPDATES)
        compose.onNodeWithText("The last update check failed. Try again when you can connect.").assertIsDisplayed()
        capture("collie-updates-failed.png")
      }
    } finally {
      instrumentation.runOnMainSync { SignalStore.settings.setTheme(previousTheme, previousDynamicColors) }
      instrumentation.removeMonitor(monitor)
      values.setAvailableUpdate(previousTimestamp, previousCode, previousName)
      values.lastCheckFailed = previousFailed
      values.lastSuccessfulCheck = previousCheck
      TextSecurePreferences.setUpdateApkEnabled(context, previousEnabled)
      TextSecurePreferences.setScreenSecurityEnabled(context, previousSecurity)
    }
  }

  private fun scrollTo(tag: String) {
    compose.onNodeWithTag(AppUpdatesSettingsTestTags.SCROLLER).performScrollToNode(hasTestTag(tag))
    compose.waitForIdle()
  }

  private fun capture(name: String) {
    compose.waitForIdle()
    instrumentation.waitForIdleSync()
    compose.waitUntil(5000) {
      instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString() == context.packageName
    }
    assertEquals("Capture must show the active app window", context.packageName,
      instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString())
    val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
    try {
      var nonBlack = 0
      for (y in 0 until bitmap.height step 8) {
        for (x in 0 until bitmap.width step 8) {
          if (bitmap.getPixel(x, y) != Color.BLACK) nonBlack++
        }
      }
      assertTrue("Screenshot must contain visible app content", nonBlack > 100)
      val additionalOutput = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let(::File)
      val directories = listOfNotNull(context.getExternalFilesDir(null), additionalOutput).distinct()
      for (directory in directories) {
        assertTrue("Could not create screenshot directory", directory.isDirectory || directory.mkdirs())
        File(directory, name).outputStream().use {
          assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
      }
    } finally {
      bitmap.recycle()
    }
  }
}
