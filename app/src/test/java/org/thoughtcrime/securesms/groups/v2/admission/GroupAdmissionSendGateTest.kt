/*
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.groups.v2.admission

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import io.mockk.verify
import okio.ByteString.Companion.toByteString
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.models.ServiceId.ACI
import org.signal.core.models.recipients.RecipientId
import org.signal.libsignal.zkgroup.groups.GroupMasterKey
import org.signal.storageservice.storage.protos.groups.local.DecryptedGroup
import org.signal.storageservice.storage.protos.groups.local.DecryptedGroupChange
import org.thoughtcrime.securesms.database.MessageTable
import org.thoughtcrime.securesms.database.NoSuchMessageException
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.database.model.MessageId
import org.thoughtcrime.securesms.database.model.StoryType
import org.thoughtcrime.securesms.database.model.databaseprotos.DecryptedGroupV2Context
import org.thoughtcrime.securesms.groups.GroupId
import org.thoughtcrime.securesms.jobs.ResendMessageJob
import org.thoughtcrime.securesms.mms.MessageGroupContext
import org.thoughtcrime.securesms.mms.OutgoingMessage
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.testutil.MockSignalStoreRule
import org.whispersystems.signalservice.api.crypto.ContentHint
import org.whispersystems.signalservice.api.messages.SignalServiceDataMessage
import org.whispersystems.signalservice.api.messages.SignalServiceGroupV2
import org.whispersystems.signalservice.internal.push.Content
import org.whispersystems.signalservice.internal.push.DataMessage
import org.whispersystems.signalservice.internal.push.GroupContextV2
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class GroupAdmissionSendGateTest {

  @get:Rule
  val store = MockSignalStoreRule()

  private val self = ACI.from(UUID.randomUUID())
  private val masterKey = GroupMasterKey(ByteArray(GroupMasterKey.SIZE) { 1 })
  private val groupId = GroupId.v2(masterKey)
  private val recipient = mockk<Recipient>(relaxed = true)
  private val messages = mockk<MessageTable>()
  private val relatedId = MessageId(42)
  private val signedChange = byteArrayOf(1, 2, 3)
  private lateinit var leave: OutgoingMessage

  @Before
  fun setUp() {
    mockkStatic(GroupAdmission::class)
    mockkObject(SignalDatabase.Companion)
    every { GroupAdmission.isHeld(any<Recipient>()) } returns true
    every { GroupAdmission.isHeld(any<GroupId.V2>()) } returns true
    every { SignalDatabase.messages } returns messages
    every { recipient.requireGroupId() } returns groupId
    every { store.account.requireAci() } returns self

    leave = OutgoingMessage.groupUpdateMessage(
      threadRecipient = recipient,
      groupContext = MessageGroupContext(
        DecryptedGroupV2Context(
          context = GroupContextV2(masterKey = masterKey.serialize().toByteString(), revision = 4, groupChange = signedChange.toByteString()),
          change = DecryptedGroupChange(revision = 4, editorServiceIdBytes = self.toByteString(), deleteMembers = listOf(self.toByteString())),
          groupState = DecryptedGroup(revision = 4)
        )
      ),
      sentTimeMillis = 1234
    )
    every { messages.getOutgoingMessage(relatedId.id) } returns leave
  }

  @After
  fun tearDown() {
    unmockkObject(SignalDatabase.Companion)
    unmockkStatic(GroupAdmission::class)
  }

  @Test
  fun heldOrdinaryMessageIsRejectedButAdmittedConversationCanSend() {
    val message = OutgoingMessage(threadRecipient = recipient, sentTimeMillis = 1234, body = "hello")
    assertFalse(GroupAdmissionSendGate.canSend(message))
    every { GroupAdmission.isHeld(recipient) } returns false
    assertTrue(GroupAdmissionSendGate.canSend(message))
  }

  @Test
  fun legacyGroupResendUsesTheEpisodeFromItsStoredContent() {
    val content = Content(dataMessage = DataMessage(groupV2 = GroupContextV2(masterKey = masterKey.serialize().toByteString(), revision = 4)))
    every { GroupAdmission.sendEpisode(groupId) } returns GroupAdmission.SendEpisode(self.toString(), "original", "member:2", false, 2)
    val job = ResendMessageJob(RecipientId.from(123), 1234L, content, ContentHint.RESENDABLE, false, groupId = null, distributionId = null)
    job.setContext(ApplicationProvider.getApplicationContext())

    every { GroupAdmission.sendEpisode(groupId) } returns GroupAdmission.SendEpisode(self.toString(), "readmitted", "member:2", false, 2)

    assertTrue(job.run().isFailure)
    verify { GroupAdmission.sendEpisode(groupId) }
  }

  @Test
  fun pureSelfLeaveCanPassBothEnqueueAndTransportGates() {
    assertTrue(GroupAdmissionSendGate.canSend(leave))
    GroupAdmissionSendGate.requireAllowed(groupId, relatedId, wire().build())
    verify(exactly = 1) { messages.getOutgoingMessage(relatedId.id) }
  }

  @Test
  fun selfLeaveCannotCarryAnotherOutgoingPayload() {
    val combined = listOf(
      leave.copy(body = "hello"),
      leave.copy(attachments = listOf(mockk())),
      leave.copy(linkPreviews = listOf(mockk())),
      leave.copy(sharedContacts = listOf(mockk())),
      leave.copy(outgoingQuote = mockk()),
      leave.copy(mentions = listOf(mockk())),
      leave.copy(bodyRanges = mockk()),
      leave.copy(messageToEdit = 12),
      leave.copy(storyType = StoryType.STORY_WITH_REPLIES),
      leave.copy(isViewOnce = true),
      leave.copy(isExpirationUpdate = true),
      leave.copy(isPaymentsNotification = true),
      leave.copy(poll = mockk())
    )
    combined.forEach { assertFalse(GroupAdmissionSendGate.canSend(it)) }
  }

  @Test
  fun aRelatedLeaveDoesNotAuthorizeBodyMediaReactionOrProfileKey() {
    val combined = listOf(
      wire().withBody("hello").build(),
      wire().withAttachments(listOf(mockk())).build(),
      wire().withReaction(SignalServiceDataMessage.Reaction("👍", false, self, 1234)).build(),
      wire().withRemoteDelete(SignalServiceDataMessage.RemoteDelete(1234)).build(),
      wire().withProfileKey(ByteArray(32)).build(),
      wire().asExpirationUpdate().build(),
      wire().asProfileKeyUpdate(true).build(),
      wire().withGroupCallUpdate(SignalServiceDataMessage.GroupCallUpdate("era")).build(),
      wire().withViewOnce(true).build()
    )
    combined.forEach { rejected(it) }
    verify(exactly = 0) { messages.getOutgoingMessage(any()) }
  }

  @Test
  fun storedLeaveMustMatchSignedChangeRevisionTimestampAndDestination() {
    rejected(wire(signed = byteArrayOf(9)).build())
    rejected(wire(revision = 5).build())
    rejected(wire(timestamp = 1235).build())
    rejected(wire(key = GroupMasterKey(ByteArray(GroupMasterKey.SIZE) { 2 })).build())

    every { recipient.requireGroupId() } returns GroupId.v2(GroupMasterKey(ByteArray(GroupMasterKey.SIZE) { 3 }))
    rejected(wire().build())
  }

  @Test
  fun missingLocalLeaveAndNonDataOperationsFailClosed() {
    assertThrows(GroupAdmissionSendGate.HeldGroupException::class.java) {
      GroupAdmissionSendGate.requireAllowed(groupId, null, wire().build())
    }
    assertThrows(GroupAdmissionSendGate.HeldGroupException::class.java) {
      GroupAdmissionSendGate.requireAllowed(groupId, relatedId, null)
    }
    every { messages.getOutgoingMessage(relatedId.id) } throws NoSuchMessageException("deleted")
    rejected(wire().build())
  }

  @Test
  fun anotherMembersDepartureCannotUseTheSelfLeaveException() {
    val other = ACI.from(UUID.randomUUID()).toByteString()
    val context = leave.messageGroupContext!!.requireGroupV2Properties().groupContext
    every { messages.getOutgoingMessage(relatedId.id) } returns leave.copy(
      messageGroupContext = MessageGroupContext(
        DecryptedGroupV2Context(
          context = context,
          change = DecryptedGroupChange(revision = 4, editorServiceIdBytes = other, deleteMembers = listOf(other)),
          groupState = DecryptedGroup(revision = 4)
        )
      )
    )
    rejected(wire().build())
  }

  @Test
  fun admittedGroupAndIndividualTrafficDoNotReadALocalLeave() {
    every { GroupAdmission.isHeld(groupId) } returns false
    GroupAdmissionSendGate.requireAllowed(groupId, null, null)
    GroupAdmissionSendGate.requireAllowed(null, null, null)
    verify(exactly = 0) { messages.getOutgoingMessage(any()) }
  }

  private fun wire(
    key: GroupMasterKey = masterKey,
    revision: Int = 4,
    signed: ByteArray = signedChange,
    timestamp: Long = 1234
  ): SignalServiceDataMessage.Builder = SignalServiceDataMessage.Builder()
    .withTimestamp(timestamp)
    .asGroupMessage(SignalServiceGroupV2.newBuilder(key).withRevision(revision).withSignedGroupChange(signed).build())

  private fun rejected(message: SignalServiceDataMessage) {
    assertThrows(GroupAdmissionSendGate.HeldGroupException::class.java) {
      GroupAdmissionSendGate.requireAllowed(groupId, relatedId, message)
    }
  }
}
