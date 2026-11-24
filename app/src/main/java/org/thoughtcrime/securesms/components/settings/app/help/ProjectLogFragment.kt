/*
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.components.settings.app.help

import androidx.compose.runtime.Composable
import androidx.navigation.fragment.findNavController
import org.signal.core.ui.compose.ComposeFragment
import org.thoughtcrime.securesms.main.setup.ProjectLogEntry

class ProjectLogFragment : ComposeFragment() {
  @Composable
  override fun FragmentContent() {
    ProjectLogEntry(onBack = { findNavController().navigateUp() })
  }
}
