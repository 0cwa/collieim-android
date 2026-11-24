/*
 * Copyright 2026 Collie contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.database

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import androidx.test.core.app.ApplicationProvider
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.database.exists
import org.signal.core.database.select
import org.signal.core.database.update
import org.signal.core.models.database.AttachmentId
import org.signal.core.models.recipients.RecipientId
import org.signal.core.util.Base64
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.jobs.AttachmentDownloadJob
import org.thoughtcrime.securesms.mms.MediaStream
import org.thoughtcrime.securesms.testutil.RecipientTestRule
import org.thoughtcrime.securesms.testutil.SystemOutLogger
import org.thoughtcrime.securesms.util.MediaUtil
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.util.UUID

@Suppress("ClassName")
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class AttachmentTableTest_removeLocally {

  @get:Rule val recipients = RecipientTestRule()
  @get:Rule val files = TemporaryFolder()

  private val db get() = SignalDatabase.writableDatabase
  private val attachments get() = SignalDatabase.attachments
  private lateinit var sender: RecipientId
  private var threadId = -1L

  companion object {
    @BeforeClass
    @JvmStatic
    fun initializeLog() = Log.initialize(SystemOutLogger())
  }

  @Before
  fun setUp() {
    sender = recipients.createRecipient("Attachment sender")
    threadId = SignalDatabase.threads.getOrCreateThreadIdFor(sender, false)
    every { AppDependencies.jobManager.find(any()) } returns emptyList()
  }

  @Test
  fun `removal preserves message and attachment identity but clears every source of bytes`() {
    val messageId = message("Keep this caption")
    val data = file()
    val thumbnail = file()
    val transfer = file()
    val metadataId = SignalDatabase.attachmentMetadata.insert("metadata-hash", ByteArray(32) { 7 })
    val id = attachment(messageId, data = data, thumbnail = thumbnail, transfer = transfer, metadataId = metadataId)
    val beforeMessage = row(MessageTable.TABLE_NAME, messageId)
    val original = attachments.getAttachment(id)!!
    clearMocks(AppDependencies.jobManager, answers = false)

    assertTrue(remove(id))

    assertEquals(beforeMessage, row(MessageTable.TABLE_NAME, messageId))
    val removed = attachments.getAttachment(id)!!
    assertTrue(removed.isLocallyRemoved)
    assertTrue(removed.isPermanentlyFailed)
    assertFalse(removed.isInProgress)
    assertFalse(removed.hasData)
    assertNull(removed.uri)
    assertNull(removed.thumbnailUri)
    assertEquals(original.mmsId, removed.mmsId)
    assertEquals(original.contentType, removed.contentType)
    assertEquals(original.fileName, removed.fileName)
    assertEquals(original.caption, removed.caption)
    assertEquals(original.uuid, removed.uuid)
    assertEquals(0L, removed.size)
    assertEquals(AttachmentTable.ArchiveTransferState.PERMANENT_FAILURE, removed.archiveTransferState)
    val stored = row(AttachmentTable.TABLE_NAME, id.id)!!
    listOf(
      AttachmentTable.DATA_FILE, AttachmentTable.DATA_RANDOM, AttachmentTable.DATA_HASH_START, AttachmentTable.DATA_HASH_END,
      AttachmentTable.THUMBNAIL_FILE, AttachmentTable.THUMBNAIL_RANDOM, AttachmentTable.TRANSFER_FILE,
      AttachmentTable.REMOTE_KEY, AttachmentTable.REMOTE_LOCATION, AttachmentTable.REMOTE_DIGEST, AttachmentTable.REMOTE_INCREMENTAL_DIGEST,
      AttachmentTable.ARCHIVE_CDN, AttachmentTable.BLUR_HASH, AttachmentTable.METADATA_ID
    ).forEach { assertNull(it, stored[it]) }
    assertFalse(data.exists())
    assertFalse(thumbnail.exists())
    assertFalse(transfer.exists())
    assertFalse(db.exists(AttachmentMetadataTable.TABLE_NAME).where("${AttachmentMetadataTable.ID} = ?", metadataId).run())
    verify { AppDependencies.jobManager.cancelAllInQueue(AttachmentDownloadJob.constructQueueString(id)) }
    verify(exactly = 0) { AppDependencies.jobManager.add(any<org.thoughtcrime.securesms.jobmanager.Job>()) }
  }

  @Test
  fun `media-only message retains a terminal part instead of an empty message`() {
    val messageId = message("")
    val id = attachment(messageId)

    assertTrue(remove(id))

    assertNotNull(row(MessageTable.TABLE_NAME, messageId))
    assertEquals("", row(MessageTable.TABLE_NAME, messageId)!![MessageTable.BODY])
    assertEquals(listOf(id), attachments.getAttachmentsForMessage(messageId).map { it.attachmentId })
    assertTrue(attachments.getAttachment(id)!!.isLocallyRemoved)
  }

  @Test
  fun `selected album part is removed without touching another part or message copy`() {
    val sharedData = file()
    val messageId = message("Album")
    val first = attachment(messageId, data = sharedData)
    val second = attachment(messageId, data = sharedData)
    val copy = attachment(message("Forwarded copy"), data = sharedData)
    val beforeSecond = row(AttachmentTable.TABLE_NAME, second.id)
    val beforeCopy = row(AttachmentTable.TABLE_NAME, copy.id)

    assertTrue(remove(first))

    assertTrue(sharedData.exists())
    assertEquals(beforeSecond, row(AttachmentTable.TABLE_NAME, second.id))
    assertEquals(beforeCopy, row(AttachmentTable.TABLE_NAME, copy.id))
    assertTrue(remove(second))
    assertTrue(sharedData.exists())
    assertTrue(remove(copy))
    assertFalse(sharedData.exists())
  }

  @Test
  fun `a file still used as another thumbnail is retained`() {
    val shared = file()
    val selected = attachment(message(), data = shared)
    val other = attachment(message(), thumbnail = shared)

    assertTrue(remove(selected))

    assertTrue(shared.exists())
    assertEquals(shared.absolutePath, row(AttachmentTable.TABLE_NAME, other.id)!![AttachmentTable.THUMBNAIL_FILE])
  }

  @Test
  fun `missing and repeated selections are no-ops`() {
    val id = attachment(message())
    assertFalse(remove(AttachmentId(Long.MAX_VALUE)))
    assertTrue(remove(id))
    val after = row(AttachmentTable.TABLE_NAME, id.id)
    assertFalse(remove(id))
    assertFalse(attachments.isAttachmentLocallyRemovable(id))
    assertEquals(after, row(AttachmentTable.TABLE_NAME, id.id))
  }

  @Test
  fun `quote sticker and long-text parts are ineligible`() {
    val longText = attachment(message(), contentType = MediaUtil.LONG_TEXT)
    val quote = attachment(message())
    val sticker = attachment(message(), contentType = "image/webp")
    db.update(AttachmentTable.TABLE_NAME).values(AttachmentTable.QUOTE to 1).where("${AttachmentTable.ID} = ?", quote.id).run()
    db.update(AttachmentTable.TABLE_NAME)
      .values(AttachmentTable.STICKER_PACK_ID to "pack", AttachmentTable.STICKER_PACK_KEY to "key", AttachmentTable.STICKER_ID to 1)
      .where("${AttachmentTable.ID} = ?", sticker.id).run()

    listOf(longText, quote, sticker).forEach { id ->
      val before = row(AttachmentTable.TABLE_NAME, id.id)
      assertFalse(remove(id))
      assertEquals(before, row(AttachmentTable.TABLE_NAME, id.id))
    }
  }

  @Test
  fun `view-once story scheduled preview and contact messages are ineligible`() {
    val cases = listOf(
      MessageTable.VIEW_ONCE to 1,
      MessageTable.STORY_TYPE to 1,
      MessageTable.PARENT_STORY_ID to 3,
      MessageTable.SCHEDULED_DATE to 1234,
      MessageTable.LINK_PREVIEWS to "[]",
      MessageTable.SHARED_CONTACTS to "[]"
    )
    cases.forEach { (column, value) ->
      val messageId = message()
      val id = attachment(messageId)
      db.update(MessageTable.TABLE_NAME).values(column to value).where("${MessageTable.ID} = ?", messageId).run()
      assertFalse("Unexpected eligibility for $column", attachments.isAttachmentLocallyRemovable(id))
      assertFalse(remove(id))
    }
  }

  @Test
  fun `pending and failed outgoing messages cannot lose sendable data`() {
    listOf(MessageTypes.BASE_OUTBOX_TYPE, MessageTypes.BASE_SENDING_TYPE, MessageTypes.BASE_SENT_FAILED_TYPE).forEach { type ->
      val id = attachment(message(type = type))
      assertFalse(remove(id))
    }
    assertTrue(remove(attachment(message(type = MessageTypes.BASE_SENT_TYPE))))
  }

  @Test
  fun `a stale selection cannot mutate an earlier revision`() {
    val original = message()
    val selected = attachment(original)
    assertTrue(attachments.isAttachmentLocallyRemovable(selected))
    val latest = message("Edited")
    db.update(MessageTable.TABLE_NAME).values(MessageTable.LATEST_REVISION_ID to latest).where("${MessageTable.ID} = ?", original).run()

    assertFalse(remove(selected))
    assertFalse(attachments.getAttachment(selected)!!.isLocallyRemoved)
  }

  @Test
  fun `shared metadata survives until its final reference is removed`() {
    val metadataId = SignalDatabase.attachmentMetadata.insert("shared", ByteArray(32) { 9 })
    val first = attachment(message(), metadataId = metadataId)
    val second = attachment(message(), metadataId = metadataId)
    assertTrue(remove(first))
    assertTrue(db.exists(AttachmentMetadataTable.TABLE_NAME).where("${AttachmentMetadataTable.ID} = ?", metadataId).run())
    assertTrue(remove(second))
    assertFalse(db.exists(AttachmentMetadataTable.TABLE_NAME).where("${AttachmentMetadataTable.ID} = ?", metadataId).run())
  }

  @Test
  fun `queued state changes and backfill cannot re-arm locally removed data`() {
    val messageId = message()
    val id = attachment(messageId)
    val old = attachments.getAttachment(id)!!
    assertTrue(remove(id))

    attachments.setTransferState(messageId, id, AttachmentTable.TRANSFER_PROGRESS_STARTED)
    attachments.setTransferProgressPermanentFailure(id, messageId)
    attachments.setRestoreTransferState(id, AttachmentTable.TRANSFER_NEEDS_RESTORE)
    attachments.setRestoreTransferState(id, AttachmentTable.TRANSFER_PROGRESS_PERMANENT_FAILURE)
    attachments.setThumbnailRestoreState(id, AttachmentTable.ThumbnailRestoreState.NEEDS_RESTORE)
    attachments.markAttachmentUploaded(messageId, old)
    assertFalse(attachments.updatePointerFromBackfill(id, old))
    assertNull(attachments.getUploadCandidate(id))
    assertNull(AttachmentDownloadJob.downloadAttachmentIfNeeded(attachments.getAttachment(id)!!))
    assertThrows(IOException::class.java) { attachments.getOrCreateTransferFile(id) }
    assertTrue(attachments.getAttachment(id)!!.isLocallyRemoved)
    assertNull(attachments.getAttachment(id)!!.remoteKey)
    assertEquals(AttachmentTable.ThumbnailRestoreState.PERMANENT_FAILURE, attachments.getAttachment(id)!!.thumbnailRestoreState)
  }

  @Test
  fun `download that completes after removal cannot publish bytes or leave its output file`() {
    val messageId = message()
    val id = attachment(messageId)
    val beforeFiles = partFiles()
    val input = object : ByteArrayInputStream(ByteArray(128) { 13 }) {
      private var removed = false
      override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (!removed) {
          removed = true
          assertTrue(remove(id))
        }
        return super.read(buffer, offset, length)
      }
    }

    attachments.finalizeAttachmentAfterDownload(messageId, id, input)

    assertTrue(attachments.getAttachment(id)!!.isLocallyRemoved)
    assertNull(attachments.getDataFileInfo(id))
    assertEquals(beforeFiles, partFiles())
  }

  @Test
  fun `stale thumbnail completion discards unreferenced output and transfer files`() {
    val id = attachment(message())
    val old = attachments.getAttachment(id)!!
    assertTrue(remove(id))
    val beforeFiles = partFiles()
    val transfer = file()

    attachments.finalizeAttachmentThumbnailAfterDownload(id, old.dataHash, old.remoteKey, ByteArrayInputStream(byteArrayOf(1, 2, 3)), transfer)
    attachments.finalizeAttachmentThumbnailAfterUpload(id, old.dataHash, old.remoteKey, byteArrayOf(4, 5, 6))

    assertFalse(transfer.exists())
    assertEquals(beforeFiles, partFiles())
    assertNull(attachments.getThumbnailFileInfo(id))
    assertTrue(attachments.getAttachment(id)!!.isLocallyRemoved)
  }

  @Test
  fun `copying another attachment cannot fill a removed destination`() {
    val source = attachment(message(), data = file())
    val destination = attachment(message())
    assertTrue(remove(destination))

    attachments.copyAttachmentData(source, destination)

    assertTrue(attachments.getAttachment(destination)!!.isLocallyRemoved)
    assertNull(attachments.getDataFileInfo(destination))
  }

  @Test
  fun `cancellation failure does not prevent committed removal cleanup or refresh`() {
    val data = file()
    val messageId = message("Retained text")
    val id = attachment(messageId, data = data)
    every { AppDependencies.jobManager.find(any()) } throws IllegalStateException("Queue unavailable")

    assertTrue(remove(id))

    assertTrue(attachments.getAttachment(id)!!.isLocallyRemoved)
    assertFalse(data.exists())
    assertEquals(messageId, SignalDatabase.threads.getThreadRecord(threadId)!!.snippetMessageId)
    assertNull(SignalDatabase.threads.getThreadRecord(threadId)!!.snippetUri)
  }

  @Test
  fun `terminal rows never expose data or thumbnail URIs even if stale flags remain`() {
    val id = attachment(message(), contentType = "image/jpeg", data = file(), thumbnail = file())
    db.update(AttachmentTable.TABLE_NAME).values(AttachmentTable.TRANSFER_STATE to AttachmentTable.TRANSFER_REMOVED_LOCALLY)
      .where("${AttachmentTable.ID} = ?", id.id).run()

    val removed = attachments.getAttachment(id)!!

    assertTrue(removed.hasData)
    assertNull(removed.uri)
    assertNull(removed.publicUri)
    assertNull(removed.thumbnailUri)
    assertNull(removed.displayUri)
    assertFalse(removed.isMediaNoLongerAvailableForDownload)
  }

  @Test
  fun `transformation finishing after removal cannot publish a replacement file`() {
    val id = attachment(message(), data = file())
    val original = attachments.getAttachment(id)!!
    val beforeFiles = partFiles()

    attachments.updateAttachmentDataForTest(original, MediaStream(ByteArrayInputStream(byteArrayOf(1, 2, 3)), "application/pdf", 0, 0)) {
      assertTrue(remove(id))
    }

    assertTrue(attachments.getAttachment(id)!!.isLocallyRemoved)
    assertNull(attachments.getDataFileInfo(id))
    assertEquals(beforeFiles, partFiles())
  }

  private fun message(body: String = "Message text", type: Long = MessageTypes.BASE_INBOX_TYPE): Long = TestSms.insert(
    db = db,
    sender = sender,
    threadId = threadId,
    encodedBody = body,
    expiresInMillis = 60_000,
    type = type
  )

  private fun attachment(
    messageId: Long,
    contentType: String = "application/pdf",
    data: File? = null,
    thumbnail: File? = null,
    transfer: File? = null,
    metadataId: Long? = null
  ): AttachmentId = AttachmentId(db.insert(AttachmentTable.TABLE_NAME, null, ContentValues().apply {
    put(AttachmentTable.MESSAGE_ID, messageId)
    put(AttachmentTable.CONTENT_TYPE, contentType)
    put(AttachmentTable.TRANSFER_STATE, AttachmentTable.TRANSFER_PROGRESS_DONE)
    put(AttachmentTable.DATA_SIZE, 128L)
    put(AttachmentTable.DATA_FILE, data?.absolutePath)
    put(AttachmentTable.DATA_RANDOM, ByteArray(32) { 5 })
    put(AttachmentTable.THUMBNAIL_FILE, thumbnail?.absolutePath)
    put(AttachmentTable.THUMBNAIL_RANDOM, ByteArray(32) { 6 })
    put(AttachmentTable.TRANSFER_FILE, transfer?.absolutePath)
    put(AttachmentTable.FILE_NAME, "document.pdf")
    put(AttachmentTable.CAPTION, "Attachment caption")
    put(AttachmentTable.ATTACHMENT_UUID, UUID.randomUUID().toString())
    put(AttachmentTable.REMOTE_LOCATION, "remote-object")
    put(AttachmentTable.REMOTE_KEY, Base64.encodeWithPadding(ByteArray(64) { 7 }))
    put(AttachmentTable.REMOTE_DIGEST, ByteArray(32) { 8 })
    put(AttachmentTable.REMOTE_INCREMENTAL_DIGEST, ByteArray(32) { 9 })
    put(AttachmentTable.DATA_HASH_START, "hash")
    put(AttachmentTable.DATA_HASH_END, "hash")
    put(AttachmentTable.ARCHIVE_CDN, 3)
    put(AttachmentTable.ARCHIVE_TRANSFER_STATE, AttachmentTable.ArchiveTransferState.FINISHED.value)
    put(AttachmentTable.ARCHIVE_THUMBNAIL_TRANSFER_STATE, AttachmentTable.ArchiveTransferState.FINISHED.value)
    put(AttachmentTable.THUMBNAIL_RESTORE_STATE, AttachmentTable.ThumbnailRestoreState.FINISHED.value)
    if (metadataId != null) put(AttachmentTable.METADATA_ID, metadataId)
  }))

  private fun remove(id: AttachmentId): Boolean = runBlocking(Dispatchers.IO) { attachments.removeAttachmentLocally(id) }

  private fun file(): File = files.newFile().apply { writeBytes(ByteArray(128) { 1 }) }

  private fun partFiles(): Set<String> = ApplicationProvider.getApplicationContext<Context>().getDir("parts", Context.MODE_PRIVATE)
    .listFiles().orEmpty().map { it.absolutePath }.toSet()

  private fun row(table: String, id: Long): Map<String, Any?>? = db.select().from(table).where("_id = ?", id).run().use { cursor ->
    if (!cursor.moveToFirst()) return@use null
    cursor.columnNames.mapIndexed { index, name ->
      name to when (cursor.getType(index)) {
        Cursor.FIELD_TYPE_NULL -> null
        Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
        Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(index)
        Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(index).toList()
        else -> cursor.getString(index)
      }
    }.toMap()
  }
}
