package org.thoughtcrime.securesms.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.jobs.RestoreNotificationsJob
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.service.KeyCachingService

/** The system drops our posted notifications on app update and reboot, and nothing else puts them back. */
class RestoreNotificationsReceiver : BroadcastReceiver() {

  companion object {
    private val TAG = Log.tag(RestoreNotificationsReceiver::class)
  }

  override fun onReceive(context: Context, intent: Intent) {
    if (KeyCachingService.isLocked() || !AppDependencies.isInitialized) {
      Log.i(TAG, "Deferring notification restore until after unlock")
      return
    }
    Log.i(TAG, "Enqueuing notification restore after ${intent.action}")
    RestoreNotificationsJob.enqueue()
  }
}
