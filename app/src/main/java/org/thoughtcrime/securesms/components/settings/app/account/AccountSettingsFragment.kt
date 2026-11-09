/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.components.settings.app.account

<<<<<<< MOLLY
import android.content.Intent
=======
import android.app.Activity
import android.content.Intent
import android.os.Bundle
>>>>>>> SIGNAL
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.annotation.StringRes
<<<<<<< MOLLY
import androidx.annotation.VisibleForTesting
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
=======
>>>>>>> SIGNAL
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
<<<<<<< MOLLY
import androidx.compose.ui.res.vectorResource
=======
>>>>>>> SIGNAL
import androidx.fragment.app.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.fragment.findNavController
import com.google.android.material.snackbar.Snackbar
<<<<<<< MOLLY
=======
import org.signal.appsettings.account.AccountSettingsAction
import org.signal.appsettings.account.AccountSettingsEvent
import org.signal.appsettings.account.AccountSettingsScreen
import org.signal.appsettings.account.TwoFactorMethod
import org.signal.core.ui.ViewModelFactory
import org.signal.core.ui.biometrics.BiometricsAuthentication
import org.signal.core.ui.biometrics.rememberBiometricsAuthentication
import org.signal.core.ui.compose.CollectActions
>>>>>>> SIGNAL
import org.signal.core.ui.compose.ComposeFragment
import org.signal.core.util.ServiceUtil
import org.signal.passwordmanager.SignalPasskeyManager
import org.thoughtcrime.securesms.R
<<<<<<< MOLLY
import org.thoughtcrime.securesms.compose.rememberStatusBarColorNestedScrollModifier
=======
import org.thoughtcrime.securesms.components.settings.app.account.twofactor.TwoFactorNavArgs
>>>>>>> SIGNAL
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.lock.v2.CreateSvrPinActivity
<<<<<<< MOLLY
import org.thoughtcrime.securesms.lock.v2.PinKeyboardType
import org.thoughtcrime.securesms.pin.RegistrationLockV2Dialog
import org.thoughtcrime.securesms.registration.ui.RegistrationActivity
import org.thoughtcrime.securesms.util.PlayStoreUtil
import org.thoughtcrime.securesms.util.navigation.safeNavigate
=======
import org.thoughtcrime.securesms.registration.ui.RegistrationIntents
import org.thoughtcrime.securesms.util.CommunicationActions
import org.thoughtcrime.securesms.util.PlayStoreUtil
import org.thoughtcrime.securesms.util.navigation.safeNavigate
import org.signal.appsettings.R as AppSettingsR
>>>>>>> SIGNAL

/**
 * Account settings shown on a primary device. Carries out the [AccountSettingsAction]s that need an Activity or the
 * legacy nav graph.
 */
class AccountSettingsFragment : ComposeFragment() {

  private val viewModel: AccountSettingsViewModel by viewModels(
    factoryProducer = ViewModelFactory.factoryProducer {
      AccountSettingsViewModel(
        accountRepository = AccountSettingsRepository(),
        arePasskeysSupported = SignalPasskeyManager.isSupported(requireContext())
      )
    }
  )

  private lateinit var pinFlowLauncher: ActivityResultLauncher<Intent>

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    pinFlowLauncher = registerForActivityResult(StartActivityForResult()) { result ->
      if (result.resultCode == Activity.RESULT_OK) {
        viewModel.onEvent(AccountSettingsEvent.PinCreated)
      }
    }
  }

  override fun onResume() {
    super.onResume()
    viewModel.onEvent(AccountSettingsEvent.ScreenResumed)
  }

  @Composable
  override fun FragmentContent() {
    val state by viewModel.state.collectAsStateWithLifecycle()

    val addMethodBiometrics = rememberBiometricsAuthentication(
      promptTitle = stringResource(AppSettingsR.string.AccountSettingsFragment__unlock_to_add_two_factor_method),
      educationSheetMessage = stringResource(AppSettingsR.string.AccountSettingsFragment__to_add_this_method_confirm_its_you),
      onAuthenticationFailed = { viewModel.onEvent(AccountSettingsEvent.AuthenticationFailed) }
    )

    val removalBiometrics = rememberBiometricsAuthentication(
      promptTitle = stringResource(AppSettingsR.string.AccountSettingsFragment__unlock_to_remove_two_factor_method),
      educationSheetMessage = stringResource(AppSettingsR.string.AccountSettingsFragment__to_remove_this_method_confirm_its_you),
      onAuthenticationFailed = { viewModel.onEvent(AccountSettingsEvent.AuthenticationFailed) }
    )

    val signalLoginBiometrics = rememberBiometricsAuthentication(
      promptTitle = stringResource(AppSettingsR.string.AccountSettingsFragment__unlock_to_view_signal_login),
      educationSheetMessage = stringResource(AppSettingsR.string.AccountSettingsFragment__to_view_your_signal_login_confirm_its_you),
      onAuthenticationFailed = { viewModel.onEvent(AccountSettingsEvent.AuthenticationFailed) }
    )

    val deleteAccountBiometrics = rememberBiometricsAuthentication(
      promptTitle = stringResource(AppSettingsR.string.AccountSettingsFragment__unlock_to_confirm_its_you),
      educationSheetMessage = stringResource(AppSettingsR.string.AccountSettingsFragment__to_delete_your_account_confirm_its_you),
      onAuthenticationFailed = { viewModel.onEvent(AccountSettingsEvent.AuthenticationFailed) }
    )

    CollectActions(viewModel.actions) { action -> handleAction(action, addMethodBiometrics, removalBiometrics, signalLoginBiometrics, deleteAccountBiometrics) }

    AccountSettingsScreen(
      state = state,
      onEvent = viewModel::onEvent
    )
  }

  private fun handleAction(
    action: AccountSettingsAction,
    addMethodBiometrics: BiometricsAuthentication,
    removalBiometrics: BiometricsAuthentication,
    signalLoginBiometrics: BiometricsAuthentication,
    deleteAccountBiometrics: BiometricsAuthentication
  ) {
    when (action) {
      AccountSettingsAction.NavigateBack -> {
        requireActivity().onBackPressedDispatcher.onBackPressed()
      }

<<<<<<< MOLLY
  private fun setPinRemindersEnabled(enabled: Boolean) {
    SignalStore.pin.setPinRemindersEnabled(enabled)
    viewModel.refreshState()
  }

  private inner class Callbacks : AccountSettingsScreenCallbacks {
    override fun onNavigationClick() {
      activity?.onBackPressedDispatcher?.onBackPressed()
    }

    @Suppress("DEPRECATION")
    override fun onChangePinClick() {
      startActivityForResult(CreateSvrPinActivity.getIntentForPinChangeFromSettings(requireContext()), CreateSvrPinActivity.REQUEST_NEW_PIN)
    }

    @Suppress("DEPRECATION")
    override fun onCreatePinClick() {
      startActivityForResult(CreateSvrPinActivity.getIntentForPinCreate(requireContext()), CreateSvrPinActivity.REQUEST_NEW_PIN)
    }

    override fun setPinRemindersEnabled(enabled: Boolean) {
      this@AccountSettingsFragment.setPinRemindersEnabled(enabled)
    }

    override fun setRegistrationLockEnabled(enabled: Boolean) {
      this@AccountSettingsFragment.setRegistrationLockEnabled(enabled)
    }

    override fun openAdvancedPinSettings() {
      findNavController().safeNavigate(R.id.action_accountSettingsFragment_to_advancedPinSettingsActivity)
    }

    override fun openChangeNumberFlow() {
      findNavController().safeNavigate(R.id.action_accountSettingsFragment_to_changePhoneNumberFragment)
    }

    override fun openDeviceTransferFlow() {
      findNavController().safeNavigate(R.id.action_accountSettingsFragment_to_oldDeviceTransferActivity)
    }

    override fun openExportAccountDataFlow() {
      findNavController().safeNavigate(R.id.action_accountSettingsFragment_to_exportAccountFragment)
    }

    override fun openUpdateAppFlow() {
      PlayStoreUtil.openPlayStoreOrOurApkDownloadPage(requireContext())
    }

    override fun openReRegistrationFlow() {
      startActivity(RegistrationActivity.newIntentForReRegistration(requireContext()))
    }

    override fun openDeleteAccountFlow() {
      findNavController().safeNavigate(R.id.action_accountSettingsFragment_to_deleteAccountFragment)
    }

    override fun deleteAllData() {
      if (!ServiceUtil.getActivityManager(AppDependencies.application).clearApplicationUserData()) {
        Toast.makeText(requireContext(), R.string.preferences_account_delete_all_data_failed, Toast.LENGTH_LONG).show()
      }
    }
  }
}

@Stable
@VisibleForTesting
interface AccountSettingsScreenCallbacks {

  fun onNavigationClick() = Unit
  fun onChangePinClick() = Unit
  fun onCreatePinClick() = Unit
  fun setPinRemindersEnabled(enabled: Boolean) = Unit
  fun setRegistrationLockEnabled(enabled: Boolean) = Unit
  fun openAdvancedPinSettings() = Unit
  fun openChangeNumberFlow() = Unit
  fun openDeviceTransferFlow() = Unit
  fun openExportAccountDataFlow() = Unit
  fun openUpdateAppFlow() = Unit
  fun openReRegistrationFlow() = Unit
  fun openDeleteAccountFlow() = Unit
  fun deleteAllData() = Unit

  object Empty : AccountSettingsScreenCallbacks
}

@VisibleForTesting
object AccountSettingsTestTags {
  const val SCROLLER = "scroller"
  const val ROW_MODIFY_PIN = "row-modify-pin"
  const val ROW_PIN_REMINDER = "row-pin-reminder"
  const val ROW_REGISTRATION_LOCK = "row-registration-lock"
  const val ROW_ADVANCED_PIN_SETTINGS = "row-advanced-pin-settings"
  const val ROW_CHANGE_PHONE_NUMBER = "row-change-phone-number"
  const val ROW_TRANSFER_ACCOUNT = "row-transfer-account"
  const val ROW_REQUEST_ACCOUNT_DATA = "row-request-account-data"
  const val ROW_UPDATE_SIGNAL = "row-update-signal"
  const val ROW_RE_REGISTER = "row-re-register"
  const val ROW_DELETE_ALL_DATA = "row-delete-all-data"
  const val ROW_DELETE_ACCOUNT = "row-delete-account"
  const val DIALOG_CONFIRM_DELETE_ALL_DATA = "dialog-confirm-delete-all-data"
}

@Composable
@VisibleForTesting
fun AccountSettingsScreen(
  state: AccountSettingsState,
  callbacks: AccountSettingsScreenCallbacks
) {
  Scaffolds.Settings(
    title = stringResource(R.string.AccountSettingsFragment__account),
    onNavigationClick = callbacks::onNavigationClick,
    navigationIcon = ImageVector.vectorResource(R.drawable.ic_arrow_left_24)
  ) { contentPadding ->
    LazyColumn(
      modifier = Modifier
        .padding(contentPadding)
        .then(rememberStatusBarColorNestedScrollModifier())
        .testTag(AccountSettingsTestTags.SCROLLER)
    ) {
      item {
        Texts.SectionHeader(
          text = stringResource(R.string.preferences_app_protection__signal_pin)
        )
      }

      if (state.isLinkedDevice) {
        item {
          Rows.TextRow(
            text = stringResource(R.string.AccountSettingsFragment_pin_settings_cannot_be_changed_from_a_linked_device),
          )
        }
      } else {
        item {
          @StringRes val textId = if (state.hasPin || state.hasRestoredAep) {
            R.string.preferences_app_protection__change_your_pin
          } else {
            R.string.preferences_app_protection__create_a_pin
          }

          Rows.TextRow(
            text = stringResource(textId),
            enabled = state.isNotDeprecatedOrUnregistered(),
            onClick = {
              if (state.hasPin) {
                callbacks.onChangePinClick()
              } else {
                callbacks.onCreatePinClick()
              }
            },
            modifier = Modifier.testTag(AccountSettingsTestTags.ROW_MODIFY_PIN)
          )
        }

        item {
          Rows.ToggleRow(
            text = stringResource(R.string.preferences_app_protection__pin_reminders),
            label = stringResource(R.string.AccountSettingsFragment__youll_be_asked_less_frequently),
            checked = state.hasPin && state.pinRemindersEnabled,
            enabled = state.hasPin && state.isNotDeprecatedOrUnregistered(),
            onCheckChanged = callbacks::setPinRemindersEnabled,
            modifier = Modifier.testTag(AccountSettingsTestTags.ROW_PIN_REMINDER)
          )
        }

        item {
          Rows.ToggleRow(
            text = stringResource(R.string.preferences_app_protection__registration_lock),
            label = stringResource(R.string.AccountSettingsFragment__require_your_signal_pin),
            checked = state.registrationLockEnabled,
            enabled = state.hasPin && state.isNotDeprecatedOrUnregistered(),
            onCheckChanged = callbacks::setRegistrationLockEnabled,
            modifier = Modifier.testTag(AccountSettingsTestTags.ROW_REGISTRATION_LOCK)
          )
        }

        item {
          Rows.TextRow(
            text = stringResource(R.string.preferences__advanced_pin_settings),
            enabled = state.isNotDeprecatedOrUnregistered(),
            onClick = callbacks::openAdvancedPinSettings,
            modifier = Modifier.testTag(AccountSettingsTestTags.ROW_ADVANCED_PIN_SETTINGS)
          )
        }
      }

      item {
        Dividers.Default()
      }

      item {
        Texts.SectionHeader(
          text = stringResource(R.string.AccountSettingsFragment__account)
        )
      }

      if (!state.userUnregistered) {
        item {
          Rows.TextRow(
            text = stringResource(R.string.AccountSettingsFragment__change_phone_number),
            enabled = state.isNotDeprecatedOrUnregistered() && !state.isLinkedDevice,
            onClick = callbacks::openChangeNumberFlow,
            modifier = Modifier.testTag(AccountSettingsTestTags.ROW_CHANGE_PHONE_NUMBER)
          )
        }
      }

      item {
        Rows.TextRow(
          text = stringResource(R.string.preferences_chats__transfer_account),
          label = stringResource(R.string.preferences_chats__transfer_account_to_a_new_android_device),
          enabled = state.canTransferWhileUnregistered || state.isNotDeprecatedOrUnregistered() && !state.isLinkedDevice,
          onClick = callbacks::openDeviceTransferFlow,
          modifier = Modifier.testTag(AccountSettingsTestTags.ROW_TRANSFER_ACCOUNT)
=======
      AccountSettingsAction.LaunchCreatePinFlow -> {
        pinFlowLauncher.launch(CreateSvrPinActivity.getIntentForPinCreate(requireContext()))
      }

      AccountSettingsAction.LaunchChangePinFlow -> {
        pinFlowLauncher.launch(CreateSvrPinActivity.getIntentForPinChangeFromSettings(requireContext()))
      }

      AccountSettingsAction.ShowPinCreatedConfirmation -> {
        Snackbar.make(requireView(), R.string.ConfirmKbsPinFragment__pin_created, Snackbar.LENGTH_LONG).show()
      }

      AccountSettingsAction.AuthenticateToViewSignalLoginDetails -> {
        signalLoginBiometrics.withBiometricsAuthentication {
          viewModel.onEvent(AccountSettingsEvent.SignalLoginDetailsAuthenticated)
        }
      }

      AccountSettingsAction.NavigateToSignalLoginDetails -> {
        findNavController().safeNavigate(R.id.action_accountSettingsFragment_to_settingsSignalLoginDetailsFragment)
      }

      AccountSettingsAction.AuthenticateToAddTotpApp -> {
        addMethodBiometrics.withBiometricsAuthentication {
          viewModel.onEvent(AccountSettingsEvent.AddTotpAppAuthenticated)
        }
      }

      AccountSettingsAction.NavigateToTotpSetup -> {
        findNavController().safeNavigate(R.id.action_accountSettingsFragment_to_authenticatorSetupFragment)
      }

      AccountSettingsAction.AuthenticateToAddPasskey -> {
        addMethodBiometrics.withBiometricsAuthentication {
          viewModel.onEvent(AccountSettingsEvent.AddPasskeyAuthenticated)
        }
      }

      AccountSettingsAction.NavigateToPasskeySetup -> {
        findNavController().safeNavigate(R.id.action_accountSettingsFragment_to_passkeySetupFragment)
      }

      is AccountSettingsAction.NavigateToRenameMethod -> {
        findNavController().safeNavigate(
          R.id.action_accountSettingsFragment_to_twoFactorNameFragment,
          Bundle().apply { TwoFactorNavArgs.putRenamedMethod(this, action.method) }
>>>>>>> SIGNAL
        )
      }

      is AccountSettingsAction.AuthenticateToRemoveMethod -> {
        removalBiometrics.withBiometricsAuthentication {
          viewModel.onEvent(AccountSettingsEvent.MethodRemovalAuthenticated(action.method))
        }
      }

      AccountSettingsAction.ShowAuthenticationFailed -> {
        toast(AppSettingsR.string.AccountSettingsFragment__authentication_required)
      }

      is AccountSettingsAction.ShowMethodRemoved -> {
        toast(
          when (action.kind) {
            TwoFactorMethod.Kind.AUTHENTICATOR_APP -> AppSettingsR.string.AccountSettingsFragment__authenticator_app_removed
            TwoFactorMethod.Kind.PASSKEY -> AppSettingsR.string.AccountSettingsFragment__passkey_removed
            TwoFactorMethod.Kind.OTHER -> AppSettingsR.string.AccountSettingsFragment__two_factor_method_removed
          }
        )
      }

      is AccountSettingsAction.ShowMethodRemovalFailed -> toast(
        when (action.kind) {
          TwoFactorMethod.Kind.AUTHENTICATOR_APP -> AppSettingsR.string.AccountSettingsFragment__couldnt_remove_authenticator_app
          TwoFactorMethod.Kind.PASSKEY -> AppSettingsR.string.AccountSettingsFragment__couldnt_remove_passkey
          TwoFactorMethod.Kind.OTHER -> AppSettingsR.string.AccountSettingsFragment__couldnt_remove_two_factor_method
        }
      )

      is AccountSettingsAction.OpenSupportArticle -> {
        CommunicationActions.openBrowserLink(requireContext(), action.url)
      }

      AccountSettingsAction.NavigateToAdvancedPinSettings -> {
        findNavController().safeNavigate(R.id.action_accountSettingsFragment_to_advancedPinSettingsActivity)
      }

      AccountSettingsAction.NavigateToChangePhoneNumber -> {
        findNavController().safeNavigate(R.id.action_accountSettingsFragment_to_changePhoneNumberFragment)
      }

      AccountSettingsAction.NavigateToDeviceTransfer -> {
        findNavController().safeNavigate(R.id.action_accountSettingsFragment_to_oldDeviceTransferActivity)
      }

      AccountSettingsAction.NavigateToExportAccountData -> {
        findNavController().safeNavigate(R.id.action_accountSettingsFragment_to_exportAccountFragment)
      }

      AccountSettingsAction.AuthenticateToDeleteAccount -> {
        deleteAccountBiometrics.withBiometricsAuthentication {
          viewModel.onEvent(AccountSettingsEvent.DeleteAccountAuthenticated)
        }
      }

      AccountSettingsAction.NavigateToDeleteAccount -> {
        findNavController().safeNavigate(R.id.action_accountSettingsFragment_to_deleteAccountFragment)
      }

      AccountSettingsAction.OpenPlayStore -> {
        PlayStoreUtil.openPlayStoreOrOurApkDownloadPage(requireContext())
      }

      AccountSettingsAction.LaunchReRegistration -> {
        startActivity(RegistrationIntents.newIntentForReRegistration(requireContext()))
      }

      AccountSettingsAction.WipeAllData -> {
        if (!ServiceUtil.getActivityManager(AppDependencies.application).clearApplicationUserData()) {
          viewModel.onEvent(AccountSettingsEvent.DataWipeFailed)
        }
      }

      AccountSettingsAction.ShowDataWipeFailed -> {
        toast(R.string.preferences_account_delete_all_data_failed)
      }

      AccountSettingsAction.ShowRegistrationLockEnableFailed -> {
        toast(R.string.preferences_app_protection__failed_to_enable_registration_lock)
      }

      AccountSettingsAction.ShowRegistrationLockDisableFailed -> {
        toast(R.string.preferences_app_protection__failed_to_disable_registration_lock)
      }
    }
  }

  private fun toast(@StringRes message: Int) {
    Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
  }
}
