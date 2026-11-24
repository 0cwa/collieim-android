package org.thoughtcrime.securesms.calls.permissions

import android.app.Application
import android.os.SystemClock
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import okio.ByteString.Companion.toByteString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.models.ServiceId
import org.signal.core.models.recipients.RecipientId
import org.thoughtcrime.securesms.calls.permissions.proto.GroupDeviceMessage
import org.thoughtcrime.securesms.calls.permissions.proto.OpaqueCallMessage
import org.thoughtcrime.securesms.database.GroupTable
import org.thoughtcrime.securesms.database.RecipientTable
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.database.model.GroupRecord
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.groups.GroupId
import org.thoughtcrime.securesms.recipients.Recipient
import org.whispersystems.signalservice.api.crypto.EnvelopeMetadata
import java.util.Optional
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [35])
class GroupCallAdmissionTest {
  private val senderAci = ServiceId.ACI.from(UUID.fromString("11111111-1111-4111-8111-111111111111"))
  private val selfAci = ServiceId.ACI.from(UUID.fromString("22222222-2222-4222-8222-222222222222"))
  private val senderId = RecipientId.from(5)
  private val groupRecipientId = RecipientId.from(8)
  private val groupId = ByteArray(32) { 3 }.toByteString()
  private val sender = mockk<Recipient>()
  private val groupRecipient = mockk<Recipient>()
  private val groups = mockk<GroupTable>()
  private val recipients = mockk<RecipientTable>()
  private val group = mockk<GroupRecord>()
  private val ticket = mockk<CallAuthorization>()

  @Before
  fun setUp() {
    mockkObject(CallPermissions, SignalDatabase, Recipient, AppDependencies)
    mockkStatic(CallPermissions::class, AppDependencies::class)
    every { AppDependencies.signalCallManager } throws AssertionError("Admission must not obtain the native call manager")
    every { CallPermissions.isCallingAllowed() } returns true
    every { CallPermissions.authorizeGroup(any(), any(), any(), any(), any(), any()) } returns ticket
    every { CallPermissions.bindCallLinkDevice(ticket, any()) } returns ticket
    every { CallPermissions.isAuthorized(ticket) } returns true
    every { SignalDatabase.groups } returns groups
    every { SignalDatabase.recipients } returns recipients
    every { groups.getGroup(any<GroupId>()) } returns Optional.of(group)
    every { sender.resolve() } returns sender
    every { sender.id } returns senderId
    every { sender.aci } returns Optional.of(senderAci)
    every { sender.isBlocked } returns false
    every { sender.isSelf } returns false
    every { group.recipientId } returns groupRecipientId
    every { group.isActive } returns true
    every { group.isAnnouncementGroup } returns false
    every { group.memberLevel(sender) } returns GroupTable.MemberLevel.FULL_MEMBER
    every { Recipient.resolved(groupRecipientId) } returns groupRecipient
    every { groupRecipient.isBlocked } returns false
  }

  @After
  fun tearDown() {
    GroupCallAdmission.clearPending()
    unmockkAll()
  }

  @Test
  fun `ordinary authorized group ring carries authenticated sender to policy`() {
    assertNotNull(GroupCallAdmission.admit(sender, metadata(), ring(), 0))
    verify(exactly = 1) { CallPermissions.authorizeGroup(groupRecipientId, senderId, 17L, 2, GroupMessageKind.RING, 0) }
    verify(exactly = 0) { AppDependencies.signalCallManager }
  }

  @Test
  fun `payload context and supplied recipient cannot override authenticated envelope`() {
    assertNull(GroupCallAdmission.admit(sender, metadata(source = selfAci), ring(), 0))
    assertNull(GroupCallAdmission.admit(sender, metadata(envelopeGroup = ByteArray(32) { 9 }), ring(), 0))
    verify(exactly = 0) { CallPermissions.authorizeGroup(any(), any(), any(), any(), any(), any()) }
  }

  @Test
  fun `nonmember and blocked sender remain denied even when group allows calls`() {
    every { group.memberLevel(sender) } returns GroupTable.MemberLevel.NOT_A_MEMBER
    assertNull(GroupCallAdmission.admit(sender, metadata(), ring(), 0))
    every { group.memberLevel(sender) } returns GroupTable.MemberLevel.FULL_MEMBER
    every { sender.isBlocked } returns true
    assertNull(GroupCallAdmission.admit(sender, metadata(), ring(), 0))
    verify(exactly = 0) { CallPermissions.authorizeGroup(any(), any(), any(), any(), any(), any()) }
  }

  @Test
  fun `announcement restriction applies to rings without denying members media keys`() {
    every { group.isAnnouncementGroup } returns true
    every { group.isAdmin(sender) } returns false
    assertNull(GroupCallAdmission.admit(sender, metadata(), ring(), 0))
    assertNotNull(GroupCallAdmission.admit(sender, metadata(), data(), 0))
    verify(exactly = 1) { CallPermissions.authorizeGroup(groupRecipientId, senderId, null, 2, GroupMessageKind.GROUP_DATA, 0) }
  }

  @Test
  fun `ring responses require an authenticated self device`() {
    val response = OpaqueCallMessage(ringResponse = OpaqueCallMessage.RingResponse(groupId, OpaqueCallMessage.RingResponse.Type.ACCEPTED, 17L)).encode()
    assertNull(GroupCallAdmission.admit(sender, metadata(), response, 0))
    every { sender.isSelf } returns true
    every { sender.aci } returns Optional.of(selfAci)
    assertNotNull(GroupCallAdmission.admit(sender, metadata(source = selfAci), response, 0))
    verify(exactly = 1) { CallPermissions.authorizeGroup(groupRecipientId, senderId, 17L, 2, GroupMessageKind.RING_RESPONSE, 0) }
  }

  @Test
  fun `call link requires exact room context and cannot be rung like a group`() {
    val linkRecipientId = RecipientId.from(11)
    every { groups.getGroup(any<GroupId>()) } returns Optional.empty()
    every { recipients.getByCallLinkRoomId(any()) } returns Optional.of(linkRecipientId)
    assertNull(GroupCallAdmission.admit(sender, metadata(), ring(), 0))
    assertNotNull(GroupCallAdmission.admit(sender, metadata(), data(), 0))
    verify(exactly = 1) { CallPermissions.authorizeGroup(linkRecipientId, senderId, null, 2, GroupMessageKind.GROUP_DATA, 0) }
  }

  @Test
  fun `early key from an unknown link participant stays managed while another participant is allowed`() {
    val linkId = RecipientId.from(11)
    val sessions = CallSessionAuthorizer(SystemClock::elapsedRealtime)
    sessions.beginOutgoing(CallContext.CALL_LINK, linkId.toLong())
    val key = sessions.callLinkSession(linkId.toLong())!!
    sessions.updateCallLinkMembers(key, setOf(CallLinkMember(99, 32)))
    val existingPeer = sessions.bindCallLinkDevice(sessions.group(CallContext.CALL_LINK, 11, 99, null, 3, GroupMessageKind.GROUP_DATA, 0, false)!!, 32)!!
    val unbound = sessions.group(CallContext.CALL_LINK, 11, senderId.toLong(), null, 2, GroupMessageKind.GROUP_DATA, 0, false)!!
    val existingUuid = UUID(0, 99)
    every { groups.getGroup(any<GroupId>()) } returns Optional.empty()
    every { recipients.getByCallLinkRoomId(any()) } returns Optional.of(linkId)
    every { CallPermissions.authorizeGroup(linkId, senderId, null, 2, GroupMessageKind.GROUP_DATA, 0) } returns unbound
    every { CallPermissions.bindCallLinkDevice(unbound, 16) } answers { sessions.bindCallLinkDevice(unbound, 16) }
    every { CallPermissions.isAuthorized(any()) } answers { sessions.valid(firstArg()) }
    every { CallPermissions.isPendingCallLinkCurrent(any()) } answers { sessions.validPendingLink(firstArg()) }
    every { CallPermissions.authorizePendingCallLink(any()) } answers { sessions.authorizePendingLink(firstArg()) }
    every { CallPermissions.updateCallLinkMembers(key, any(), any()) } answers {
      val devices = secondArg<Map<Long, UUID>>()
      sessions.updateCallLinkMembers(key, devices.map { (demux, uuid) -> CallLinkMember(if (uuid == senderAci.rawUuid) senderId.toLong() else 99L, demux) }.toSet())
    }

    val media = OpaqueCallMessage(groupCallMessage = GroupDeviceMessage(groupId, mediaKey = GroupDeviceMessage.MediaKey(0, ByteArray(32) { 7 }.toByteString(), 16))).encode()
    assertNull(GroupCallAdmission.admit(sender, metadata(), media, 0))
    assertTrue(sessions.valid(existingPeer))
    assertTrue(GroupCallAdmission.updateCallLinkParticipants(key, mapOf(32L to existingUuid), listOf(existingUuid)).isEmpty())
    val ready = GroupCallAdmission.updateCallLinkParticipants(key, mapOf(32L to existingUuid, 16L to senderAci.rawUuid), listOf(existingUuid, senderAci.rawUuid))
    assertEquals(1, ready.size)
    assertTrue(sessions.valid(ready.single().authorization))
    assertTrue(sessions.valid(existingPeer))

    // The SFU callback can publish between binding a candidate and inserting it in the queue.
    val racingCandidate = sessions.group(CallContext.CALL_LINK, 11, senderId.toLong(), null, 2, GroupMessageKind.GROUP_DATA, 0, false)!!
    every { CallPermissions.authorizeGroup(linkId, senderId, null, 2, GroupMessageKind.GROUP_DATA, 0) } returns racingCandidate
    every { CallPermissions.bindCallLinkDevice(racingCandidate, 48) } answers { sessions.bindCallLinkDevice(racingCandidate, 48) }
    every { CallPermissions.isPendingCallLinkCurrent(any()) } answers {
      sessions.updateCallLinkMembers(key, setOf(CallLinkMember(99, 32), CallLinkMember(senderId.toLong(), 16), CallLinkMember(senderId.toLong(), 48)))
      sessions.validPendingLink(firstArg())
    }
    val racingMedia = OpaqueCallMessage(groupCallMessage = GroupDeviceMessage(groupId, mediaKey = GroupDeviceMessage.MediaKey(0, ByteArray(32) { 7 }.toByteString(), 48))).encode()
    val admittedWithoutLostWakeup = GroupCallAdmission.admit(sender, metadata(), racingMedia, 0)
    assertNotNull(admittedWithoutLostWakeup)
    assertTrue(sessions.valid(admittedWithoutLostWakeup!!.authorization))
    assertTrue(GroupCallAdmission.updateCallLinkParticipants(key, mapOf(32L to existingUuid, 16L to senderAci.rawUuid, 48L to senderAci.rawUuid), listOf(existingUuid, senderAci.rawUuid)).isEmpty())
    verify(exactly = 0) { AppDependencies.signalCallManager }
  }

  @Test
  fun `unavailable group data and denied policy never obtain native manager`() {
    every { groups.getGroup(any<GroupId>()) } throws IllegalStateException("Unavailable")
    assertNull(GroupCallAdmission.admit(sender, metadata(), ring(), 0))
    every { sender.resolve() } throws IllegalStateException("Unavailable")
    assertNull(GroupCallAdmission.admit(sender, metadata(), ring(), 0))
    every { CallPermissions.isCallingAllowed() } returns false
    assertNull(GroupCallAdmission.admit(sender, metadata(), ring(), 0))
    verify(exactly = 0) { AppDependencies.signalCallManager }
  }

  private fun metadata(source: ServiceId = senderAci, envelopeGroup: ByteArray? = null) = EnvelopeMetadata(source, null, 2, true, envelopeGroup, selfAci, 0)

  private fun ring() = OpaqueCallMessage(ringIntention = OpaqueCallMessage.RingIntention(groupId, OpaqueCallMessage.RingIntention.Type.RING, 17L)).encode()

  private fun data() = OpaqueCallMessage(groupCallMessage = GroupDeviceMessage(groupId, leaving = GroupDeviceMessage.Leaving(16))).encode()
}
