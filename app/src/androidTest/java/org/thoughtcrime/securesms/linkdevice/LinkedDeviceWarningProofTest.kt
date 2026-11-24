package org.thoughtcrime.securesms.linkdevice

import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Environment
import android.provider.MediaStore
import android.view.WindowManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.components.settings.app.AppSettingsActivity
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.testing.SignalActivityRule

/** Offline device proof that an unlinked-device warning follows the active dialog. */
@RunWith(AndroidJUnit4::class)
class LinkedDeviceWarningProofTest {
  @get:Rule val harness = SignalActivityRule(othersCount = 0, networkEnabled = false)
  @get:Rule val compose = createComposeRule()

  @Test
  fun unlinkedWarningWaitsForTheActiveDialog() {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val context = instrumentation.targetContext
    val before = SignalStore.settings.isScreenSecurityEnabled
    SignalStore.settings.isScreenSecurityEnabled = false
    try {
      ActivityScenario.launch<AppSettingsActivity>(AppSettingsActivity.linkedDevices(context)).use { scenario ->
        scenario.onActivity { activity ->
          activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
          val model = ViewModelProvider(activity)[LinkDeviceViewModel::class.java]
          model.onSyncErrorContactSupport()
          SignalStore.misc.newLinkedDeviceId = 7
          SignalStore.misc.newLinkedDeviceCreatedTime = System.currentTimeMillis()
          val checkDevices = LinkDeviceViewModel::class.java.getDeclaredMethod("checkForNewDevice", List::class.java)
          checkDevices.isAccessible = true
          checkDevices.invoke(model, emptyList<Any>())
        }
        val warning = context.getString(R.string.LinkDeviceFragment__device_unlinked)
        compose.onNodeWithText(context.getString(R.string.LinkDeviceFragment__submit_debug_log)).assertIsDisplayed()
        compose.onNodeWithText(warning).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.LinkDeviceFragment__cancel)).performClick()
        compose.onNodeWithText(warning).assertIsDisplayed()
        compose.waitUntil(15_000) {
          var ownsFocusedWindow = false
          instrumentation.runOnMainSync {
            ownsFocusedWindow = android.view.inspector.WindowInspector.getGlobalWindowViews().any {
              it.context.packageName == context.packageName && it.isShown && it.hasWindowFocus()
            }
          }
          ownsFocusedWindow
        }
        instrumentation.uiAutomation.waitForIdle(500, 5_000)
        capture("link-device-deferred-warning.png")
      }
    } finally {
      SignalStore.settings.isScreenSecurityEnabled = before
    }
  }

  private fun capture(name: String) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
    try {
      val resolver = instrumentation.targetContext.contentResolver
      val uri = checkNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
        put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/collie-settings-navigation/")
        put(MediaStore.MediaColumns.IS_PENDING, 1)
      }))
      checkNotNull(resolver.openOutputStream(uri)).use {
        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
      }
      resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
    } finally {
      bitmap.recycle()
    }
  }
}
