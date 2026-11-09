package org.thoughtcrime.securesms.components.settings.app.subscription.donate

import org.thoughtcrime.securesms.R

/** Destinations that can initiate a new payment, including dialogs over entry forms. */
internal object DonationCheckoutDestinations {
  fun isPaymentEntry(destinationId: Int): Boolean = destinationId in setOf(
    R.id.creditCardFragment,
    R.id.yourInformationIsPrivateBottomSheet,
    R.id.paypalCompleteOrderBottomSheet,
    R.id.bankTransferMandateFragment,
    R.id.bankTransferDetailsFragment,
    R.id.idealTransferDetailsFragment,
    R.id.idealTransferBankSelectionDialogFragment,
    R.id.gatewaySelectorBottomSheet,
    R.id.setCurrencyFragment
  )
}
