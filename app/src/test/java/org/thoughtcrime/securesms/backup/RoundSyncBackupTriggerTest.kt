package org.thoughtcrime.securesms.backup

import android.app.Application
import android.app.BackgroundServiceStartNotAllowedException
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.thoughtcrime.securesms.backup.RoundSyncBackupTrigger.TriggerResult
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class RoundSyncBackupTriggerTest {
  private val component = ComponentName("de.felixnuesse.extract", "ca.pkay.rcloneexplorer.Services.SyncService")
  private var startedIntent: Intent? = null
  private var startFailure: RuntimeException? = null
  private var allowBinding = false
  private var boundConnection: ServiceConnection? = null
  private var pendingBinding: CompletableFuture<ServiceConnection>? = null
  @Volatile private var bindingReady = false
  private var unbindCount = 0
  private val context: Context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
    override fun startService(service: Intent): ComponentName {
      if (!bindingReady) {
        startFailure?.let { throw it }
      }
      startedIntent = service
      return component
    }

    override fun bindService(service: Intent, connection: ServiceConnection, flags: Int): Boolean {
      assertEquals(component, service.component)
      assertEquals(Context.BIND_AUTO_CREATE, flags)
      if (allowBinding) {
        boundConnection = connection
        val pending = pendingBinding
        if (pending == null) {
          reportBindingReady(connection)
        } else {
          pending.complete(connection)
        }
      }
      return allowBinding
    }

    override fun unbindService(connection: ServiceConnection) {
      assertTrue(boundConnection == null || boundConnection === connection)
      boundConnection = null
      bindingReady = false
      unbindCount++
    }
  }

  @Test
  fun constructsStartTaskIntent() {
    val intent = RoundSyncBackupTrigger.createStartTaskIntent(24)
    assertEquals(component, intent.component)
    assertEquals("START_TASK", intent.action)
    assertEquals(24, intent.getIntExtra("task", -1))
    assertTrue(intent.getBooleanExtra("notification", false))
    assertFalse(RoundSyncBackupTrigger.createStartTaskIntent(24, false).getBooleanExtra("notification", true))
  }

  @Test
  fun rejectsInvalidTaskId() {
    assertEquals(TriggerResult.INVALID_TASK_ID, RoundSyncBackupTrigger.triggerTask(context, 0))
    assertEquals(TriggerResult.INVALID_TASK_ID, RoundSyncBackupTrigger.triggerTask(context, -1))
    assertNull(startedIntent)
  }

  @Test
  fun skipsMissingPackage() {
    assertEquals(TriggerResult.NOT_INSTALLED, RoundSyncBackupTrigger.triggerTask(context, 24))
    assertNull(startedIntent)
  }

  @Test
  fun skipsDisabledSetting() {
    installRoundSync()
    assertEquals(TriggerResult.DISABLED, RoundSyncBackupTrigger.triggerIfEnabled(context, false) { error("Unexpected task ID read") })
    assertNull(startedIntent)
  }

  @Test
  fun startsConfiguredTask() {
    installRoundSync()
    assertEquals(TriggerResult.TRIGGERED, RoundSyncBackupTrigger.triggerIfEnabled(context, true) { 24 })
    assertEquals(component, startedIntent?.component)
    assertEquals(24, startedIntent?.getIntExtra("task", -1))
  }

  @Test
  fun reportsIllegalStateFailure() {
    installRoundSync()
    startFailure = IllegalStateException("Background start denied")
    assertEquals(TriggerResult.FAILED, RoundSyncBackupTrigger.triggerTask(context, 24))
  }

  @Test
  @Config(sdk = [35])
  fun retriesDeniedStartWhileBoundAndReleasesConnection() {
    installRoundSync()
    startFailure = BackgroundServiceStartNotAllowedException("Background start denied")
    allowBinding = true

    assertEquals(TriggerResult.TRIGGERED, startFromWorker().get(10, TimeUnit.SECONDS))
    assertEquals(24, startedIntent?.getIntExtra("task", -1))
    assertEquals(1, unbindCount)
    assertNull(boundConnection)
  }

  @Test
  @Config(sdk = [35])
  fun waitsForBindingReadinessBeforeRetrying() {
    installRoundSync()
    startFailure = BackgroundServiceStartNotAllowedException("Background start denied")
    allowBinding = true
    val binding = CompletableFuture<ServiceConnection>()
    pendingBinding = binding

    val result = startFromWorker()
    val connection = binding.get(5, TimeUnit.SECONDS)
    try {
      assertThrows(TimeoutException::class.java) { result.get(200, TimeUnit.MILLISECONDS) }
    } finally {
      reportBindingReady(connection)
    }

    assertEquals(TriggerResult.TRIGGERED, result.get(10, TimeUnit.SECONDS))
    assertEquals(24, startedIntent?.getIntExtra("task", -1))
    assertEquals(1, unbindCount)
    assertNull(boundConnection)
  }

  @Test
  @Config(sdk = [35])
  fun reportsFailureAndCleansUpWhenBindingIsRejected() {
    installRoundSync()
    startFailure = BackgroundServiceStartNotAllowedException("Background start denied")

    assertEquals(TriggerResult.FAILED, startFromWorker().get(10, TimeUnit.SECONDS))
    assertEquals(1, unbindCount)
    assertNull(startedIntent)
  }

  @Test
  fun reportsSecurityFailure() {
    installRoundSync()
    startFailure = SecurityException("Service start denied")
    assertEquals(TriggerResult.FAILED, RoundSyncBackupTrigger.triggerTask(context, 24))
  }

  private fun installRoundSync() {
    shadowOf(context.packageManager).installPackage(PackageInfo().apply { packageName = component.packageName })
  }

  private fun reportBindingReady(connection: ServiceConnection) {
    bindingReady = true
    connection.onNullBinding(component)
  }

  private fun startFromWorker(): CompletableFuture<TriggerResult> = CompletableFuture.supplyAsync {
    RoundSyncBackupTrigger.triggerTask(context, 24)
  }
}
