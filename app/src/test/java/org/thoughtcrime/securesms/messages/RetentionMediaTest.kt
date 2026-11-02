package org.thoughtcrime.securesms.messages

import android.app.Application
import android.net.Uri
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thoughtcrime.securesms.attachments.ArchivedAttachment
import org.thoughtcrime.securesms.attachments.DatabaseAttachment
import org.thoughtcrime.securesms.database.MediaTable
import org.thoughtcrime.securesms.database.MessageType
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.jobs.MultiDeviceDeleteSyncJob
import org.thoughtcrime.securesms.mms.IncomingMessage
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.testutil.RecipientTestRule
import org.thoughtcrime.securesms.util.AttachmentUtil
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class RetentionMediaTest {
  @get:Rule val recipients = RecipientTestRule()

  @Before fun setup() {
    every { recipients.signalStore.settings.isDeleteMediaOnly } returns true
    mockkObject(MultiDeviceDeleteSyncJob.Companion)
  }

  @After fun cleanup() = unmockkObject(MultiDeviceDeleteSyncJob.Companion)

  @Test fun `single final media removal keeps body and refreshes latest preview locally`() {
    val id = recipients.createRecipient("Alice")
    val threadId = SignalDatabase.threads.getOrCreateThreadIdFor(Recipient.resolved(id))
    val attachment = insertMedia(id, threadId, 100L, "kept body")
    setPreview(threadId, attachment.mmsId)

    assertNull(AttachmentUtil.deleteAttachment(attachment))

    assertEquals("kept body", SignalDatabase.messages.getMessageRecord(attachment.mmsId).body)
    assertEquals(0, SignalDatabase.attachments.getAttachmentsForMessage(attachment.mmsId).size)
    val thread = SignalDatabase.threads.getThreadRecord(threadId)!!
    assertEquals("kept body", thread.snippet)
    assertNull(thread.snippetUri)
    assertNull(thread.snippetContentType)
    verify(exactly = 0) { MultiDeviceDeleteSyncJob.enqueueAttachmentDelete(any(), any()) }
  }

  @Test fun `bulk final media removal preserves messages and refreshes latest preview`() {
    val id = recipients.createRecipient("Bob")
    val threadId = SignalDatabase.threads.getOrCreateThreadIdFor(Recipient.resolved(id))
    val first = insertMedia(id, threadId, 100L, "first body")
    val last = insertMedia(id, threadId, 200L, "last body")
    setPreview(threadId, last.mmsId)
    val records = listOf(first, last).map { MediaTable.MediaRecord(it, id, id, threadId, it.mmsId, 200L, false) }

    assertEquals(emptySet<Any>(), AttachmentUtil.deleteAttachments(records) {})

    assertEquals("first body", SignalDatabase.messages.getMessageRecord(first.mmsId).body)
    assertEquals("last body", SignalDatabase.messages.getMessageRecord(last.mmsId).body)
    val thread = SignalDatabase.threads.getThreadRecord(threadId)!!
    assertEquals("last body", thread.snippet)
    assertNull(thread.snippetUri)
    assertNull(thread.snippetContentType)
    verify(exactly = 0) { MultiDeviceDeleteSyncJob.enqueueAttachmentDelete(any(), any()) }
  }

  @Test fun `removing an older attachment leaves latest preview intact`() {
    val id = recipients.createRecipient("Carol")
    val threadId = SignalDatabase.threads.getOrCreateThreadIdFor(Recipient.resolved(id))
    val first = insertMedia(id, threadId, 100L, "first body")
    val last = insertMedia(id, threadId, 200L, "last body")
    setPreview(threadId, last.mmsId)
    val before = SignalDatabase.threads.getThreadRecord(threadId)!!

    AttachmentUtil.deleteAttachment(first)

    val after = SignalDatabase.threads.getThreadRecord(threadId)!!
    assertEquals(before.snippetMessageId, after.snippetMessageId)
    assertEquals(before.snippet, after.snippet)
    assertEquals(before.snippetUri, after.snippetUri)
    assertEquals(before.snippetContentType, after.snippetContentType)
  }

  private fun insertMedia(id: org.signal.core.models.recipients.RecipientId, threadId: Long, timestamp: Long, body: String): DatabaseAttachment {
    val attachment = ArchivedAttachment("image/jpeg", 1024, 3, 0, ByteArray(32), "key", 3, ByteArray(32), null, null, 100, 100, null, null, false, false, null, false, false, null, UUID.randomUUID(), null, null)
    val message = IncomingMessage(type = MessageType.NORMAL, from = id, body = body, sentTimeMillis = timestamp, serverTimeMillis = timestamp, receivedTimeMillis = timestamp, attachments = listOf(attachment))
    val messageId = SignalDatabase.messages.insertMessageInbox(message, threadId).get().messageId
    SignalDatabase.threads.update(threadId, false)
    return SignalDatabase.attachments.getAttachmentsForMessage(messageId).single()
  }

  private fun setPreview(threadId: Long, messageId: Long) {
    SignalDatabase.threads.updateSnippetUriSilently(threadId, messageId, Uri.parse("content://example/preview"))
    SignalDatabase.threads.updateSnippetContentTypeSilently(threadId, messageId, "image/jpeg")
    assertNotNull(SignalDatabase.threads.getThreadRecord(threadId)!!.snippetUri)
  }
}
