/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.components.settings.app

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import assertk.assertThat
import assertk.assertions.isEqualTo
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.appsettings.routes.AppSettingsRoute
import org.signal.core.util.getParcelableExtraCompat
import org.signal.remoteconfig.RemoteConfig

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AppSettingsActivityIntentsTest {

  private val context: Application = ApplicationProvider.getApplicationContext()

  @Before
  fun setUp() {
    mockkObject(RemoteConfig)
  }

  @After
  fun tearDown() {
    unmockkObject(RemoteConfig)
  }

  @Test
  fun `privacy always uses authenticated legacy screen in both navigation modes`() {
    listOf(false, true).forEach { navigationEnabled ->
      every { RemoteConfig.useAppSettingsNavigation } returns navigationEnabled
      val intent = AppSettingsActivity.privacy(context)
      assertThat(intent.component?.className).isEqualTo(AppSettingsActivity::class.java.name)
      assertThat(intent.startRoute()).isEqualTo(AppSettingsRoute.PrivacyRoute.Privacy)
    }
  }

  @Test
  fun `network always uses Molly network settings in both navigation modes`() {
    listOf(false, true).forEach { navigationEnabled ->
      every { RemoteConfig.useAppSettingsNavigation } returns navigationEnabled
      val intent = AppSettingsActivity.proxy(context)
      assertThat(intent.component?.className).isEqualTo(AppSettingsActivity::class.java.name)
      assertThat(intent.startRoute()).isEqualTo(AppSettingsRoute.DataAndStorageRoute.Proxy)
    }
  }

  @Test
  fun `data and storage always uses the legacy host in both navigation modes`() {
    listOf(false, true).forEach { navigationEnabled ->
      every { RemoteConfig.useAppSettingsNavigation } returns navigationEnabled
      val intent = AppSettingsActivity.manageStorage(context)
      assertThat(intent.component?.className).isEqualTo(AppSettingsActivity::class.java.name)
      assertThat(intent.startRoute()).isEqualTo(AppSettingsRoute.DataAndStorageRoute.DataAndStorage)
    }
  }

  @Test
  fun `notifications and play services errors use Molly notification settings in both modes`() {
    listOf(false, true).forEach { navigationEnabled ->
      every { RemoteConfig.useAppSettingsNavigation } returns navigationEnabled
      val notifications = AppSettingsActivity.notifications(context)
      assertThat(notifications.component?.className).isEqualTo(AppSettingsActivity::class.java.name)
      assertThat(notifications.startRoute()).isEqualTo(AppSettingsRoute.NotificationsRoute.Notifications)
      val error = AppSettingsActivity.playServicesProblem(context, 42)
      assertThat(error.component?.className).isEqualTo(AppSettingsActivity::class.java.name)
      assertThat(error.startRoute()).isEqualTo(AppSettingsRoute.NotificationsRoute.PlayServicesProblem(42))
    }
  }

  @Test
  fun `only the dedicated setup intent requests the push section in both navigation modes`() {
    listOf(false, true).forEach { navigationEnabled ->
      every { RemoteConfig.useAppSettingsNavigation } returns navigationEnabled

      val setup = AppSettingsActivity.pushNotifications(context)
      assertThat(setup.component?.className).isEqualTo(AppSettingsActivity::class.java.name)
      assertThat(setup.startRoute()).isEqualTo(AppSettingsRoute.NotificationsRoute.Notifications)
      assertThat(setup.getBooleanExtra(EXTRA_FOCUS_PUSH_NOTIFICATIONS, false)).isEqualTo(true)
      assertThat(AppSettingsActivity.notifications(context).getBooleanExtra(EXTRA_FOCUS_PUSH_NOTIFICATIONS, false)).isEqualTo(false)
      assertThat(AppSettingsActivity.playServicesProblem(context, 42).getBooleanExtra(EXTRA_FOCUS_PUSH_NOTIFICATIONS, false)).isEqualTo(false)
    }
  }

  /** The start route rides under a host-specific extra key, so find it by type rather than by name. */
  private fun Intent.startRoute(): AppSettingsRoute? {
    return extras?.keySet()?.firstNotNullOfOrNull { getParcelableExtraCompat(it, AppSettingsRoute::class.java) }
  }
}
