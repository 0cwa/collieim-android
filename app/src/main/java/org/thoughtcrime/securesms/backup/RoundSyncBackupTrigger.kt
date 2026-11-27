package org.thoughtcrime.securesms.backup

import android.app.BackgroundServiceStartNotAllowedException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.keyvalue.SignalStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object RoundSyncBackupTrigger {
  const val PACKAGE_NAME = "de.felixnuesse.extract"
  const val SYNC_SERVICE_CLASS = "ca.pkay.rcloneexplorer.Services.SyncService"
  const val ACTION_START_TASK = "START_TASK"
  const val EXTRA_TASK = "task"
  const val EXTRA_NOTIFICATION = "notification"

  private val TAG = Log.tag(RoundSyncBackupTrigger::class)
  private const val BIND_TIMEOUT_SECONDS = 5L

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
      startTaskService(context, createStartTaskIntent(taskId)) -> TriggerResult.TRIGGERED
      else -> {
        Log.w(TAG, "Round Sync service could not be started")
        TriggerResult.FAILED
      }
    }
  }

  private fun startTaskService(context: Context, intent: Intent): Boolean {
    return try {
      context.startService(intent) != null
    } catch (e: IllegalStateException) {
      if (Build.VERSION.SDK_INT < 28 || Looper.myLooper() == Looper.getMainLooper()) {
        throw e
      }
      if (Build.VERSION.SDK_INT >= 31 && e !is BackgroundServiceStartNotAllowedException) {
        throw e
      }

      // Android can consider a service's app foreground while our backup foreground service is bound to it.
      // Round Sync's IntentService provides no binder. Its null binding signals readiness for START_TASK.
      startWhileBound(context, intent)
    }
  }

  private fun startWhileBound(context: Context, intent: Intent): Boolean {
    val bindingReady = CountDownLatch(1)
    var canStart = false
    val connection = object : ServiceConnection {
      override fun onServiceConnected(name: ComponentName, service: IBinder) = onNullBinding(name)

      override fun onNullBinding(name: ComponentName) {
        canStart = true
        bindingReady.countDown()
      }

      override fun onBindingDied(name: ComponentName) = bindingReady.countDown()

      override fun onServiceDisconnected(name: ComponentName) = Unit
    }

    val bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
    try {
      if (!bound || !bindingReady.await(BIND_TIMEOUT_SECONDS, TimeUnit.SECONDS) || !canStart) {
        return false
      }
      return context.startService(intent) != null
    } catch (_: InterruptedException) {
      Thread.currentThread().interrupt()
      return false
    } finally {
      context.unbindService(connection)
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
