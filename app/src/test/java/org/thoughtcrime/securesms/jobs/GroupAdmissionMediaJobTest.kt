/*
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.jobs

import android.app.Application
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.models.database.AttachmentId
import org.thoughtcrime.securesms.database.AttachmentTable
import org.thoughtcrime.securesms.database.FakeMessageRecords
import org.thoughtcrime.securesms.database.MessageTable
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.database.ThreadTable
import org.thoughtcrime.securesms.database.model.MessageRecord
import org.thoughtcrime.securesms.groups.v2.admission.GroupAdmission
import org.thoughtcrime.securesms.recipients.Recipient

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class GroupAdmissionMediaJobTest {

  private val attachmentId = AttachmentId(7)
  private val messageId = 12L
  private val attachments = mockk<AttachmentTable>(relaxUnitFun = true)
  private val messages = mockk<MessageTable>()
  private val threads = mockk<ThreadTable>()
  private val recipient = mockk<Recipient>()
  private val message = mockk<MessageRecord>()

  @Before
  fun setUp() {
    mockkObject(SignalDatabase.Companion)
    mockkStatic(GroupAdmission::class)
    every { SignalDatabase.attachments } returns attachments
    every { SignalDatabase.messages } returns messages
    every { SignalDatabase.threads } returns threads
    every { messages.getMessageRecordOrNull(messageId) } returns message
    every { message.threadId } returns 3L
    every { threads.getRecipientForThreadId(3L) } returns recipient
    every { GroupAdmission.isHeld(recipient) } returns true
  }

  @After
  fun tearDown() {
    unmockkStatic(GroupAdmission::class)
    unmockkObject(SignalDatabase.Companion)
  }

  @Test
  fun forcedQueuedTransitDownloadWaitsForAdmission() {
    every { attachments.getAttachment(attachmentId) } returns FakeMessageRecords.buildDatabaseAttachment(
      attachmentId = attachmentId,
      mmsId = messageId,
      hasData = false,
      transferProgress = AttachmentTable.TRANSFER_PROGRESS_STARTED
    )

    AttachmentDownloadJob(messageId, attachmentId, true).doWork()

    verify(exactly = 1) { attachments.setTransferState(messageId, attachmentId, AttachmentTable.TRANSFER_PROGRESS_PENDING) }
    verify(exactly = 0) { attachments.getOrCreateTransferFile(any()) }
  }

  @Test
  fun queuedArchiveRestoreWaitsForAdmission() {
    every { attachments.getAttachment(attachmentId) } returns FakeMessageRecords.buildDatabaseAttachment(
      attachmentId = attachmentId,
      mmsId = messageId,
      hasData = false,
      transferProgress = AttachmentTable.TRANSFER_RESTORE_IN_PROGRESS
    )

    RestoreAttachmentJob.forOffloadedRestore(attachmentId, messageId, null).doWork()

    verify(exactly = 1) { attachments.setTransferState(messageId, attachmentId, AttachmentTable.TRANSFER_RESTORE_OFFLOADED) }
    verify(exactly = 0) { attachments.getOrCreateTransferFile(any()) }
  }

  @Test
  fun queuedArchiveThumbnailWaitsForAdmission() {
    every { attachments.getAttachment(attachmentId) } returns FakeMessageRecords.buildDatabaseAttachment(
      attachmentId = attachmentId,
      mmsId = messageId,
      hasData = false,
      thumbnailRestoreState = AttachmentTable.ThumbnailRestoreState.IN_PROGRESS
    )

    RestoreAttachmentThumbnailJob(messageId, attachmentId, highPriority = true).onRun()

    verify(exactly = 1) { attachments.setThumbnailRestoreState(attachmentId, AttachmentTable.ThumbnailRestoreState.NEEDS_RESTORE) }
    verify(exactly = 0) { attachments.createArchiveThumbnailTransferFile() }
  }
}
