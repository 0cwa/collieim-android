/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.components.settings.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.signal.appsettings.routes.AppSettingsNavigation
import org.signal.appsettings.routes.AppSettingsRoute
import org.signal.appsettings.routes.AppSettingsRouter
import org.signal.core.ui.compose.WindowOverlayHost
import org.signal.core.ui.compose.split.detailEntry
import org.signal.core.ui.compose.theme.SignalTheme
import org.signal.core.util.getParcelableExtraCompat
import org.signal.remoteconfig.RemoteConfig
import org.thoughtcrime.securesms.PassphraseRequiredActivity
import org.thoughtcrime.securesms.avatar.picker.AvatarPickerResult
import org.thoughtcrime.securesms.avatar.picker.registerAvatarPickerScreens
import org.thoughtcrime.securesms.components.enableSettingsEdgeToEdge
import org.thoughtcrime.securesms.components.settings.app.appearance.registerAppearanceRoutes
import org.thoughtcrime.securesms.components.settings.app.chats.registerChatsRoutes
import org.thoughtcrime.securesms.components.settings.app.labs.registerLabsRoutes
import org.thoughtcrime.securesms.components.settings.app.notifications.registerNotificationsRoutes
import org.thoughtcrime.securesms.components.settings.app.privacy.registerPrivacyRoutes
import org.thoughtcrime.securesms.components.settings.app.storage.registerDataAndStorageRoutes
import org.thoughtcrime.securesms.components.settings.app.updates.registerAppUpdatesRoutes
import org.thoughtcrime.securesms.components.settings.app.usernamelinks.registerUsernameLinkScreens
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.main.EmptyDetailScreen
import org.thoughtcrime.securesms.profiles.manage.editprofile.registerEditProfileScreens
import org.thoughtcrime.securesms.util.DynamicNoActionBarTheme
import org.thoughtcrime.securesms.util.DynamicTheme

/**
 * Hosts app settings in [AppSettingsNavigation] rather than the fragment nav graph, gated by
 * [RemoteConfig.useAppSettingsNavigation].
 */
class AppSettingsNavigationActivity : PassphraseRequiredActivity() {

  companion object {
    private const val START_ROUTE = "app.settings.navigation.args.START_ROUTE"

    fun createIntent(context: Context, startRoute: AppSettingsRoute): Intent {
      return Intent(context, AppSettingsNavigationActivity::class.java)
        .putExtra(START_ROUTE, startRoute)
    }
  }

  private val dynamicTheme: DynamicTheme = DynamicNoActionBarTheme()
  private val router: AppSettingsRouter by viewModels()

  override fun onPreCreate() {
    super.onPreCreate()
    dynamicTheme.onCreate(this)
  }

  override fun onCreate(savedInstanceState: Bundle?, ready: Boolean) {
    enableSettingsEdgeToEdge()
    super.onCreate(savedInstanceState, ready)

    val startRoute = intent.getParcelableExtraCompat(START_ROUTE, AppSettingsRoute::class.java)
    if (savedInstanceState == null && startRoute == AppSettingsRoute.NotificationsRoute.Notifications) {
      startActivity(AppSettingsActivity.notifications(this))
      finish()
      return
    }
    if (savedInstanceState == null && startRoute is AppSettingsRoute.NotificationsRoute.PlayServicesProblem) {
      startActivity(AppSettingsActivity.playServicesProblem(this, startRoute.errorCode))
      finish()
      return
    }
    if (savedInstanceState == null && startRoute == AppSettingsRoute.DataAndStorageRoute.DataAndStorage) {
      startActivity(AppSettingsActivity.manageStorage(this))
      finish()
      return
    }
    if (savedInstanceState == null && startRoute == AppSettingsRoute.DataAndStorageRoute.Proxy) {
      startActivity(AppSettingsActivity.proxy(this))
      finish()
      return
    }
    if (savedInstanceState == null && (startRoute == AppSettingsRoute.PrivacyRoute.Privacy || startRoute == AppSettingsRoute.PrivacyRoute.ScreenLock)) {
      startActivity(AppSettingsActivity.privacy(this))
      finish()
      return
    }
    if (savedInstanceState == null && startRoute != null && startRoute != AppSettingsRoute.Empty) {
      router.openDirectly(startRoute)
    }

    setContent {
      SignalTheme {
        WindowOverlayHost {
          AppSettingsNavigation(
            router = router,
            onClose = ::finish,
            home = { selectedRoute -> AppSettingsEntry(parentEventEmitter = router::onEvent, selectedRoute = selectedRoute) },
            emptyDetailContent = { EmptyDetailScreen() },
            defaultDetailRoute = AppSettingsRoute.AccountRoute.ManageProfile,
            tryLaunchActivity = ::tryLaunchActivity,
            detailEntries = {
              registerAppearanceRoutes(router::onEvent)
              registerChatsRoutes(router::onEvent)
              registerNotificationsRoutes(router::onEvent)
              registerLabsRoutes(router::onEvent)
              registerAppUpdatesRoutes(router::onEvent)
              registerPrivacyRoutes(router::onEvent)
              registerDataAndStorageRoutes(router::onEvent)
              registerEditProfileScreens(router::onEvent)
              registerAvatarPickerScreens(router::onEvent, onResult = { router.resultBus.sendResult<AvatarPickerResult>(result = it) })
              registerUsernameLinkScreens(router::onEvent)

              // TODO Temporary escape hatch while most destinations are unregistered here; remove before the flag ships.
              detailEntry<AppSettingsRoute.InternalRoute.Internal> {
                LaunchedEffect(Unit) { disableAppSettingsNavigation() }
              }
            }
          )
        }
      }
    }

    recreateOnThemeChange()
    performActionOnCreate(savedInstanceState)
  }

  private fun tryLaunchActivity(route: AppSettingsRoute): Boolean {
    val intent = when (route) {
      AppSettingsRoute.NotificationsRoute.Notifications -> AppSettingsActivity.notifications(this)
      is AppSettingsRoute.NotificationsRoute.PlayServicesProblem -> AppSettingsActivity.playServicesProblem(this, route.errorCode)
      AppSettingsRoute.DataAndStorageRoute.Proxy -> AppSettingsActivity.proxy(this)
      AppSettingsRoute.DataAndStorageRoute.DataAndStorage -> AppSettingsActivity.manageStorage(this)
      AppSettingsRoute.PrivacyRoute.Privacy, AppSettingsRoute.PrivacyRoute.ScreenLock -> AppSettingsActivity.privacy(this)
      else -> return false
    }

    startActivity(intent)
    return true
  }

  private fun disableAppSettingsNavigation() {
    lifecycleScope.launch {
      withContext(Dispatchers.Default) {
        RemoteConfig.overrides -= RemoteConfig.APP_SETTINGS_NAVIGATION_KEY
        SignalStore.remoteConfig.overrides = RemoteConfig.overrides
      }

      startActivity(AppSettingsActivity.home(this@AppSettingsNavigationActivity))
      finish()
    }
  }

  override fun onResume() {
    super.onResume()
    dynamicTheme.onResume(this)
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    finish()
    startActivity(intent)
  }
}
