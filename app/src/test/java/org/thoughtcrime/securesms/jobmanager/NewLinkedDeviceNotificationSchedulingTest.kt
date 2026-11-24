package org.thoughtcrime.securesms.jobmanager

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.core.content.contentValuesOf
import androidx.test.core.app.ApplicationProvider
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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.util.logging.Log
import org.signal.storage.database.DatabaseSecret
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
import java.io.File
import kotlin.random.Random
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import net.zetetic.database.sqlcipher.SQLiteDatabase as SqlCipherDatabase

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [35])
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
  fun `factory decodes the legacy payload with the migrated scheduling parameters`() {
    // Pre-change wire payload: deviceId = 2, deviceCreatedAt = 42, no phase field.
    val oldPayload = byteArrayOf(0x08, 0x02, 0x10, 0x2a)
    val oldParameters = Job.Parameters.Builder()
      .setQueue(NewLinkedDeviceNotificationJob.KEY)
      .setCreateTime(1000L)
      .setInitialDelay(123.minutes.inWholeMilliseconds)
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
    assertFalse(restored.parameters.constraintKeys.contains(NetworkConstraint.KEY))
    assertTrue(restored.parameters.constraintKeys.contains(MasterSecretConstraint.KEY))
  }

  @Test
  fun `persisted legacy reminders become eligible offline without changing queue order or deadlines`() {
    val oldReminder = NewLinkedDeviceNotificationJob.Factory().create(
      Job.Parameters.Builder()
        .setQueue(NewLinkedDeviceNotificationJob.KEY)
        .setInitialDelay(90.minutes.inWholeMilliseconds)
        .addConstraint(NetworkConstraint.KEY)
        .build(),
      byteArrayOf(0x08, 0x02, 0x10, 0x2a)
    )
    val early = NewLinkedDeviceNotificationJob(3, 5678L, isEarlyNotification = true)
    val reminder = NewLinkedDeviceNotificationJob(3, 5678L)
    val oldSpec = fullSpec(oldReminder, createTime = 1000L)
    val earlySpec = fullSpec(early, createTime = 2000L)
    val reminderSpec = fullSpec(reminder, createTime = 2000L).let {
      it.copy(jobSpec = it.jobSpec.copy(initialDelay = 120.minutes.inWholeMilliseconds))
    }
    val otherSpec = oldSpec.copy(
      jobSpec = oldSpec.jobSpec.copy(id = "unrelated", factoryKey = "UnrelatedJob", queueKey = null),
      constraintSpecs = listOf(ConstraintSpec("unrelated", NetworkConstraint.KEY, false))
    )
    val specs = listOf(oldSpec, earlySpec, reminderSpec, otherSpec)

    val app = ApplicationProvider.getApplicationContext<Application>()
    val file = File(app.cacheDir, "legacy-job-storage.db")
    val jobDatabase = JobDatabase(app, DatabaseSecret.fromBytes(ByteArray(32)))
    try {
      SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
        jobDatabase.onCreate(cipherFacade(db))
        db.version = 5
        for ((spec, constraints) in specs) {
          db.insertOrThrow(
            "job_spec",
            null,
            contentValuesOf(
              "job_spec_id" to spec.id,
              "factory_key" to spec.factoryKey,
              "queue_key" to spec.queueKey,
              "create_time" to spec.createTime,
              "queue_priority" to spec.queuePriority,
              "initial_delay" to spec.initialDelay,
              "serialized_data" to spec.serializedData
            )
          )
          for (constraint in constraints) {
            db.insertOrThrow("constraint_spec", null, contentValuesOf("job_spec_id" to spec.id, "factory_key" to constraint.factoryKey))
          }
        }
      }

      SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
        jobDatabase.onUpgrade(cipherFacade(db), db.version, 6)
        db.version = 6
      }

      SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
        val storedSpecs = db.rawQuery("SELECT job_spec_id, queue_key, create_time, queue_priority, initial_delay, serialized_data FROM job_spec ORDER BY _id", null).use {
          buildList {
            while (it.moveToNext()) {
              val original = specs.single { row -> row.jobSpec.id == it.getString(0) }.jobSpec
              assertTrue(original.serializedData!!.contentEquals(it.getBlob(5)))
              add(original.copy(queueKey = it.getString(1), createTime = it.getLong(2), queuePriority = it.getInt(3), initialDelay = it.getLong(4)))
            }
          }
        }
        val storedConstraints = db.rawQuery("SELECT job_spec_id, factory_key FROM constraint_spec", null).use {
          buildList {
            while (it.moveToNext()) {
              add(ConstraintSpec(it.getString(0), it.getString(1), false))
            }
          }
        }
        assertEquals(specs.map { it.jobSpec }, storedSpecs)
        assertEquals(specs.map { it.jobSpec.queuePriority }, storedSpecs.map { it.queuePriority })
        assertEquals(specs.map { it.jobSpec.initialDelay }, storedSpecs.map { it.initialDelay })
        assertEquals(specs.flatMap { it.constraintSpecs }.filterNot { it.jobSpecId == oldReminder.id && it.factoryKey == NetworkConstraint.KEY }.toSet(), storedConstraints.toSet())

        val database = mockk<JobDatabase>(relaxed = true)
        every { database.getAllMinimalJobSpecs() } returns storedSpecs.map { it.toMinimalJobSpec() }
        every { database.getJobSpecs(any()) } returns storedSpecs
        every { database.getConstraintSpecsForJobs(any()) } answers {
          val ids = firstArg<Collection<String>>()
          storedConstraints.filter { it.jobSpecId in ids }
        }
        val storage = FastJobStorage(database).apply { init() }
        fun eligibleOffline(time: Long) = storage.getNextEligibleJob(time) { job ->
          storage.getConstraintSpecs(job.id).none { it.factoryKey == NetworkConstraint.KEY }
        }?.id

        val earlyAt = earlySpec.jobSpec.createTime + earlySpec.jobSpec.initialDelay
        val legacyAt = oldSpec.jobSpec.createTime + oldSpec.jobSpec.initialDelay
        val reminderAt = reminderSpec.jobSpec.createTime + reminderSpec.jobSpec.initialDelay
        assertNull(eligibleOffline(earlyAt))
        assertEquals(early.id, eligibleOffline(earlyAt + 1))
        storage.markJobAsRunning(early.id, earlyAt + 1)
        assertNull(eligibleOffline(reminderAt + 1))
        storage.deleteJob(early.id)
        assertNull(eligibleOffline(legacyAt))
        assertEquals(oldReminder.id, eligibleOffline(legacyAt + 1))
        storage.deleteJob(oldReminder.id)
        assertNull(eligibleOffline(reminderAt))
        assertEquals(reminder.id, eligibleOffline(reminderAt + 1))
      }
    } finally {
      SQLiteDatabase.deleteDatabase(file)
    }
  }

  // Run the production upgrade SQL against real SQLite rows in the host JVM.
  private fun cipherFacade(db: SQLiteDatabase): SqlCipherDatabase = mockk {
    every { execSQL(any()) } answers { db.execSQL(firstArg()) }
    every { execSQL(any(), any()) } answers { db.execSQL(firstArg(), secondArg()) }
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
