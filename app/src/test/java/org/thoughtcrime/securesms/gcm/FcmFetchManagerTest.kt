package org.thoughtcrime.securesms.gcm

import android.app.Application
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.os.PersistableBundle
import androidx.test.core.app.ApplicationProvider
import im.molly.unifiedpush.lifecycle.UnifiedPushAccountTransition
import im.molly.unifiedpush.lifecycle.UnifiedPushLifecycle
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.Runs
import io.mockk.slot
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.models.ServiceId
import org.signal.core.util.AppForegroundObserver
import org.signal.core.util.ServiceUtil
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.jobs.MessageFetchJob
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.keyvalue.UnifiedPushValues
import org.thoughtcrime.securesms.messages.WebSocketDrainer
import org.thoughtcrime.securesms.messages.UnifiedPushFetchResult
import org.thoughtcrime.securesms.push.SignalServiceNetworkAccess
import org.thoughtcrime.securesms.service.KeyCachingService
import org.thoughtcrime.securesms.testutil.MockSignalStoreRule
import org.thoughtcrime.securesms.testutil.SystemOutLogger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [35])
class FcmFetchManagerTest {

  @get:Rule
  val signalStore = MockSignalStoreRule()

  private val values = mockk<UnifiedPushValues>(relaxUnitFun = true)
  private val networkAccess = mockk<SignalServiceNetworkAccess>()
  private val context: Application get() = ApplicationProvider.getApplicationContext()

  @Before
  fun setUp() {
    Log.initialize(SystemOutLogger())
    UnifiedPushLifecycle.resetForTests()
    mockkObject(AppDependencies, WebSocketDrainer)
    mockkStatic(WebSocketDrainer::class)
    every { AppDependencies.signalServiceNetworkAccess } returns networkAccess
    every { networkAccess.isCensored() } returns false
    every { SignalStore.unifiedpush } returns values

    val aci = mockk<ServiceId.ACI>()
    every { aci.toString() } returns "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    every { SignalStore.account.isRegistered } returns true
    every { SignalStore.account.aci } returns aci
    every { SignalStore.account.hasAciIdentityKey() } returns true
    every { SignalStore.account.servicePassword } returns "SYNTHETIC-SERVICE-PASSWORD"
    every { SignalStore.account.deviceId } returns 1
    every { values.enabled } returns true
    every { values.device } returns im.molly.unifiedpush.model.MollySocketDevice(4, "SYNTHETIC-RELAY-PASSWORD")
    every { values.endpoint } returns "https://push.example.invalid/endpoint"
    every { values.endpointP256dh } returns "SYNTHETIC-P256DH"
    every { values.endpointAuth } returns "SYNTHETIC-AUTH"
    every { values.endpointInstance } returns "fixture-instance"
  }

  @After
  fun tearDown() {
    unmockkObject(AppDependencies, WebSocketDrainer)
    unmockkStatic(WebSocketDrainer::class)
    unmockkStatic(AppForegroundObserver::class)
    unmockkStatic(KeyCachingService::class)
    unmockkStatic(ServiceUtil::class)
    unmockkStatic(FcmJobService::class)
  }

  @Test
  fun `UnifiedPush fetch delegates to observer admission and never registers a drainer token`() {
    val lease = UnifiedPushAccountTransition.acquireLease()!!
    val authorization = UnifiedPushAccountTransition.fetchAuthorization(lease)
    lease.close()
    every { WebSocketDrainer.blockUntilUnifiedPushFetchDrainedAndProcessed(authorization, any()) } returns UnifiedPushFetchResult.DRAINED

    assertTrue(FcmFetchManager.retrieveMessages(context, authorization))

    verify(exactly = 1) { WebSocketDrainer.blockUntilUnifiedPushFetchDrainedAndProcessed(authorization, any()) }
    verify(exactly = 0) { WebSocketDrainer.blockUntilDrainedAndProcessed(any(), any()) }
  }

  @Test
  fun `ordinary fetch keeps its existing drainer token path`() {
    every { WebSocketDrainer.blockUntilDrainedAndProcessed(any(), any()) } returns true

    assertTrue(FcmFetchManager.retrieveMessages(context))

    verify(exactly = 1) { WebSocketDrainer.blockUntilDrainedAndProcessed(any(), "FcmFetch") }
    verify(exactly = 0) { WebSocketDrainer.blockUntilUnifiedPushFetchDrainedAndProcessed(any(), any()) }
  }

  @Test
  fun `UnifiedPush MessageFetch fallback uses observer admission and keeps foreground handling`() {
    val lease = UnifiedPushAccountTransition.acquireLease()!!
    val authorization = UnifiedPushAccountTransition.fetchAuthorization(lease)
    lease.close()
    every { WebSocketDrainer.blockUntilUnifiedPushFetchDrainedAndProcessed(authorization, any()) } returns UnifiedPushFetchResult.DRAINED
    mockkStatic(AppForegroundObserver::class)
    every { AppForegroundObserver.addListener(any()) } just Runs
    every { AppForegroundObserver.removeListener(any()) } just Runs

    val job = MessageFetchJob.forUnifiedPush(authorization)
    job.setContext(context)
    job.onRun()

    verify(exactly = 1) { WebSocketDrainer.blockUntilUnifiedPushFetchDrainedAndProcessed(authorization, any()) }
    verify(exactly = 0) { WebSocketDrainer.blockUntilDrainedAndProcessed() }
    verify(exactly = 1) { AppForegroundObserver.addListener(any()) }
    verify(exactly = 1) { AppForegroundObserver.removeListener(any()) }
  }

  @Test
  fun `UnifiedPush timeout transfers the same authorization to the system fallback`() {
    val lease = UnifiedPushAccountTransition.acquireLease()!!
    val authorization = UnifiedPushAccountTransition.fetchAuthorization(lease)
    lease.close()
    every { WebSocketDrainer.blockUntilUnifiedPushFetchDrainedAndProcessed(authorization, any()) } returns UnifiedPushFetchResult.RETRYABLE_FAILURE
    mockkStatic(FcmJobService::class)
    every { FcmJobService.schedule(context, authorization) } returns Unit

    assertEquals(false, FcmFetchManager.retrieveMessages(context, authorization))

    verify(exactly = 1) { FcmJobService.schedule(context, authorization) }
  }

  @Test
  fun `revoked UnifiedPush demand does not schedule a fallback`() {
    val lease = UnifiedPushAccountTransition.acquireLease()!!
    val authorization = UnifiedPushAccountTransition.fetchAuthorization(lease)
    lease.close()
    every { WebSocketDrainer.blockUntilUnifiedPushFetchDrainedAndProcessed(authorization, any()) } returns UnifiedPushFetchResult.REVOKED
    mockkStatic(FcmJobService::class)
    every { FcmJobService.schedule(context, authorization) } returns Unit

    assertTrue(FcmFetchManager.retrieveMessages(context, authorization))

    verify(exactly = 0) { FcmJobService.schedule(context, authorization) }
  }

  @Test
  fun `system fallback delegates authorized work to observer admission`() {
    val originalLease = UnifiedPushAccountTransition.acquireLease()!!
    val authorization = UnifiedPushAccountTransition.fetchAuthorization(originalLease)
    originalLease.close()

    val scheduler = mockk<JobScheduler>()
    val scheduledJob = slot<JobInfo>()
    mockkStatic(ServiceUtil::class)
    every { ServiceUtil.getJobScheduler(context) } returns scheduler
    every { scheduler.schedule(capture(scheduledJob)) } returns JobScheduler.RESULT_SUCCESS
    FcmJobService.schedule(context, authorization)

    val parameters = mockk<JobParameters>()
    every { parameters.extras } returns scheduledJob.captured.extras
    mockkStatic(KeyCachingService::class)
    every { KeyCachingService.isLocked() } returns false
    mockkStatic(AppForegroundObserver::class)
    every { AppForegroundObserver.isForegrounded() } returns false

    every { WebSocketDrainer.blockUntilUnifiedPushFetchDrainedAndProcessed(authorization, any()) } returns UnifiedPushFetchResult.DRAINED

    val service = Robolectric.buildService(FcmJobService::class.java).create().get()
    assertTrue(service.onStartJob(parameters))
    verify(timeout = 5000, exactly = 1) { WebSocketDrainer.blockUntilUnifiedPushFetchDrainedAndProcessed(authorization, any()) }
    verify(exactly = 0) { WebSocketDrainer.blockUntilDrainedAndProcessed() }
  }

  @Test
  fun `stale UnifiedPush fallback does not replace an ordinary persisted fallback`() {
    val scheduler = mockk<JobScheduler>()
    val scheduledJobs = mutableMapOf<Int, JobInfo>()
    mockkStatic(ServiceUtil::class)
    every { ServiceUtil.getJobScheduler(context) } returns scheduler
    every { scheduler.schedule(any()) } answers {
      val job = firstArg<JobInfo>()
      scheduledJobs[job.id] = job
      JobScheduler.RESULT_SUCCESS
    }

    val previousProcessAuthorization = UnifiedPushAccountTransition.FetchAuthorization(
      processInstanceId = "previous-process-instance",
      generation = 17,
      accountId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
      localDeviceId = 1,
      pushDeviceId = 4,
      instance = "fixture-instance",
    )
    FcmJobService.schedule(context)
    FcmJobService.schedule(context, previousProcessAuthorization)

    assertEquals("both independent requests must remain scheduled", setOf(1337, 1338), scheduledJobs.keys)
    val ordinaryJob = scheduledJobs.getValue(1337)
    val unifiedPushJob = scheduledJobs.getValue(1338)
    assertTrue(unifiedPushJob.extras.getBoolean("up_fetch_authorized", false))

    mockkStatic(KeyCachingService::class)
    mockkStatic(AppForegroundObserver::class)
    every { KeyCachingService.isLocked() } returns false
    every { AppForegroundObserver.isForegrounded() } returns false

    val drainCalls = java.util.concurrent.atomic.AtomicInteger()
    val ordinaryDrain = CountDownLatch(1)
    every { WebSocketDrainer.blockUntilDrainedAndProcessed() } answers {
      drainCalls.incrementAndGet()
      ordinaryDrain.countDown()
      true
    }

    val service = Robolectric.buildService(FcmJobService::class.java).create().get()
    val staleParameters = mockk<JobParameters>()
    every { staleParameters.extras } returns unifiedPushJob.extras
    assertFalse("a prior-process UnifiedPush authorization must be rejected before worker scheduling", service.onStartJob(staleParameters))
    assertEquals(0, drainCalls.get())

    val ordinaryParameters = mockk<JobParameters>()
    every { ordinaryParameters.extras } returns ordinaryJob.extras
    assertTrue("the ordinary persisted fallback must still run", service.onStartJob(ordinaryParameters))
    assertTrue(ordinaryDrain.await(5, TimeUnit.SECONDS))
    assertEquals(1, drainCalls.get())
  }

  @Test
  fun `pre system fallback job preserves authorization in its serialized form`() {
    val authorization = UnifiedPushAccountTransition.FetchAuthorization(
      processInstanceId = "synthetic-process",
      generation = 17,
      accountId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
      localDeviceId = 1,
      pushDeviceId = 4,
      instance = "fixture-instance",
    )
    val job = MessageFetchJob.forUnifiedPush(authorization)
    val serialized = checkNotNull(job.serialize())
    val restored = MessageFetchJob.Factory().create(job.parameters, serialized)

    assertArrayEquals(serialized, restored.serialize())
    assertEquals(MessageFetchJob.KEY, restored.factoryKey)
  }
}
