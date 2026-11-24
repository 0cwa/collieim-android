package org.thoughtcrime.securesms.jobs

import android.app.Application
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.models.recipients.RecipientId
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.calls.permissions.CallPermissions
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.jobmanager.Job
import org.thoughtcrime.securesms.jobs.protos.GroupCallPeekJobData
import org.thoughtcrime.securesms.testutil.SystemOutLogger

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [35])
class GroupCallPeekJobCallPermissionTest {
  private var generation = 1L
  private val group = RecipientId.from(9)
  private val sender = RecipientId.from(1)

  @Before
  fun setUp() {
    Log.initialize(SystemOutLogger())
    mockkObject(CallPermissions)
    mockkStatic(CallPermissions::class, AppDependencies::class)
    every { AppDependencies.signalCallManager } throws AssertionError("Denied remote peek must not obtain native manager")
    every { AppDependencies.jobManager } throws AssertionError("Denied remote peek must not enqueue work")
    every { CallPermissions.generation() } answers { generation }
    every { CallPermissions.runIfCurrent(any(), any()) } answers {
      if (firstArg<Long>() == generation) { secondArg<Runnable>().run(); true } else false
    }
  }

  @After
  fun tearDown() = unmockkAll()

  @Test
  fun `legacy and revoked jobs cannot reach either native manager or next queue`() {
    every { CallPermissions.isGroupPeekPermitValid(any(), group, sender) } returns false
    for (permit in listOf(null, "revoked")) {
      val data = data(permit)
      GroupCallPeekJob.enqueue(data)
      assertTrue(GroupCallPeekJob.Factory().create(Job.Parameters.Builder().build(), data.encode()).run().isSuccess)
      assertTrue(GroupCallPeekWorkerJob.Factory().create(Job.Parameters.Builder().build(), data.encode()).run().isSuccess)
    }
    verify(exactly = 0) { AppDependencies.signalCallManager }
    verify(exactly = 0) { AppDependencies.jobManager }
  }

  @Test
  fun `worker retains arrival permit and blocks revocation between queue validation and native lookup`() {
    every { CallPermissions.isGroupPeekPermitValid("arrival", group, sender) } answers { generation = 3; true }
    val job = GroupCallPeekWorkerJob.Factory().create(Job.Parameters.Builder().build(), data("arrival").encode())
    assertEquals("arrival", GroupCallPeekJobData.ADAPTER.decode(job.serialize()).callPermissionPermit)
    assertTrue(job.run().isSuccess)
    verify(exactly = 0) { AppDependencies.signalCallManager }
  }

  private fun data(permit: String?) = GroupCallPeekJobData(9, 1, 100, callPermissionPermit = permit)
}
