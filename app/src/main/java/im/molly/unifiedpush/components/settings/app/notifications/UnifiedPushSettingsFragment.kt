package im.molly.unifiedpush.components.settings.app.notifications

import android.net.Uri
import android.os.Bundle
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.launch
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import im.molly.unifiedpush.enrollment.EnrollmentFile
import im.molly.unifiedpush.enrollment.PairingTransport
import im.molly.unifiedpush.enrollment.PairingV1
import im.molly.unifiedpush.enrollment.RelayDisclosure
import im.molly.unifiedpush.health.PushHealth
import im.molly.unifiedpush.model.MollySocket
import im.molly.unifiedpush.model.RegistrationStatus
import org.signal.core.util.eventbus.EventBus
import org.signal.core.util.eventbus.RestartableSubscription
import org.signal.core.util.eventbus.ThreadMode
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.components.settings.DSLConfiguration
import org.thoughtcrime.securesms.components.settings.DSLSettingsFragment
import org.thoughtcrime.securesms.components.settings.DSLSettingsText
import org.thoughtcrime.securesms.components.settings.configure
import org.thoughtcrime.securesms.events.PushServiceEvent
import org.thoughtcrime.securesms.util.adapter.mapping.MappingAdapter

class UnifiedPushSettingsFragment : DSLSettingsFragment(
  R.string.NotificationDeliveryMethod__unifiedpush,
  R.menu.unifiedpush_settings,
) {

  companion object {
    const val PAIRING_OFFER_ARGUMENT = "pairingOffer"
  }

  private lateinit var viewModel: UnifiedPushSettingsViewModel
  private var pairingDialog: AlertDialog? = null

  private val qrScanLauncher: ActivityResultLauncher<Unit> =
    registerForActivityResult(MollySocketQrScannerActivity.Contract()) { data ->
      val disclosure = viewModel.scanDisclosure
      viewModel.scanDisclosure = null
      if (data != null) {
        if (data.trimStart().startsWith("{")) {
          handlePairingOffer(data, disclosure)
        } else if (disclosure == null) {
          RelayDisclosureDialogs.showStaleAction(requireContext())
        } else {
          val mollySocket = MollySocket.parseLink(Uri.parse(data))
          if (mollySocket == null) {
            Toast.makeText(requireContext(), R.string.PairingV1__invalid_offer, Toast.LENGTH_LONG).show()
          } else {
            RelayDisclosureDialogs.showRelay(requireContext(), mollySocket) {
              viewModel.setMollySocket(mollySocket, disclosure)
            }
          }
        }
      }
    }

  override fun bindAdapter(adapter: MappingAdapter) {
    val factory = UnifiedPushSettingsViewModel.Factory(requireActivity().application)

    viewModel = ViewModelProvider(this, factory)[UnifiedPushSettingsViewModel::class.java]

    viewModel.state.observe(viewLifecycleOwner) {
      adapter.submitList(getConfiguration(it).toMappingModelList())
    }
    viewModel.pairingState.observe(viewLifecycleOwner) { result ->
      when (result) {
        is UnifiedPushSettingsViewModel.PairingUi.Paired -> {
          viewModel.consumePairingResult()
          MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.PairingV1__paired, result.hostLabel))
            .setMessage(R.string.PairingV1__waiting_confirmation)
            .setPositiveButton(android.R.string.ok, null)
            .show()
        }
        is UnifiedPushSettingsViewModel.PairingUi.Offline -> {
          showOfflinePairing(result.sessionId, result.hadSubmitUrl)
        }
        UnifiedPushSettingsViewModel.PairingUi.AwaitingPush -> {
          viewModel.consumePairingResult()
          Toast.makeText(requireContext(), R.string.PairingV1__waiting_confirmation, Toast.LENGTH_LONG).show()
        }
        is UnifiedPushSettingsViewModel.PairingUi.Error -> {
          viewModel.consumePairingResult()
          Toast.makeText(requireContext(), result.message, Toast.LENGTH_LONG).show()
        }
        else -> Unit
      }
    }
    RestartableSubscription { EventBus.default.subscribe<PushServiceEvent>(ThreadMode.MAIN, ::onPushServiceEvent) }
      .bindTo(viewLifecycleOwner, Lifecycle.State.RESUMED)

    val firstScanOffer = arguments?.getString(PAIRING_OFFER_ARGUMENT)
    val pendingConsent = viewModel.pendingPairingConsent
    if (pendingConsent != null) {
      showPairingDistributor(pendingConsent)
    } else if (firstScanOffer != null) {
      if (viewModel.claimInitialPairingOffer(firstScanOffer)) {
        handlePairingOffer(firstScanOffer, RelayDisclosure.capture(requireContext()))
      }
    } else if (!viewModel.pairingFlowActive) {
      viewModel.updateRegistration()
    }
  }

  override fun onDestroyView() {
    pairingDialog?.dismiss()
    pairingDialog = null
    super.onDestroyView()
  }

  /** Older activity state may still contain this dialog. The former export UI is no longer shown. */
  class ExportResultDialog : DialogFragment() {
    override fun onCreate(savedInstanceState: Bundle?) {
      super.onCreate(savedInstanceState)
      showsDialog = false
    }

    override fun onStart() {
      super.onStart()
      dismissAllowingStateLoss()
    }
  }

  private fun handlePairingOffer(json: String, disclosure: RelayDisclosure.Snapshot?) {
    if (viewModel.pairingBusy) return
    val scan = try {
      viewModel.parsePairingScan(json)
    } catch (e: EnrollmentFile.Invalid) {
      showPairingError(when (e.code) {
        "unsupported_intent" -> R.string.PairingV1__unsupported_add_receiver
        "session_expired" -> R.string.PairingV1__offer_expired
        "invalid_submit_url" -> R.string.PairingV1__invalid_submit_url
        else -> R.string.PairingV1__invalid_offer
      })
      return
    }
    if (disclosure == null) {
      showPairingError(R.string.PairingV1__endpoint_unavailable)
      return
    }
    viewModel.enterPairingFlow()
    val pending = UnifiedPushSettingsViewModel.PendingPairingConsent(scan, disclosure)
    viewModel.pendingPairingConsent = pending
    showPairingDistributor(pending)
  }

  private fun showPairingDistributor(pending: UnifiedPushSettingsViewModel.PendingPairingConsent) {
    val distributors = viewModel.pairingDistributors()
    val selectedId = pending.distributor?.applicationId ?: viewModel.pairingSelectedDistributor()
    val selected = distributors.firstOrNull { it.applicationId == selectedId }
    when {
      distributors.isEmpty() -> {
        viewModel.pendingPairingConsent = null
        showPairingError(R.string.PairingV1__select_distributor_first)
      }
      selected != null -> showPairingConsent(pending, selected)
      else -> pairingDialog = MaterialAlertDialogBuilder(requireContext())
        .setTitle(R.string.PairingV1__select_distributor_title)
        .setItems(distributors.map { it.name }.toTypedArray()) { _, index ->
          showPairingConsent(pending, distributors[index])
        }
        .setNegativeButton(android.R.string.cancel) { _, _ -> viewModel.pendingPairingConsent = null }
        .setOnCancelListener { viewModel.pendingPairingConsent = null }
        .show()
    }
  }

  /** UI fixture used by API 35 screenshot instrumentation to render the real approval dialog. */
  internal fun showPairingConsentFixtureForTest(wrapperJson: String) {
    val scan = viewModel.parsePairingScan(wrapperJson)
    require(scan.offer.intent == "add_receiver" && scan.bridgeDeviceId != null)
    val disclosure = requireNotNull(RelayDisclosure.capture(requireContext()))
    val pending = UnifiedPushSettingsViewModel.PendingPairingConsent(scan, disclosure)
    viewModel.pendingPairingConsent = pending
    viewModel.enterPairingFlow()
    showPairingConsent(pending, Distributor("test.collie.distributor", "Test distributor"), scan.offer.providerOrigin)
  }

  private fun showPairingConsent(
    choice: UnifiedPushSettingsViewModel.PendingPairingConsent,
    distributor: Distributor,
    fixtureOrigin: String? = null,
  ) {
    val pending = choice.copy(distributor = distributor)
    viewModel.pendingPairingConsent = pending
    val scan = pending.scan
    val offer = scan.offer
    val origin = fixtureOrigin ?: when (val plan = viewModel.evaluatePairingOffer(offer, distributor.applicationId)) {
      is PairingV1Coordinator.OfferPlan.Ready -> plan.consent.providerOrigin
      is PairingV1Coordinator.OfferPlan.Register -> plan.details.currentOrigin
      is PairingV1Coordinator.OfferPlan.Failed -> {
        viewModel.pendingPairingConsent = null
        Toast.makeText(requireContext(), plan.message, Toast.LENGTH_LONG).show()
        return
      }
    }
    val provider = when {
      offer.intent == "add_receiver" && origin != null -> getString(R.string.PairingV1__push_server, origin)
      offer.providerOrigin != null -> getString(R.string.PairingV1__push_server, offer.providerOrigin)
      origin != null -> getString(R.string.PairingV1__distributor_server, origin)
      else -> getString(R.string.PairingV1__distributor_server_unknown)
    }
    val submission = scan.submitUrl?.let {
      val submitOrigin = it.newBuilder().encodedPath("/").query(null).build().toString().removeSuffix("/")
      getString(R.string.PairingV1__submission_destination, submitOrigin)
    } ?: getString(R.string.PairingV1__manual_submission)
    val isReceiver = offer.intent == "add_receiver"
    val replacing = !isReceiver && viewModel.hasExistingBridgeSetup()
    val title = when {
      isReceiver -> R.string.PairingV1__add_receiver_consent_title
      replacing -> R.string.PairingV1__replace_setup_title
      else -> R.string.PairingV1__consent_title
    }
    val body = when {
      isReceiver -> R.string.PairingV1__add_receiver_consent_message
      replacing -> R.string.PairingV1__replace_setup_message
      else -> R.string.PairingV1__consent_message
    }
    val confirmation = when {
      isReceiver -> R.string.PairingV1__add_receiver_consent_confirm
      replacing -> R.string.PairingV1__replace_setup_confirm
      else -> R.string.PairingV1__consent_confirm
    }
    pairingDialog = MaterialAlertDialogBuilder(requireContext())
      .setTitle(getString(title, offer.hostLabel))
      .setMessage(getString(body, provider, distributor.name, submission))
      .setNegativeButton(android.R.string.cancel) { _, _ -> viewModel.pendingPairingConsent = null }
      .setOnCancelListener { viewModel.pendingPairingConsent = null }
      .setPositiveButton(confirmation) { _, _ ->
        viewModel.pendingPairingConsent = null
        viewModel.pair(scan, pending.disclosure, distributor.applicationId, offer.providerOrigin ?: origin, replacing)
      }
      .show()
  }

  private fun showOfflinePairing(sessionId: String, hadSubmitUrl: Boolean) {
    pairingDialog = MaterialAlertDialogBuilder(requireContext())
      .setTitle(R.string.PairingV1__offline_title)
      .setMessage(if (hadSubmitUrl) R.string.PairingV1__offline_unreachable else R.string.PairingV1__offline_manual)
      .setPositiveButton(R.string.PairingV1__share_request) { _, _ ->
        if (viewModel.sharePairingRequest(requireContext(), sessionId)) viewModel.consumePairingResult()
        else showPairingError(R.string.PairingV1__share_failed)
      }
      .setNegativeButton(R.string.PairingV1__close_request) { _, _ -> viewModel.closeOfflinePairing(sessionId) }
      .setOnCancelListener { viewModel.closeOfflinePairing(sessionId) }
      .show()
  }

  private fun showPairingError(@StringRes message: Int) {
    Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
  }

  private fun showControlStateRecoveryDialog() {
    MaterialAlertDialogBuilder(requireContext())
      .setTitle(R.string.UnifiedPushSettingsFragment__control_state_recovery_title)
      .setMessage(R.string.UnifiedPushSettingsFragment__control_state_recovery_revocation_message)
      .setPositiveButton(R.string.UnifiedPushSettingsFragment__control_state_recovery_confirm) { _, _ ->
        viewModel.recoverCorruptControlState()
      }
      .setNegativeButton(android.R.string.cancel, null)
      .show()
  }

  private fun showRemoveRelayAccessDialog() {
    MaterialAlertDialogBuilder(requireContext())
      .setTitle(R.string.UnifiedPushSettingsFragment__remove_relay_access_title)
      .setMessage(R.string.UnifiedPushSettingsFragment__remove_relay_access_message)
      .setPositiveButton(android.R.string.ok, null)
      .show()
  }

  private fun onPushServiceEvent(event: PushServiceEvent) {
    viewModel.refresh()
  }

  override fun onOptionsItemSelected(item: MenuItem): Boolean {
    if (item.itemId != R.id.unifiedpush_advanced) return super.onOptionsItemSelected(item)
    viewModel.state.value?.let(::showAdvanced)
    return true
  }

  private fun showAdvanced(state: UnifiedPushSettingsState) {
    val actions = mutableListOf<Pair<Int, () -> Unit>>()
    if (state.aci != null) {
      actions += R.string.PairingV1__copy_account to { viewModel.copyAciToClipboard(requireContext()) }
    }
    if (state.registrationFailed) {
      actions += R.string.UnifiedPushSettingsFragment__retry_registration to { viewModel.retryRegistration() }
    }
    if (state.controlStateCorrupt) {
      actions += R.string.UnifiedPushSettingsFragment__control_state_recovery to { showControlStateRecoveryDialog() }
    }
    if (viewModel.hasExistingBridgeSetup() || state.encryptedEnrollment) {
      actions += R.string.UnifiedPushSettingsFragment__remove_relay_access_title to { showRemoveRelayAccessDialog() }
    }
    if (actions.isEmpty()) return
    MaterialAlertDialogBuilder(requireContext())
      .setTitle(R.string.PairingV1__advanced)
      .setItems(actions.map { getString(it.first) }.toTypedArray()) { _, index -> actions[index].second.invoke() }
      .setNegativeButton(android.R.string.cancel, null)
      .show()
  }

  private fun getConfiguration(state: UnifiedPushSettingsState): DSLConfiguration = configure {
    textPref(
      title = DSLSettingsText.from(R.string.UnifiedPushSettingsFragment__status),
      summary = DSLSettingsText.from(getStatusSummary(state)),
    )

    val relaySummary = when {
      state.pairingHost != null && state.pairingOrigin != null ->
        getString(R.string.PairingV1__relay_summary, state.pairingHost, state.pairingOrigin)
      state.pairingOrigin != null -> state.pairingOrigin
      state.airGapped -> getString(R.string.PairingV1__configured_manually)
      else -> state.mollySocketUrl ?: getString(R.string.PairingV1__scan_to_pair)
    }
    clickPref(
      title = DSLSettingsText.from(R.string.PairingV1__notification_relay),
      summary = DSLSettingsText.from(relaySummary),
      isEnabled = !viewModel.pairingBusy,
      onClick = {
        val disclosure = state.relayDisclosure
        if (disclosure == null) {
          RelayDisclosureDialogs.showStaleAction(requireContext())
        } else {
          viewModel.scanDisclosure = disclosure
          qrScanLauncher.launch()
        }
      },
    )

    if (state.distributors.isEmpty()) {
      textPref(
        title = DSLSettingsText.from(R.string.UnifiedPushSettingsFragment__distributor_app),
        summary = DSLSettingsText.from(R.string.UnifiedPushSettingsFragment__none_available),
      )
    } else {
      radioListPref(
        title = DSLSettingsText.from(R.string.UnifiedPushSettingsFragment__distributor_app),
        listItems = state.distributors.map { it.name }.toTypedArray(),
        selected = state.selected,
        onSelected = { if (!viewModel.pairingBusy) viewModel.setUnifiedPushDistributor(state.distributors[it].applicationId) },
      )
    }

    val legacy = !state.encryptedEnrollment && (state.airGapped || state.mollySocketUrl != null)
    if (legacy) {
      val isRegistered = state.registrationStatus == RegistrationStatus.REGISTERED
      val summary = when {
        state.airGapped -> R.string.UnifiedPushSettingsFragment__test_push_legacy_manual
        !isRegistered -> R.string.UnifiedPushSettingsFragment__test_push_legacy_wait
        else -> R.string.UnifiedPushSettingsFragment__tap_to_request_a_test_notification_from_mollysocket
      }
      clickPref(
        title = DSLSettingsText.from(R.string.UnifiedPushSettingsFragment__test_configuration),
        summary = DSLSettingsText.from(summary),
        isEnabled = !viewModel.pairingBusy && !state.airGapped && isRegistered,
        onClick = {
          viewModel.pingMollySocket()
          Toast.makeText(requireContext(), R.string.UnifiedPushSettingsFragment__a_test_notification_should_appear_in_a_few_moments, Toast.LENGTH_SHORT).show()
        },
      )
    } else {
      clickPref(
        title = DSLSettingsText.from(R.string.UnifiedPushSettingsFragment__test_push),
        summary = DSLSettingsText.from(getPushTestSummary(state)),
        isEnabled = state.encryptedEnrollment && !state.awaitingPushConfirmation && !state.pushTestRunning && !viewModel.pairingBusy,
        onClick = { viewModel.testPush() },
      )
    }
  }

  @StringRes
  private fun getPushTestSummary(state: UnifiedPushSettingsState): Int {
    return when {
      state.pushTestRunning -> R.string.UnifiedPushSettingsFragment__test_push_running
      !state.encryptedEnrollment || state.awaitingPushConfirmation -> R.string.UnifiedPushSettingsFragment__test_push_unavailable
      state.pushTestResultAt == 0L -> R.string.UnifiedPushSettingsFragment__test_push_summary
      else -> when (state.pushTestResult) {
        PushHealth.Hop.NONE -> R.string.UnifiedPushSettingsFragment__test_push_ok
        PushHealth.Hop.DISTRIBUTOR -> R.string.UnifiedPushSettingsFragment__test_push_failed_distributor
        PushHealth.Hop.SIGNAL_SEND -> R.string.UnifiedPushSettingsFragment__test_push_failed_signal_send
        PushHealth.Hop.COLLIESOCKET_OR_PROVIDER -> R.string.UnifiedPushSettingsFragment__test_push_failed_colliesocket_or_provider
        PushHealth.Hop.UNAVAILABLE -> R.string.UnifiedPushSettingsFragment__test_push_try_again
        null -> R.string.UnifiedPushSettingsFragment__test_push_summary
      }
    }
  }

  @StringRes
  private fun getStatusSummary(state: UnifiedPushSettingsState): Int = when {
    viewModel.pairingBusy -> R.string.PairingV1__pairing_in_progress
    state.pushFallback -> R.string.PairingV1__status_websocket
    state.controlStateCorrupt -> R.string.UnifiedPushSettingsFragment__control_state_unavailable
    state.registrationFailed -> R.string.UnifiedPushSettingsFragment__registration_failed
    state.selectedNotAck || state.endpoint == null -> R.string.PairingV1__status_not_set_up
    state.awaitingPushConfirmation -> R.string.PairingV1__waiting_confirmation
    state.encryptedEnrollment -> R.string.PairingV1__status_connected
    state.mollySocketUrl == null && !state.airGapped -> R.string.PairingV1__status_not_set_up
    state.device == null -> R.string.PairingV1__status_not_set_up
    state.registrationStatus == RegistrationStatus.REGISTERED -> R.string.PairingV1__status_connected
    state.airGapped -> R.string.PairingV1__waiting_for_relay
    else -> R.string.UnifiedPushSettingsFragment__status_summary_pending
  }
}
