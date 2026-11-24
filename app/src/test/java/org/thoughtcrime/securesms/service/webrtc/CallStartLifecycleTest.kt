/*
 * Copyright 2026 Collie contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package org.thoughtcrime.securesms.service.webrtc

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import org.junit.After
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
import org.robolectric.util.ReflectionHelpers
import org.signal.core.util.eventbus.EventBus
import org.signal.core.util.eventbus.ThreadMode
import org.signal.core.util.logging.Log
import org.signal.ringrtc.CallManager
import org.thoughtcrime.securesms.calls.permissions.CallPermissionTestEnvironment
import org.thoughtcrime.securesms.calls.permissions.CallPermissions
import org.thoughtcrime.securesms.calls.runtime.CallPermissionRuntime
import org.thoughtcrime.securesms.calls.runtime.CallRuntime
import org.thoughtcrime.securesms.calls.runtime.SerializedCallRuntime
import org.thoughtcrime.securesms.events.CallStartEvent
import org.thoughtcrime.securesms.events.WebRtcViewModel
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.ringrtc.RemotePeer
import org.thoughtcrime.securesms.service.webrtc.state.WebRtcServiceState
import org.thoughtcrime.securesms.testutil.EmptyLogger
import org.whispersystems.signalservice.api.messages.calls.OfferMessage
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit

/** Exercises the actual manager's local-start/UI boundary with inert native and video work. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [35], instrumentedPackages = ["org.signal.ringrtc"])
class CallStartLifecycleTest {
  private val policy = CallPermissionTestEnvironment()
  private lateinit var manager: SignalCallManager
  private lateinit var queue: ControlledExecutor
  private lateinit var subscription: EventBus.Subscription
  private val events = mutableListOf<CallStartEvent>()
  private var initializations = 0
  private var starts = 0
  private var stops = 0
  private var initialize: () -> Unit = {}

  @Before
  fun setUp() {
    Log.initialize(EmptyLogger())
    policy.setUp()
    val self = policy.recipient(1)
    every { Recipient.self() } returns self
    val values = SignalStore.callPermissions
    every { values.isOnDemandCallingEnabled() } returns true
    queue = ControlledExecutor()
    manager = SignalCallManager(ApplicationProvider.getApplicationContext(), mockk<CallManager>(relaxed = true), queue)
    ReflectionHelpers.setField(manager, "needsToSetSelfUuid", false)
    ReflectionHelpers.setField(manager, "callRuntime", CallPermissionRuntime(SerializedCallRuntime(queue, { initializations++; initialize() }, {})))

    val processor = object : WebRtcActionProcessor(mockk(relaxed = true), "CallStartLifecycleTest") {
      override fun handlePreJoinCall(currentState: WebRtcServiceState, remotePeer: RemotePeer): WebRtcServiceState {
        starts++
        return currentState.builder().changeCallInfoState().callRecipient(policy.recipient(remotePeer.id.toLong())).callState(WebRtcViewModel.State.CALL_PRE_JOIN).build()
      }

      override fun handleOutgoingCall(currentState: WebRtcServiceState, remotePeer: RemotePeer, offerType: OfferMessage.Type): WebRtcServiceState {
        starts++
        return currentState.builder().changeCallInfoState().callRecipient(policy.recipient(remotePeer.id.toLong())).callState(WebRtcViewModel.State.CALL_OUTGOING).build()
      }

      override fun handleCancelPreJoinCall(currentState: WebRtcServiceState): WebRtcServiceState {
        stops++
        return currentState.builder().changeCallInfoState().callState(WebRtcViewModel.State.IDLE).build()
      }

      override fun handleLocalHangup(currentState: WebRtcServiceState): WebRtcServiceState = handleCancelPreJoinCall(currentState)
    }
    ReflectionHelpers.setField(manager, "serviceState", WebRtcServiceState(processor))
    EventBus.default.removeStickyEvent<CallStartEvent>()
    EventBus.default.removeStickyEvent<WebRtcViewModel>()
    subscription = EventBus.default.subscribe<CallStartEvent>(ThreadMode.POSTING) { events += it }
  }

  @After
  fun tearDown() {
    subscription.unsubscribe()
    EventBus.default.removeStickyEvent<CallStartEvent>()
    EventBus.default.removeStickyEvent<WebRtcViewModel>()
    queue.shutdownNow()
    policy.tearDown()
  }

  @Test
  fun pendingStartIsVisibleBeforeInitializationAndClearsAfterDispatch() {
    manager.startPreJoinCall(policy.recipient(7))
    val pending = requireNotNull(manager.pendingLocalStart)

    assertSame(pending, events.single())
    assertNull(EventBus.default.getStickyEvent<CallStartEvent>())
    assertTrue(pending.isPreJoin)
    assertEquals(0, initializations)
    assertEquals(0, starts)

    queue.drain()

    assertEquals(1, initializations)
    assertEquals(1, starts)
    assertNull(manager.pendingLocalStart)
    assertNull(EventBus.default.getStickyEvent<CallStartEvent>())
    assertEquals(CallRuntime.Result.DISPATCHED, events.last().result)
    assertEquals(WebRtcViewModel.State.CALL_PRE_JOIN, EventBus.default.getStickyEvent<WebRtcViewModel>()?.state)
  }

  @Test
  fun backBeforeInitializationCancelsExactAttemptWithoutStartingEngine() {
    manager.startPreJoinCall(policy.recipient(7))
    val pending = requireNotNull(manager.pendingLocalStart)

    assertTrue(manager.cancelPendingLocalStart(pending.attemptId))
    assertFalse(manager.cancelPendingLocalStart(pending.attemptId))
    assertNull(manager.pendingLocalStart)
    assertEquals(CallRuntime.Result.CANCELLED, events.last().result)
    assertEquals(pending.attemptId, manager.latestLocalStart?.attemptId)
    assertEquals(CallRuntime.Result.CANCELLED, manager.latestLocalStart?.result)
    queue.drain()

    assertEquals(0, initializations)
    assertEquals(0, starts)
    assertNull(EventBus.default.getStickyEvent<WebRtcViewModel>())
  }

  @Test
  fun cancellationDuringInitializationPreventsCallDispatch() {
    initialize = { manager.cancelPendingLocalStart(requireNotNull(manager.pendingLocalStart).attemptId) }
    manager.startOutgoingAudioCall(policy.recipient(7))
    queue.drain()

    assertEquals(1, initializations)
    assertEquals(0, starts)
    assertNull(manager.pendingLocalStart)
    assertEquals(CallRuntime.Result.CANCELLED, events.last().result)
    assertNull(EventBus.default.getStickyEvent<WebRtcViewModel>())
  }

  @Test
  fun anOldScreenAndOldCompletionCannotCancelOrClearNewStart() {
    manager.startPreJoinCall(policy.recipient(7))
    val first = requireNotNull(manager.pendingLocalStart)
    manager.startPreJoinCall(policy.recipient(8))
    val second = requireNotNull(manager.pendingLocalStart)

    assertFalse(manager.cancelPendingLocalStart(first.attemptId))
    queue.runNext()
    assertSame(second, manager.pendingLocalStart)
    assertSame(second, events.last())

    queue.drain()
    assertEquals(1, initializations)
    assertEquals(1, starts)
    assertEquals(second.attemptId, events.last().attemptId)
    assertEquals(CallRuntime.Result.DISPATCHED, events.last().result)
  }

  @Test
  fun backRacingCompletedInitializationTearsDownOnlySameAttempt() {
    manager.startOutgoingAudioCall(policy.recipient(7))
    val pending = requireNotNull(manager.pendingLocalStart)
    queue.drain()
    assertNull(manager.pendingLocalStart)

    // The main thread can still show Starting while its native-state event is waiting for it.
    assertTrue(manager.cancelPendingLocalStart(pending.attemptId))
    queue.drain()

    assertEquals(1, starts)
    assertEquals(1, stops)
    assertFalse(CallPermissions.canContinueCall(policy.recipient(7).id))
  }

  @Test
  fun oldTeardownCannotCancelReplacementAfterItDispatches() {
    manager.startPreJoinCall(policy.recipient(7))
    val first = requireNotNull(manager.pendingLocalStart)
    queue.drain()

    assertTrue(manager.cancelPendingLocalStart(first.attemptId))
    manager.startPreJoinCall(policy.recipient(8))
    val second = requireNotNull(manager.pendingLocalStart)
    queue.drain()

    // The old cleanup still tears down its own resources before the queued replacement starts.
    assertEquals(1, stops)
    assertEquals(2, starts)
    assertNull(manager.pendingLocalStart)
    assertEquals(second.attemptId, manager.latestLocalStart?.attemptId)
    assertEquals("The replacement outcome survives dispatch and pending cleanup", CallRuntime.Result.DISPATCHED, manager.latestLocalStart?.result)
    assertTrue(CallPermissions.canContinueCall(policy.recipient(8).id))

    // onStop from the old Activity can arrive after the replacement is no longer "pending".
    manager.cancelPreJoinIfCurrent(first.attemptId)
    queue.drain()
    assertEquals(1, stops)
    assertEquals(second.attemptId, manager.latestLocalStart?.attemptId)
    assertEquals(CallRuntime.Result.DISPATCHED, manager.latestLocalStart?.result)
    assertTrue(CallPermissions.canContinueCall(policy.recipient(8).id))
  }

  @Test
  fun cancellationSubscriberCanQueueReplacementBeforeOldCleanup() {
    manager.startPreJoinCall(policy.recipient(7))
    val first = requireNotNull(manager.pendingLocalStart)
    val replacement = EventBus.default.subscribe<CallStartEvent>(ThreadMode.POSTING) { event ->
      if (event.attemptId == first.attemptId && event.result == CallRuntime.Result.CANCELLED) {
        manager.startPreJoinCall(policy.recipient(8))
      }
    }
    try {
      assertTrue(manager.cancelPendingLocalStart(first.attemptId))
      queue.drain()

      assertEquals(1, initializations)
      assertEquals(1, starts)
      assertEquals(0, stops)
      assertTrue(CallPermissions.canContinueCall(policy.recipient(8).id))
      assertEquals(policy.recipient(8).id, EventBus.default.getStickyEvent<WebRtcViewModel>()?.recipient?.id)
    } finally {
      replacement.unsubscribe()
    }
  }

  @Test
  fun cancellingQueuedJoinReleasesItsExistingLobbyAndGrant() {
    val recipient = policy.recipient(7)
    manager.startPreJoinCall(recipient)
    queue.drain()
    assertTrue(CallPermissions.canContinueCall(recipient.id))

    manager.startOutgoingAudioCall(recipient)
    val join = requireNotNull(manager.pendingLocalStart)
    assertTrue(manager.cancelPendingLocalStart(join.attemptId))
    queue.drain()

    assertEquals(1, initializations)
    assertEquals("Only the lobby started; the queued outgoing call was cancelled", 1, starts)
    assertEquals(1, stops)
    assertFalse(CallPermissions.canContinueCall(recipient.id))
    assertNull(manager.pendingLocalStart)
  }

  @Test
  fun initializationFailurePublishesManagedErrorAndClearsProgress() {
    initialize = { throw IllegalStateException("Synthetic startup failure") }
    manager.startOutgoingAudioCall(policy.recipient(7))
    queue.drain()

    assertEquals(1, initializations)
    assertEquals(0, starts)
    assertEquals(CallRuntime.State.FAILED, manager.callRuntime.state)
    assertNull(manager.pendingLocalStart)
    assertEquals(CallRuntime.Result.FAILED, events.last().result)
    assertEquals(CallRuntime.Result.FAILED, manager.latestLocalStart?.result)
    assertEquals(WebRtcViewModel.State.NETWORK_FAILURE, EventBus.default.getStickyEvent<WebRtcViewModel>()?.state)
  }

  @Test
  fun revocationCompletesProgressBeforeInitializationQueueDrains() {
    manager.startPreJoinCall(policy.recipient(7))
    CallPermissions.setAllCallingDisabled(true)

    assertNull(manager.pendingLocalStart)
    assertEquals(CallRuntime.Result.DENIED, events.last().result)
    assertEquals(CallRuntime.Result.DENIED, manager.latestLocalStart?.result)
    queue.drain()

    assertEquals(0, initializations)
    assertEquals(0, starts)
    assertNull(EventBus.default.getStickyEvent<WebRtcViewModel>())
  }

  private class ControlledExecutor : AbstractExecutorService() {
    private val pending = ArrayDeque<Runnable>()
    private var closed = false
    override fun execute(command: Runnable) { pending.addLast(command) }
    fun runNext() { pending.removeFirst().run() }
    fun drain() { while (pending.isNotEmpty()) runNext() }
    override fun shutdown() { closed = true }
    override fun shutdownNow(): MutableList<Runnable> = pending.toMutableList().also { pending.clear(); closed = true }
    override fun isShutdown() = closed
    override fun isTerminated() = closed && pending.isEmpty()
    override fun awaitTermination(timeout: Long, unit: TimeUnit) = isTerminated
  }
}
