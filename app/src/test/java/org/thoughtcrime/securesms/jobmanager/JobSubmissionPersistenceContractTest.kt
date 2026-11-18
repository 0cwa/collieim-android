package org.thoughtcrime.securesms.jobmanager

import android.app.Application
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.signal.core.util.Debouncer
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.jobmanager.persistence.JobStorage
import org.thoughtcrime.securesms.testutil.SystemOutLogger

/** Contract needed by callers that must not mistake scheduling for durable acceptance. */
class JobSubmissionPersistenceContractTest {
  private val storage = mockk<JobStorage>(relaxed = true)
  private val tracker = mockk<JobTracker>(relaxed = true)
  private val job = mockk<Job>(relaxed = true)
  private val params = mockk<Job.Parameters>(relaxed = true)
  private lateinit var controller: JobController

  @Before fun setup() {
    Log.initialize(SystemOutLogger())
    every { job.id } returns "synthetic-id"
    every { job.factoryKey } returns "synthetic-factory"
    every { job.parameters } returns params
    every { params.id } returns "synthetic-id"
    every { params.maxInstancesForFactory } returns 2
    every { params.maxInstancesForQueue } returns Job.Parameters.UNLIMITED
    every { params.queue } returns null
    every { params.constraintKeys } returns emptyList()
    controller = JobController(mockk<Application>(relaxed = true), storage, mockk(relaxed = true), mockk(relaxed = true), tracker, mockk(relaxed = true), mockk<Debouncer>(relaxed = true), mockk(relaxed = true), 0, 0, 1000, emptyList())
  }

  @Test fun `storage insertion precedes job submission callback`() {
    controller.submitNewJobChain(listOf(listOf(job)))
    verifyOrder { storage.insertJobs(any()); job.onSubmit() }
  }

  @Test fun `factory limit ignores request without insertion or callback`() {
    every { storage.getJobCountForFactory("synthetic-factory") } returns 2
    controller.submitNewJobChain(listOf(listOf(job)))
    verify { tracker.onStateChange(job, JobTracker.JobState.IGNORED) }
    verify(exactly = 0) { storage.insertJobs(any()) }
    verify(exactly = 0) { job.onSubmit() }
  }

  @Test fun `insertion failure cannot acknowledge callback`() {
    every { storage.insertJobs(any()) } throws IllegalStateException("synthetic storage failure")
    assertThrows(IllegalStateException::class.java) { controller.submitNewJobChain(listOf(listOf(job))) }
    verify(exactly = 0) { job.onSubmit() }
  }
}
