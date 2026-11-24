/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.screens.linkaccount

import org.signal.registration.screens.quickrestore.QrState

data class LinkAccountScreenState(
  val qrCodeState: QrState = QrState.Loading,
  val linkingCode: String? = null,
  val displayQrOverlay: Boolean = false,
  val isRegistering: Boolean = false,
  val isWaitingForPrimary: Boolean = false,
  val showError: Boolean = false,
  val showDeleteDataDialog: Boolean = false,
  val showCreateAccount: Boolean = true
) {
  // This state is logged by the view model. The provisioning URL must stay out of logs.
  override fun toString(): String =
    "LinkAccountScreenState(qrCodeState=$qrCodeState, linkingCode=***, displayQrOverlay=$displayQrOverlay, isRegistering=$isRegistering, isWaitingForPrimary=$isWaitingForPrimary, showError=$showError, showDeleteDataDialog=$showDeleteDataDialog, showCreateAccount=$showCreateAccount)"
}
