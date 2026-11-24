package org.thoughtcrime.securesms.jobs

import android.Manifest
import android.os.ParcelFileDescriptor
import android.app.Notification
import android.app.NotificationManager
import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.notifications.NotificationIds
import org.thoughtcrime.securesms.testing.SignalActivityRule

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 35)
class LinkedDeviceAlertProofTest {
  @get:Rule val harness = SignalActivityRule(othersCount = 0, networkEnabled = false)

  @Test
  fun showsEarlyAlertAndReminderInTheNotificationShade() {
    val context = harness.context
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val automation = instrumentation.uiAutomation
    val notifications = context.getSystemService(NotificationManager::class.java)
    val createdAt = System.currentTimeMillis()
    automation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
    try {
      postAndCapture(createdAt, true, R.string.NewLinkedDeviceNotification__you_linked_new_device, "linked-device-early")
      notifications.cancel(NotificationIds.NEW_LINKED_DEVICE)
      assertEquals(2 to createdAt, SignalStore.misc.takeNewLinkedDeviceWarning())

      postAndCapture(createdAt, false, R.string.NewLinkedDeviceNotification__reminder_new_device_linked, "linked-device-reminder")
      assertEquals(2 to createdAt, SignalStore.misc.takeNewLinkedDeviceWarning())
    } finally {
      notifications.cancel(NotificationIds.NEW_LINKED_DEVICE)
      automation.executeShellCommand("cmd statusbar collapse").close()
    }
  }

  private fun postAndCapture(createdAt: Long, early: Boolean, titleId: Int, filename: String) {
    val context = harness.context
    val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
    val notifications = context.getSystemService(NotificationManager::class.java)
    val title = context.getString(titleId)
    NewLinkedDeviceNotificationJob(2, createdAt, early).apply { setContext(context) }.run()

    assertTrue("Android did not post the expected notification", await {
      notifications.activeNotifications.any {
        it.id == NotificationIds.NEW_LINKED_DEVICE &&
          it.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() == title
      }
    })
    ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("cmd statusbar expand-notifications")).use {
      it.readBytes()
    }
    // Android's shade can render the notification without exposing its title as a visible node.
    // Assert the posted title above and retain the rendered shade for visual verification.
    SystemClock.sleep(850)
    automation.waitForIdle(500, 6_000)

    val bitmap = checkNotNull(automation.takeScreenshot())
    val resolver = context.contentResolver
    try {
      val uri = checkNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, "$filename.png")
        put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
        put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/collie-settings-navigation/")
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

  private fun await(condition: () -> Boolean): Boolean {
    val deadline = SystemClock.elapsedRealtime() + 10_000
    while (!condition()) {
      if (SystemClock.elapsedRealtime() >= deadline) return false
      SystemClock.sleep(100)
    }
    return true
  }
}
