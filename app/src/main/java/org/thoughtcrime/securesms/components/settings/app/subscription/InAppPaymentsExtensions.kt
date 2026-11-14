/*
 * Copyright 2025 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.components.settings.app.subscription

import org.signal.donations.PaymentSourceType
import org.thoughtcrime.securesms.database.InAppPaymentTable
import org.thoughtcrime.securesms.database.model.databaseprotos.InAppPaymentData
import org.thoughtcrime.securesms.jobs.InAppPaymentKeepAliveJob
import org.thoughtcrime.securesms.jobs.protos.InAppPaymentSourceData

fun PaymentSourceType.toInAppPaymentSourceDataCode(): InAppPaymentSourceData.Code {
  return when (this) {
    PaymentSourceType.Unknown -> InAppPaymentSourceData.Code.UNKNOWN
    PaymentSourceType.GooglePlayBilling -> InAppPaymentSourceData.Code.GOOGLE_PLAY_BILLING
    PaymentSourceType.PayPal -> InAppPaymentSourceData.Code.PAY_PAL
    PaymentSourceType.Stripe.CreditCard -> InAppPaymentSourceData.Code.CREDIT_CARD
    PaymentSourceType.Stripe.GooglePay -> InAppPaymentSourceData.Code.GOOGLE_PAY
    PaymentSourceType.Stripe.IDEAL -> InAppPaymentSourceData.Code.IDEAL
    PaymentSourceType.Stripe.SEPADebit -> InAppPaymentSourceData.Code.SEPA_DEBIT
  }
}

/**
 * Whether this payment failed at the payment step rather than the redemption step. Redemption errors are excluded
 * because the money was taken successfully, and keep-alive errors are excluded because they come from a periodic
 * subscription refresh rather than from anything the user did.
 */
fun InAppPaymentTable.InAppPayment.isPaymentFailure(): Boolean {
  return data.error?.let {
    it.type != InAppPaymentData.Error.Type.REDEMPTION && it.data_ != InAppPaymentKeepAliveJob.KEEP_ALIVE
  } ?: false
}
