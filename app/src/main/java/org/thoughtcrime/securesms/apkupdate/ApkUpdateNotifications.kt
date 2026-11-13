/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.apkupdate

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.signal.core.util.PendingIntentFlags
import org.signal.core.util.ServiceUtil
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.notifications.NotificationChannels
import org.thoughtcrime.securesms.notifications.NotificationIds

object ApkUpdateNotifications {
  /** A user action opens only the official release page. No APK is downloaded or installed here. */
  fun showUpdateAvailable(context: Context, versionName: String): Boolean {
    val manager = ServiceUtil.getNotificationManager(context)
    val channel = manager.getNotificationChannel(NotificationChannels.getInstance().APP_UPDATES)
    if (!manager.areNotificationsEnabled() || channel == null || channel.importance == NotificationManager.IMPORTANCE_NONE) return false
    val pendingIntent = PendingIntent.getActivity(
      context,
      0,
      Intent(Intent.ACTION_VIEW, Uri.parse(CollieUpdateIndex.releasePage(versionName))),
      PendingIntentFlags.immutable()
    )
    val notification = NotificationCompat.Builder(context, NotificationChannels.getInstance().APP_UPDATES)
      .setContentTitle(context.getString(R.string.ApkUpdateNotifications_prompt_install_title))
      .setContentText(context.getString(R.string.CollieUpdates__available_notification, versionName))
      .setSmallIcon(R.drawable.ic_notification)
      .setColor(ContextCompat.getColor(context, R.color.notification_background_ultramarine))
      .setPriority(NotificationCompat.PRIORITY_DEFAULT)
      .setCategory(NotificationCompat.CATEGORY_REMINDER)
      .setContentIntent(pendingIntent)
      .setAutoCancel(true)
      .setOnlyAlertOnce(true)
      .build()
    return try {
      manager.notify(NotificationIds.APK_UPDATE_PROMPT_INSTALL, notification)
      true
    } catch (_: SecurityException) {
      // Notification permission can change between the check and the post. Keep the candidate
      // available in settings and allow a later check to notify after permission is restored.
      false
    }
  }

  fun dismissUpdatePrompt(context: Context) {
    ServiceUtil.getNotificationManager(context).cancel(NotificationIds.APK_UPDATE_PROMPT_INSTALL)
    ServiceUtil.getNotificationManager(context).cancel(NotificationIds.APK_UPDATE_FAILED_INSTALL)
  }
}
