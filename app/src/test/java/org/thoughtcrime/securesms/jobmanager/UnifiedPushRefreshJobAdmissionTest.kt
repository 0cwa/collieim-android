package org.thoughtcrime.securesms.jobmanager

import im.molly.unifiedpush.enrollment.RelayDisclosure
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test
import org.thoughtcrime.securesms.jobs.UnifiedPushRefreshJob

class UnifiedPushRefreshJobAdmissionTest {

  @Test fun `delivery setup bypasses the routine refresh instance limit`() {
    val authorization = mockk<RelayDisclosure.RefreshAuthorization>()

    assertEquals(2, UnifiedPushRefreshJob().parameters.maxInstancesForFactory)
    assertEquals(Job.Parameters.UNLIMITED, UnifiedPushRefreshJob(authorization, configureDelivery = true).parameters.maxInstancesForFactory)
    assertEquals(Job.Parameters.UNLIMITED, UnifiedPushRefreshJob.configurePreferredDelivery().parameters.maxInstancesForFactory)
  }
}
