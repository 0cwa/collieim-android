/*
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.jobs

import im.molly.unifiedpush.control.ControlFrame
import im.molly.unifiedpush.control.ControlState
import im.molly.unifiedpush.control.ControlUpdate
import im.molly.unifiedpush.lifecycle.UnifiedPushLifecycle
import im.molly.unifiedpush.model.MollySocketDevice
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.signal.core.models.ServiceId
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.jobmanager.Job
import org.thoughtcrime.securesms.jobmanager.JobManager
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.keyvalue.UnifiedPushValues
import org.thoughtcrime.securesms.testutil.MockSignalStoreRule
import org.thoughtcrime.securesms.testutil.SystemOutLogger
import org.whispersystems.signalservice.api.SignalServiceMessageSender
import org.whispersystems.signalservice.api.messages.SendMessageResult
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class UnifiedPushControlJobTest {

  @get:Rule
  val signalStore = MockSignalStoreRule()

  private val unifiedPush = mockk<UnifiedPushValues>()
  private val jobManager = mockk<JobManager>(relaxed = true)

  @Before
  fun setUp() {
    Log.initialize(SystemOutLogger())
    UnifiedPushLifecycle.resetForTests()
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

  @Test
  fun `control send keeps its captured route until completion and fences an account ABA attempt`() {
    val accountId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    val accountNow = AtomicReference(accountId)
    val aci = mockk<ServiceId.ACI>()
    every { aci.toString() } answers { accountNow.get() }
    every { SignalStore.account.isRegistered } returns true
    every { SignalStore.account.aci } returns aci
    every { SignalStore.account.hasAciIdentityKey() } returns true
    every { SignalStore.account.servicePassword } returns "SYNTHETIC-SERVICE-PASSWORD"
    every { SignalStore.account.deviceId } returns 1

    val device = MollySocketDevice(deviceId = 4, password = "SYNTHETIC-REMOTE-DEVICE-PASSWORD")
    every { unifiedPush.device } returns device
    every { unifiedPush.endpoint } returns "https://push.example.invalid/endpoint"
    every { unifiedPush.endpointP256dh } returns "SYNTHETIC-P256DH"
    every { unifiedPush.endpointAuth } returns "SYNTHETIC-AUTH"
    every { unifiedPush.endpointInstance } returns "fixture-instance"

    val frame = ControlFrame.header(accountId, device.deviceId, 6) + ByteArray(20)
    val update = ControlUpdate(6, "https://push.example.invalid/new", "p", "a", frame, 1)
    var state = ControlState(key = "SYNTHETIC-CONTROL-KEY", activeEpoch = 5, highestEpoch = 6, update = update)
    every { unifiedPush.controlState } answers { state }
    every { unifiedPush.mutateControl(any(), any()) } answers {
      state = secondArg<(ControlState) -> ControlState>().invoke(state)
      state
    }

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

    val transitionMarker = CountDownLatch(1)
    val transitionBody = CountDownLatch(1)
    val transitionError = AtomicReference<Throwable?>()
    val jobExecutor = Executors.newSingleThreadExecutor()
    val transitionExecutor = Executors.newSingleThreadExecutor()
    try {
      val job = jobExecutor.submit<Throwable?> { runCatching { newJob().onRun() }.exceptionOrNull() }
      assertTrue(sendEntered.await(5, TimeUnit.SECONDS))

      val transition = transitionExecutor.submit {
        try {
          runBlocking {
            UnifiedPushLifecycle.withTransition(
              persistIncompleteMarker = { pending -> if (pending) transitionMarker.countDown() },
              readyAfter = { true },
            ) {
              accountNow.set("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")
              accountNow.set(accountId)
              transitionBody.countDown()
            }
          }
        } catch (failure: Throwable) {
          transitionError.set(failure)
        }
      }

      assertTrue(transitionMarker.await(5, TimeUnit.SECONDS))
      assertEquals("in-flight sender lease must delay identity publication", 1L, transitionBody.count)
      finishSend.countDown()

      assertTrue(job.get(5, TimeUnit.SECONDS) is IOException)
      transition.get(5, TimeUnit.SECONDS)
      assertNull(transitionError.get())
      assertEquals("same-ACI ABA does not restore the old execution generation", accountId, accountNow.get())
      assertEquals(0, state.update!!.attempts)
      verify(exactly = 1) { sender.sendControlMessage(accountId, 1, device.deviceId, frame) }
      assertEquals(0L, transitionBody.count)
    } finally {
      finishSend.countDown()
      jobExecutor.shutdownNow()
      transitionExecutor.shutdownNow()
    }
  }

  private fun newJob() = UnifiedPushControlJob.Factory().create(Job.Parameters.Builder().build(), null)
}
