package org.thoughtcrime.securesms.calls.permissions

import android.app.Application
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import okio.ByteString.Companion.toByteString
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
    every { AppDependencies.signalCallManager } throws AssertionError("Admission must not obtain the native call manager")
    every { CallPermissions.isCallingAllowed() } returns true
    every { CallPermissions.authorizeGroup(any(), any(), any(), any(), any(), any()) } returns ticket
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
  fun tearDown() = unmockkAll()

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
  fun `unavailable group data and denied policy never obtain native manager`() {
    every { groups.getGroup(any<GroupId>()) } throws IllegalStateException("Unavailable")
    assertNull(GroupCallAdmission.admit(sender, metadata(), ring(), 0))
    every { CallPermissions.isCallingAllowed() } returns false
    assertNull(GroupCallAdmission.admit(sender, metadata(), ring(), 0))
    verify(exactly = 0) { AppDependencies.signalCallManager }
  }

  private fun metadata(source: ServiceId = senderAci, envelopeGroup: ByteArray? = null) = EnvelopeMetadata(source, null, 2, true, envelopeGroup, selfAci, 0)

  private fun ring() = OpaqueCallMessage(ringIntention = OpaqueCallMessage.RingIntention(groupId, OpaqueCallMessage.RingIntention.Type.RING, 17L)).encode()

  private fun data() = OpaqueCallMessage(groupCallMessage = GroupDeviceMessage(groupId, leaving = GroupDeviceMessage.Leaving(16))).encode()
}
