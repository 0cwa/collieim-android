package org.thoughtcrime.securesms.components.settings.app.privacy

import androidx.compose.runtime.Composable
import androidx.navigation.fragment.findNavController
import org.signal.appsettings.routes.AppSettingsRoute
import org.signal.core.ui.compose.ComposeFragment
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.components.settings.app.appSettingsRouter
import org.thoughtcrime.securesms.util.navigation.safeNavigate

/**
 * Fragment host for [PrivacySettingsEntry] until app settings moves to Nav3. Only translates routes into the nav graph.
 */
class PrivacySettingsFragment : ComposeFragment() {

  private val appSettingsRouter by appSettingsRouter(onNavigateToScreen = ::navigateToScreen)

  private fun navigateToScreen(route: AppSettingsRoute) {
    when (route) {
      AppSettingsRoute.PrivacyRoute.PhoneNumberPrivacy -> findNavController().safeNavigate(R.id.action_privacySettingsFragment_to_phoneNumberPrivacySettingsFragment)
      AppSettingsRoute.PrivacyRoute.BlockedUsers -> findNavController().safeNavigate(R.id.action_privacySettingsFragment_to_blockedUsersActivity)
      AppSettingsRoute.PrivacyRoute.HiddenContacts -> findNavController().safeNavigate(R.id.action_privacySettingsFragment_to_hiddenContactsFragment)
      AppSettingsRoute.PrivacyRoute.ExpiringMessages -> findNavController().safeNavigate(R.id.action_privacySettingsFragment_to_disappearingMessagesTimerSelectFragment)
      AppSettingsRoute.PrivacyRoute.Advanced -> findNavController().safeNavigate(R.id.action_privacySettingsFragment_to_advancedPrivacySettingsFragment)
      // COLLIE: The proxy row of the inline network section opens the standalone network settings.
      AppSettingsRoute.DataAndStorageRoute.Proxy -> findNavController().safeNavigate(R.id.action_privacy_to_networkPreferenceFragment)
      else -> error("Unexpected route: ${route.javaClass.name}")
    }
  }

  @Composable
  override fun FragmentContent() {
    PrivacySettingsEntry(parentEventEmitter = appSettingsRouter::onEvent)
  }
}
