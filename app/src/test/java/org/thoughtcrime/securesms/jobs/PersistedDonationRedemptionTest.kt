package org.thoughtcrime.securesms.jobs

import android.app.Application
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.util.logging.Log
import org.signal.donations.InAppPaymentType
import org.thoughtcrime.securesms.database.InAppPaymentTable
import org.thoughtcrime.securesms.database.MessageTable
import org.thoughtcrime.securesms.database.model.MmsMessageRecord
import org.thoughtcrime.securesms.database.model.databaseprotos.GiftBadge
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.jobmanager.Job
import org.thoughtcrime.securesms.jobs.protos.InAppPaymentRedemptionJobData
import org.thoughtcrime.securesms.testutil.MockAppDependenciesRule
import org.thoughtcrime.securesms.testutil.MockSignalStoreRule
import org.thoughtcrime.securesms.testutil.SystemOutLogger

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE)
class PersistedDonationRedemptionTest {
  private val store = MockSignalStoreRule()
  private val dependencies = MockAppDependenciesRule()
  @get:Rule val rules: RuleChain = RuleChain.outerRule(store).around(dependencies)
  private val payments = mockk<InAppPaymentTable>(relaxed = true)

  @Before fun setup() {
    Log.initialize(SystemOutLogger())
    every { store.account.isRegistered } returns true
    mockkObject(SignalDatabase.Companion)
    every { SignalDatabase.inAppPayments } returns payments
  }

  @After fun cleanup() { unmockkObject(SignalDatabase.Companion) }

  @Test fun `deserialized donation payment does not contact service or mutate record`() {
    val payment = mockk<InAppPaymentTable.InAppPayment>()
    every { payment.type } returns InAppPaymentType.RECURRING_DONATION
    every { payments.getById(InAppPaymentTable.InAppPaymentId(17)) } returns payment
    val service = AppDependencies.donationsService
    val data = InAppPaymentRedemptionJobData(inAppPaymentId = 17)
    val restored = InAppPaymentRedemptionJob.Factory().create(Job.Parameters.Builder().build(), data.encode())
    assertTrue(restored.run().isSuccess)
    verify(exactly = 1) { payments.getById(InAppPaymentTable.InAppPaymentId(17)) }
    restored.onFailure()
    verify(exactly = 2) { payments.getById(InAppPaymentTable.InAppPaymentId(17)) }
    verify(exactly = 0) { payments.update(any()) }
    verify(exactly = 0) { service.redeemDonationReceipt(any(), any(), any()) }
    verify(exactly = 0) { service.redeemArchivesReceipt(any()) }
  }
  @Test fun `already redeemed gift does not redeem again or change state`() {
    val messages = mockk<MessageTable>(relaxed = true)
    every { SignalDatabase.messages } returns messages
    val message = mockk<MmsMessageRecord>()
    every { message.giftBadge } returns GiftBadge(redemptionState = GiftBadge.RedemptionState.REDEEMED)
    every { messages.getMessageRecord(23L) } returns message
    val service = AppDependencies.donationsService
    val data = InAppPaymentRedemptionJobData(giftMessageId = 23L)
    val restored = InAppPaymentRedemptionJob.Factory().create(Job.Parameters.Builder().build(), data.encode())
    assertTrue(restored.run().isSuccess)
    verify(exactly = 1) { messages.getMessageRecord(23L) }
    verify(exactly = 0) { messages.markGiftRedemptionCompleted(any()) }
    verify(exactly = 0) { messages.markGiftRedemptionFailed(any()) }
    verify(exactly = 0) { service.redeemDonationReceipt(any(), any(), any()) }
  }

}
