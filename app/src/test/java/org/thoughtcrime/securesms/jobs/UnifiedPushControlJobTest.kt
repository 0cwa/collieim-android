/*
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.jobs

import im.molly.unifiedpush.control.ControlState
import im.molly.unifiedpush.control.ControlUpdate
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.jobmanager.Job
import org.thoughtcrime.securesms.jobmanager.JobManager
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.keyvalue.UnifiedPushValues
import org.thoughtcrime.securesms.testutil.MockSignalStoreRule
import org.thoughtcrime.securesms.testutil.SystemOutLogger

class UnifiedPushControlJobTest {

  @get:Rule
  val signalStore = MockSignalStoreRule()

  private val unifiedPush = mockk<UnifiedPushValues>()
  private val jobManager = mockk<JobManager>(relaxed = true)

  @Before
  fun setUp() {
    Log.initialize(SystemOutLogger())
    mockkObject(AppDependencies)
    mockkStatic(AppDependencies::class)
    every { AppDependencies.jobManager } returns jobManager
    every { SignalStore.unifiedpush } returns unifiedPush
  }

  @After
  fun tearDown() {
    unmockkStatic(AppDependencies::class)
    unmockkObject(AppDependencies)
  }

  @Test
  fun `terminal failure schedules another bounded send while the update is pending`() {
    val update = ControlUpdate(2, "https://push.example.invalid/e", "p", "a", byteArrayOf(1), 1)
    every { unifiedPush.controlState } returns ControlState(key = "key", activeEpoch = 1, update = update)
    newJob().onFailure()

    verify(exactly = 1) { jobManager.cancelAllInQueue(UnifiedPushControlJob.KEY) }
    verify(exactly = 1) { jobManager.add(any<UnifiedPushControlJob>()) }
  }

  @Test
  fun `terminal failure does not schedule another send after confirmation`() {
    every { unifiedPush.controlState } returns ControlState(key = "key", activeEpoch = 2)

    newJob().onFailure()

    verify(exactly = 0) { jobManager.cancelAllInQueue(UnifiedPushControlJob.KEY) }
    verify(exactly = 0) { jobManager.add(any<UnifiedPushControlJob>()) }
  }

  private fun newJob() = UnifiedPushControlJob.Factory().create(Job.Parameters.Builder().build(), null)
}
