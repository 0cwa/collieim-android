package org.thoughtcrime.securesms.backup

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.keyvalue.SignalStore

object RoundSyncBackupTrigger {
  const val PACKAGE_NAME = "de.felixnuesse.extract"
  const val SYNC_SERVICE_CLASS = "ca.pkay.rcloneexplorer.Services.SyncService"
  const val ACTION_START_TASK = "START_TASK"
  const val EXTRA_TASK = "task"
  const val EXTRA_NOTIFICATION = "notification"

  private val TAG = Log.tag(RoundSyncBackupTrigger::class)

  enum class TriggerResult { TRIGGERED, DISABLED, INVALID_TASK_ID, NOT_INSTALLED, FAILED }

  @JvmStatic
  fun isInstalled(context: Context): Boolean {
    return try {
      context.packageManager.getApplicationInfo(PACKAGE_NAME, 0)
      true
    } catch (_: PackageManager.NameNotFoundException) {
      false
    }
  }

  @JvmStatic
  fun createStartTaskIntent(taskId: Int, notification: Boolean = true): Intent {
    return Intent(ACTION_START_TASK)
      .setComponent(ComponentName(PACKAGE_NAME, SYNC_SERVICE_CLASS))
      .putExtra(EXTRA_TASK, taskId)
      .putExtra(EXTRA_NOTIFICATION, notification)
  }

  @JvmStatic
  fun triggerIfEnabled(context: Context): TriggerResult = safelyTrigger {
    triggerIfEnabled(context, SignalStore.molly.roundSyncEnabled) { SignalStore.molly.roundSyncTaskId }
  }

  internal fun triggerIfEnabled(context: Context, enabled: Boolean, taskId: () -> Int): TriggerResult {
    return if (enabled) triggerTask(context, taskId()) else TriggerResult.DISABLED
  }

  @JvmStatic
  fun triggerTask(context: Context, taskId: Int): TriggerResult = safelyTrigger {
    when {
      taskId <= 0 -> TriggerResult.INVALID_TASK_ID
      !isInstalled(context) -> TriggerResult.NOT_INSTALLED
      context.startService(createStartTaskIntent(taskId)) != null -> TriggerResult.TRIGGERED
      else -> {
        Log.w(TAG, "Round Sync service could not be started")
        TriggerResult.FAILED
      }
    }
  }

  private inline fun safelyTrigger(trigger: () -> TriggerResult): TriggerResult {
    return try {
      trigger()
    } catch (e: RuntimeException) {
      Log.w(TAG, "Could not trigger Round Sync", e)
      TriggerResult.FAILED
    }
  }
}
