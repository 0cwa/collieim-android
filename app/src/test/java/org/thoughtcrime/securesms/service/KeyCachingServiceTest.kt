package org.thoughtcrime.securesms.service

import android.app.AlarmManager
import android.app.Application
import android.app.ForegroundServiceStartNotAllowedException
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.BuildConfig
import org.thoughtcrime.securesms.crypto.MasterSecret
import org.thoughtcrime.securesms.notifications.NotificationChannels
import org.thoughtcrime.securesms.preferences.widgets.PassphraseLockTriggerPreference
import org.thoughtcrime.securesms.testutil.EmptyLogger
import org.thoughtcrime.securesms.util.DynamicTheme
import org.thoughtcrime.securesms.util.TextSecurePreferences

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [33])
class KeyCachingServiceTest {
  @Before
  fun setUp() {
    Log.initialize(EmptyLogger())
    mockkStatic(TextSecurePreferences::class, NotificationChannels::class, DynamicTheme::class)
    every { TextSecurePreferences.isPassphraseLockEnabled(any()) } returns true
    every { TextSecurePreferences.getPassphraseLockTrigger(any()) } returns PassphraseLockTriggerPreference(setOf("timeout"))
    every { TextSecurePreferences.getPassphraseLockTimeout(any()) } returns 60L
    every { NotificationChannels.getInstance() } returns mockk()
    every { DynamicTheme.resolveColor(any(), any()) } returns 0
    KeyCachingService.setMasterSecret(mockk<MasterSecret>(relaxed = true))
  }

  @After
  fun tearDown() {
    KeyCachingService.clearMasterSecret()
    unmockkStatic(TextSecurePreferences::class, NotificationChannels::class, DynamicTheme::class)
  }

  @Test
  fun `refused foreground promotion retains a timeout alarm after screen unlock and service destruction`() {
    val context = ApplicationProvider.getApplicationContext<Application>()
    val controller = Robolectric.buildService(KeyCachingService::class.java).create()
    val service = controller.get()
    shadowOf(service).setThrowInStartForeground(ForegroundServiceStartNotAllowedException("refused"))
    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    val before = SystemClock.elapsedRealtime()

    KeyCachingService::class.java.getDeclaredMethod("foregroundService").apply { isAccessible = true }.invoke(service)

    val alarm = requireNotNull(shadowOf(alarmManager).peekNextScheduledAlarm())
    assertEquals(AlarmManager.ELAPSED_REALTIME_WAKEUP, alarm.type)
    assertTrue(alarm.triggerAtTime >= before + 60_000L)
    assertTrue(alarm.triggerAtTime <= SystemClock.elapsedRealtime() + 60_000L)
    assertEquals(KeyCachingService::class.java.name, shadowOf(alarm.operation).savedIntent.component?.className)
    assertEquals(BuildConfig.APPLICATION_ID + ".service.action.PASSPHRASE_EXPIRED_EVENT", shadowOf(alarm.operation).savedIntent.action)
    context.sendBroadcast(Intent(Intent.ACTION_SCREEN_ON))
    context.sendBroadcast(Intent(Intent.ACTION_USER_PRESENT))
    controller.destroy()
    assertNotNull(shadowOf(alarmManager).peekNextScheduledAlarm())
  }

  @Test
  fun `successful retry cancels fallback alarm when screen is on and unlocked`() {
    assertRetryAlarm(interactive = true, locked = false, expectAlarm = false)
  }

  @Test
  fun `successful retry keeps fallback alarm when screen is off`() {
    assertRetryAlarm(interactive = false, locked = false, expectAlarm = true)
  }

  @Test
  fun `successful retry keeps fallback alarm when keyguard is locked`() {
    assertRetryAlarm(interactive = true, locked = true, expectAlarm = true)
  }

  @Test
  fun `locked actionless request stops without promoting the service or caching a key`() {
    KeyCachingService.clearMasterSecret()
    val controller = Robolectric.buildService(KeyCachingService::class.java).create()
    val service = controller.get()

    try {
      val shadow = shadowOf(service)
      assertEquals(
        KeyCachingService.START_NOT_STICKY,
        service.onStartCommand(Intent(service, KeyCachingService::class.java), 0, 42)
      )

      assertTrue(KeyCachingService.isLocked())
      assertTrue(shadow.isStoppedBySelf)
      verify(exactly = 0) { TextSecurePreferences.isPassphraseLockEnabled(any()) }
      verify(exactly = 0) { NotificationChannels.getInstance() }
    } finally {
      controller.destroy()
    }
  }

  private fun assertRetryAlarm(interactive: Boolean, locked: Boolean, expectAlarm: Boolean) {
    val context = ApplicationProvider.getApplicationContext<Application>()
    val controller = Robolectric.buildService(KeyCachingService::class.java).create()
    val service = controller.get()
    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
    shadowOf(powerManager).setIsInteractive(interactive)
    shadowOf(keyguardManager).setKeyguardLocked(locked)
    val foregroundService = KeyCachingService::class.java.getDeclaredMethod("foregroundService").apply { isAccessible = true }

    try {
      shadowOf(service).setThrowInStartForeground(ForegroundServiceStartNotAllowedException("refused"))
      foregroundService.invoke(service)
      val fallback = shadowOf(alarmManager).peekNextScheduledAlarm()
      assertNotNull(fallback)

      shadowOf(service).setThrowInStartForeground(null)
      foregroundService.invoke(service)

      if (expectAlarm) {
        assertEquals(fallback, shadowOf(alarmManager).peekNextScheduledAlarm())
      } else {
        assertNull(shadowOf(alarmManager).peekNextScheduledAlarm())
      }
    } finally {
      controller.destroy()
    }
  }
}
