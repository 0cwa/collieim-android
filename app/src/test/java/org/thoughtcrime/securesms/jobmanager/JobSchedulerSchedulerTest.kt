package org.thoughtcrime.securesms.jobmanager

import android.app.Application
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.jobmanager.impl.ChargingConstraint
import org.thoughtcrime.securesms.jobmanager.impl.NetworkConstraint
import org.thoughtcrime.securesms.testutil.SystemOutLogger
import java.util.ArrayDeque
import java.util.concurrent.Executor
import java.util.function.LongSupplier
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [35])
class JobSchedulerSchedulerTest {

  private val context: Application get() = ApplicationProvider.getApplicationContext()
  private val system: JobScheduler get() = context.getSystemService(JobScheduler::class.java)
  private val commands = ArrayDeque<Runnable>()
  private val executor = Executor { commands.addLast(it) }
  private var now = 1_000_000L
  private lateinit var scheduler: JobSchedulerScheduler

  @Before
  fun setUp() {
    Log.initialize(SystemOutLogger())
    system.cancelAll()
    scheduler = JobSchedulerScheduler(context, executor, LongSupplier { now })
  }

  @After
  fun tearDown() {
    system.cancelAll()
  }

  @Test
  fun `an earlier wakeup replaces a pending later wakeup`() {
    scheduler.schedule(2.hours.inWholeMilliseconds, emptyList())
    runAll()
    val later = pending()

    scheduler.schedule(2.minutes.inWholeMilliseconds, emptyList())
    runAll()

    assertEquals(now + 2.minutes.inWholeMilliseconds, pending().wakeupAt)
    assertEquals(2.minutes.inWholeMilliseconds, pending().minLatencyMillis)
    assertNotEquals(later.generation, pending().generation)
    assertEquals(1, system.allPendingJobs.size)
  }

  @Test
  fun `equal and later requests keep an already scheduled earlier wakeup`() {
    scheduler.schedule(2.minutes.inWholeMilliseconds, emptyList())
    runAll()
    val early = pending()

    scheduler.schedule(2.minutes.inWholeMilliseconds, emptyList())
    scheduler.schedule(2.hours.inWholeMilliseconds, emptyList())
    runAll()

    assertEquals(early.generation, pending().generation)
    assertEquals(early.wakeupAt, pending().wakeupAt)
    assertEquals(1, system.allPendingJobs.size)
  }

  @Test
  fun `reversed executor order still keeps the earliest original deadline`() {
    val requestedAt = now
    scheduler.schedule(2.minutes.inWholeMilliseconds, emptyList())
    scheduler.schedule(2.hours.inWholeMilliseconds, emptyList())
    now += 30.seconds.inWholeMilliseconds

    commands.removeLast().run()
    commands.removeFirst().run()

    assertEquals(requestedAt + 2.minutes.inWholeMilliseconds, pending().wakeupAt)
    assertEquals(90.seconds.inWholeMilliseconds, pending().minLatencyMillis)
    assertEquals(1, system.allPendingJobs.size)
  }

  @Test
  fun `completed early generation can be replaced by the reminder before Android removes it`() {
    val linkedAt = now
    scheduler.schedule(2.minutes.inWholeMilliseconds, emptyList())
    runAll()
    val early = pending()
    now = early.wakeupAt

    scheduler.onWakeupFinished(early.id, early.extras)
    runAll()
    // Android can still report the old JobInfo after jobFinished has been called.
    assertEquals(early.generation, pending().generation)
    val reminderAt = linkedAt + 2.hours.inWholeMilliseconds
    scheduler.schedule(reminderAt - now, emptyList())
    runAll()

    assertEquals(reminderAt, pending().wakeupAt)
    assertNotEquals(early.generation, pending().generation)
    assertEquals(early.id, pending().id)
    assertEquals(1, system.allPendingJobs.size)
  }

  @Test
  fun `stale completion cannot consume a newer scheduled generation`() {
    scheduler.schedule(2.hours.inWholeMilliseconds, emptyList())
    runAll()
    val old = pending()
    scheduler.schedule(2.minutes.inWholeMilliseconds, emptyList())
    runAll()
    val current = pending()

    scheduler.onWakeupFinished(old.id, old.extras)
    runAll()
    scheduler.schedule(3.hours.inWholeMilliseconds, emptyList())
    runAll()

    assertEquals(current.generation, pending().generation)
    assertEquals(current.wakeupAt, pending().wakeupAt)
  }

  @Test
  fun `overdue network wakeup is retained until its generation actually completes`() {
    val constraints = listOf(NetworkConstraint.Factory(context).create())
    val id = "NETWORK".hashCode()
    scheduler.schedule(1.minutes.inWholeMilliseconds, constraints)
    runAll()
    val waitingForNetwork = pending(id)
    now += 10.minutes.inWholeMilliseconds

    scheduler.schedule(2.hours.inWholeMilliseconds, constraints)
    runAll()

    assertTrue(waitingForNetwork.wakeupAt < now)
    assertEquals(waitingForNetwork.generation, pending(id).generation)
    assertEquals(waitingForNetwork.wakeupAt, pending(id).wakeupAt)
    assertNotNull(pending(id).requiredNetwork)
  }

  @Test
  fun `legacy pending job is converted to one immediate wakeup with known metadata`() {
    val legacy = JobInfo.Builder(0, ComponentName(context, JobSchedulerScheduler.SystemService::class.java))
      .setMinimumLatency(2.hours.inWholeMilliseconds)
      .setPersisted(true)
      .build()
    assertEquals(JobScheduler.RESULT_SUCCESS, system.schedule(legacy))

    scheduler.schedule(2.minutes.inWholeMilliseconds, emptyList())
    runAll()
    val replacement = pending()
    assertEquals(now, replacement.wakeupAt)
    assertEquals(0L, replacement.minLatencyMillis)
    assertTrue(!replacement.generation.isNullOrBlank())

    scheduler.schedule(5.minutes.inWholeMilliseconds, emptyList())
    runAll()
    assertEquals(replacement.generation, pending().generation)
    assertEquals(1, system.allPendingJobs.size)
  }

  @Test
  fun `persisted job retains network and charging requirements with a stable constraint identity`() {
    val network = NetworkConstraint.Factory(context).create()
    val charging = ChargingConstraint.Factory().create()
    val id = "CHARGING-NETWORK".hashCode()
    scheduler.schedule(2.minutes.inWholeMilliseconds, listOf(network, charging))
    runAll()
    val original = pending(id)

    assertTrue(original.isPersisted)
    assertTrue(original.isRequireCharging)
    assertNotNull(original.requiredNetwork)
    assertEquals(ComponentName(context, JobSchedulerScheduler.SystemService::class.java), original.service)
    scheduler.schedule(2.hours.inWholeMilliseconds, listOf(charging, network))
    runAll()

    assertEquals(original.generation, pending(id).generation)
    assertEquals(1, system.allPendingJobs.size)
  }

  @Test
  fun `many delayed jobs share one system wakeup instead of consuming a slot per event`() {
    repeat(150) { index ->
      scheduler.schedule((index % 5 + 1).minutes.inWholeMilliseconds, emptyList())
    }
    while (commands.isNotEmpty()) {
      commands.removeLast().run()
    }

    assertEquals(1, system.allPendingJobs.size)
    assertEquals(now + 1.minutes.inWholeMilliseconds, pending().wakeupAt)
  }

  @Test
  fun `new scheduler instance respects the deadline retained in Android job extras`() {
    scheduler.schedule(5.minutes.inWholeMilliseconds, emptyList())
    runAll()
    val persisted = pending()
    val restarted = JobSchedulerScheduler(context, executor, LongSupplier { now })

    restarted.schedule(2.hours.inWholeMilliseconds, emptyList())
    runAll()
    assertEquals(persisted.generation, pending().generation)

    restarted.schedule(1.minutes.inWholeMilliseconds, emptyList())
    runAll()
    assertEquals(now + 1.minutes.inWholeMilliseconds, pending().wakeupAt)
    assertNotEquals(persisted.generation, pending().generation)
  }

  @Test
  fun `synchronous rearming persists before pending executor work is drained`() {
    scheduler.schedule(2.hours.inWholeMilliseconds, emptyList())

    assertTrue(scheduler.scheduleSynchronously(2.minutes.inWholeMilliseconds, emptyList()))
    val successor = pending()
    assertEquals(now + 2.minutes.inWholeMilliseconds, successor.wakeupAt)

    runAll()
    assertEquals(successor.generation, pending().generation)
    assertEquals(1, system.allPendingJobs.size)
  }

  @Test
  fun `retrying a consumed generation restores its protection against later timers`() {
    scheduler.schedule(2.minutes.inWholeMilliseconds, emptyList())
    runAll()
    val retrying = pending()
    now = retrying.wakeupAt

    scheduler.onWakeupFinished(retrying.id, retrying.extras)
    scheduler.onWakeupRetrying(retrying.id, retrying.extras)
    scheduler.schedule(2.hours.inWholeMilliseconds, emptyList())
    runAll()

    assertEquals(retrying.generation, pending().generation)
    assertEquals(retrying.wakeupAt, pending().wakeupAt)
  }

  private fun runAll() {
    while (commands.isNotEmpty()) {
      commands.removeFirst().run()
    }
  }

  private fun pending(id: Int = 0): JobInfo = requireNotNull(system.getPendingJob(id))

  private val JobInfo.wakeupAt: Long
    get() = extras.getLong(JobSchedulerScheduler.WAKEUP_AT)

  private val JobInfo.generation: String?
    get() = extras.getString(JobSchedulerScheduler.WAKEUP_GENERATION)
}
