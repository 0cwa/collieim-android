package org.thoughtcrime.securesms.jobmanager

import android.app.job.JobInfo
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.thoughtcrime.securesms.jobmanager.persistence.ConstraintSpec
import org.thoughtcrime.securesms.jobmanager.persistence.JobStorage
import org.thoughtcrime.securesms.jobs.MinimalJobSpec
import java.util.function.Predicate
import kotlin.time.Duration.Companion.minutes

/** Exercises durable wakeup recovery without starting any job-runner threads. */
class JobControllerDelayedWakeupTest {
  private val storedJobs = mutableListOf<MinimalJobSpec>()
  private val constraintsByJob = mutableMapOf<String, List<ConstraintSpec>>()
  private val scheduled = mutableListOf<Wakeup>()
  private val storage = mockk<JobStorage>(relaxed = true)
  private val jobInstantiator = mockk<JobInstantiator>(relaxed = true)
  private lateinit var controller: JobController

  @Before
  fun setUp() {
    every { storage.getAllMinimalJobSpecsMatchingFilter(any()) } answers {
      storedJobs.filter { firstArg<Predicate<MinimalJobSpec>>().test(it) }
    }
    every { storage.getConstraintSpecs(any()) } answers { constraintsByJob[firstArg()] ?: emptyList() }
    every { storage.updateAllJobsToBePending() } answers {
      storedJobs.replaceAll { it.copy(isRunning = false) }
    }
    every { storage.deleteJob(any()) } answers {
      storedJobs.removeAll { it.id == firstArg<String>() }
      Unit
    }

    val constraintFactories = listOf("lock", "network").associateWith { key ->
      Constraint.Factory { TestConstraint(key) }
    }
    val scheduler = Scheduler { delay, constraints ->
      scheduled += Wakeup(delay, constraints.map { it.factoryKey })
    }
    controller = JobController(
      mockk(),
      storage,
      jobInstantiator,
      ConstraintInstantiator(constraintFactories),
      mockk(relaxed = true),
      scheduler,
      mockk(relaxed = true),
      mockk(relaxed = true),
      0,
      0,
      0,
      emptyList()
    )
  }

  @Test
  fun `first wakeup restores durable timers after initialization resets interrupted jobs`() {
    val created = System.currentTimeMillis() - 1.minutes.inWholeMilliseconds
    add(job("early", created, 3.minutes.inWholeMilliseconds, isRunning = true, queuePriority = Job.Parameters.PRIORITY_HIGH), "lock")
    add(job("reminder", created, 120.minutes.inWholeMilliseconds), "lock")

    controller.init()
    assertTrue(scheduled.isEmpty())
    verify(exactly = 0) { storage.getConstraintSpecs(any()) }

    // JobManager marks itself initialized before this call, allowing constraints to query it.
    val before = System.currentTimeMillis()
    controller.wakeUp()
    val after = System.currentTimeMillis()

    val deadline = created + 3.minutes.inWholeMilliseconds + 1
    assertEquals(1, scheduled.size)
    assertEquals(listOf("lock"), scheduled.single().constraints)
    assertTrue(scheduled.single().delay in (deadline - after)..(deadline - before))
    assertFalse(storedJobs.any { it.isRunning })
    verify(exactly = 1) { storage.updateAllJobsToBePending() }
    verify(exactly = 0) { jobInstantiator.instantiate(any(), any(), any()) }
  }

  @Test
  fun `timer wakeup rearms later work while unrelated long running work prevents a queue drain`() {
    val created = System.currentTimeMillis() - 1.minutes.inWholeMilliseconds
    add(job("running", created, 0, isRunning = true).copy(queueKey = "unrelated"), "lock")
    add(job("due", created, 0), "lock")
    add(job("later", created, 3.minutes.inWholeMilliseconds), "lock")

    val before = System.currentTimeMillis()
    controller.wakeUp()
    val after = System.currentTimeMillis()

    val deadline = created + 3.minutes.inWholeMilliseconds + 1
    assertEquals(1, scheduled.size)
    assertTrue(scheduled.single().delay in (deadline - after)..(deadline - before))
    assertTrue(storedJobs.first { it.id == "running" }.isRunning)
    assertFalse(controller.runnersStarted.get())
  }

  @Test
  fun `completion can persist future deadlines through synchronous scheduler before returning`() {
    val created = System.currentTimeMillis()
    add(job("future", created, 3.minutes.inWholeMilliseconds), "lock")
    val synchronous = mutableListOf<Wakeup>()

    controller.schedulePendingJobs(Scheduler { delay, constraints ->
      synchronous += Wakeup(delay, constraints.map { it.factoryKey })
    })

    assertEquals(1, synchronous.size)
    assertTrue(synchronous.single().delay > 0)
    assertEquals(listOf("lock"), synchronous.single().constraints)
    assertTrue(scheduled.isEmpty())
  }

  @Test
  fun `draining early alert rearms original reminder deadline`() {
    add(job("early", 500, 1000, queuePriority = Job.Parameters.PRIORITY_HIGH), "lock")
    add(job("reminder", 500, 10_000), "lock")

    controller.schedulePendingJobs(1000)
    assertEquals(listOf(Wakeup(501, listOf("lock"))), scheduled)

    val completedEarly = mockk<Job>()
    every { completedEarly.id } returns "early"
    controller.onSuccess(completedEarly, null)
    scheduled.clear()
    controller.schedulePendingJobs(1501)

    assertEquals(listOf(Wakeup(9000, listOf("lock"))), scheduled)
    assertEquals(listOf("reminder"), storedJobs.map { it.id })
    verify(exactly = 0) { jobInstantiator.instantiate(any(), any(), any()) }
  }

  @Test
  fun `past due reminder blocked by future early alert does not create immediate wakeup loop`() {
    add(job("reminder", 100, 500), "lock")
    add(job("early", 500, 2000, queuePriority = Job.Parameters.PRIORITY_HIGH), "lock")

    controller.schedulePendingJobs(1000)

    assertEquals(listOf(Wakeup(1501, listOf("lock"))), scheduled)
    verify(exactly = 0) { storage.getConstraintSpecs("reminder") }
  }

  @Test
  fun `latest of initial delay and retry backoff determines deadline`() {
    add(job("backoff", 100, 1100).copy(lastRunAttemptTime = 900, nextBackoffInterval = 2100), "lock")
    add(job("initial", 100, 5900).copy(lastRunAttemptTime = 900, nextBackoffInterval = 100), "network")

    controller.schedulePendingJobs(1000)

    assertEquals(setOf(Wakeup(2001, listOf("lock")), Wakeup(5001, listOf("network"))), scheduled.toSet())
  }

  @Test
  fun `equivalent constraint sets share earliest timer while different sets remain independent`() {
    add(job("later", 100, 3000), "network", "lock")
    add(job("earlier", 100, 1500), "lock", "network", "lock")
    add(job("local", 100, 2000), "lock")

    controller.schedulePendingJobs(1000)

    assertEquals(setOf(Wakeup(601, listOf("lock", "network")), Wakeup(1101, listOf("lock"))), scheduled.toSet())
    assertEquals(2, scheduled.size)
  }

  @Test
  fun `running and already eligible jobs do not create future timers`() {
    add(job("running", 100, 3000, isRunning = true), "lock")
    add(job("due", 100, 100), "lock")

    controller.schedulePendingJobs(1000)

    assertTrue(scheduled.isEmpty())
    verify(exactly = 0) { storage.getConstraintSpecs(any()) }
  }

  @Test
  fun `memory only future work retains a timer after earlier work drains`() {
    add(job("early", 100, 1500), "lock")
    add(job("memory", 100, 3000).copy(isMemoryOnly = true), "lock")

    controller.schedulePendingJobs(1000)
    assertEquals(listOf(Wakeup(601, listOf("lock"))), scheduled)

    storedJobs.removeAll { it.id == "early" }
    scheduled.clear()
    controller.schedulePendingJobs(1601)

    assertEquals(listOf(Wakeup(1500, listOf("lock"))), scheduled)
  }

  @Test
  fun `clock rollback follows immediate eligibility instead of delaying jobs again`() {
    add(job("future-created", 1100, 3000), "lock")
    add(job("future-attempt", 100, 3000).copy(lastRunAttemptTime = 1100, nextBackoffInterval = 5000), "lock")

    controller.schedulePendingJobs(1000)

    assertTrue(scheduled.isEmpty())
  }

  @Test
  fun `strict eligibility boundary gets a one millisecond timer then no further timer`() {
    add(job("boundary", 100, 900), "lock")

    controller.schedulePendingJobs(1000)
    assertEquals(listOf(Wakeup(1, listOf("lock"))), scheduled)

    scheduled.clear()
    controller.schedulePendingJobs(1001)
    assertTrue(scheduled.isEmpty())
  }

  @Test
  fun `extreme persisted delay cannot overflow into an already due timestamp`() {
    add(job("large-delay", 1000, Long.MAX_VALUE), "lock")

    controller.schedulePendingJobs(1000)

    assertEquals(listOf(Wakeup(Long.MAX_VALUE - 1000, listOf("lock"))), scheduled)
  }

  private fun add(job: MinimalJobSpec, vararg constraints: String) {
    storedJobs += job
    constraintsByJob[job.id] = constraints.map { ConstraintSpec(job.id, it, job.isMemoryOnly) }
  }

  private fun job(id: String, createTime: Long, initialDelay: Long, isRunning: Boolean = false, queuePriority: Int = Job.Parameters.PRIORITY_DEFAULT) = MinimalJobSpec(
    id = id,
    factoryKey = "NewLinkedDeviceNotificationJob",
    queueKey = "NewLinkedDeviceNotificationJob",
    createTime = createTime,
    lastRunAttemptTime = 0,
    nextBackoffInterval = 0,
    globalPriority = Job.Parameters.PRIORITY_DEFAULT,
    queuePriority = queuePriority,
    isRunning = isRunning,
    isMemoryOnly = false,
    initialDelay = initialDelay
  )

  private data class Wakeup(val delay: Long, val constraints: List<String>)

  private class TestConstraint(private val key: String) : Constraint {
    override fun isMet() = true
    override fun getFactoryKey() = key
    override fun applyToJobInfo(jobInfoBuilder: JobInfo.Builder) = Unit
  }
}
