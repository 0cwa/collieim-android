package im.molly.unifiedpush.components.settings.app.notifications

import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.launch
import androidx.annotation.StringRes
import androidx.lifecycle.Lifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.lifecycle.ViewModelProvider
import im.molly.unifiedpush.health.PushHealth
import im.molly.unifiedpush.enrollment.ExportMode
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
import org.thoughtcrime.securesms.util.adapter.mapping.MappingAdapter

class UnifiedPushSettingsFragment : DSLSettingsFragment(R.string.NotificationDeliveryMethod__unifiedpush) {

  private lateinit var viewModel: UnifiedPushSettingsViewModel

  private val qrScanLauncher: ActivityResultLauncher<Unit> =
    registerForActivityResult(MollySocketQrScannerActivity.Contract()) { mollySocket ->
      if (mollySocket != null) {
        viewModel.setMollySocket(mollySocket)
      }
    }

  override fun bindAdapter(adapter: MappingAdapter) {
    val factory = UnifiedPushSettingsViewModel.Factory(requireActivity().application)

    viewModel = ViewModelProvider(this, factory)[UnifiedPushSettingsViewModel::class.java]

    viewModel.state.observe(viewLifecycleOwner) {
      adapter.submitList(getConfiguration(it).toMappingModelList())
    }

    RestartableSubscription { EventBus.default.subscribe<PushServiceEvent>(ThreadMode.MAIN, ::onPushServiceEvent) }
      .bindTo(viewLifecycleOwner, Lifecycle.State.RESUMED)

    viewModel.updateRegistration()
  }

  private fun showExportModeDialog(state: UnifiedPushSettingsState) {
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
          0 -> showExportEpochDialog(ExportMode.NEW_INTENT)
          1 -> showRecoveryAcknowledgement()
          2 -> showRerenderDialog(eligible)
        }
      }
      .setNegativeButton(android.R.string.cancel, null)
      .show()
  }

  private fun showRecoveryAcknowledgement() {
    MaterialAlertDialogBuilder(requireContext())
      .setTitle(R.string.UnifiedPushSettingsFragment__export_recovery_title)
      .setMessage(R.string.UnifiedPushSettingsFragment__export_recovery_message)
      .setPositiveButton(R.string.UnifiedPushSettingsFragment__export_continue) { _, _ -> showExportEpochDialog(ExportMode.RECOVERY) }
      .setNegativeButton(android.R.string.cancel, null)
      .show()
  }

  private fun showExportEpochDialog(mode: ExportMode) {
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
        viewModel.exportEnrollment(requireContext(), input.text.toString(), mode)
      }
      .setNegativeButton(android.R.string.cancel, null)
      .show()
  }

  private fun showRerenderDialog(exports: List<SavedExport>) {
    val labels = exports.mapIndexed { index, saved ->
      getString(R.string.UnifiedPushSettingsFragment__saved_export_label, index + 1, saved.epoch)
    }.toTypedArray()
    MaterialAlertDialogBuilder(requireContext())
      .setTitle(R.string.UnifiedPushSettingsFragment__rerender_export_title)
      .setItems(labels) { _, which ->
        viewModel.exportEnrollment(requireContext(), "", ExportMode.RERENDER, exports[which].requestId)
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
          .setMessage(R.string.UnifiedPushSettingsFragment__forget_export_warning)
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
      .setMessage(R.string.UnifiedPushSettingsFragment__control_state_recovery_message)
      .setPositiveButton(R.string.UnifiedPushSettingsFragment__control_state_recovery_confirm) { _, _ ->
        viewModel.recoverCorruptControlState()
      }
      .setNegativeButton(android.R.string.cancel, null)
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
          qrScanLauncher.launch()
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
            viewModel.copyParamsToClipboard(requireContext())
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
        isEnabled = !state.controlStateCorrupt,
        onClick = { showExportModeDialog(state) },
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
