/*
 * Copyright 2025 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.components.settings.app.help

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
<<<<<<< MOLLY
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment.Companion.CenterVertically
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.fragment.app.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import im.molly.app.base.ApkInfo
=======
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.fragment.findNavController
import org.signal.appsettings.help.HelpSettingsAction
import org.signal.appsettings.help.HelpSettingsScreen
import org.signal.appsettings.help.HelpSettingsViewModel
import org.signal.core.ui.compose.CollectActions
>>>>>>> SIGNAL
import org.signal.core.ui.compose.ComposeFragment
import org.signal.core.ui.viewModel
import org.signal.core.util.Util
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.util.CommunicationActions
import org.thoughtcrime.securesms.util.navigation.safeNavigate
import org.signal.appsettings.R as AppSettingsR

class HelpSettingsFragment : ComposeFragment() {

<<<<<<< MOLLY
  private val viewModel: HelpSettingsViewModel by viewModels()
=======
  private val viewModel: HelpSettingsViewModel by viewModel { HelpSettingsViewModel(versionName = BuildConfig.VERSION_NAME) }
>>>>>>> SIGNAL

  @Composable
  override fun FragmentContent() {
    val state by viewModel.state.collectAsStateWithLifecycle()
<<<<<<< MOLLY
    val navController: NavController = remember { findNavController() }
=======
>>>>>>> SIGNAL

    CollectActions(viewModel.actions) { handleAction(it) }

<<<<<<< MOLLY
    Scaffolds.Settings(
      title = stringResource(R.string.preferences__help),
      onNavigationClick = { navController.popBackStack() },
      navigationIcon = SignalIcons.ArrowStart.imageVector,
      navigationContentDescription = stringResource(id = R.string.Material3SearchToolbar__close)
    ) { contentPadding ->
      LazyColumn(
        modifier = Modifier.padding(contentPadding)
      ) {
        item {
          Rows.LinkRow(
            text = stringResource(R.string.HelpSettingsFragment__molly_im_website),
            icon = ImageVector.vectorResource(R.drawable.symbol_open_20),
            onClick = {
              CommunicationActions.openBrowserLink(context, getString(R.string.website_url))
            }
          )
        }

        item {
          Rows.LinkRow(
            text = stringResource(R.string.HelpSettingsFragment__support_center),
            icon = ImageVector.vectorResource(R.drawable.symbol_open_20),
            onClick = {
              CommunicationActions.openBrowserLink(context, getString(R.string.support_center_url))
            }
          )
        }

        item {
          Rows.TextRow(
            text = stringResource(id = R.string.HelpSettingsFragment__contact_us),
            onClick = {
              navController.safeNavigate(R.id.action_helpSettingsFragment_to_helpFragment)
            }
          )
        }

        item {
          Dividers.Default()
        }

        item {
          Rows.TextRow(
            text = stringResource(R.string.HelpSettingsFragment__version),
            onClick = {
              navController.safeNavigate(R.id.action_helpSettingsFragment_to_appUpdatesFragment)
            },
            label = ApkInfo.versionName,
            onLongClick = {
              Util.copyToClipboard(context, ApkInfo.versionName)
              Toast.makeText(context, R.string.HelpSettingsFragment__copied_to_clipboard, Toast.LENGTH_SHORT).show()
            }
          )
        }

        item {
          Rows.ToggleRow(
            checked = state.logEnabled,
            text = stringResource(id = R.string.preferences__enable_debug_log),
            onCheckChanged = { checked ->
              if (!checked) {
                MaterialAlertDialogBuilder(requireContext())
                  .setMessage(R.string.HelpSettingsFragment_disable_and_delete_debug_log)
                  .setPositiveButton(android.R.string.ok) { dialog, _ ->
                    viewModel.setLogEnabled(false)
                    dialog.dismiss()
                  }
                  .setNegativeButton(android.R.string.cancel, null)
                  .show()
              } else {
                viewModel.setLogEnabled(true)
              }
            }
          )
        }

        item {
          Rows.TextRow(
            enabled = state.logEnabled,
            text = stringResource(id = R.string.HelpSettingsFragment__debug_log),
            onClick = {
              navController.safeNavigate(R.id.action_helpSettingsFragment_to_submitDebugLogActivity)
            }
          )
        }

        item {
          Rows.TextRow(
            text = stringResource(id = R.string.HelpSettingsFragment__licenses),
            onClick = {
              navController.safeNavigate(R.id.action_helpSettingsFragment_to_licenseFragment)
            }
          )
        }

        item {
          Rows.LinkRow(
            text = stringResource(R.string.HelpSettingsFragment__terms_amp_privacy_policy),
            icon = ImageVector.vectorResource(R.drawable.symbol_open_20),
            onClick = {
              CommunicationActions.openBrowserLink(context, getString(R.string.terms_and_privacy_policy_url))
            }
          )
        }

        item {
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .padding(defaultPadding()),
            verticalAlignment = CenterVertically
          ) {
            TextAndLabel(
              label = StringBuilder().apply {
                append(getString(R.string.HelpFragment__copyright_signal_messenger))
                append("\n")
                append(getString(R.string.HelpFragment__licenced_under_the_agplv3))
                append("\n")
                append(getString(R.string.HelpSettingsFragment__signal_is_a_501c3))
              }.toString()
            )
          }
        }
=======
    HelpSettingsScreen(
      state = state,
      onEvent = viewModel::onEvent
    )
  }

  private fun handleAction(action: HelpSettingsAction) {
    when (action) {
      HelpSettingsAction.NavigateBack -> findNavController().popBackStack()
      HelpSettingsAction.OpenSupportCenter -> CommunicationActions.openBrowserLink(requireContext(), getString(R.string.support_center_url))
      HelpSettingsAction.NavigateToContactUs -> findNavController().safeNavigate(R.id.action_helpSettingsFragment_to_helpFragment)
      is HelpSettingsAction.CopyVersionToClipboard -> {
        Util.copyToClipboard(requireContext(), action.versionName)
        Toast.makeText(requireContext(), AppSettingsR.string.HelpSettingsFragment__copied_to_clipboard, Toast.LENGTH_SHORT).show()
>>>>>>> SIGNAL
      }
      HelpSettingsAction.NavigateToDebugLog -> findNavController().safeNavigate(R.id.action_helpSettingsFragment_to_submitDebugLogActivity)
      HelpSettingsAction.NavigateToLicenses -> findNavController().safeNavigate(R.id.action_helpSettingsFragment_to_licenseFragment)
      HelpSettingsAction.OpenTermsAndPrivacyPolicy -> CommunicationActions.openBrowserLink(requireContext(), getString(R.string.terms_and_privacy_policy_url))
    }
  }
}
