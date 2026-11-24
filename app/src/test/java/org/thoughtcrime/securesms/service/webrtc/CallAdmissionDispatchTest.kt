package org.thoughtcrime.securesms.service.webrtc

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import org.robolectric.util.ReflectionHelpers
import org.signal.core.models.ServiceId.ACI
import org.signal.core.models.recipients.RecipientId
import org.signal.core.util.logging.Log
import org.signal.ringrtc.CallId
import org.signal.ringrtc.CallManager
import org.thoughtcrime.securesms.calls.permissions.CallAuthorization
import org.thoughtcrime.securesms.calls.permissions.CallPermission
import org.thoughtcrime.securesms.calls.permissions.CallPermissionTestEnvironment
import org.thoughtcrime.securesms.calls.permissions.CallPermissions
import org.thoughtcrime.securesms.calls.permissions.CallSessionAuthorizer
import org.thoughtcrime.securesms.calls.permissions.DirectMessageKind
import org.thoughtcrime.securesms.ringrtc.RemotePeer
import org.thoughtcrime.securesms.service.webrtc.state.WebRtcServiceState
import org.thoughtcrime.securesms.testutil.SystemOutLogger
import org.whispersystems.signalservice.api.messages.calls.HangupMessage
import org.whispersystems.signalservice.api.messages.calls.OfferMessage
import java.time.Duration
import java.util.UUID
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit

/** Tests the actual serialized native-dispatch boundary with the real managed session authorizer. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, instrumentedPackages = ["org.signal.ringrtc"])
class CallAdmissionDispatchTest {
  private val policy = CallPermissionTestEnvironment()
  private lateinit var queue: ControlledExecutor
  private lateinit var manager: SignalCallManager
  private val native: CallManager = mockk(relaxed = true)
  private val peer = RemotePeer(RecipientId.from(7), CallId(41))
  private val metadata = WebRtcData.CallMetadata(peer, 2)

  @Before
  fun setUp() {
    Log.initialize(SystemOutLogger())
    policy.setUp()
    queue = ControlledExecutor()
    manager = SignalCallManager(ApplicationProvider.getApplicationContext(), native, queue)
    ReflectionHelpers.setField(manager, "needsToSetSelfUuid", false)
  }

  @After
  fun tearDown() {
    policy.tearDown()
    queue.shutdownNow()
  }

  @Test
  fun `revocation drops every queued message category before native dispatch`() {
    val ticket = authorizedOutgoingIce()
    enqueueEveryCategory(ticket)
    CallPermissions.setAllCallingDisabled(true)
    queue.drain()

    verifyNoRemoteDispatch()
  }

  @Test
  fun `expiry drops every queued message category before native dispatch`() {
    val ticket = authorizedOutgoingIce()
    enqueueEveryCategory(ticket)
    ShadowSystemClock.advanceBy(Duration.ofMillis(CallSessionAuthorizer.SIGNALING_LEASE_MS + 1))
    queue.drain()

    verifyNoRemoteDispatch()
  }

  @Test
  fun `an unchanged authorized call dispatches ICE normally`() {
    val ticket = authorizedOutgoingIce()
    val candidates = listOf(byteArrayOf(1, 2, 3))
    manager.receivedIceCandidates(ticket, metadata, candidates)
    queue.drain()

    verify(exactly = 1) { native.receivedIceCandidates(peer.callId, peer, 2, candidates) }
  }

  @Test
  fun `revocation after queue admission is checked again immediately before JNI`() {
    val ticket = authorizedOutgoingIce()
    val interactor: WebRtcInteractor = mockk(relaxed = true)
    every { interactor.callManager } returns native
    val processor = object : WebRtcActionProcessor(interactor, "AdmissionRaceTest") {
      override fun handleReceivedIceCandidates(currentState: WebRtcServiceState, callMetadata: WebRtcData.CallMetadata, iceCandidates: List<ByteArray>): WebRtcServiceState {
        CallPermissions.setAllCallingDisabled(true)
        return super.handleReceivedIceCandidates(currentState, callMetadata, iceCandidates)
      }
    }
    ReflectionHelpers.setField(manager, "serviceState", WebRtcServiceState(processor))

    manager.receivedIceCandidates(ticket, metadata, listOf(byteArrayOf(1)))
    queue.drain()

    verifyNoRemoteDispatch()
  }

  private fun authorizedOutgoingIce(): CallAuthorization {
    CallPermissions.setOverride(policy.recipient(7), CallPermission.BLOCK)
    queue.drain()
    check(CallPermissions.beginOutgoing(peer.id))
    check(CallPermissions.bindOutgoing(peer.id, 41))
    return requireNotNull(CallPermissions.admitDirect(peer.id, 41, 2, DirectMessageKind.ICE, 0))
  }

  private fun verifyNoRemoteDispatch() {
    verify(exactly = 0) { native.receivedOffer(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    verify(exactly = 0) { native.receivedAnswer(any(), any(), any(), any(), any(), any()) }
    verify(exactly = 0) { native.receivedIceCandidates(any(), any(), any(), any()) }
    verify(exactly = 0) { native.receivedHangup(any(), any(), any(), any(), any()) }
    verify(exactly = 0) { native.receivedBusy(any(), any(), any()) }
    verify(exactly = 0) { native.receivedCallMessage(any(), any(), any(), any(), any()) }
  }

  private fun enqueueEveryCategory(ticket: CallAuthorization) {
    manager.receivedOffer(ticket, metadata, WebRtcData.OfferMetadata(byteArrayOf(1), OfferMessage.Type.AUDIO_CALL), WebRtcData.ReceivedOfferMetadata(ACI.from(UUID.randomUUID()), byteArrayOf(1), 1, 1))
    manager.receivedAnswer(ticket, metadata, WebRtcData.AnswerMetadata(byteArrayOf(1)), WebRtcData.ReceivedAnswerMetadata(byteArrayOf(1)))
    manager.receivedIceCandidates(ticket, metadata, listOf(byteArrayOf(1)))
    manager.receivedCallHangup(ticket, metadata, WebRtcData.HangupMetadata(HangupMessage.Type.NORMAL, 2))
    manager.receivedCallBusy(ticket, metadata)
    manager.receivedOpaqueMessage(ticket, WebRtcData.OpaqueMessageMetadata(UUID.randomUUID(), byteArrayOf(1), 2, 0))
  }

  private class ControlledExecutor : AbstractExecutorService() {
    private val pending = ArrayDeque<Runnable>()
    private var closed = false
    override fun execute(command: Runnable) { pending.addLast(command) }
    fun drain() { while (pending.isNotEmpty()) pending.removeFirst().run() }
    override fun shutdown() { closed = true }
    override fun shutdownNow(): MutableList<Runnable> = pending.toMutableList().also { pending.clear(); closed = true }
    override fun isShutdown() = closed
    override fun isTerminated() = closed && pending.isEmpty()
    override fun awaitTermination(timeout: Long, unit: TimeUnit) = isTerminated
  }
}
