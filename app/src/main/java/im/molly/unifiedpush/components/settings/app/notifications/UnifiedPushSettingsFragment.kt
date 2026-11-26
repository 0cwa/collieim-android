package im.molly.unifiedpush.components.settings.app.notifications

import android.app.Dialog
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.MenuItem
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.launch
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.checkbox.MaterialCheckBox
import im.molly.unifiedpush.enrollment.ExportMode
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
import org.thoughtcrime.securesms.util.WindowUtil
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

  private val enrollmentDocumentLauncher: ActivityResultLauncher<String> =
    registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
      viewModel.completeEnrollmentExport(uri)
    }

  private val pairingSaveLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
    viewModel.completePairingSave(uri)
  }

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
          if (viewModel.pendingPairingSaveId == null) showOfflinePairing(result.sessionId, result.hadSubmitUrl)
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
    viewModel.exportState.observe(viewLifecycleOwner) { result ->
      if (result == null || result.message != null) viewModel.refresh()
      else viewModel.state.value?.let { adapter.submitList(getConfiguration(it).toMappingModelList()) }
      showPendingExportResult()
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

  override fun onResume() {
    super.onResume()
    if (::viewModel.isInitialized) showPendingExportResult()
  }

  override fun onDestroyView() {
    pairingDialog?.dismiss()
    pairingDialog = null
    super.onDestroyView()
  }

  private fun showPendingExportResult() {
    val result = UnifiedPushSettingsViewModel.unreadExport() ?: return
    if (childFragmentManager.isStateSaved || childFragmentManager.findFragmentByTag(ExportResultDialog.TAG) != null) return
    ExportResultDialog().apply {
      arguments = bundleOf(ExportResultDialog.RESULT_ID to result.id)
    }.showNow(childFragmentManager, ExportResultDialog.TAG)
  }

  class ExportResultDialog : DialogFragment() {
    private val resultId: String? get() = requireArguments().getString(RESULT_ID)

    override fun onCreate(savedInstanceState: Bundle?) {
      super.onCreate(savedInstanceState)
      showsDialog = resultId != null && UnifiedPushSettingsViewModel.unreadExport()?.id == resultId
      isCancelable = false
    }

    override fun onStart() {
      super.onStart()
      if (!showsDialog) dismissAllowingStateLoss()
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
      val result = UnifiedPushSettingsViewModel.unreadExport()?.takeIf { it.id == resultId }
      val dialog = MaterialAlertDialogBuilder(requireContext())
        .setTitle(R.string.UnifiedPushSettingsFragment__export_enrollment)
        .setMessage(result?.message)
        .setPositiveButton(android.R.string.ok) { _, _ ->
          result?.let { UnifiedPushSettingsViewModel.acknowledgeExportResult(it.id) }
        }
        .create()
      WindowUtil.initializeScreenshotSecurity(requireContext(), requireNotNull(dialog.window))
      return dialog
    }

    companion object {
      const val TAG = "relay-export-result"
      const val RESULT_ID = "export-result-id"
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
    showPairingConsent(pending, Distributor("org.collie.pack", "Collie Pack"), scan.offer.providerOrigin)
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
      getString(R.string.PairingV1__submission_destination, it.toString())
    } ?: getString(R.string.PairingV1__manual_submission)
    val isReceiver = offer.intent == "add_receiver"
    val builder = MaterialAlertDialogBuilder(requireContext())
      .setTitle(getString(if (isReceiver) R.string.PairingV1__add_receiver_consent_title else R.string.PairingV1__consent_title, offer.hostLabel))
      .setMessage(getString(if (isReceiver) R.string.PairingV1__add_receiver_consent_message else R.string.PairingV1__consent_message, provider, distributor.name, submission))
      .setNegativeButton(android.R.string.cancel) { _, _ -> viewModel.pendingPairingConsent = null }
      .setOnCancelListener { viewModel.pendingPairingConsent = null }
      .setPositiveButton(if (isReceiver) R.string.PairingV1__add_receiver_consent_confirm else R.string.PairingV1__consent_confirm, null)
    val state = viewModel.state.value
    val needsServerEpoch = !isReceiver && state != null && (state.enrollmentEpoch > 0 || state.savedExports.isNotEmpty() || state.hasLegacyPendingExport)
    val epochInput = if (needsServerEpoch) EditText(requireContext()).apply {
      inputType = InputType.TYPE_CLASS_NUMBER
      hint = getString(R.string.PairingV1__current_relay_epoch)
      setText(pending.serverEpoch)
      doAfterTextChanged { pending.serverEpoch = it.toString() }
    } else null
    if (epochInput != null) {
      val recovery = MaterialCheckBox(requireContext()).apply {
        setText(R.string.PairingV1__allow_epoch_recovery)
        isChecked = pending.allowRecovery
        setOnCheckedChangeListener { _, checked -> pending.allowRecovery = checked }
      }
      val padding = (24 * resources.displayMetrics.density).toInt()
      builder.setView(LinearLayout(requireContext()).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(padding, 0, padding, 0)
        addView(epochInput)
        addView(recovery)
      })
    }
    val dialog = builder.show()
    pairingDialog = dialog
    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
      val epoch = pending.serverEpoch.toLongOrNull()
      if (epochInput != null && (epoch == null || epoch !in 0L until 9007199254740991L)) {
        epochInput.error = getString(R.string.UnifiedPushSettingsFragment__export_enrollment_invalid_epoch)
      } else if (state != null && epoch != null && epoch < state.enrollmentEpoch && !pending.allowRecovery) {
        epochInput?.error = getString(R.string.PairingV1__epoch_recovery_required)
      } else {
        viewModel.pendingPairingConsent = null
        dialog.dismiss()
        viewModel.pair(scan, pending.disclosure, distributor.applicationId, offer.providerOrigin ?: origin, epoch, pending.allowRecovery)
      }
    }
  }

  private fun showOfflinePairing(sessionId: String, hadSubmitUrl: Boolean) {
    pairingDialog = MaterialAlertDialogBuilder(requireContext())
      .setTitle(R.string.PairingV1__offline_title)
      .setMessage(if (hadSubmitUrl) R.string.PairingV1__offline_unreachable else R.string.PairingV1__offline_manual)
      .setPositiveButton(R.string.PairingV1__share_request) { _, _ ->
        if (viewModel.sharePairingRequest(requireContext(), sessionId)) viewModel.consumePairingResult()
        else showPairingError(R.string.PairingV1__file_action_failed)
      }
      .setNegativeButton(R.string.PairingV1__save_request) { _, _ ->
        viewModel.pendingPairingSaveId = sessionId
        pairingSaveLauncher.launch("colliesocket-pairing-request.json")
      }
      .setOnCancelListener { viewModel.cancelOfflinePairing(sessionId) }
      .show()
  }

  private fun showPairingError(@StringRes message: Int) {
    Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
  }

  private fun showExportModeDialog(state: UnifiedPushSettingsState) {
    val disclosure = state.relayDisclosure ?: return RelayDisclosureDialogs.showStaleAction(requireContext())
    val eligible = state.savedExports.filter { it.eligible }
    val choices = mutableListOf(
      getString(R.string.UnifiedPushSettingsFragment__export_new_intent),
      getString(R.string.UnifiedPushSettingsFragment__export_recovery_intent),
    )
    if (eligible.isNotEmpty()) choices += getString(R.string.UnifiedPushSettingsFragment__export_rerender_intent)
    MaterialAlertDialogBuilder(requireContext())
      .setTitle(R.string.UnifiedPushSettingsFragment__export_enrollment)
      .setItems(choices.toTypedArray()) { _, which ->
        when (which) {
          0 -> showExportEpochDialog(ExportMode.NEW_INTENT, disclosure)
          1 -> showRecoveryAcknowledgement(disclosure)
          2 -> showRerenderDialog(eligible, disclosure)
        }
      }
      .setNegativeButton(android.R.string.cancel, null)
      .show()
  }

  private fun showRecoveryAcknowledgement(disclosure: RelayDisclosure.Snapshot) {
    MaterialAlertDialogBuilder(requireContext())
      .setTitle(R.string.UnifiedPushSettingsFragment__export_recovery_title)
      .setMessage(R.string.UnifiedPushSettingsFragment__export_recovery_message)
      .setPositiveButton(R.string.UnifiedPushSettingsFragment__export_continue) { _, _ -> showExportEpochDialog(ExportMode.RECOVERY, disclosure) }
      .setNegativeButton(android.R.string.cancel, null)
      .show()
  }

  private fun showExportEpochDialog(mode: ExportMode, disclosure: RelayDisclosure.Snapshot) {
    val input = EditText(requireContext()).apply {
      inputType = InputType.TYPE_CLASS_NUMBER
      hint = getString(R.string.UnifiedPushSettingsFragment__export_enrollment_epoch_hint)
    }
    val padding = (24 * resources.displayMetrics.density).toInt()
    val container = FrameLayout(requireContext()).apply {
      setPadding(padding, 0, padding, 0)
      addView(input)
    }
    MaterialAlertDialogBuilder(requireContext())
      .setTitle(R.string.UnifiedPushSettingsFragment__export_enrollment)
      .setMessage(R.string.UnifiedPushSettingsFragment__export_enrollment_epoch_message)
      .setView(container)
      .setPositiveButton(R.string.UnifiedPushSettingsFragment__export_enrollment_export) { _, _ ->
        val serverEpoch = input.text.toString()
        RelayDisclosureDialogs.showExport(requireContext()) {
          viewModel.prepareEnrollmentExport(serverEpoch, mode, disclosure = disclosure)?.let(enrollmentDocumentLauncher::launch)
        }
      }
      .setNegativeButton(android.R.string.cancel, null)
      .show()
  }

  private fun showRerenderDialog(exports: List<SavedExport>, disclosure: RelayDisclosure.Snapshot) {
    val labels = exports.mapIndexed { index, saved ->
      getString(R.string.UnifiedPushSettingsFragment__saved_export_label, index + 1, saved.epoch)
    }.toTypedArray()
    MaterialAlertDialogBuilder(requireContext())
      .setTitle(R.string.UnifiedPushSettingsFragment__rerender_export_title)
      .setItems(labels) { _, which ->
        val requestId = exports[which].requestId
        RelayDisclosureDialogs.showExport(requireContext()) {
          viewModel.prepareEnrollmentExport("", ExportMode.RERENDER, requestId, disclosure)?.let(enrollmentDocumentLauncher::launch)
        }
      }
      .setNegativeButton(android.R.string.cancel, null)
      .show()
  }

  private fun showForgetIntentDialog(state: UnifiedPushSettingsState) {
    val items = state.savedExports.map { saved ->
      Triple(
        getString(
          if (saved.eligible) R.string.UnifiedPushSettingsFragment__forget_export_label
          else R.string.UnifiedPushSettingsFragment__forget_superseded_export_label,
          saved.epoch,
        ),
        saved.requestId,
        false,
      )
    }.toMutableList()
    if (state.hasLegacyPendingExport) {
      items += Triple(getString(R.string.UnifiedPushSettingsFragment__forget_legacy_export_label), "", true)
    }
    MaterialAlertDialogBuilder(requireContext())
      .setTitle(R.string.UnifiedPushSettingsFragment__forget_export_title)
      .setItems(items.map { it.first }.toTypedArray()) { _, which ->
        val selected = items[which]
        MaterialAlertDialogBuilder(requireContext())
          .setTitle(R.string.UnifiedPushSettingsFragment__forget_export_title)
          .setMessage(R.string.UnifiedPushSettingsFragment__forget_export_revocation_warning)
          .setPositiveButton(R.string.UnifiedPushSettingsFragment__forget_export_confirm) { _, _ ->
            if (selected.third) viewModel.forgetLegacyExport() else viewModel.forgetExport(selected.second)
          }
          .setNegativeButton(android.R.string.cancel, null)
          .show()
      }
      .setNegativeButton(android.R.string.cancel, null)
      .show()
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
    if (!state.controlStateCorrupt && !viewModel.isExportPending()) {
      actions += R.string.PairingV1__manual_save to {
        val disclosure = state.relayDisclosure
        if (disclosure == null) {
          RelayDisclosureDialogs.showStaleAction(requireContext())
        } else if (state.enrollmentEpoch > 0 || state.savedExports.isNotEmpty() || state.hasLegacyPendingExport) {
          showExportEpochDialog(ExportMode.NEW_INTENT, disclosure)
        } else {
          RelayDisclosureDialogs.showExport(requireContext()) {
            viewModel.prepareEnrollmentExport("", ExportMode.NEW_INTENT, disclosure = disclosure)
              ?.let(enrollmentDocumentLauncher::launch)
          }
        }
      }
      actions += R.string.PairingV1__export_options to { showExportModeDialog(state) }
    }
    if (state.controlStateCorrupt) {
      actions += R.string.UnifiedPushSettingsFragment__control_state_recovery to { showControlStateRecoveryDialog() }
    } else if (state.pendingExportCount > 0) {
      actions += R.string.UnifiedPushSettingsFragment__pending_exports to { showForgetIntentDialog(state) }
    }
    if (state.registrationFailed) {
      actions += R.string.UnifiedPushSettingsFragment__retry_registration to { viewModel.retryRegistration() }
    }
    if (state.airGapped && state.serverParameters != null) {
      actions += R.string.UnifiedPushSettingsFragment__server_parameters to {
        val disclosure = state.relayDisclosure
        if (disclosure == null) {
          RelayDisclosureDialogs.showStaleAction(requireContext())
        } else {
          RelayDisclosureDialogs.showCopy(requireContext()) {
            viewModel.copyParamsToClipboard(requireContext(), disclosure)
          }
        }
      }
    }
    if (state.aci != null) {
      actions += R.string.PairingV1__copy_account to { viewModel.copyAciToClipboard(requireContext()) }
    }
    actions += R.string.UnifiedPushSettingsFragment__relay_trust_title to {
      MaterialAlertDialogBuilder(requireContext())
        .setTitle(R.string.UnifiedPushSettingsFragment__relay_trust_title)
        .setMessage(R.string.UnifiedPushSettingsFragment__relay_trust_summary)
        .setPositiveButton(android.R.string.ok, null)
        .show()
    }
    actions += R.string.UnifiedPushSettingsFragment__remove_relay_access_title to { showRemoveRelayAccessDialog() }
    MaterialAlertDialogBuilder(requireContext())
      .setTitle(R.string.PairingV1__advanced)
      .setItems(actions.map { getString(it.first) }.toTypedArray()) { _, index -> actions[index].second.invoke() }
      .setNegativeButton(android.R.string.cancel, null)
      .show()
  }

  private fun getConfiguration(state: UnifiedPushSettingsState): DSLConfiguration = configure {
    textPref(
      title = DSLSettingsText.from(R.string.UnifiedPushSettingsFragment__status),
      summary = DSLSettingsText.from(if (state.receiverUpdatePending) R.string.PairingV1__waiting_confirmation else getStatusSummary(state)),
    )

    val relaySummary = when {
      state.pairingHost != null && state.pairingOrigin != null ->
        getString(R.string.PairingV1__relay_summary, state.pairingHost, state.pairingOrigin)
      state.pairingOrigin != null -> state.pairingOrigin
      state.airGapped -> getString(R.string.UnifiedPushSettingsFragment__mollysocket_server_air_gapped)
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

    val legacy = !state.encryptedEnrollment && !state.airGapped && state.mollySocketUrl != null
    if (legacy) {
      clickPref(
        title = DSLSettingsText.from(R.string.UnifiedPushSettingsFragment__test_configuration),
        summary = DSLSettingsText.from(R.string.UnifiedPushSettingsFragment__tap_to_request_a_test_notification_from_mollysocket),
        isEnabled = !viewModel.pairingBusy && state.registrationStatus == RegistrationStatus.REGISTERED,
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
      state.pushTestResultAt == 0L -> R.string.UnifiedPushSettingsFragment__test_push_summary
      else -> when (state.pushTestResult) {
        PushHealth.Hop.NONE -> R.string.UnifiedPushSettingsFragment__test_push_ok
        PushHealth.Hop.DISTRIBUTOR -> R.string.UnifiedPushSettingsFragment__test_push_failed_distributor
        PushHealth.Hop.SIGNAL_SEND -> R.string.UnifiedPushSettingsFragment__test_push_failed_signal_send
        PushHealth.Hop.COLLIESOCKET_OR_PROVIDER -> R.string.UnifiedPushSettingsFragment__test_push_failed_colliesocket_or_provider
        PushHealth.Hop.UNAVAILABLE -> R.string.UnifiedPushSettingsFragment__test_push_unavailable
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
    state.airGapped -> R.string.UnifiedPushSettingsFragment__status_summary_air_gapped_pending
    else -> R.string.UnifiedPushSettingsFragment__status_summary_pending
  }
}
