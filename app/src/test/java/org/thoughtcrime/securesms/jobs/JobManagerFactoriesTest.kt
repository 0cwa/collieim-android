package org.thoughtcrime.securesms.jobs

import android.app.Application
import io.mockk.mockk
import org.junit.Assert.assertTrue
import org.junit.Test

class JobManagerFactoriesTest {
  @Test
  fun test_PushContentReceiveJob_is_retired() {
    val factories = JobManagerFactories.getJobFactories(mockk<Application>())

    assertTrue(factories["PushContentReceiveJob"] is FailingJob.Factory)
  }

  @Test
  fun test_AttachmentUploadJob_is_retired() {
    val factories = JobManagerFactories.getJobFactories(mockk<Application>())

    assertTrue(factories["AttachmentUploadJob"] is FailingJob.Factory)
  }

  @Test
  fun test_MmsSendJob_is_retired() {
    val factories = JobManagerFactories.getJobFactories(mockk<Application>())

    assertTrue(factories["MmsSendJob"] is FailingJob.Factory)
  }
  @Test
  fun retiredDonationJobsAcceptOldPayloadsAndFailWithoutExecutingThem() {
    val factories = JobManagerFactories.getJobFactories(mockk<Application>())
    val retiredKeys = listOf(
      "InAppPaymentAuthCheckJob",
      "InAppPurchaseOneTimeContextJob",
      "InAppPaymentPayPalOneTimeSetupJob",
      "InAppPaymentPayPalRecurringSetupJob",
      "InAppPaymentStripeOneTimeSetupJob",
      "InAppPaymentStripeRecurringSetupJob",
      "RefreshDonationSubscriptionStatusJob"
    )

    for (key in retiredKeys) {
      val factory = requireNotNull(factories[key]) { key }
      val job = factory.create(org.thoughtcrime.securesms.jobmanager.Job.Parameters.Builder().build(), byteArrayOf(1, 2, 3))
      assertTrue(key, job.run().isFailure)
    }

    assertTrue(factories[InAppPaymentPurchaseTokenJob.KEY] is InAppPaymentPurchaseTokenJob.Factory)
    assertTrue(factories[InAppPaymentRecurringContextJob.KEY] is InAppPaymentRecurringContextJob.Factory)
  }

}
