package org.thoughtcrime.securesms.notifications

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.jobs.RestoreNotificationsJob
import org.thoughtcrime.securesms.service.KeyCachingService

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class RestoreNotificationsReceiverTest {
  private val context = ApplicationProvider.getApplicationContext<Application>()

  @Before
  fun setUp() {
    mockkStatic(KeyCachingService::class)
    mockkStatic(AppDependencies::class)
    mockkObject(RestoreNotificationsJob.Companion)
    every { RestoreNotificationsJob.enqueue() } returns Unit
  }

  @After
  fun tearDown() {
    unmockkObject(RestoreNotificationsJob.Companion)
    unmockkStatic(AppDependencies::class)
    unmockkStatic(KeyCachingService::class)
  }

  @Test
  fun `locked process defers restoration even when dependencies were initialized`() {
    every { KeyCachingService.isLocked() } returns true
    every { AppDependencies.isInitialized } returns true
    RestoreNotificationsReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
    verify(exactly = 0) { RestoreNotificationsJob.enqueue() }
  }

  @Test
  fun `uninitialized dependencies defer restoration`() {
    every { KeyCachingService.isLocked() } returns false
    every { AppDependencies.isInitialized } returns false
    RestoreNotificationsReceiver().onReceive(context, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
    verify(exactly = 0) { RestoreNotificationsJob.enqueue() }
  }

  @Test
  fun `unlocked initialized process schedules restoration`() {
    every { KeyCachingService.isLocked() } returns false
    every { AppDependencies.isInitialized } returns true
    RestoreNotificationsReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
    verify(exactly = 1) { RestoreNotificationsJob.enqueue() }
  }
}
