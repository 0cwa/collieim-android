package org.thoughtcrime.securesms.components.settings.app.notifications

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thoughtcrime.securesms.keyvalue.AccountValues
import org.thoughtcrime.securesms.keyvalue.SettingsValues
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.keyvalue.UnifiedPushValues
import org.thoughtcrime.securesms.notifications.SlowNotificationHeuristics
import org.thoughtcrime.securesms.notifications.NotificationChannels
import org.thoughtcrime.securesms.recipients.LiveRecipient
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.testutil.MockAppDependenciesRule
import org.thoughtcrime.securesms.testutil.MockSignalStoreRule
import org.thoughtcrime.securesms.util.SecurePreferenceManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class MollyNotificationStartupRaceTest {
  private val dependencies = MockAppDependenciesRule()
  private val signalStore = MockSignalStoreRule(setOf(AccountValues::class, SettingsValues::class))

  @get:Rule
  val rules: RuleChain = RuleChain.outerRule(dependencies).around(signalStore)

  @Before
  fun setUp() {
    val context = ApplicationProvider.getApplicationContext<Application>()
    mockkStatic(SecurePreferenceManager::class)
    every { SecurePreferenceManager.getSecurePreferences(any()) } returns context.getSharedPreferences("notification-startup-test", 0)
    val recipient = mockk<Recipient>()
    val liveRecipient = mockk<LiveRecipient>(relaxed = true)
    mockkStatic(Recipient::class)
    every { Recipient.self() } returns recipient
    every { recipient.live() } returns liveRecipient
    every { SignalStore.unifiedpush } returns mockk<UnifiedPushValues>(relaxed = true)
    mockkStatic(NotificationChannels::class)
    every { NotificationChannels.supported() } returns false
    every { NotificationChannels.getInstance() } returns mockk(relaxed = true)
    mockkStatic(SlowNotificationHeuristics::class)
  }

  @After
  fun tearDown() {
    unmockkStatic(SlowNotificationHeuristics::class)
    unmockkStatic(NotificationChannels::class)
    unmockkStatic(Recipient::class)
    unmockkStatic(SecurePreferenceManager::class)
  }

  @Test
  fun `slow startup refresh preserves pending Play Services error`() {
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    every { SlowNotificationHeuristics.isBatteryOptimizationsOn() } answers {
      entered.countDown()
      check(release.await(5, TimeUnit.SECONDS))
      true
    }
    every { SlowNotificationHeuristics.isHavingDelayedNotifications() } returns true
    try {
      val viewModel = NotificationsSettingsViewModel()
      assertTrue(entered.await(5, TimeUnit.SECONDS))
      viewModel.setPlayServicesErrorCode(42)
      release.countDown()
      runBlocking {
        withTimeout(5000) { viewModel.state.first { it.messageNotificationsState.troubleshootNotifications } }
      }
      assertEquals(42, viewModel.state.value.playServicesErrorCode)
      viewModel.setPlayServicesErrorCode(null)
      viewModel.refresh()
      assertNull(viewModel.state.value.playServicesErrorCode)
    } finally {
      release.countDown()
    }
  }
}
