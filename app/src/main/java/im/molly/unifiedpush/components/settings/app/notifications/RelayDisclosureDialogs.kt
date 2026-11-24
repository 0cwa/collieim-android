package im.molly.unifiedpush.components.settings.app.notifications

import android.content.Context
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.core.text.BidiFormatter
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import im.molly.unifiedpush.model.MollySocket
import org.thoughtcrime.securesms.R

/** Confirmation for actions that disclose the linked-device credential to a chosen relay. */
object RelayDisclosureDialogs {
  fun showRelay(context: Context, relay: MollySocket, onConfirm: () -> Unit): AlertDialog {
    val destination = when (relay) {
      is MollySocket.WebServer -> context.getString(R.string.RelayDisclosureDialog__relay_address, BidiFormatter.getInstance().unicodeWrap(relay.url))
      is MollySocket.AirGapped -> context.getString(R.string.RelayDisclosureDialog__manual_destination)
    }
    return show(context, R.string.RelayDisclosureDialog__select_title, destination, R.string.RelayDisclosureDialog__select_confirm, onConfirm)
  }

  fun showCopy(context: Context, onConfirm: () -> Unit): AlertDialog = show(
    context,
    R.string.RelayDisclosureDialog__copy_title,
    context.getString(R.string.RelayDisclosureDialog__copy_message),
    R.string.RelayDisclosureDialog__copy_confirm,
    onConfirm,
  )

  fun showExport(context: Context, onConfirm: () -> Unit): AlertDialog = show(
    context,
    R.string.RelayDisclosureDialog__export_title,
    context.getString(R.string.RelayDisclosureDialog__export_message),
    R.string.RelayDisclosureDialog__export_confirm,
    onConfirm,
  )

  fun showStaleAction(context: Context) {
    Toast.makeText(context, R.string.UnifiedPushSettingsFragment__disclosure_stale, Toast.LENGTH_LONG).show()
  }

  private fun show(
    context: Context,
    @StringRes title: Int,
    introduction: String,
    @StringRes confirm: Int,
    onConfirm: () -> Unit,
  ): AlertDialog = MaterialAlertDialogBuilder(context)
    .setTitle(title)
    .setMessage(introduction + "\n\n" + context.getString(R.string.RelayDisclosureDialog__trust_message))
    .setPositiveButton(confirm) { _, _ -> onConfirm() }
    .setNegativeButton(android.R.string.cancel, null)
    .show()
}
