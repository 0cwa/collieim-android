/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.components.settings.app.help

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.signal.appsettings.help.HelpSettingsAction
import org.signal.appsettings.help.HelpSettingsScreen
import org.signal.appsettings.help.HelpSettingsViewModel
import org.signal.appsettings.routes.AppSettingsNavigationEvent
import org.signal.core.ui.compose.CollectActions
import org.signal.core.util.Util
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import im.molly.app.base.ApkInfo
import org.signal.appsettings.help.HelpSettingsEvent
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.components.settings.app.AppSettingsRepository
import org.thoughtcrime.securesms.util.TextSecurePreferences
import androidx.activity.compose.LocalActivity
import androidx.fragment.app.FragmentActivity
import org.thoughtcrime.securesms.main.setup.WhatsNewDialogFragment
import org.thoughtcrime.securesms.main.setup.WhatsNewTab
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.util.CommunicationActions

/**
 * The help section, wired to its view model and to the links it opens.
 */
@Composable
fun HelpSettingsEntry(parentEventEmitter: (AppSettingsNavigationEvent) -> Unit) {
  val context = LocalContext.current
  val supportCenterUrl = stringResource(R.string.support_center_url)
  val termsAndPrivacyPolicyUrl = stringResource(R.string.terms_and_privacy_policy_url)
  val websiteUrl = stringResource(R.string.website_url)
  val viewModel: HelpSettingsViewModel = viewModel {
    HelpSettingsViewModel(
      versionName = ApkInfo.versionName,
      logEnabled = TextSecurePreferences.isLogEnabled(context),
      showAppUpdates = AppSettingsRepository.showAppUpdates(),
      parentEventEmitter = parentEventEmitter
    )
  }
  val state by viewModel.state.collectAsStateWithLifecycle()

  fun setLogEnabled(enabled: Boolean) {
    TextSecurePreferences.setLogEnabled(context, enabled)
    Log.setLogging(enabled)
    if (!enabled) Log.wipeLogs()
    viewModel.onEvent(HelpSettingsEvent.LogEnabledSaved(enabled))
  }

  val fragmentManager = (LocalActivity.current as? FragmentActivity)?.supportFragmentManager
  CollectActions(viewModel.actions) { action ->
    when (action) {
      HelpSettingsAction.OpenWebsite -> CommunicationActions.openBrowserLink(context, websiteUrl)
      is HelpSettingsAction.SetLogEnabled -> {
        if (action.enabled) {
          setLogEnabled(true)
        } else {
          MaterialAlertDialogBuilder(context)
            .setMessage(R.string.HelpSettingsFragment_disable_and_delete_debug_log)
            .setPositiveButton(android.R.string.ok) { _, _ -> setLogEnabled(false) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        }
      }
      HelpSettingsAction.OpenWhatsNew -> fragmentManager?.let { WhatsNewDialogFragment.show(it, WhatsNewTab.PROJECT_LOG) }
      HelpSettingsAction.OpenSupportCenter -> CommunicationActions.openBrowserLink(context, supportCenterUrl)
      is HelpSettingsAction.CopyVersionToClipboard -> Util.copyToClipboard(context, action.versionName)
      HelpSettingsAction.OpenTermsAndPrivacyPolicy -> CommunicationActions.openBrowserLink(context, termsAndPrivacyPolicyUrl)
    }
  }

  HelpSettingsScreen(
    state = state,
    onEvent = viewModel::onEvent
  )
}
