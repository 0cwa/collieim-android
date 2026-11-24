package org.thoughtcrime.securesms.jobs

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.spyk
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.signal.core.util.ServiceUtil
import org.signal.core.util.logging.Log
import org.signal.remoteconfig.RemoteConfig
import org.signal.storage.keyvalue.KeyValueDataSet
import org.signal.storage.keyvalue.KeyValueStore
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.components.settings.app.AppSettingsActivity
import org.thoughtcrime.securesms.keyvalue.MiscellaneousValues
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.notifications.NotificationChannels
import org.thoughtcrime.securesms.notifications.NotificationIds
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.testutil.MockSignalStoreRule
import org.thoughtcrime.securesms.testutil.SnapshotKeyValuePersistentStorage
import org.thoughtcrime.securesms.testutil.SystemOutLogger
import org.thoughtcrime.securesms.util.DateUtils
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [35], qualifiers = "en")
class NewLinkedDeviceNotificationJobTest {

  @get:Rule
  val signalStore = MockSignalStoreRule()

  private val context: Application get() = ApplicationProvider.getApplicationContext()
  private val manager: NotificationManager get() = context.getSystemService(NotificationManager::class.java)
  private lateinit var channels: NotificationChannels
  private lateinit var self: Recipient
  private lateinit var misc: MiscellaneousValues

  @Before
  fun setUp() {
    Log.initialize(SystemOutLogger())
    mockkObject(Recipient.Companion, RemoteConfig)
    self = mockk()
    every { Recipient.self() } returns self
    every { self.isRegistered } returns true
    every { RemoteConfig.useAppSettingsNavigation } returns false
    val seededStore = KeyValueDataSet().apply { putInteger("misc.first_install_version", 1) }
    misc = MiscellaneousValues(KeyValueStore(SnapshotKeyValuePersistentStorage(seededStore), Runnable::run), context)
    every { SignalStore.misc } returns misc

    mockkStatic(NotificationChannels::class)
    every { NotificationChannels.supported() } returns false
    // Keep the real channel constants without initializing unrelated app dependencies or channels.
    channels = spyk(
      NotificationChannels::class.java.getDeclaredConstructor(Application::class.java).apply { isAccessible = true }.newInstance(context)
    )
    every { NotificationChannels.getInstance() } returns channels
    every { channels.areNotificationsEnabled() } returns true
    manager.createNotificationChannel(NotificationChannel(channels.NEW_LINKED_DEVICE, "Linked devices", NotificationManager.IMPORTANCE_HIGH))
    manager.cancelAll()
  }

  @After
  fun tearDown() {
    manager.cancelAll()
    unmockkStatic(NotificationChannels::class)
    unmockkObject(Recipient.Companion, RemoteConfig)
  }

  @Test
  fun `early alert keeps the existing message and opens linked devices`() {
    assertTrue(runNotification(early = true))

    val notification = requireNotNull(shadowOf(manager).getNotification(NotificationIds.NEW_LINKED_DEVICE))
    assertEquals(context.getString(R.string.NewLinkedDeviceNotification__you_linked_new_device), notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
    assertEquals(expectedBody(), notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
    assertEquals(channels.NEW_LINKED_DEVICE, notification.channelId)
    assertTrue(notification.flags and Notification.FLAG_AUTO_CANCEL != 0)
    assertFalse(notification.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
    assertEquals(DEVICE_ID, misc.newLinkedDeviceId)
    assertEquals(CREATED_AT, misc.newLinkedDeviceCreatedTime)

    val expectedIntent = AppSettingsActivity.linkedDevices(context)
    val actualIntent = shadowOf(notification.contentIntent).savedIntent
    assertTrue(expectedIntent.filterEquals(actualIntent))
    assertEquals(expectedIntent.extras!!.keySet(), actualIntent.extras!!.keySet())
    for (key in expectedIntent.extras!!.keySet()) {
      @Suppress("DEPRECATION")
      assertEquals(expectedIntent.extras!!.get(key), actualIntent.extras!!.get(key))
    }
  }

  @Test
  fun `reminder replaces the earlier notification with explicit reminder wording and can alert again`() {
    assertTrue(runNotification(early = true))
    assertTrue(runNotification(early = false))

    val notification = requireNotNull(shadowOf(manager).getNotification(NotificationIds.NEW_LINKED_DEVICE))
    assertEquals(1, shadowOf(manager).allNotifications.size)
    assertEquals("Reminder: A new device was linked", notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
    assertEquals(expectedBody(), notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
    assertFalse(notification.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
    assertNotNull(notification.contentIntent)
  }

  @Test
  fun `dismissing or opening the early alert does not consume the later reminder`() {
    assertTrue(runNotification(early = true))
    manager.cancel(NotificationIds.NEW_LINKED_DEVICE)
    // Linked devices consumes the pair atomically when its initial device list is loaded.
    assertEquals(DEVICE_ID to CREATED_AT, misc.takeNewLinkedDeviceWarning())

    assertTrue(runNotification(early = false))

    val notification = requireNotNull(shadowOf(manager).getNotification(NotificationIds.NEW_LINKED_DEVICE))
    assertEquals("Reminder: A new device was linked", notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
    assertEquals(DEVICE_ID, misc.newLinkedDeviceId)
    assertEquals(CREATED_AT, misc.newLinkedDeviceCreatedTime)
  }

  @Test
  fun `disabled notifications still retain the linked device warning for both phases`() {
    every { channels.areNotificationsEnabled() } returns false

    for (early in listOf(true, false)) {
      misc.takeNewLinkedDeviceWarning()

      assertTrue(runNotification(early))

      assertTrue(shadowOf(manager).allNotifications.isEmpty())
      assertEquals(DEVICE_ID, misc.newLinkedDeviceId)
      assertEquals(CREATED_AT, misc.newLinkedDeviceCreatedTime)
    }
  }

  @Test
  fun `neither phase notifies or records a device after registration is lost`() {
    every { self.isRegistered } returns false

    for (early in listOf(true, false)) {
      val job = NewLinkedDeviceNotificationJob(DEVICE_ID, CREATED_AT, isEarlyNotification = early).apply { setContext(context) }
      assertTrue(job.run().isFailure)
    }

    assertTrue(shadowOf(manager).allNotifications.isEmpty())
    assertEquals(0, misc.newLinkedDeviceId)
    assertEquals(0L, misc.newLinkedDeviceCreatedTime)
  }

  @Test
  fun `device identity and creation time are saved before the notification can be tapped`() {
    val recordingManager = mockk<NotificationManager>()
    every { recordingManager.notify(NotificationIds.NEW_LINKED_DEVICE, any()) } answers {
      assertEquals(DEVICE_ID, misc.newLinkedDeviceId)
      assertEquals(CREATED_AT, misc.newLinkedDeviceCreatedTime)
    }
    mockkStatic(ServiceUtil::class)
    try {
      every { ServiceUtil.getNotificationManager(context) } returns recordingManager

      assertTrue(runNotification(early = true))

      verify(exactly = 1) { recordingManager.notify(NotificationIds.NEW_LINKED_DEVICE, any()) }
    } finally {
      unmockkStatic(ServiceUtil::class)
    }
  }

  private fun runNotification(early: Boolean): Boolean {
    return NewLinkedDeviceNotificationJob(DEVICE_ID, CREATED_AT, isEarlyNotification = early).apply { setContext(context) }.run().isSuccess
  }

  private fun expectedBody(): String {
    return context.getString(
      R.string.NewLinkedDeviceNotification__a_new_device_was_linked,
      DateUtils.getDateTimeString(context, Locale.getDefault(), CREATED_AT)
    )
  }

  companion object {
    private const val DEVICE_ID = 2
    private const val CREATED_AT = 1_795_353_600_000L
  }
}
