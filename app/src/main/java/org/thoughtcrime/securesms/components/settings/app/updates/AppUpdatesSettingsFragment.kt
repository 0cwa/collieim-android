/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.components.settings.app.updates

import androidx.compose.runtime.Composable
import org.signal.core.ui.compose.ComposeFragment
import org.thoughtcrime.securesms.components.settings.app.appSettingsRouter

/**
 * Settings for Collie updates, or app-store guidance for externally managed builds.
 */
class AppUpdatesSettingsFragment : ComposeFragment() {

  private val appSettingsRouter by appSettingsRouter()

  @Composable
  override fun FragmentContent() {
    AppUpdatesSettingsEntry(parentEventEmitter = appSettingsRouter::onEvent)
  }
}
