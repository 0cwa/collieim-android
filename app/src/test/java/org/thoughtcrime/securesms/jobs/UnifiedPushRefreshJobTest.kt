/*
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.jobs

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import im.molly.unifiedpush.MollySocketRepository
import im.molly.unifiedpush.UnifiedPushDistributor
import im.molly.unifiedpush.model.LinkStatus
import im.molly.unifiedpush.control.ControlState
import im.molly.unifiedpush.control.ControlFrame
import im.molly.unifiedpush.control.ControlUpdate
import im.molly.unifiedpush.model.MollySocketDevice
import im.molly.unifiedpush.model.RegistrationStatus
import im.molly.unifiedpush.lifecycle.UnifiedPushLifecycle
import im.molly.unifiedpush.lifecycle.UnifiedPushAccountTransition
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.models.ServiceId
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.jobmanager.Job
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.keyvalue.UnifiedPushValues
import org.thoughtcrime.securesms.linkdevice.Device
import org.thoughtcrime.securesms.linkdevice.LinkDeviceRepository
import org.thoughtcrime.securesms.testutil.MockSignalStoreRule
import org.thoughtcrime.securesms.testutil.SystemOutLogger
import org.whispersystems.signalservice.api.SignalServiceMessageSender
import org.whispersystems.signalservice.api.messages.SendMessageResult
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class UnifiedPushRefreshJobTest {

  @get:Rule
  val signalStore = MockSignalStoreRule()

  private val unifiedPush = mockk<UnifiedPushValues>(relaxUnitFun = true)
  private val device = MollySocketDevice(deviceId = 4, password = "retained-relay-credential")
  private var storedDevice: MollySocketDevice? = device
  private var storedStatus = RegistrationStatus.UNKNOWN
  private var transitionMarker = false
  private val retainedUpdate = ControlUpdate(5, "https://push.example.invalid/old", "p", "a", byteArrayOf(1), 1)
  private var controlState = ControlState(key = "retained-control-key", activeEpoch = 4, highestEpoch = 5, importSequence = 7, update = retainedUpdate)

  @Before
  fun setUp() {
    Log.initialize(SystemOutLogger())
    UnifiedPushLifecycle.resetForTests()
    mockkObject(LinkDeviceRepository)

    every { SignalStore.unifiedpush } returns unifiedPush
    every { signalStore.account.isRegistered } returns true
    val aci = mockk<ServiceId.ACI>()
    every { aci.toString() } returns "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    every { signalStore.account.aci } returns aci
    every { signalStore.account.hasAciIdentityKey() } returns true
    every { signalStore.account.servicePassword } returns "SYNTHETIC-SERVICE-PASSWORD"
    every { signalStore.account.deviceId } returns 1
    every { unifiedPush.enabled } returns false
    every { unifiedPush.airGapped } returns false
    every { unifiedPush.registrationStatus } answers { storedStatus }
    every { unifiedPush.registrationStatus = any() } answers { storedStatus = firstArg() }
    every { unifiedPush.endpoint } returns "https://push.example.invalid/endpoint"
    every { unifiedPush.endpointP256dh } returns "SYNTHETIC-P256DH"
    every { unifiedPush.endpointAuth } returns "SYNTHETIC-AUTH"
    every { unifiedPush.endpointInstance } returns "fixture-instance"
    every { unifiedPush.mollySocketUrl } returns "https://molly.example.invalid"
    every { unifiedPush.device } answers { storedDevice }
    every { unifiedPush.device = any() } answers { storedDevice = firstArg() }
    every { unifiedPush.controlState } answers { controlState }
    every { unifiedPush.hasIncompleteAccountTransition } answers { transitionMarker }
    every { unifiedPush.setIncompleteAccountTransition(any()) } answers { transitionMarker = firstArg() }
    every { unifiedPush.mutateControl(any(), any()) } answers {
      controlState = secondArg<(ControlState) -> ControlState>().invoke(controlState)
      controlState
    }

    coEvery { LinkDeviceRepository.loadDevices() } returns listOf(
      Device(id = device.deviceId, name = "MollySocket", createdMillis = null, lastSeenMillis = 0, registrationId = 1)
    )
  }

  @After
  fun tearDown() {
    unmockkObject(LinkDeviceRepository)
    unmockkObject(MollySocketRepository, UnifiedPushDistributor, AppDependencies)
    unmockkStatic(AppDependencies::class)
  }

  @Test
  fun `failed unlink keeps relay credentials and reports a retryable failure`() {
    coEvery { LinkDeviceRepository.removeDevice(device.deviceId) } returns false

    val failure = runCleanup()

    assertTrue(failure is IOException)
    assertTrue(UnifiedPushRefreshJob().onShouldRetry(failure as IOException))
    assertSame(device, storedDevice)
    verify(exactly = 0) { unifiedPush.device = any() }
    coVerify(exactly = 1) { LinkDeviceRepository.removeDevice(device.deviceId) }
  }

  @Test
  fun `successful unlink clears relay credentials`() {
    coEvery { LinkDeviceRepository.removeDevice(device.deviceId) } returns true

    assertNull(runCleanup())

    assertNull(storedDevice)
    assertEquals("retained-control-key", controlState.key)
    assertEquals(7L, controlState.importSequence)
    assertNull(controlState.update)
    verify(exactly = 1) { unifiedPush.device = null }
    coVerify(exactly = 1) { LinkDeviceRepository.removeDevice(device.deviceId) }
  }

  @Test
  fun `refresh continuation cannot acquire the generation of a competing transition`() {
    val lease = UnifiedPushAccountTransition.acquireLease()!!
    val job = UnifiedPushRefreshJob()
    val clearedGeneration = checkNotNull(job.clearDevice(device, lease))

    val sameGenerationLease = job.acquireLeaseAfterDeviceClear(clearedGeneration)
    assertNotNull("the clear transition's own generation remains usable", sameGenerationLease)
    sameGenerationLease!!.close()

    val replacement = runBlocking {
      UnifiedPushLifecycle.withTransitionAndGeneration(
        persistIncompleteMarker = { transitionMarker = it },
        readyAfter = { true },
      ) {
        storedDevice = MollySocketDevice(deviceId = 8, password = "replacement-relay-credential")
      }
    }

    assertTrue(replacement.generation != clearedGeneration)
    assertNull("a continuation from the old refresh cannot adopt a later generation", job.acquireLeaseAfterDeviceClear(clearedGeneration))
  }

  @Test
  fun `unknown link status keeps relay credentials without attempting unlink`() {
    coEvery { LinkDeviceRepository.loadDevices() } returns null

    assertTrue(runCleanup() is IOException)

    assertSame(device, storedDevice)
    verify(exactly = 0) { unifiedPush.device = any() }
    coVerify(exactly = 0) { LinkDeviceRepository.removeDevice(any()) }
  }

  @Test
  fun `device created for old account is removed when a transition starts before publication`() {
    val created = MollySocketDevice(deviceId = 5, password = "new-relay-credential")
    storedDevice = null
    every { unifiedPush.enabled } returns true
    every { unifiedPush.airGapped } returns true
    every { unifiedPush.endpoint } returns "https://push.example.invalid/endpoint"
    every { unifiedPush.vapidPublicKey } returns "SYNTHETIC-VAPID"
    every { unifiedPush.lastReceivedTime } returns 1L

    mockkObject(MollySocketRepository, UnifiedPushDistributor, AppDependencies)
    mockkStatic(AppDependencies::class)
    every { UnifiedPushDistributor.checkIfActive() } returns true
    every { AppDependencies.resetNetwork(any()) } returns Unit

    val createEntered = CountDownLatch(1)
    val finishCreate = CountDownLatch(1)
    every { MollySocketRepository.createDevice() } answers {
      createEntered.countDown()
      check(finishCreate.await(5, TimeUnit.SECONDS))
      created
    }
    every { MollySocketRepository.removeDevice(created) } returns Unit

    val transitionMarker = CountDownLatch(1)
    val transitionBody = CountDownLatch(1)
    val transitionError = AtomicReference<Throwable?>()
    val jobExecutor = Executors.newSingleThreadExecutor()
    val transitionExecutor = Executors.newSingleThreadExecutor()
    try {
      val job = jobExecutor.submit<Throwable?> {
        val refresh = UnifiedPushRefreshJob(testPing = false, fromNewEndpoint = true)
          .apply { setContext(ApplicationProvider.getApplicationContext()) }
        runCatching { refresh.onRun() }.exceptionOrNull()
      }

      assertTrue(createEntered.await(5, TimeUnit.SECONDS))
      val transition = transitionExecutor.submit {
        try {
          runBlocking {
            UnifiedPushLifecycle.withTransition(
              persistIncompleteMarker = { pending -> if (pending) transitionMarker.countDown() },
              readyAfter = { true },
            ) { transitionBody.countDown() }
          }
        } catch (failure: Throwable) {
          transitionError.set(failure)
        }
      }

      assertTrue(transitionMarker.await(5, TimeUnit.SECONDS))
      assertTrue("transition must wait for the leased create", transitionBody.count == 1L)
      finishCreate.countDown()

      assertNull(job.get(5, TimeUnit.SECONDS))
      transition.get(5, TimeUnit.SECONDS)
      assertNull(transitionError.get())
      assertNull("obsolete device must not be published", storedDevice)
      assertEquals("control key and sequence survive a stale create", "retained-control-key", controlState.key)
      assertEquals(7L, controlState.importSequence)
      verify(exactly = 0) { unifiedPush.device = created }
      verify(exactly = 1) { MollySocketRepository.removeDevice(created) }
      assertTrue(transitionBody.count == 0L)
    } finally {
      finishCreate.countDown()
      jobExecutor.shutdownNow()
      transitionExecutor.shutdownNow()
    }
  }

  @Test
  fun `refresh recreates a validated device instead of registering the removed lease snapshot`() {
    val created = MollySocketDevice(deviceId = 6, password = "new-relay-credential")
    storedDevice = device
    every { unifiedPush.enabled } returns true
    every { unifiedPush.airGapped } returns true
    every { unifiedPush.vapidPublicKey } returns "SYNTHETIC-VAPID"
    every { unifiedPush.vapidKeySynced } returns true
    every { unifiedPush.lastReceivedTime } returns 0L

    mockkObject(MollySocketRepository, UnifiedPushDistributor, AppDependencies)
    mockkStatic(AppDependencies::class)
    every { UnifiedPushDistributor.checkIfActive() } returns true
    every { AppDependencies.resetNetwork(any()) } returns Unit
    every { MollySocketRepository.getDeviceStatus(device) } returns LinkStatus.NOT_LINKED
    every { MollySocketRepository.createDevice() } returns created

    val job = UnifiedPushRefreshJob(testPing = false, fromNewEndpoint = true)
      .apply { setContext(ApplicationProvider.getApplicationContext()) }
    job.onRun()

    assertEquals(created, storedDevice)
    assertEquals(RegistrationStatus.PENDING, unifiedPush.registrationStatus)
    verify(exactly = 1) { MollySocketRepository.createDevice() }
    verify(exactly = 0) { MollySocketRepository.registerDeviceOnServer(any(), any(), any(), any()) }
  }

  @Test
  fun `route removal waits for a selected control send before clearing device and update`() {
    val accountId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    val aci = mockk<ServiceId.ACI>()
    every { aci.toString() } returns accountId
    every { SignalStore.account.isRegistered } returns true
    every { SignalStore.account.aci } returns aci
    every { SignalStore.account.hasAciIdentityKey() } returns true
    every { SignalStore.account.servicePassword } returns "SYNTHETIC-SERVICE-PASSWORD"
    every { SignalStore.account.deviceId } returns 1
    every { unifiedPush.device } answers { storedDevice }
    every { unifiedPush.endpoint } returns "https://push.example.invalid/endpoint"
    every { unifiedPush.endpointP256dh } returns "SYNTHETIC-P256DH"
    every { unifiedPush.endpointAuth } returns "SYNTHETIC-AUTH"
    every { unifiedPush.endpointInstance } returns "fixture-instance"

    val frame = ControlFrame.header(accountId, device.deviceId, 5) + ByteArray(20)
    val update = ControlUpdate(5, "https://push.example.invalid/new", "p", "a", frame, 1)
    controlState = ControlState(key = "retained-control-key", activeEpoch = 4, highestEpoch = 5, importSequence = 7, update = update)
    every { unifiedPush.controlState } answers { controlState }
    every { unifiedPush.mutateControl(any(), any()) } answers {
      controlState = secondArg<(ControlState) -> ControlState>().invoke(controlState)
      controlState
    }

    mockkObject(AppDependencies, MollySocketRepository)
    mockkStatic(AppDependencies::class)
    val sender = mockk<SignalServiceMessageSender>()
    val sendResult = mockk<SendMessageResult>()
    every { AppDependencies.signalServiceMessageSender } returns sender
    val sendEntered = CountDownLatch(1)
    val finishSend = CountDownLatch(1)
    every { sender.sendControlMessage(accountId, 1, device.deviceId, frame) } answers {
      sendEntered.countDown()
      check(finishSend.await(5, TimeUnit.SECONDS))
      sendResult
    }
    coEvery { LinkDeviceRepository.removeDevice(device.deviceId) } returns true
    every { MollySocketRepository.getDeviceStatus(device) } returns LinkStatus.LINKED
    every { unifiedPush.enabled } returns true
    val removeEntered = CountDownLatch(1)
    val markerSet = CountDownLatch(1)
    every { unifiedPush.setIncompleteAccountTransition(any()) } answers {
      transitionMarker = firstArg()
      if (transitionMarker) markerSet.countDown()
    }
    every { MollySocketRepository.removeDevice(device) } answers {
      removeEntered.countDown()
      // The refresh must reach local transition only after the remote removal succeeds.
      check(sendEntered.await(5, TimeUnit.SECONDS))
      Unit
    }

    val sendExecutor = Executors.newSingleThreadExecutor()
    val refreshExecutor = Executors.newSingleThreadExecutor()
    try {
      val send = sendExecutor.submit<Throwable?> { runCatching { newControlJob().onRun() }.exceptionOrNull() }
      assertTrue(sendEntered.await(5, TimeUnit.SECONDS))
      every { unifiedPush.enabled } returns false
      val refresh = refreshExecutor.submit<Throwable?> {
        val job = UnifiedPushRefreshJob().apply { setContext(ApplicationProvider.getApplicationContext()) }
        runCatching { job.onRun() }.exceptionOrNull()
      }

      assertTrue(removeEntered.await(5, TimeUnit.SECONDS))
      assertTrue("local mutation must enter the lifecycle transition", markerSet.await(5, TimeUnit.SECONDS))
      // Remote removal has succeeded, but local authority remains intact until the send lease drains.
      assertEquals(device, storedDevice)
      assertEquals(update, controlState.update)
      finishSend.countDown()
      send.get(5, TimeUnit.SECONDS)
      assertNull(refresh.get(5, TimeUnit.SECONDS))

      assertNull(storedDevice)
      assertNull(controlState.update)
      assertEquals(7L, controlState.importSequence)
    } finally {
      finishSend.countDown()
      sendExecutor.shutdownNow()
      refreshExecutor.shutdownNow()
    }
  }

  private fun runCleanup(): Throwable? {
    val executor = Executors.newSingleThreadExecutor()
    return try {
      executor.submit<Throwable?> {
        val job = UnifiedPushRefreshJob().apply { setContext(ApplicationProvider.getApplicationContext()) }
        runCatching { job.onRun() }.exceptionOrNull()
      }.get(5, TimeUnit.SECONDS)
    } finally {
      executor.shutdownNow()
    }
  }

  private fun newControlJob() = UnifiedPushControlJob.Factory().create(Job.Parameters.Builder().build(), null)
}
