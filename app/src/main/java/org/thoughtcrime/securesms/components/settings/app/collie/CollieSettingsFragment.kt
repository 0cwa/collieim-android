/*
 * Copyright 2026 Collie contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.components.settings.app.collie

import androidx.compose.runtime.Composable
import androidx.navigation.fragment.findNavController
import org.signal.appsettings.routes.AppSettingsRoute
import org.signal.core.ui.compose.ComposeFragment
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.components.settings.app.AppSettingsNavigationActivity
import org.thoughtcrime.securesms.components.settings.app.appSettingsRouter
import org.thoughtcrime.securesms.util.navigation.safeNavigate

class CollieSettingsFragment : ComposeFragment() {

  private val appSettingsRouter by appSettingsRouter(onNavigateToScreen = ::navigateToScreen)

  @Composable
  override fun FragmentContent() {
    CollieSettingsEntry(parentEventEmitter = appSettingsRouter::onEvent)
  }

  private fun navigateToScreen(route: AppSettingsRoute) {
    when (route) {
      AppSettingsRoute.AppUpdates -> findNavController().safeNavigate(R.id.action_collieSettingsFragment_to_appUpdatesFragment)
      AppSettingsRoute.CollieRoute.GroupPrivacy -> startActivity(AppSettingsNavigationActivity.createIntent(requireContext(), route))
      AppSettingsRoute.CollieCalls -> findNavController().safeNavigate(R.id.callsSettingsFragment)
      else -> error("Unexpected route: ${route.javaClass.name}")
    }
  }
}
