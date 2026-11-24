/*
 * Copyright 2026 Collie contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.components.settings.app.collie

import androidx.compose.runtime.Composable
import org.signal.core.ui.compose.ComposeFragment
import org.thoughtcrime.securesms.components.settings.app.appSettingsRouter

class CollieSettingsFragment : ComposeFragment() {

  private val appSettingsRouter by appSettingsRouter()

  @Composable
  override fun FragmentContent() {
    CollieSettingsEntry(parentEventEmitter = appSettingsRouter::onEvent)
  }
}
