package org.thoughtcrime.securesms.jobs

import android.app.PendingIntent
import androidx.core.app.NotificationCompat
import org.signal.core.util.PendingIntentFlags.cancelCurrent
import org.signal.core.util.ServiceUtil
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.components.settings.app.AppSettingsActivity
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.jobmanager.Job
import org.thoughtcrime.securesms.jobs.protos.NewLinkedDeviceNotificationJobData
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.notifications.NotificationChannels
import org.thoughtcrime.securesms.notifications.NotificationIds
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.util.DateUtils
import java.util.Locale
import kotlin.random.Random
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

/**
 * Notifies users shortly after a device is linked, then reminds them at the original randomized time.
 */
class NewLinkedDeviceNotificationJob private constructor(
  private val data: NewLinkedDeviceNotificationJobData,
  parameters: Parameters
) : Job(parameters) {
  companion object {
    const val KEY: String = "NewLinkedDeviceNotificationJob"
    private val TAG = Log.tag(NewLinkedDeviceNotificationJob::class.java)

    @JvmStatic
    fun enqueue(deviceId: Int, deviceCreatedAt: Long) {
      AppDependencies.jobManager.addAll(
        listOf(
          NewLinkedDeviceNotificationJob(deviceId, deviceCreatedAt, isEarlyNotification = true),
          NewLinkedDeviceNotificationJob(deviceId, deviceCreatedAt)
        )
      )
    }

    /**
     * The early alert waits 1 - 5 minutes; the reminder keeps the original 60 - 179 minute delay.
     */
    private fun getRandomDelay(isEarlyNotification: Boolean): Long {
      val delay = if (isEarlyNotification) Random.nextInt(1, 6) else Random.nextInt(60, 180)
      return delay.minutes.inWholeMilliseconds
    }
  }

  constructor(
    deviceId: Int,
    deviceCreatedAt: Long,
    isEarlyNotification: Boolean = false
  ) : this(
    NewLinkedDeviceNotificationJobData(deviceId, deviceCreatedAt, isEarlyNotification),
    Parameters.Builder()
      .setQueue(KEY)
      // Keep publication serialized, but never leave an early alert behind a delayed reminder.
      .setQueuePriority(if (isEarlyNotification) Parameters.PRIORITY_HIGH else Parameters.PRIORITY_DEFAULT)
      .setInitialDelay(getRandomDelay(isEarlyNotification))
      .setLifespan(7.days.inWholeMilliseconds)
      .setMaxAttempts(Parameters.UNLIMITED)
      .build()
  )

  override fun serialize(): ByteArray = data.encode()

  override fun getFactoryKey(): String = KEY

  override fun run(): Result {
    if (!Recipient.self().isRegistered) {
      Log.w(TAG, "Not registered")
      return Result.failure()
    }

    // Restore the in-app warning for each alert, even if the earlier alert was acknowledged.
    // Save before posting so an immediate tap can also detect a device that was since unlinked.
    SignalStore.misc.setNewLinkedDeviceWarning(data.deviceId, data.deviceCreatedAt)

    if (NotificationChannels.getInstance().areNotificationsEnabled()) {
      val pendingIntent = PendingIntent.getActivity(context, 0, AppSettingsActivity.linkedDevices(context), cancelCurrent())
      val title = if (data.isEarlyNotification) R.string.NewLinkedDeviceNotification__you_linked_new_device else R.string.NewLinkedDeviceNotification__reminder_new_device_linked
      val builder = NotificationCompat.Builder(context, NotificationChannels.getInstance().NEW_LINKED_DEVICE)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(context.getString(title))
        .setContentText(context.getString(R.string.NewLinkedDeviceNotification__a_new_device_was_linked, DateUtils.getDateTimeString(context, Locale.getDefault(), data.deviceCreatedAt)))
        .setContentIntent(pendingIntent)
        .setAutoCancel(true)

      ServiceUtil.getNotificationManager(context).notify(NotificationIds.NEW_LINKED_DEVICE, builder.build())
    }
    return Result.success()
  }

  override fun onFailure() = Unit

  class Factory : Job.Factory<NewLinkedDeviceNotificationJob?> {
    override fun create(parameters: Parameters, serializedData: ByteArray?): NewLinkedDeviceNotificationJob {
      val data = NewLinkedDeviceNotificationJobData.ADAPTER.decode(serializedData!!)
      return NewLinkedDeviceNotificationJob(data, parameters)
    }
  }
}
