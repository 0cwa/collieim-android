/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.components.settings.app.subscription

import android.app.Application
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isGreaterThan
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import io.mockk.every
import io.mockk.verify
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.database.deleteAll
import org.signal.core.util.billing.BillingPurchaseResult
import org.signal.core.util.billing.BillingPurchaseState
import org.signal.core.util.money.FiatMoney
import org.signal.donations.InAppPaymentType
import org.thoughtcrime.securesms.components.settings.app.subscription.DonationSerializationHelper.toFiatValue
import org.thoughtcrime.securesms.database.InAppPaymentSubscriberTable
import org.thoughtcrime.securesms.database.InAppPaymentTable
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.database.model.InAppPaymentSubscriberRecord
import org.thoughtcrime.securesms.database.model.databaseprotos.BadgeList
import org.thoughtcrime.securesms.database.model.databaseprotos.InAppPaymentData
import org.thoughtcrime.securesms.testutil.MockAppDependenciesRule
import org.thoughtcrime.securesms.testutil.MockSignalStoreRule
import org.thoughtcrime.securesms.testutil.SignalDatabaseRule
import org.whispersystems.signalservice.api.storage.IAPSubscriptionId
import org.whispersystems.signalservice.api.subscriptions.ActiveSubscription
import org.whispersystems.signalservice.api.subscriptions.SubscriberId
import java.math.BigDecimal
import java.util.Currency
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class InAppPaymentsRepositoryTest {

  companion object {
    private const val IAP_TOKEN = "test_token"
    private const val STRIPE_INTENT_ID = "pi_test"
    private const val STRIPE_CLIENT_SECRET = "pi_test_secret"
  }

  @get:Rule
  val signalStore = MockSignalStoreRule()

  @get:Rule
  val appDependencies = MockAppDependenciesRule()

  @get:Rule
  val signalDatabaseRule = SignalDatabaseRule()

  private val testAmount = FiatMoney(BigDecimal.valueOf(5), Currency.getInstance("USD")).toFiatValue()
  private val euroAmount = FiatMoney(BigDecimal.valueOf(5), Currency.getInstance("EUR")).toFiatValue()
  private val testBadge = BadgeList.Badge(id = "test-badge")

  @Before
  fun setUp() {
    SignalDatabase.inAppPayments.writableDatabase.deleteAll(InAppPaymentTable.TABLE_NAME)
    SignalDatabase.inAppPaymentSubscribers.writableDatabase.deleteAll(InAppPaymentSubscriberTable.TABLE_NAME)

    every { signalStore.inAppPayments.getRecurringDonationCurrency() } returns Currency.getInstance("USD")
  }

  /**
   * Regression test for a crash loop (issue #14872). A PayPal/Braintree charge failure carries none of Stripe's
   * outcome fields, so [ActiveSubscription.ChargeFailure.outcomeType] (and its siblings) deserialize to null.
   * Copying those straight into the non-null proto ChargeFailure previously threw an NPE on every keep-alive run,
   * leaving the app unable to start once a donation was canceled server-side.
   */
  @Test
  fun `updateInAppPaymentWithCancelation records cancelation when charge failure has null Stripe outcome fields`() {
    val subscriberId = SubscriberId.generate()
    SignalDatabase.inAppPaymentSubscribers.insertOrReplace(
      InAppPaymentSubscriberRecord(
        subscriberId = subscriberId,
        currency = Currency.getInstance("USD"),
        type = InAppPaymentSubscriberRecord.Type.DONATION,
        requiresCancel = false,
        paymentMethodType = InAppPaymentData.PaymentMethodType.PAYPAL,
        iapSubscriptionId = null
      )
    )

    val paymentId = SignalDatabase.inAppPayments.insert(
      type = InAppPaymentType.RECURRING_DONATION,
      state = InAppPaymentTable.State.END,
      subscriberId = subscriberId,
      endOfPeriod = null,
      inAppPaymentData = InAppPaymentData()
    )

    // Braintree/PayPal charge failure: code + message present, all Stripe outcome fields null.
    val chargeFailure = ActiveSubscription.ChargeFailure("2003", "Do Not Honor", null, null, null)
    val activeSubscription = canceledSubscription(chargeFailure)

    InAppPaymentsRepository.updateInAppPaymentWithCancelation(activeSubscription, InAppPaymentSubscriberRecord.Type.DONATION)

    val cancellation = SignalDatabase.inAppPayments.getById(paymentId)!!.data.cancellation
    assertThat(cancellation).isNotNull()
    assertThat(cancellation!!.reason).isEqualTo(InAppPaymentData.Cancellation.Reason.PAST_DUE)
    assertThat(cancellation.chargeFailure).isNotNull()
    assertThat(cancellation.chargeFailure!!.code).isEqualTo("2003")
    assertThat(cancellation.chargeFailure.outcomeType).isEqualTo("")
  }

  @Test
  fun `isPurchaseValidatedByService is false for a purchase that is not in the purchased state`() {
    insertRedeemedPayment(insertBackupSubscriber(IAP_TOKEN))

    assertThat(InAppPaymentsRepository.isPurchaseValidatedByService(purchase(state = BillingPurchaseState.PENDING))).isFalse()
  }

  @Test
  fun `isPurchaseValidatedByService is true for an acknowledged purchase with no local subscriber`() {
    assertThat(InAppPaymentsRepository.isPurchaseValidatedByService(purchase(isAcknowledged = true))).isTrue()
  }

  @Test
  fun `isPurchaseValidatedByService is true for an unacknowledged purchase whose token was redeemed`() {
    insertRedeemedPayment(insertBackupSubscriber(IAP_TOKEN))

    assertThat(InAppPaymentsRepository.isPurchaseValidatedByService(purchase())).isTrue()
  }

  /**
   * The token on the subscriber record is written as soon as the subscriber id is created, well before the service has
   * seen it, so a redemption must only vouch for the token it was actually redeemed against.
   */
  @Test
  fun `isPurchaseValidatedByService is false for an unacknowledged purchase whose token differs from the redeemed one`() {
    insertRedeemedPayment(insertBackupSubscriber("some_other_token"))

    assertThat(InAppPaymentsRepository.isPurchaseValidatedByService(purchase())).isFalse()
  }

  @Test
  fun `isPurchaseValidatedByService is false for an unacknowledged purchase whose token was never redeemed`() {
    val subscriberId = insertBackupSubscriber(IAP_TOKEN)
    SignalDatabase.inAppPayments.insert(
      type = InAppPaymentType.RECURRING_BACKUP,
      state = InAppPaymentTable.State.END,
      subscriberId = subscriberId,
      endOfPeriod = null,
      inAppPaymentData = InAppPaymentData()
    )

    assertThat(InAppPaymentsRepository.isPurchaseValidatedByService(purchase())).isFalse()
  }

  // region recoverAbandonedPayments

  // endregion

  private fun insertRequiresAction(type: InAppPaymentType, data: InAppPaymentData): InAppPaymentTable.InAppPaymentId {
    return SignalDatabase.inAppPayments.insert(
      type = type,
      state = InAppPaymentTable.State.REQUIRES_ACTION,
      subscriberId = null,
      endOfPeriod = null,
      inAppPaymentData = data
    )
  }

  private fun stripeRequiresActionData(): InAppPaymentData {
    return InAppPaymentData(
      amount = euroAmount,
      paymentMethodType = InAppPaymentData.PaymentMethodType.IDEAL,
      stripeRequiresAction = InAppPaymentData.StripeRequiresActionState(
        uri = "https://example.com",
        returnUri = "sgnlpay://3DS",
        paymentMethodId = "pm_test",
        stripeIntentId = STRIPE_INTENT_ID,
        stripeClientSecret = STRIPE_CLIENT_SECRET
      )
    )
  }

  private fun insertPendingRecurringDonation(paymentMethodType: InAppPaymentData.PaymentMethodType) {
    insertRecurringDonation(
      state = InAppPaymentTable.State.PENDING,
      data = InAppPaymentData(
        level = 500L,
        paymentMethodType = paymentMethodType,
        redemption = InAppPaymentData.RedemptionState(
          stage = InAppPaymentData.RedemptionState.Stage.CONVERSION_STARTED
        )
      )
    )
  }

  private fun insertRecurringDonation(
    state: InAppPaymentTable.State,
    data: InAppPaymentData,
    endOfPeriod: Duration? = 1000.seconds
  ): InAppPaymentTable.InAppPaymentId {
    return SignalDatabase.inAppPayments.insert(
      type = InAppPaymentType.RECURRING_DONATION,
      state = state,
      subscriberId = null,
      endOfPeriod = endOfPeriod,
      inAppPaymentData = data
    )
  }

  private fun purchase(
    state: BillingPurchaseState = BillingPurchaseState.PURCHASED,
    isAcknowledged: Boolean = false,
    purchaseToken: String = IAP_TOKEN
  ): BillingPurchaseResult {
    return BillingPurchaseResult.Success(
      purchaseState = state,
      purchaseToken = purchaseToken,
      isAcknowledged = isAcknowledged,
      purchaseTime = System.currentTimeMillis(),
      isAutoRenewing = true
    )
  }

  private fun insertBackupSubscriber(token: String): SubscriberId {
    val subscriberId = SubscriberId.generate()

    SignalDatabase.inAppPaymentSubscribers.insertOrReplace(
      InAppPaymentSubscriberRecord(
        subscriberId = subscriberId,
        currency = null,
        type = InAppPaymentSubscriberRecord.Type.BACKUP,
        requiresCancel = false,
        paymentMethodType = InAppPaymentData.PaymentMethodType.GOOGLE_PLAY_BILLING,
        iapSubscriptionId = IAPSubscriptionId.GooglePlayBillingPurchaseToken(token)
      )
    )

    return subscriberId
  }

  private fun insertRedeemedPayment(subscriberId: SubscriberId) {
    SignalDatabase.inAppPayments.insert(
      type = InAppPaymentType.RECURRING_BACKUP,
      state = InAppPaymentTable.State.END,
      subscriberId = subscriberId,
      endOfPeriod = null,
      inAppPaymentData = InAppPaymentData(
        redemption = InAppPaymentData.RedemptionState(stage = InAppPaymentData.RedemptionState.Stage.REDEEMED)
      )
    )
  }

  private fun canceledSubscription(chargeFailure: ActiveSubscription.ChargeFailure?): ActiveSubscription {
    val periodEnd = System.currentTimeMillis().milliseconds.inWholeSeconds + 45.days.inWholeSeconds
    return ActiveSubscription(
      ActiveSubscription.Subscription(
        2000,
        "USD",
        BigDecimal.ONE,
        periodEnd,
        false,
        periodEnd,
        false,
        "canceled",
        "BRAINTREE",
        "PAYPAL",
        false
      ),
      chargeFailure
    )
  }
}
