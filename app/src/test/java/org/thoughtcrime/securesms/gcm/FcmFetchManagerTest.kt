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
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
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
import org.thoughtcrime.securesms.push.SignalServiceNetworkAccess
import org.thoughtcrime.securesms.service.KeyCachingService
import org.thoughtcrime.securesms.testutil.MockSignalStoreRule
import org.thoughtcrime.securesms.testutil.SystemOutLogger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
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
    every { values.device } returns im.molly.unifiedpush.model.MollySocketDevice(4, "SYNTHETIC-RELAY-PASSWORD")
    every { values.endpoint } returns "https://push.example.invalid/endpoint"
    every { values.endpointP256dh } returns "SYNTHETIC-P256DH"
    every { values.endpointAuth } returns "SYNTHETIC-AUTH"
    every { values.endpointInstance } returns "fixture-instance"
  }

  @After
  fun tearDown() {
    unmockkObject(AppDependencies, WebSocketDrainer)
    unmockkStatic(AppForegroundObserver::class)
    unmockkStatic(KeyCachingService::class)
    unmockkStatic(ServiceUtil::class)
    unmockkStatic(FcmJobService::class)
  }

  @Test
  fun `queued UnifiedPush fetch holds its lease through the websocket drain`() {
    val originalLease = UnifiedPushAccountTransition.acquireLease()!!
    val authorization = UnifiedPushAccountTransition.fetchAuthorization(originalLease)
    originalLease.close()

    val drainEntered = CountDownLatch(1)
    val finishDrain = CountDownLatch(1)
    every { WebSocketDrainer.blockUntilDrainedAndProcessed(any(), any()) } answers {
      drainEntered.countDown()
      check(finishDrain.await(5, TimeUnit.SECONDS))
      true
    }

    val executor = Executors.newFixedThreadPool(2)
    try {
      val fetch = executor.submit<Boolean> { FcmFetchManager.retrieveMessages(context, authorization) }
      assertTrue(drainEntered.await(5, TimeUnit.SECONDS))

      val markerPersisted = CountDownLatch(1)
      val transitionBody = CountDownLatch(1)
      val transition = executor.submit {
        runBlocking {
          UnifiedPushLifecycle.withTransition(
            persistIncompleteMarker = { if (it) markerPersisted.countDown() },
            readyAfter = { true },
          ) { transitionBody.countDown() }
        }
      }

      assertTrue(markerPersisted.await(5, TimeUnit.SECONDS))
      assertEquals("account mutation must wait for the actual websocket drain", 1L, transitionBody.count)
      finishDrain.countDown()
      assertTrue(fetch.get(5, TimeUnit.SECONDS))
      transition.get(5, TimeUnit.SECONDS)
      assertEquals(0L, transitionBody.count)
    } finally {
      finishDrain.countDown()
      executor.shutdownNow()
    }
  }

  @Test
  fun `UnifiedPush timeout transfers the same authorization to the system fallback`() {
    val lease = UnifiedPushAccountTransition.acquireLease()!!
    val authorization = UnifiedPushAccountTransition.fetchAuthorization(lease)
    lease.close()
    every { WebSocketDrainer.blockUntilDrainedAndProcessed(any(), any()) } returns false
    mockkStatic(FcmJobService::class)
    every { FcmJobService.schedule(context, authorization) } returns Unit

    assertEquals(false, FcmFetchManager.retrieveMessages(context, authorization))

    verify(exactly = 1) { FcmJobService.schedule(context, authorization) }
  }

  @Test
  fun `system fallback holds its reacquired lease through the actual drain`() {
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

    val drainEntered = CountDownLatch(1)
    val finishDrain = CountDownLatch(1)
    every { WebSocketDrainer.blockUntilDrainedAndProcessed() } answers {
      drainEntered.countDown()
      check(finishDrain.await(5, TimeUnit.SECONDS))
      true
    }

    val service = Robolectric.buildService(FcmJobService::class.java).create().get()
    assertTrue(service.onStartJob(parameters))
    assertTrue(drainEntered.await(5, TimeUnit.SECONDS))

    val executor = Executors.newSingleThreadExecutor()
    val markerPersisted = CountDownLatch(1)
    val transitionBody = CountDownLatch(1)
    try {
      val transition = executor.submit {
        runBlocking {
          UnifiedPushLifecycle.withTransition(
            persistIncompleteMarker = { if (it) markerPersisted.countDown() },
            readyAfter = { true },
          ) { transitionBody.countDown() }
        }
      }
      assertTrue(markerPersisted.await(5, TimeUnit.SECONDS))
      assertEquals("account mutation must wait for the actual system fallback drain", 1L, transitionBody.count)
      finishDrain.countDown()
      transition.get(5, TimeUnit.SECONDS)
      assertEquals(0L, transitionBody.count)
    } finally {
      finishDrain.countDown()
      executor.shutdownNow()
    }
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
