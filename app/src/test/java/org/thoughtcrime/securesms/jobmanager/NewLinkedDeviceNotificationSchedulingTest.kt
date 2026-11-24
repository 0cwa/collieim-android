package org.thoughtcrime.securesms.jobmanager

import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.database.JobDatabase
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.jobmanager.impl.MasterSecretConstraint
import org.thoughtcrime.securesms.jobmanager.impl.NetworkConstraint
import org.thoughtcrime.securesms.jobmanager.persistence.ConstraintSpec
import org.thoughtcrime.securesms.jobmanager.persistence.FullSpec
import org.thoughtcrime.securesms.jobmanager.persistence.JobSpec
import org.thoughtcrime.securesms.jobs.FastJobStorage
import org.thoughtcrime.securesms.jobs.NewLinkedDeviceNotificationJob
import org.thoughtcrime.securesms.jobs.protos.NewLinkedDeviceNotificationJobData
import org.thoughtcrime.securesms.jobs.toMinimalJobSpec
import org.thoughtcrime.securesms.testutil.SystemOutLogger
import kotlin.random.Random
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

class NewLinkedDeviceNotificationSchedulingTest {

  @Before
  fun setUp() {
    Log.initialize(SystemOutLogger())
  }

  @Test
  fun `enqueue submits both notification phases together without a dependency between them`() {
    val manager = mockk<JobManager>()
    val submitted = slot<List<Job>>()
    mockkObject(AppDependencies)
    mockkStatic(AppDependencies::class)
    try {
      every { AppDependencies.jobManager } returns manager
      every { manager.addAll(capture(submitted)) } returns Unit

      NewLinkedDeviceNotificationJob.enqueue(2, 1234L)

      verify(exactly = 1) { manager.addAll(any<List<Job>>()) }
      val jobs = submitted.captured
      assertEquals(2, jobs.size)
      assertEquals(listOf(true, false), jobs.map { NewLinkedDeviceNotificationJobData.ADAPTER.decode(it.serialize()!!).isEarlyNotification })
      assertEquals(2, jobs.map { it.id }.distinct().size)
      for (job in jobs) {
        val data = NewLinkedDeviceNotificationJobData.ADAPTER.decode(job.serialize()!!)
        assertEquals(2, data.deviceId)
        assertEquals(1234L, data.deviceCreatedAt)
        assertFalse(job.parameters.isMemoryOnly)
        assertEquals(7.days.inWholeMilliseconds, job.parameters.lifespan)
        assertEquals(Job.Parameters.UNLIMITED, job.parameters.maxAttempts)
        assertTrue(job.parameters.constraintKeys.contains(MasterSecretConstraint.KEY))
        assertFalse(job.parameters.constraintKeys.contains(NetworkConstraint.KEY))
      }
    } finally {
      unmockkStatic(AppDependencies::class)
      unmockkObject(AppDependencies)
    }
  }

  @Test
  fun `early delay includes both one and five minutes`() {
    mockkObject(Random.Default)
    try {
      every { Random.nextInt(1, 6) } returnsMany listOf(1, 5)

      val first = NewLinkedDeviceNotificationJob(2, 1234L, isEarlyNotification = true)
      val last = NewLinkedDeviceNotificationJob(2, 1234L, isEarlyNotification = true)

      assertEquals(1.minutes.inWholeMilliseconds, first.parameters.initialDelay)
      assertEquals(5.minutes.inWholeMilliseconds, last.parameters.initialDelay)
      verify(exactly = 2) { Random.nextInt(1, 6) }
    } finally {
      unmockkObject(Random.Default)
    }
  }

  @Test
  fun `reminder keeps the original sixty through one hundred seventy nine minute delay`() {
    mockkObject(Random.Default)
    try {
      every { Random.nextInt(60, 180) } returnsMany listOf(60, 179)

      val first = NewLinkedDeviceNotificationJob(2, 1234L)
      val last = NewLinkedDeviceNotificationJob(2, 1234L)

      assertEquals(60.minutes.inWholeMilliseconds, first.parameters.initialDelay)
      assertEquals(179.minutes.inWholeMilliseconds, last.parameters.initialDelay)
      verify(exactly = 2) { Random.nextInt(60, 180) }
    } finally {
      unmockkObject(Random.Default)
    }
  }

  @Test
  fun `factory restores either phase without redrawing its delay or resetting its parameters`() {
    for (early in listOf(true, false)) {
      val original = NewLinkedDeviceNotificationJob(3, 5678L, isEarlyNotification = early)
      val restored = NewLinkedDeviceNotificationJob.Factory().create(original.parameters, original.serialize())

      assertSame(original.parameters, restored.parameters)
      assertEquals(original.id, restored.id)
      assertEquals(original.parameters.initialDelay, restored.parameters.initialDelay)
      assertEquals(original.parameters.queuePriority, restored.parameters.queuePriority)
      assertEquals(NewLinkedDeviceNotificationJob.KEY, restored.factoryKey)
      assertEquals(
        NewLinkedDeviceNotificationJobData.ADAPTER.decode(original.serialize()),
        NewLinkedDeviceNotificationJobData.ADAPTER.decode(restored.serialize())
      )
    }
  }

  @Test
  fun `legacy persisted payload remains a reminder with its existing schedule and constraints`() {
    // Pre-change wire payload: deviceId = 2, deviceCreatedAt = 42, no phase field.
    val oldPayload = byteArrayOf(0x08, 0x02, 0x10, 0x2a)
    val oldParameters = Job.Parameters.Builder()
      .setQueue(NewLinkedDeviceNotificationJob.KEY)
      .setCreateTime(1000L)
      .setInitialDelay(123.minutes.inWholeMilliseconds)
      .addConstraint(NetworkConstraint.KEY)
      .setLifespan(7.days.inWholeMilliseconds)
      .setMaxAttempts(Job.Parameters.UNLIMITED)
      .build()

    val restored = NewLinkedDeviceNotificationJob.Factory().create(oldParameters, oldPayload)
    val data = NewLinkedDeviceNotificationJobData.ADAPTER.decode(restored.serialize())

    assertFalse(data.isEarlyNotification)
    assertEquals(2, data.deviceId)
    assertEquals(42L, data.deviceCreatedAt)
    assertEquals(oldParameters.id, restored.id)
    assertEquals(1000L, restored.parameters.createTime)
    assertEquals(123.minutes.inWholeMilliseconds, restored.parameters.initialDelay)
    assertEquals(Job.Parameters.PRIORITY_DEFAULT, restored.parameters.queuePriority)
    assertTrue(restored.parameters.constraintKeys.contains(NetworkConstraint.KEY))
    assertTrue(restored.parameters.constraintKeys.contains(MasterSecretConstraint.KEY))
  }

  @Test
  fun `early notification can run ahead of an older pending reminder before and after storage reload`() {
    val oldReminder = NewLinkedDeviceNotificationJob.Factory().create(
      Job.Parameters.Builder()
        .setQueue(NewLinkedDeviceNotificationJob.KEY)
        .setInitialDelay(120.minutes.inWholeMilliseconds)
        .build(),
      byteArrayOf(0x08, 0x02, 0x10, 0x2a)
    )
    val early = NewLinkedDeviceNotificationJob(3, 5678L, isEarlyNotification = true)
    val reminderSpec = fullSpec(oldReminder, createTime = 1000L)
    val earlySpec = fullSpec(early, createTime = 2000L)
    val specs = listOf(reminderSpec, earlySpec)
    val database = mockk<JobDatabase>(relaxed = true)
    every { database.getAllMinimalJobSpecs() } returns specs.map { it.jobSpec.toMinimalJobSpec() }
    every { database.getJobSpecs(any()) } returns specs.map { it.jobSpec }
    every { database.getConstraintSpecsForJobs(any()) } returns specs.flatMap { it.constraintSpecs }
    every { database.getAllDependencySpecs() } returns emptyList()
    val storage = FastJobStorage(database)
    storage.insertJobs(specs)

    assertEquals(oldReminder.parameters.queue, early.parameters.queue)
    assertTrue(early.parameters.queuePriority > oldReminder.parameters.queuePriority)
    val justBeforeEarly = earlySpec.jobSpec.createTime + earlySpec.jobSpec.initialDelay - 1
    val afterEarly = earlySpec.jobSpec.createTime + earlySpec.jobSpec.initialDelay + 1
    assertNull(storage.getNextEligibleJob(justBeforeEarly) { true })
    assertEquals(early.id, storage.getNextEligibleJob(afterEarly) { true }?.id)

    val reloaded = FastJobStorage(database).apply { init() }
    assertNull(reloaded.getNextEligibleJob(justBeforeEarly) { true })
    assertEquals(early.id, reloaded.getNextEligibleJob(afterEarly) { true }?.id)

    reloaded.deleteJob(early.id)
    assertNull(reloaded.getNextEligibleJob(afterEarly) { true })
    assertEquals(oldReminder.id, reloaded.getJobSpec(oldReminder.id)?.id)
    val afterReminder = reminderSpec.jobSpec.createTime + reminderSpec.jobSpec.initialDelay + 1
    assertEquals(oldReminder.id, reloaded.getNextEligibleJob(afterReminder) { true }?.id)
  }

  private fun fullSpec(job: Job, createTime: Long): FullSpec {
    val parameters = job.parameters
    val spec = JobSpec(
      id = job.id,
      factoryKey = job.factoryKey,
      queueKey = parameters.queue,
      createTime = createTime,
      lastRunAttemptTime = 0,
      nextBackoffInterval = 0,
      runAttempt = 0,
      maxAttempts = parameters.maxAttempts,
      lifespan = parameters.lifespan,
      serializedData = job.serialize(),
      serializedInputData = null,
      isRunning = false,
      isMemoryOnly = parameters.isMemoryOnly,
      globalPriority = parameters.globalPriority,
      queuePriority = parameters.queuePriority,
      initialDelay = parameters.initialDelay
    )
    return FullSpec(spec, parameters.constraintKeys.map { ConstraintSpec(job.id, it, parameters.isMemoryOnly) }, emptyList())
  }
}
