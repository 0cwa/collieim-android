package im.molly.unifiedpush.components.settings.app.notifications

import android.app.Dialog
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.launch
import androidx.annotation.StringRes
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import im.molly.unifiedpush.enrollment.ExportMode
import im.molly.unifiedpush.enrollment.EnrollmentFile
import im.molly.unifiedpush.enrollment.PairingV1
import im.molly.unifiedpush.enrollment.PairingRegistrationGate
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
import org.thoughtcrime.securesms.components.settings.DSLSettingsIcon
import org.thoughtcrime.securesms.components.settings.DSLSettingsText
import org.thoughtcrime.securesms.components.settings.configure
import org.thoughtcrime.securesms.events.PushServiceEvent
import org.thoughtcrime.securesms.util.WindowUtil
import org.thoughtcrime.securesms.util.adapter.mapping.MappingAdapter

class UnifiedPushSettingsFragment : DSLSettingsFragment(R.string.NotificationDeliveryMethod__unifiedpush) {

  companion object {
    const val PAIRING_OFFER_ARGUMENT = "pairingOffer"
  }

  private lateinit var viewModel: UnifiedPushSettingsViewModel

  private val enrollmentDocumentLauncher: ActivityResultLauncher<String> =
    registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
      viewModel.completeEnrollmentExport(uri)
    }

  private val pairingSaveLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
    val sessionId = viewModel.pendingPairingSaveId
    viewModel.pendingPairingSaveId = null
    if (uri != null && (sessionId == null || !viewModel.savePairingRequest(requireContext(), sessionId, uri))) {
      Toast.makeText(requireContext(), R.string.PairingV1__file_action_failed, Toast.LENGTH_LONG).show()
    }
  }

  private val pairingReceiptLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
    if (uri != null) {
      val accepted = viewModel.readPairingReceipt(requireContext(), uri)
      showPairingReceiptResult(accepted)
    }
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
    viewModel.exportState.observe(viewLifecycleOwner) { result ->
      if (result == null || result.message != null) viewModel.refresh()
      else viewModel.state.value?.let { adapter.submitList(getConfiguration(it).toMappingModelList()) }
      showPendingExportResult()
    }

    RestartableSubscription { EventBus.default.subscribe<PushServiceEvent>(ThreadMode.MAIN, ::onPushServiceEvent) }
      .bindTo(viewLifecycleOwner, Lifecycle.State.RESUMED)

    val firstScanOffer = arguments?.getString(PAIRING_OFFER_ARGUMENT)
    if (firstScanOffer != null) {
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
    viewModel.enterPairingFlow()
    val offer = try {
      viewModel.parsePairingOffer(json)
    } catch (e: EnrollmentFile.Invalid) {
      Toast.makeText(
        requireContext(),
        if (e.code == "unsupported_intent") R.string.PairingV1__unsupported_add_receiver else R.string.PairingV1__invalid_offer,
        Toast.LENGTH_LONG,
      ).show()
      return
    }
    if (disclosure == null) {
      Toast.makeText(requireContext(), R.string.PairingV1__endpoint_unavailable, Toast.LENGTH_LONG).show()
      return
    }
    val distributors = viewModel.pairingDistributors()
    if (distributors.isEmpty()) {
      Toast.makeText(requireContext(), R.string.PairingV1__select_distributor_first, Toast.LENGTH_LONG).show()
      return
    }
    val saved = viewModel.pairingSelectedDistributor()
    if (saved == null || distributors.none { it.applicationId == saved }) {
      val labels = distributors.map { "${it.name} (${it.applicationId})" }.toTypedArray()
      var selected = 0
      MaterialAlertDialogBuilder(requireContext())
        .setTitle(R.string.PairingV1__select_distributor_title)
        .setSingleChoiceItems(labels, selected) { _, which -> selected = which }
        .setPositiveButton(android.R.string.ok) { _, _ ->
          val distributorId = distributors[selected].applicationId
          viewModel.selectPairingDistributor(distributorId)
          evaluatePairingOffer(offer, RelayDisclosure.capture(requireContext()), distributorId)
        }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
      return
    }
    evaluatePairingOffer(offer, disclosure, saved)
  }

  private fun evaluatePairingOffer(
    offer: PairingV1.Offer,
    disclosure: RelayDisclosure.Snapshot?,
    distributorId: String,
  ) {
    when (val plan = viewModel.evaluatePairingOffer(offer, distributorId)) {
      is PairingV1Coordinator.OfferPlan.Ready -> showPairingConsent(offer, disclosure, plan.consent)
      is PairingV1Coordinator.OfferPlan.Register -> showPairingRegistrationConsent(plan.details, disclosure)
      is PairingV1Coordinator.OfferPlan.Failed -> Toast.makeText(requireContext(), plan.message, Toast.LENGTH_LONG).show()
    }
  }

  private fun showPairingRegistrationConsent(
    details: PairingV1Coordinator.RegistrationDetails,
    disclosure: RelayDisclosure.Snapshot?,
  ) {
    val offer = details.offer
    val provider = offer.providerOrigin ?: getString(R.string.PairingV1__provider_unspecified)
    val message = getString(
      R.string.PairingV1__registration_consent_message,
      offer.hostLabel,
      provider,
      details.distributorLabel,
      details.distributorId,
      details.accountId,
      offer.vapidPublicKey,
    )
    MaterialAlertDialogBuilder(requireContext())
      .setTitle(R.string.PairingV1__registration_consent_title)
      .setMessage(message)
      .setPositiveButton(R.string.PairingV1__registration_consent_confirm) { _, _ ->
        val completion: (Result<PairingRegistrationGate.Endpoint>) -> Unit = { result ->
          if (isAdded) {
            if (result.isFailure) {
              Toast.makeText(requireContext(), R.string.PairingV1__registration_failed, Toast.LENGTH_LONG).show()
            } else {
              val freshDisclosure = RelayDisclosure.capture(requireContext())
              when (val plan = viewModel.evaluatePairingOffer(offer, details.distributorId)) {
                is PairingV1Coordinator.OfferPlan.Ready -> showPairingConsent(offer, freshDisclosure, plan.consent)
                is PairingV1Coordinator.OfferPlan.Register -> Toast.makeText(requireContext(), R.string.PairingV1__registration_not_ready, Toast.LENGTH_LONG).show()
                is PairingV1Coordinator.OfferPlan.Failed -> Toast.makeText(requireContext(), plan.message, Toast.LENGTH_LONG).show()
              }
            }
          }
        }
        val started = viewModel.registerPairingEndpoint(details, disclosure, completion)
        if (!started) {
          when (val latest = viewModel.evaluatePairingOffer(offer, details.distributorId)) {
            is PairingV1Coordinator.OfferPlan.Ready -> showPairingConsent(offer, RelayDisclosure.capture(requireContext()), latest.consent)
            is PairingV1Coordinator.OfferPlan.Failed -> Toast.makeText(requireContext(), latest.message, Toast.LENGTH_LONG).show()
            is PairingV1Coordinator.OfferPlan.Register -> Toast.makeText(requireContext(), R.string.PairingV1__registration_not_started, Toast.LENGTH_LONG).show()
          }
        } else Toast.makeText(requireContext(), R.string.PairingV1__registration_started, Toast.LENGTH_LONG).show()
      }
      .setNegativeButton(android.R.string.cancel) { _, _ -> viewModel.cancelPairingAttempt(offer.sessionId) }
      .show()
  }

  private fun showPairingConsent(
    offer: PairingV1.Offer,
    disclosure: RelayDisclosure.Snapshot?,
    details: PairingV1Coordinator.ConsentDetails,
  ) {
    val message = getString(
      if (offer.providerOrigin == null) R.string.PairingV1__consent_message_selected_provider
      else R.string.PairingV1__consent_message,
      offer.hostLabel,
      details.providerOrigin,
      details.distributorLabel,
      details.distributorId,
      offer.vapidPublicKey,
      details.accountId,
    )
    MaterialAlertDialogBuilder(requireContext())
      .setTitle(R.string.PairingV1__consent_title)
      .setMessage(message)
      .setPositiveButton(R.string.PairingV1__consent_confirm) { _, _ ->
        viewModel.sealPairing(offer, disclosure, details) { result ->
          when (result) {
            is PairingV1Coordinator.SealResult.Ready -> showPairingActions(result.session.id)
            is PairingV1Coordinator.SealResult.Failed -> Toast.makeText(requireContext(), result.message, Toast.LENGTH_LONG).show()
          }
        }
      }
      .setNegativeButton(android.R.string.cancel) { _, _ -> viewModel.cancelPairingAttempt(offer.sessionId) }
      .show()
  }

  private fun showPairingActions(sessionId: String) {
    val actions = arrayOf(
      getString(R.string.PairingV1__copy_request),
      getString(R.string.PairingV1__share_request),
      getString(R.string.PairingV1__save_request),
      getString(R.string.PairingV1__paste_receipt),
      getString(R.string.PairingV1__open_receipt),
      getString(R.string.PairingV1__local_status),
    )
    MaterialAlertDialogBuilder(requireContext())
      .setTitle(R.string.PairingV1__request_ready)
      .setItems(actions) { _, index ->
        when (index) {
          0 -> if (!viewModel.copyPairingRequest(requireContext(), sessionId)) {
            Toast.makeText(requireContext(), R.string.PairingV1__file_action_failed, Toast.LENGTH_LONG).show()
          }
          1 -> if (!viewModel.sharePairingRequest(requireContext(), sessionId)) {
            Toast.makeText(requireContext(), R.string.PairingV1__file_action_failed, Toast.LENGTH_LONG).show()
          }
          2 -> {
            viewModel.pendingPairingSaveId = sessionId
            pairingSaveLauncher.launch("colliesocket-pairing-request-$sessionId.json")
          }
          3 -> showPastePairingReceiptDialog()
          4 -> pairingReceiptLauncher.launch(arrayOf("application/json", "text/*"))
          5 -> showPairingLocalStatus()
        }
      }
      .setNegativeButton(android.R.string.cancel, null)
      .show()
  }

  private fun showPastePairingReceiptDialog() {
    val input = EditText(requireContext()).apply {
      inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
      minLines = 4
      maxLines = 10
      hint = getString(R.string.PairingV1__receipt_hint)
    }
    MaterialAlertDialogBuilder(requireContext())
      .setTitle(R.string.PairingV1__paste_receipt)
      .setMessage(R.string.PairingV1__receipt_message)
      .setView(input)
      .setPositiveButton(R.string.PairingV1__verify_receipt) { _, _ ->
        showPairingReceiptResult(viewModel.acceptPairingReceipt(input.text.toString()))
      }
      .setNeutralButton(R.string.PairingV1__paste_from_clipboard) { _, _ ->
        showPairingReceiptResult(viewModel.pastePairingReceipt(requireContext()))
      }
      .setNegativeButton(android.R.string.cancel, null)
      .show()
  }

  private fun showPairingReceiptResult(accepted: Boolean) {
    val message = if (!accepted) getString(R.string.PairingV1__receipt_invalid)
    else getString(R.string.PairingV1__receipt_authenticated)
    MaterialAlertDialogBuilder(requireContext())
      .setTitle(R.string.PairingV1__receipt_title)
      .setMessage(message)
      .setPositiveButton(android.R.string.ok, null)
      .show()
  }

  private fun showPairingLocalStatus() {
    val status = viewModel.pairingStatus()
    if (status == null) {
      Toast.makeText(requireContext(), R.string.PairingV1__status_unavailable, Toast.LENGTH_LONG).show()
      return
    }
    val message = getString(
      R.string.PairingV1__status_message,
      status.sessionId,
      status.hostLabel,
      status.providerOrigin,
      status.distributorLabel,
      java.text.DateFormat.getDateTimeInstance().format(java.util.Date(status.deadlineSeconds * 1000)),
      getString(if (status.receiptAccepted) R.string.PairingV1__receipt_authenticated else R.string.PairingV1__receipt_pending),
      getString(if (status.requestReady) R.string.PairingV1__request_available else R.string.PairingV1__request_unavailable),
    )
    MaterialAlertDialogBuilder(requireContext())
      .setTitle(R.string.PairingV1__local_status)
      .setMessage(message)
      .setPositiveButton(android.R.string.ok, null)
      .show()
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

  private fun getConfiguration(state: UnifiedPushSettingsState): DSLConfiguration {
    return configure {
      textPref(
        title = DSLSettingsText.from(getString(R.string.UnifiedPushSettingsFragment__status)),
        summary = DSLSettingsText.from(getStatusSummary(state)),
      )

      if (state.pushFallback) {
        textPref(
          title = DSLSettingsText.from(R.string.UnifiedPushSettingsFragment__push_warning_title, requireContext().getColor(R.color.signal_alert_primary)),
          summary = DSLSettingsText.from(R.string.UnifiedPushSettingsFragment__push_warning_summary),
        )
      }

      if (state.registrationFailed) {
        clickPref(
          title = DSLSettingsText.from(R.string.UnifiedPushSettingsFragment__retry_registration),
          onClick = { viewModel.retryRegistration() },
        )
      }

      clickPref(
        title = DSLSettingsText.from(R.string.UnifiedPushSettingsFragment__mollysocket_server),
        summary = DSLSettingsText.from(
          if (state.airGapped) {
            getString(R.string.UnifiedPushSettingsFragment__mollysocket_server_air_gapped)
          } else {
            state.mollySocketUrl ?: "Error"
          }
        ),
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
          summary = DSLSettingsText.from(R.string.UnifiedPushSettingsFragment__none_available)
        )
      } else {
        radioListPref(
          title = DSLSettingsText.from(R.string.UnifiedPushSettingsFragment__distributor_app),
          listItems = state.distributors.map { it.name }.toTypedArray(),
          selected = state.selected,
          onSelected = {
            viewModel.setUnifiedPushDistributor(state.distributors[it].applicationId)
          },
        )
      }

      dividerPref()

      if (state.airGapped) {
        clickPref(
          title = DSLSettingsText.from(getString(R.string.UnifiedPushSettingsFragment__server_parameters)),
          summary = DSLSettingsText.from(getString(R.string.UnifiedPushSettingsFragment__tap_to_copy_to_clipboard)),
          iconEnd = DSLSettingsIcon.from(org.signal.core.ui.R.drawable.symbol_copy_android_24),
          isEnabled = state.serverParameters != null,
          onClick = {
            val disclosure = state.relayDisclosure
            if (disclosure == null) {
              RelayDisclosureDialogs.showStaleAction(requireContext())
            } else {
              RelayDisclosureDialogs.showCopy(requireContext()) {
                viewModel.copyParamsToClipboard(requireContext(), disclosure)
              }
            }
          },
        )
      } else {
        val aciOrUnknown = state.aci ?: getString(R.string.Recipient_unknown)

        clickPref(
          title = DSLSettingsText.from(getString(R.string.UnifiedPushSettingsFragment__account_id)),
          summary = DSLSettingsText.from(aciOrUnknown),
          iconEnd = DSLSettingsIcon.from(org.signal.core.ui.R.drawable.symbol_copy_android_24),
          isEnabled = state.aci != null,
          onClick = {
            viewModel.copyAciToClipboard(requireContext())
          },
        )

        clickPref(
          title = DSLSettingsText.from(getString(R.string.UnifiedPushSettingsFragment__test_configuration)),
          summary = DSLSettingsText.from(getString(R.string.UnifiedPushSettingsFragment__tap_to_request_a_test_notification_from_mollysocket)),
          onClick = {
            viewModel.pingMollySocket()
            Toast.makeText(context, getString(R.string.UnifiedPushSettingsFragment__a_test_notification_should_appear_in_a_few_moments), Toast.LENGTH_SHORT).show()
          },
        )
      }

      clickPref(
        title = DSLSettingsText.from(R.string.UnifiedPushSettingsFragment__test_push),
        summary = DSLSettingsText.from(getPushTestSummary(state)),
        isEnabled = !state.pushTestRunning,
        onClick = { viewModel.testPush() },
      )

      if (state.controlStateCorrupt) {
        clickPref(
          title = DSLSettingsText.from(R.string.UnifiedPushSettingsFragment__control_state_recovery),
          summary = DSLSettingsText.from(R.string.UnifiedPushSettingsFragment__control_state_recovery_summary),
          onClick = { showControlStateRecoveryDialog() },
        )
      }

      if (!state.controlStateCorrupt && state.pendingExportCount > 0) {
        clickPref(
          title = DSLSettingsText.from(R.string.UnifiedPushSettingsFragment__pending_exports),
          summary = DSLSettingsText.from(getString(R.string.UnifiedPushSettingsFragment__pending_exports_summary, state.pendingExportCount)),
          onClick = { showForgetIntentDialog(state) },
        )
      }

      clickPref(
        title = DSLSettingsText.from(R.string.UnifiedPushSettingsFragment__export_enrollment),
        summary = DSLSettingsText.from(
          if (state.controlStateCorrupt) R.string.UnifiedPushSettingsFragment__export_enrollment_state_unavailable
          else R.string.UnifiedPushSettingsFragment__export_enrollment_summary
        ),
        isEnabled = !state.controlStateCorrupt && !viewModel.isExportPending(),
        onClick = { showExportModeDialog(state) },
      )

      viewModel.pairingStatus()?.let { pairing ->
        clickPref(
          title = DSLSettingsText.from(R.string.PairingV1__local_status),
          summary = DSLSettingsText.from(
            if (pairing.receiptAccepted) R.string.PairingV1__status_summary_receipt
            else if (pairing.requestReady) R.string.PairingV1__status_summary_ready
            else R.string.PairingV1__status_summary_unavailable
          ),
          onClick = { showPairingActions(pairing.sessionId) },
        )
      }

      dividerPref()

      textPref(
        title = DSLSettingsText.from(R.string.UnifiedPushSettingsFragment__relay_trust_title),
        summary = DSLSettingsText.from(R.string.UnifiedPushSettingsFragment__relay_trust_summary),
      )

      clickPref(
        title = DSLSettingsText.from(R.string.UnifiedPushSettingsFragment__remove_relay_access_title),
        summary = DSLSettingsText.from(R.string.UnifiedPushSettingsFragment__remove_relay_access_summary),
        onClick = { showRemoveRelayAccessDialog() },
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
  private fun getStatusSummary(state: UnifiedPushSettingsState): Int {
    return when {
      state.controlStateCorrupt -> R.string.UnifiedPushSettingsFragment__control_state_unavailable
      state.distributors.isEmpty() -> R.string.UnifiedPushSettingsFragment__status_summary_no_distributor
      state.selected == -1 -> R.string.UnifiedPushSettingsFragment__status_summary_distributor_not_selected
      state.registrationFailed -> R.string.UnifiedPushSettingsFragment__registration_failed
      state.selectedNotAck -> R.string.UnifiedPushSettingsFragment__status_summary_missing_endpoint
      state.endpoint == null -> R.string.UnifiedPushSettingsFragment__status_summary_missing_endpoint
      state.mollySocketUrl == null && !state.airGapped -> R.string.UnifiedPushSettingsFragment__status_summary_mollysocket_url_missing
      state.device == null -> R.string.UnifiedPushSettingsFragment__status_summary_linked_device_error
      else -> getRegistrationStatusSummary(state)
    }
  }

  @StringRes
  private fun getRegistrationStatusSummary(state: UnifiedPushSettingsState): Int {
    return if (state.airGapped) {
      when (state.registrationStatus) {
        RegistrationStatus.REGISTERED -> android.R.string.ok
        else -> R.string.UnifiedPushSettingsFragment__status_summary_air_gapped_pending
      }
    } else {
      when (state.registrationStatus) {
        RegistrationStatus.UNKNOWN,
        RegistrationStatus.PENDING -> R.string.UnifiedPushSettingsFragment__status_summary_pending

        RegistrationStatus.BAD_RESPONSE,
        RegistrationStatus.SERVER_ERROR -> R.string.UnifiedPushSettingsFragment__status_summary_bad_response

        RegistrationStatus.REGISTERED -> android.R.string.ok
        RegistrationStatus.FORBIDDEN_PASSWORD -> R.string.UnifiedPushSettingsFragment__status_summary_pending
        RegistrationStatus.FORBIDDEN_UUID -> R.string.UnifiedPushSettingsFragment__status_summary_forbidden_uuid
        RegistrationStatus.FORBIDDEN_ENDPOINT -> R.string.UnifiedPushSettingsFragment__status_summary_forbidden_endpoint
      }
    }
  }
}
