/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.components.settings.app.privacy

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import org.signal.appsettings.routes.AppSettingsNavigationEvent
import org.signal.appsettings.routes.AppSettingsRoute
import org.signal.appsettings.routes.navigateBack
import org.signal.core.ui.compose.split.detailEntry
import org.thoughtcrime.securesms.components.settings.app.privacy.pnp.PhoneNumberPrivacySettingsEntry
import org.thoughtcrime.securesms.components.settings.app.AppSettingsActivity

/**
 * Registers the routes within the privacy section of app settings.
 */
fun EntryProviderScope<NavKey>.registerPrivacyRoutes(parentEventEmitter: (AppSettingsNavigationEvent) -> Unit) {
  detailEntry<AppSettingsRoute.PrivacyRoute.Privacy> {
    LegacyPrivacyRedirect(parentEventEmitter)
  }

  detailEntry<AppSettingsRoute.PrivacyRoute.PhoneNumberPrivacy> {
    PhoneNumberPrivacySettingsEntry(parentEventEmitter = parentEventEmitter)
  }

  detailEntry<AppSettingsRoute.PrivacyRoute.ScreenLock> {
    LegacyPrivacyRedirect(parentEventEmitter)
  }
}

@Composable
private fun LegacyPrivacyRedirect(parentEventEmitter: (AppSettingsNavigationEvent) -> Unit) {
  val context = LocalContext.current
  LaunchedEffect(Unit) {
    context.startActivity(AppSettingsActivity.privacy(context))
    parentEventEmitter.navigateBack()
  }
}
