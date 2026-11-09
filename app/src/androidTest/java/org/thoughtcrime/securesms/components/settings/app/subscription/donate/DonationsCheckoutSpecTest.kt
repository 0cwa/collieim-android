package org.thoughtcrime.securesms.components.settings.app.subscription.donate

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.mockk.unmockkObject
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.signal.core.database.deleteAll
import org.signal.donations.InAppPaymentType
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.components.settings.app.subscription.InAppPaymentsRepository
import org.thoughtcrime.securesms.components.settings.app.subscription.permits.DonationPermits
import org.thoughtcrime.securesms.database.InAppPaymentTable
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.database.model.InAppPaymentSubscriberRecord
import org.thoughtcrime.securesms.database.model.databaseprotos.InAppPaymentData
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.testing.DisableAnimationsRule
import org.thoughtcrime.securesms.testing.InAppPaymentsRule
import org.thoughtcrime.securesms.testing.SignalActivityRule
import org.thoughtcrime.securesms.testing.endpoints.MockEndpoints
import org.thoughtcrime.securesms.testing.endpoints.failure
import org.thoughtcrime.securesms.testing.endpoints.ok
import org.whispersystems.signalservice.api.subscriptions.SubscriberId
import java.util.Currency
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Existing subscribers can cancel; Collie offers no new native donation checkout. */
@RunWith(AndroidJUnit4::class)
class DonationsCheckoutSpecTest {
  @get:Rule val harness = SignalActivityRule()
  @get:Rule val iapRule = InAppPaymentsRule()
  @get:Rule val animations = DisableAnimationsRule()

  @Before fun setUp() {
    SignalDatabase.inAppPayments.writableDatabase.deleteAll(InAppPaymentTable.TABLE_NAME)
    InAppPaymentsRepository.setSubscriber(
      InAppPaymentSubscriberRecord(
        subscriberId = SubscriberId.generate(),
        type = InAppPaymentSubscriberRecord.Type.DONATION,
        requiresCancel = false,
        paymentMethodType = InAppPaymentData.PaymentMethodType.UNKNOWN,
        currency = Currency.getInstance("USD"),
        iapSubscriptionId = null
      )
    )
    SignalStore.inAppPayments.updateLocalStateForLocalSubscribe(InAppPaymentSubscriberRecord.Type.DONATION)
    WorldState(existingSubscription = SubscriptionState.ACTIVE).apply()
  }

  @After fun tearDown() { unmockkObject(DonationPermits) }

  @Test fun cancellationContinuesAcrossRecreation() = checkCancellationAcrossRecreation(success = true)
  @Test fun failedCancellationSurvivesRecreation() = checkCancellationAcrossRecreation(success = false)

  private fun checkCancellationAcrossRecreation(success: Boolean) {
    val requestStarted = CountDownLatch(1)
    val allowResponse = CountDownLatch(1)
    MockEndpoints.responder.register({ it.method == "DELETE" && it.path.startsWith("/v1/subscription/") }) {
      requestStarted.countDown()
      check(allowResponse.await(20, TimeUnit.SECONDS))
      if (success) ok() else failure(500)
    }

    val intent = CheckoutFlowActivity.createIntent(InstrumentationRegistry.getInstrumentation().targetContext, InAppPaymentType.RECURRING_DONATION)
    ActivityScenario.launch<CheckoutFlowActivity>(intent).use { scenario ->
      try {
        await { onView(withText(R.string.SubscribeFragment__cancel_subscription)).check(matches(isDisplayed())) }
        onView(withText(R.string.DonateToSignalFragment__continue)).check(doesNotExist())
        onView(withText(R.string.SubscribeFragment__update_subscription)).check(doesNotExist())
        onView(withText(R.string.SubscribeFragment__cancel_subscription)).perform(click())
        onView(withText(R.string.SubscribeFragment__confirm)).perform(click())
        assertTrue("Cancellation request did not start", requestStarted.await(15, TimeUnit.SECONDS))
        scenario.recreate()
        allowResponse.countDown()
        if (success) {
          await { assertTrue(SignalStore.inAppPayments.isDonationSubscriptionManuallyCancelled()) }
        } else {
          await { onView(withText(R.string.DonationsErrors__failed_to_cancel_subscription)).check(matches(isDisplayed())) }
          assertFalse(SignalStore.inAppPayments.isDonationSubscriptionManuallyCancelled())
        }
      } finally {
        allowResponse.countDown()
      }
    }
  }

  private fun await(assertion: () -> Unit) {
    val deadline = System.currentTimeMillis() + 15_000
    var last: Throwable? = null
    while (System.currentTimeMillis() < deadline) {
      try { assertion(); return } catch (error: Throwable) { last = error; Thread.sleep(100) }
    }
    throw AssertionError("Timed out waiting for donation screen", last)
  }
}
