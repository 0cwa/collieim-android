/*
 * Copyright 2026 Collie contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package org.thoughtcrime.securesms.pin

import android.content.Context
import android.text.InputType
import android.view.WindowManager
import android.widget.EditText
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.signal.core.util.PinHashUtil
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.keyvalue.SignalStore

/** A local UI check, not protection against direct service access with copied credentials. */
object RecoveryPinGuard {
  @JvmStatic
  fun ifEnabled(context: Context, onVerified: Runnable) {
    if (SignalStore.pin.recoveryPinGuardEnabled) verify(context, onVerified) else onVerified.run()
  }

  @JvmStatic
  fun verify(context: Context, onVerified: Runnable) = verify(context, onVerified, Runnable {})

  @JvmStatic
  fun verify(context: Context, onVerified: Runnable, onCancelled: Runnable) {
    if (!SignalStore.svr.hasPin() || SignalStore.svr.hasOptedOut() || SignalStore.svr.localPinHash == null) {
      MaterialAlertDialogBuilder(context).setMessage(R.string.RecoveryPinGuard__no_pin).setPositiveButton(android.R.string.ok, null).setOnDismissListener { onCancelled.run() }.show()
      return
    }
    val input = EditText(context).apply {
      inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
      setSingleLine(true)
      isSaveEnabled = false
    }
    val dialog = MaterialAlertDialogBuilder(context)
      .setTitle(R.string.RecoveryPinGuard__enter_current_pin)
      .setView(input)
      .setNegativeButton(android.R.string.cancel, null)
      .setPositiveButton(android.R.string.ok, null)
      .create()
    dialog.window?.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
    var verified = false
    dialog.setOnDismissListener { if (!verified) onCancelled.run() }
    dialog.setOnShowListener {
      dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
        val hash = SignalStore.svr.localPinHash
        val valid = hash != null && SignalStore.svr.hasPin() && !SignalStore.svr.hasOptedOut() && PinHashUtil.verifyLocalPinHash(hash, input.text.toString())
        input.text.clear()
        if (valid) {
          verified = true
          dialog.dismiss()
          onVerified.run()
        } else {
          input.error = context.getString(R.string.RecoveryPinGuard__incorrect_pin)
        }
      }
    }
    dialog.show()
  }
}
