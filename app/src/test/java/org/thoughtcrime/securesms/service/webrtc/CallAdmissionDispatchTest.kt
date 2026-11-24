package org.thoughtcrime.securesms.service.webrtc

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import org.robolectric.util.ReflectionHelpers
import org.signal.core.models.ServiceId.ACI
import org.signal.core.models.recipients.RecipientId
import org.signal.core.util.concurrent.KeyedSerialMonoLifoExecutor
import org.signal.core.util.logging.Log
import org.signal.ringrtc.CallId
import org.signal.ringrtc.CallManager
import org.signal.ringrtc.GroupCall
import org.signal.storageservice.storage.protos.groups.ExternalGroupCredential
import org.thoughtcrime.securesms.calls.permissions.CallAuthorization
import org.thoughtcrime.securesms.calls.permissions.CallContext
import org.thoughtcrime.securesms.calls.permissions.CallPermission
import org.thoughtcrime.securesms.calls.permissions.CallPermissionTestEnvironment
import org.thoughtcrime.securesms.calls.permissions.CallPermissions
import org.thoughtcrime.securesms.calls.permissions.CallSessionAuthorizer
import org.thoughtcrime.securesms.calls.permissions.DirectMessageKind
import org.thoughtcrime.securesms.calls.permissions.GroupMessageKind
import org.thoughtcrime.securesms.database.GroupTable
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.groups.GroupId
import org.thoughtcrime.securesms.groups.GroupManager
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.ringrtc.RemotePeer
import org.thoughtcrime.securesms.service.webrtc.state.WebRtcServiceState
import org.thoughtcrime.securesms.testutil.SystemOutLogger
import org.whispersystems.signalservice.api.messages.calls.HangupMessage
import org.whispersystems.signalservice.api.messages.calls.OfferMessage
import java.time.Duration
import java.util.Optional
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
    unmockkStatic(GroupManager::class)
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

  @Test
  fun `contact blocked during dispatch cannot reach JNI or revive its direct session`() {
    val ticket = authorizedOutgoingIce()
    val recipient = policy.recipient(peer.id.toLong())
    val interactor: WebRtcInteractor = mockk(relaxed = true)
    every { interactor.callManager } returns native
    val processor = object : WebRtcActionProcessor(interactor, "BlockRaceTest") {
      override fun handleReceivedIceCandidates(currentState: WebRtcServiceState, callMetadata: WebRtcData.CallMetadata, iceCandidates: List<ByteArray>): WebRtcServiceState {
        every { recipient.isBlocked } returns true
        return super.handleReceivedIceCandidates(currentState, callMetadata, iceCandidates)
      }
    }
    ReflectionHelpers.setField(manager, "serviceState", WebRtcServiceState(processor))

    manager.receivedIceCandidates(ticket, metadata, listOf(byteArrayOf(1)))
    queue.drain()

    verifyNoRemoteDispatch()
    every { recipient.isBlocked } returns false
    assertFalse(CallPermissions.isAuthorized(ticket))
    assertFalse(CallPermissions.canContinueCall(recipient.id))
  }

  @Test
  fun `member removed during opaque dispatch cannot reach JNI or end the group session`() {
    val group = policy.group(8)
    val sender = policy.recipient(7)
    val otherMember = policy.recipient(9)
    val record = SignalDatabase.groups.getGroup(group.id).get()
    check(CallPermissions.beginOutgoing(group.id))
    val ticket = requireNotNull(CallPermissions.authorizeGroup(group.id, sender.id, null, 2, GroupMessageKind.GROUP_DATA, 0))
    val interactor: WebRtcInteractor = mockk(relaxed = true)
    every { interactor.callManager } returns native
    val processor = object : WebRtcActionProcessor(interactor, "MembershipRaceTest") {
      override fun handleReceivedOpaqueMessage(currentState: WebRtcServiceState, opaqueMessageMetadata: WebRtcData.OpaqueMessageMetadata): WebRtcServiceState {
        every { record.memberLevel(sender) } returns GroupTable.MemberLevel.NOT_A_MEMBER
        return super.handleReceivedOpaqueMessage(currentState, opaqueMessageMetadata)
      }
    }
    ReflectionHelpers.setField(manager, "serviceState", WebRtcServiceState(processor))

    manager.receivedOpaqueMessage(ticket, WebRtcData.OpaqueMessageMetadata(UUID(0, 7), byteArrayOf(1), 2, 0))
    queue.drain()

    verifyNoRemoteDispatch()
    assertNotNull(CallPermissions.authorizeGroup(group.id, otherMember.id, null, 3, GroupMessageKind.GROUP_DATA, 0))
  }

  @Test
  fun `group blocked while fetching peek credentials cannot reach native peek`() {
    val group = policy.group(8)
    prepareGroupPeek(group) { every { group.isBlocked } returns true }

    manager.peekGroupCall(group.id)
    queue.drain()

    verify(exactly = 1) { GroupManager.getExternalGroupCredential(any(), any()) }
    verify(exactly = 0) { native.peekGroupCall(any(), any(), any(), any()) }
  }

  @Test
  fun `group left while fetching ringing credentials cannot reach native peek`() {
    val group = policy.group(8)
    val record = SignalDatabase.groups.getGroup(group.id).get()
    val groupId = prepareGroupPeek(group) { every { record.isActive } returns false }

    manager.peekGroupCallForRingingCheck(prepareRingingPeek(group, groupId))
    queue.drain()

    verify(exactly = 1) { GroupManager.getExternalGroupCredential(any(), any()) }
    verify(exactly = 0) { native.peekGroupCall(any(), any(), any(), any()) }
  }

  @Test
  fun `unchanged default group permission still reaches native peek`() {
    val group = policy.group(8)
    prepareGroupPeek(group)

    manager.peekGroupCall(group.id)
    queue.drain()

    verify(exactly = 1) { native.peekGroupCall(any(), any(), any(), any()) }
  }

  @Test
  fun `policy change during final group lookup invalidates the original peek`() {
    val group = policy.group(8)
    prepareGroupPeek(group) {
      var changePolicy = true
      every { Recipient.resolved(group.id) } answers {
        if (changePolicy) {
          changePolicy = false
          CallPermissions.setDefault(CallContext.DIRECT, CallPermission.BLOCK)
        }
        group
      }
    }

    manager.peekGroupCall(group.id)
    queue.drain()

    verify(exactly = 1) { GroupManager.getExternalGroupCredential(any(), any()) }
    verify(exactly = 0) { native.peekGroupCall(any(), any(), any(), any()) }
    // The group remains allowed; it is the original request that was revoked.
    check(CallPermissions.canReceiveGroup(group.id))
  }

  @Test
  fun `remote peek loses its sender permit during credential fetch`() {
    val group = policy.group(8)
    val sender = policy.recipient(7)
    val record = SignalDatabase.groups.getGroup(group.id).get()
    val permit = requireNotNull(CallPermissions.createGroupPeekPermit(group.id, sender.id))
    prepareGroupPeek(group) { every { record.memberLevel(sender) } returns GroupTable.MemberLevel.NOT_A_MEMBER }

    manager.peekGroupCall(group.id, null) { CallPermissions.isGroupPeekPermitValid(permit, group.id, sender.id) }
    queue.drain()

    verify(exactly = 1) { GroupManager.getExternalGroupCredential(any(), any()) }
    verify(exactly = 0) { native.peekGroupCall(any(), any(), any(), any()) }
  }

  @Test
  fun `ringing peek loses its ringer permission during credential fetch`() {
    val group = policy.group(8)
    val sender = policy.recipient(7)
    val record = SignalDatabase.groups.getGroup(group.id).get()
    val groupId = prepareGroupPeek(group) { every { record.memberLevel(sender) } returns GroupTable.MemberLevel.NOT_A_MEMBER }

    manager.peekGroupCallForRingingCheck(prepareRingingPeek(group, groupId))
    queue.drain()

    verify(exactly = 1) { GroupManager.getExternalGroupCredential(any(), any()) }
    verify(exactly = 0) { native.peekGroupCall(any(), any(), any(), any()) }
  }

  @Test
  fun `unchanged authorized incoming ring still reaches native peek`() {
    val group = policy.group(8)
    val groupId = prepareGroupPeek(group)

    manager.peekGroupCallForRingingCheck(prepareRingingPeek(group, groupId))
    queue.drain()

    verify(exactly = 1) { native.peekGroupCall(any(), any(), any(), any()) }
  }

  @Test
  fun `announcement group ringer demoted during credentials cannot reach native peek`() {
    val group = policy.group(8)
    val sender = policy.recipient(7)
    val record = SignalDatabase.groups.getGroup(group.id).get()
    every { record.isAnnouncementGroup } returns true
    every { record.isAdmin(sender) } returns true
    val groupId = prepareGroupPeek(group) { every { record.isAdmin(sender) } returns false }

    manager.peekGroupCallForRingingCheck(prepareRingingPeek(group, groupId))
    queue.drain()

    verify(exactly = 1) { GroupManager.getExternalGroupCredential(any(), any()) }
    verify(exactly = 0) { native.peekGroupCall(any(), any(), any(), any()) }
  }

  private fun prepareRingingPeek(group: Recipient, groupId: GroupId.V2): GroupCallRingCheckInfo {
    val sender = policy.recipient(7)
    val aci = ACI.from(UUID(0, 7))
    every { Recipient.externalPush(aci) } returns sender
    requireNotNull(CallPermissions.authorizeGroup(group.id, sender.id, 41, 2, GroupMessageKind.RING, 0))
    return GroupCallRingCheckInfo(group.id, groupId, 41, aci, CallManager.RingUpdate.REQUESTED)
  }

  private fun prepareGroupPeek(group: Recipient, duringCredentials: () -> Unit = {}): GroupId.V2 {
    val groupId = GroupId.push(ByteArray(32) { 3 }).requireV2()
    every { group.groupId } returns Optional.of(groupId)
    every { group.requireGroupId() } returns groupId
    every { SignalStore.internal } returns mockk { every { groupCallingServer } returns "https://calling.invalid" }
    mockkStatic(GroupManager::class)
    every { GroupManager.getExternalGroupCredential(any(), any()) } answers {
      duringCredentials()
      ExternalGroupCredential(token = "test-credential")
    }
    every { GroupManager.getUuidCipherTexts(any(), any()) } returns emptyMap()
    ReflectionHelpers.setField(manager, "keyedExecutor", KeyedSerialMonoLifoExecutor(queue))
    return groupId
  }

  @Test
  fun `late group callbacks cannot update a subsequent group call`() {
    val oldGroup: GroupCall = mockk(relaxed = true)
    val newGroup: GroupCall = mockk(relaxed = true)
    val processor = object : WebRtcActionProcessor(mockk(relaxed = true), "GroupCallbackTest") {
      var updates = 0
      override fun handleGroupRequestUpdateMembers(currentState: WebRtcServiceState): WebRtcServiceState {
        updates++
        return currentState
      }
      override fun handleGroupRemoteDeviceStateChanged(currentState: WebRtcServiceState): WebRtcServiceState {
        updates++
        return currentState
      }
    }
    val oldState = WebRtcServiceState(processor).apply { callInfoState.groupCall = oldGroup }
    installCallbackState(oldState)
    manager.requestGroupMembers(oldGroup)

    // Normal hangup/new call does not need a policy mutation. Both callbacks
    // queued before teardown and callbacks arriving afterward are stale.
    val newState = WebRtcServiceState(processor).apply { callInfoState.groupCall = newGroup }
    installCallbackState(newState)
    manager.onRemoteDeviceStatesChanged(oldGroup)
    queue.drain()
    assertEquals(0, processor.updates)

    manager.requestGroupMembers(newGroup)
    manager.onRemoteDeviceStatesChanged(newGroup)
    queue.drain()
    assertEquals(2, processor.updates)
  }

  @Test
  fun `late direct media callbacks cannot update a subsequent call to the same contact`() {
    val newPeer = RemotePeer(peer.id, CallId(42))
    val processor = object : WebRtcActionProcessor(mockk(relaxed = true), "PeerCallbackTest") {
      var updates = 0
      override fun handleRemoteAudioEnable(currentState: WebRtcServiceState, enable: Boolean): WebRtcServiceState {
        updates++
        return currentState
      }
    }
    val state = WebRtcServiceState(processor).apply {
      // The old object remains mapped until onCallConcluded performs cleanup.
      callInfoState.peerMap[peer.hashCode()] = peer
      callInfoState.peerMap[newPeer.hashCode()] = newPeer
      callInfoState.activePeer = newPeer
    }
    installCallbackState(state)
    manager.onCallEvent(peer, CallManager.CallEvent.REMOTE_AUDIO_ENABLE)
    queue.drain()
    assertEquals(0, processor.updates)

    manager.onCallEvent(newPeer, CallManager.CallEvent.REMOTE_AUDIO_ENABLE)
    queue.drain()
    assertEquals(1, processor.updates)
  }

  @Test
  fun `old native object cannot acquire a new policy generation while reset is pending`() {
    val oldGroup: GroupCall = mockk(relaxed = true)
    val processor = object : WebRtcActionProcessor(mockk(relaxed = true), "GroupOriginTest") {
      var updates = 0
      override fun handleGroupRequestUpdateMembers(currentState: WebRtcServiceState): WebRtcServiceState {
        updates++
        return currentState
      }
    }
    installCallbackState(WebRtcServiceState(processor).apply { callInfoState.groupCall = oldGroup })
    // Model a native callback racing ahead of the queued reset listener.
    CallPermissions.setRevocationListener { }
    CallPermissions.setAllCallingDisabled(true)
    CallPermissions.setAllCallingDisabled(false)

    manager.requestGroupMembers(oldGroup)
    queue.drain()
    assertEquals(0, processor.updates)
  }

  @Test
  fun `late start of an old mapped peer cannot bind a new pending call`() {
    policy.recipient(peer.id.toLong())
    check(CallPermissions.beginOutgoing(peer.id))
    val state = WebRtcServiceState(object : WebRtcActionProcessor(mockk(relaxed = true), "OldStartTest") {})
    state.callInfoState.peerMap[peer.hashCode()] = peer
    installCallbackState(state)

    manager.onStartCall(peer, CallId(42), true, CallManager.CallMediaType.AUDIO_CALL)
    queue.drain()
    check(CallPermissions.bindOutgoing(peer.id, 43))
  }

  @Test
  fun `async completions retain request generation even after calling is enabled again`() {
    val requestGeneration = CallPermissions.generation()
    CallPermissions.setAllCallingDisabled(true)
    CallPermissions.setAllCallingDisabled(false)
    queue.drain()
    var completions = 0
    val completion = SignalCallManager.ProcessAction { state, _ -> completions++; state }
    val process = SignalCallManager::class.java.getDeclaredMethod("process", java.lang.Long.TYPE, SignalCallManager.ProcessAction::class.java)
    process.isAccessible = true

    process.invoke(manager, requestGeneration, completion)
    queue.drain()
    assertEquals(0, completions)

    process.invoke(manager, CallPermissions.generation(), completion)
    queue.drain()
    assertEquals(1, completions)
  }

  private fun installCallbackState(state: WebRtcServiceState) {
    ReflectionHelpers.setField(manager, "serviceState", state)
    ReflectionHelpers.setField(manager, "groupCallGeneration", CallPermissions.generation())
    val origins = ReflectionHelpers.getField<MutableMap<RemotePeer, Long>>(manager, "peerGenerations")
    origins.clear()
    state.callInfoState.peerMap.values.forEach { origins[it] = CallPermissions.generation() }
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
