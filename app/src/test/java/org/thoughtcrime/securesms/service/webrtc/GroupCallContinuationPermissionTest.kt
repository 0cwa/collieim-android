package org.thoughtcrime.securesms.service.webrtc

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.util.logging.Log
import org.signal.remoteconfig.RemoteConfig
import org.signal.ringrtc.CallManager
import org.signal.ringrtc.GroupCall
import org.thoughtcrime.securesms.calls.permissions.CallPermissionTestEnvironment
import org.thoughtcrime.securesms.calls.permissions.CallPermissions
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.groups.GroupId
import org.thoughtcrime.securesms.groups.GroupManager
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.ringrtc.RemotePeer
import org.thoughtcrime.securesms.service.webrtc.state.WebRtcServiceState
import org.thoughtcrime.securesms.testutil.SystemOutLogger
import java.util.Optional

/** Uses the real policy facade and processors; only native transport and group storage are mocked. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, instrumentedPackages = ["org.signal.ringrtc"])
class GroupCallContinuationPermissionTest {
  private val policy = CallPermissionTestEnvironment()
  private val native: GroupCall = mockk(relaxed = true)
  private val interactor: WebRtcInteractor = mockk(relaxed = true)
  private val nativeManager: CallManager = mockk(relaxed = true)
  private lateinit var group: Recipient
  private lateinit var processor: ExposedProcessor
  private lateinit var state: WebRtcServiceState
  private val groupId = GroupId.push(ByteArray(32) { 3 }).requireV2()

  @Before
  fun setUp() {
    Log.initialize(SystemOutLogger())
    policy.setUp()
    group = policy.group(9)
    every { group.groupId } returns Optional.of(groupId)
    every { group.requireGroupId() } returns groupId
    every { interactor.context } returns ApplicationProvider.getApplicationContext()
    every { interactor.callManager } returns nativeManager
    mockkStatic(GroupManager::class)
    every { GroupManager.getUuidCipherTexts(any(), any()) } returns emptyMap()
    check(CallPermissions.beginOutgoing(group.id))
    processor = ExposedProcessor(interactor)
    state = WebRtcServiceState(processor).apply {
      callInfoState.callRecipient = group
      callInfoState.groupCall = native
    }
  }

  @After
  fun tearDown() {
    unmockkObject(RemoteConfig, RingRtcDynamicConfiguration)
    unmockkStatic(GroupManager::class, RemoteConfig::class, RingRtcDynamicConfiguration::class, WebRtcUtil::class)
    policy.tearDown()
  }

  @Test
  fun `permitted membership proof and roster keep the existing native path`() {
    processor.requestProof(state, native.hashCode())
    verify(exactly = 1) { interactor.requestGroupMembershipProof(groupId, native) }

    val proof = byteArrayOf(1, 2, 3)
    processor.proof(state, native.hashCode(), proof)
    processor.members(state)

    verify(exactly = 1) { native.setMembershipProof(proof) }
    verify(exactly = 1) { native.setGroupMembers(emptyList()) }
  }

  @Test
  fun `membership proof is rejected after blocking or leaving the group`() {
    every { group.isBlocked } returns true
    processor.proof(state, native.hashCode(), byteArrayOf(1))
    every { group.isBlocked } returns false
    every { SignalDatabase.groups.getGroup(group.id).get().isActive } returns false
    processor.proof(state, native.hashCode(), byteArrayOf(1))

    verify(exactly = 0) { native.setMembershipProof(any()) }
  }

  @Test
  fun `departure during roster lookup cannot reach native group members`() {
    val record = SignalDatabase.groups.getGroup(group.id).get()
    every { GroupManager.getUuidCipherTexts(any(), any()) } answers {
      every { record.isActive } returns false
      emptyMap()
    }

    processor.members(state)

    verify(exactly = 0) { native.setGroupMembers(any()) }
  }

  @Test
  fun `roster work cannot attach itself to a replacement policy generation`() {
    every { GroupManager.getUuidCipherTexts(any(), any()) } answers {
      CallPermissions.setAllCallingDisabled(true)
      CallPermissions.setAllCallingDisabled(false)
      check(CallPermissions.beginOutgoing(group.id))
      emptyMap()
    }

    processor.members(state)

    verify(exactly = 0) { native.setGroupMembers(any()) }
  }

  @Test
  fun `group blocked during native client creation is disconnected before connect`() {
    preparePreJoin()
    val preJoin = ExposedPreJoin(interactor)
    val lobby = WebRtcServiceState(preJoin).apply { callInfoState.callRecipient = group }
    every { nativeManager.createGroupCall(any(), any(), any(), any(), any(), any(), any(), any(), any()) } answers {
      every { group.isBlocked } returns true
      native
    }

    preJoin.start(lobby, RemotePeer(group.id))

    verify(exactly = 1) { native.disconnect() }
    verify(exactly = 0) { native.connect() }
    assertTrue(preJoin.terminated)
    every { group.isBlocked } returns false
    assertFalse(CallPermissions.canContinueCall(group.id))
  }

  private fun preparePreJoin() {
    mockkObject(RemoteConfig, RingRtcDynamicConfiguration)
    mockkStatic(RemoteConfig::class, RingRtcDynamicConfiguration::class, WebRtcUtil::class)
    every { RemoteConfig.dredDuration } returns 0
    every { RingRtcDynamicConfiguration.getAudioConfig() } returns mockk()
    every { RingRtcDynamicConfiguration.getSvcConfig() } returns null
    every { WebRtcUtil.getProxyInfo() } returns null
    every { SignalStore.internal } returns mockk { every { groupCallingServer } returns "https://calling.invalid" }
  }

  private class ExposedProcessor(interactor: WebRtcInteractor) : GroupActionProcessor(mockk(relaxed = true), interactor, "ContinuationTest") {
    fun requestProof(state: WebRtcServiceState, hash: Int) = handleGroupRequestMembershipProof(state, hash)
    fun proof(state: WebRtcServiceState, hash: Int, token: ByteArray) = handleGroupMembershipProofResponse(state, hash, token)
    fun members(state: WebRtcServiceState) = handleGroupRequestUpdateMembers(state)
  }

  private class ExposedPreJoin(interactor: WebRtcInteractor) : GroupPreJoinActionProcessor(mockk(relaxed = true), interactor) {
    var terminated = false
    fun start(state: WebRtcServiceState, peer: RemotePeer) = handlePreJoinCall(state, peer)
    override fun terminateGroupCall(currentState: WebRtcServiceState): WebRtcServiceState {
      terminated = true
      CallPermissions.endAllSessions()
      return currentState
    }
  }
}
