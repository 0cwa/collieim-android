package org.thoughtcrime.securesms.components.settings.app.subscription.donate

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.thoughtcrime.securesms.R

class DonationCheckoutDestinationsTest {
  @Test
  fun `old payment entry destinations cannot be restored`() {
    val entryDestinations = listOf(
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

    entryDestinations.forEach { assertTrue(DonationCheckoutDestinations.isPaymentEntry(it)) }
  }

  @Test
  fun `subscription cancellation and pending payment screens survive restoration`() {
    val retainedDestinations = listOf(
      R.id.donateToSignalFragment,
      R.id.stripePaymentInProgressFragment,
      R.id.paypalPaymentInProgressFragment,
      R.id.stripe3dsDialogFragment,
      R.id.paypalConfirmationFragment,
      R.id.donationPendingBottomSheet
    )

    retainedDestinations.forEach { assertFalse(DonationCheckoutDestinations.isPaymentEntry(it)) }
  }
}
