package org.thoughtcrime.securesms.jobmanager

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import im.molly.unifiedpush.lifecycle.UnifiedPushAccountTransition
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.util.Debouncer
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.jobmanager.migrations.UnifiedPushMessageFetchJobMigration
import org.thoughtcrime.securesms.jobmanager.persistence.FullSpec
import org.thoughtcrime.securesms.jobmanager.persistence.JobStorage
import org.thoughtcrime.securesms.jobs.JobManagerFactories
import org.thoughtcrime.securesms.jobs.MessageFetchJob
import org.thoughtcrime.securesms.messages.WebSocketDrainer
import org.thoughtcrime.securesms.testutil.SystemOutLogger
import java.util.concurrent.ConcurrentHashMap

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class UnifiedPushMessageFetchAdmissionTest {

  private val application: Application get() = ApplicationProvider.getApplicationContext()
  private val jobStorage = mockk<JobStorage>(relaxed = true)
  private val jobInstantiator = mockk<JobInstantiator>(relaxed = true)
  private val constraintInstantiator = mockk<ConstraintInstantiator>(relaxed = true)
  private val jobTracker = mockk<JobTracker>(relaxed = true)
  private val scheduler = mockk<Scheduler>(relaxed = true)
  private val debouncer = mockk<Debouncer>(relaxed = true)
  private val callback = mockk<JobController.Callback>(relaxed = true)
  private val factoryCounts = ConcurrentHashMap<String, Int>()
  private val insertedFactoryKeys = mutableListOf<String>()

  @Before
  fun setUp() {
    Log.initialize(SystemOutLogger())
    factoryCounts.clear()
    insertedFactoryKeys.clear()
    every { jobStorage.getJobCountForFactory(any()) } answers { factoryCounts[firstArg()] ?: 0 }
    every { jobStorage.getJobCountForFactoryAndQueue(any(), any()) } returns 0
    every { jobStorage.getEligibleJobCount(any()) } returns 0
    every { jobStorage.insertJobs(any()) } answers {
      firstArg<List<FullSpec>>().forEach { spec ->
        val key = spec.jobSpec.factoryKey
        insertedFactoryKeys += key
        factoryCounts[key] = (factoryCounts[key] ?: 0) + 1
      }
    }
  }

  @After
  fun tearDown() {
    unmockkObject(WebSocketDrainer)
  }

  @Test
  fun `old persisted UnifiedPush request migrates to its own cap while ordinary demand remains admitted`() {
    val authorization = UnifiedPushAccountTransition.FetchAuthorization(
      processInstanceId = "previous-process-instance",
      generation = 17,
      accountId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
      localDeviceId = 1,
      pushDeviceId = 4,
      instance = "fixture-instance",
    )
    val oldUnifiedPushJob = MessageFetchJob.forUnifiedPush(authorization)
    val oldPersistedData = JobMigration.JobData(
      factoryKey = MessageFetchJob.KEY,
      queueKey = "__notification_received",
      maxAttempts = 3,
      lifespan = -1,
      data = oldUnifiedPushJob.serialize(),
    )
    val migratedData = UnifiedPushMessageFetchJobMigration().migrate(oldPersistedData)

    assertEquals(MessageFetchJob.UNIFIED_PUSH_KEY, migratedData.factoryKey)
    assertEquals(
      MessageFetchJob.KEY,
      UnifiedPushMessageFetchJobMigration().migrate(oldPersistedData.copy(data = null)).factoryKey,
    )
    val restoredUnifiedPushJob = MessageFetchJob.UnifiedPushFactory().create(oldUnifiedPushJob.parameters, migratedData.data)
    assertEquals(MessageFetchJob.UNIFIED_PUSH_KEY, restoredUnifiedPushJob.factoryKey)

    val factories = JobManagerFactories.getJobFactories(application)
    assertTrue("legacy persisted jobs still have a factory", factories.containsKey(MessageFetchJob.KEY))
    assertTrue("migrated jobs have a factory", factories.containsKey(MessageFetchJob.UNIFIED_PUSH_KEY))
    assertEquals(13, JobManager.CURRENT_VERSION)

    val controller = JobController(
      application,
      jobStorage,
      jobInstantiator,
      constraintInstantiator,
      jobTracker,
      scheduler,
      debouncer,
      callback,
      0,
      0,
      1,
      emptyList(),
    )
    controller.submitNewJobChain(listOf(listOf(restoredUnifiedPushJob)))
    controller.submitNewJobChain(listOf(listOf(MessageFetchJob())))

    assertEquals(
      "factory limits must admit both independent origins on the shared queue",
      listOf(MessageFetchJob.UNIFIED_PUSH_KEY, MessageFetchJob.KEY),
      insertedFactoryKeys,
    )

    mockkObject(WebSocketDrainer)
    val rejectedAfterRestart = MessageFetchJob.UnifiedPushFactory().create(restoredUnifiedPushJob.parameters, migratedData.data)
    rejectedAfterRestart.setContext(application)
    rejectedAfterRestart.onRun()

    assertEquals("a stale UnifiedPush job does not consume the ordinary request", 1, factoryCounts[MessageFetchJob.KEY])
    verify(exactly = 0) { WebSocketDrainer.blockUntilDrainedAndProcessed() }
  }
}
