package org.thoughtcrime.securesms.backup.v2

import android.app.Application
import android.database.MatrixCursor
import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import io.mockk.every
import io.mockk.mockk
import okio.ByteString.Companion.toByteString
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.archive.proto.ChatItem
import org.signal.archive.proto.FilePointer
import org.signal.blurhash.BlurHash
import org.signal.core.models.database.AttachmentId
import org.signal.core.models.recipients.RecipientId
import org.signal.core.util.Base64
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.attachments.AttachmentMetadata
import org.thoughtcrime.securesms.attachments.Cdn
import org.thoughtcrime.securesms.attachments.DatabaseAttachment
import org.thoughtcrime.securesms.attachments.LocalBackupKey
import org.thoughtcrime.securesms.attachments.TombstoneAttachment
import org.thoughtcrime.securesms.audio.AudioHash
import org.thoughtcrime.securesms.backup.v2.exporters.BackupMessageRecord
import org.thoughtcrime.securesms.backup.v2.exporters.ChatItemArchiveExporter
import org.thoughtcrime.securesms.backup.v2.util.toLocalAttachment
import org.thoughtcrime.securesms.backup.v2.util.toRemoteFilePointer
import org.thoughtcrime.securesms.database.AttachmentTable
import org.thoughtcrime.securesms.database.MessageTable
import org.thoughtcrime.securesms.database.MessageTypes
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.testutil.SystemOutLogger

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class RemovedAttachmentArchiveTest {

  @Test
  fun `removed attachments never export stale archive transit or local recovery data`() {
    // Deliberately retain stale fields in this snapshot: the terminal state must win over all of them.
    val removed = attachment()

    for (mode in BackupMode.entries) {
      val pointer = removed.toRemoteFilePointer(backupMode = mode)

      assertThat(pointer.locatorInfo).isEqualTo(FilePointer.LocatorInfo())
      assertThat(pointer.incrementalMac).isNull()
      assertThat(pointer.incrementalMacChunkSize).isNull()
      assertThat(pointer.blurHash).isNull()
      assertThat(pointer.audioWaveform).isNull()
      assertThat(pointer.audioDurationSeconds).isNull()
      assertThat(pointer.contentType).isEqualTo("image/jpeg")
      assertThat(pointer.fileName).isEqualTo("holiday.jpg")
      assertThat(pointer.caption).isEqualTo("Keep this caption")
    }
  }

  @Test
  fun `removed attachment archive restores an unavailable marker without a download pointer`() {
    val pointer = attachment().toRemoteFilePointer(backupMode = BackupMode.LOCAL)
    val restored = requireNotNull(pointer.toLocalAttachment(wasDownloaded = true))

    assertThat(restored is TombstoneAttachment).isTrue()
    assertThat(restored.isPermanentlyFailed).isTrue()
    assertThat(restored.isInProgress).isFalse()
    assertThat(restored.uri).isNull()
    assertThat(restored.thumbnailUri).isNull()
    assertThat(restored.remoteLocation).isNull()
    assertThat(restored.remoteKey).isNull()
    assertThat(restored.remoteDigest).isNull()
    assertThat(restored.contentType).isEqualTo("image/jpeg")
    assertThat(restored.fileName).isEqualTo("holiday.jpg")
    assertThat(restored.caption).isEqualTo("Keep this caption")
  }

  @Test
  fun `ordinary archived attachment keeps its local backup key and archive locator`() {
    val pointer = attachment(transferState = AttachmentTable.TRANSFER_PROGRESS_DONE)
      .toRemoteFilePointer(backupMode = BackupMode.LOCAL)

    assertThat(pointer.locatorInfo?.key).isEqualTo(REMOTE_KEY.toByteString())
    assertThat(pointer.locatorInfo?.plaintextHash).isEqualTo(PLAINTEXT_HASH.toByteString())
    assertThat(pointer.locatorInfo?.localKey).isEqualTo(LOCAL_KEY.toByteString())
    assertThat(pointer.locatorInfo?.mediaTierCdnNumber).isEqualTo(3)
    assertThat(pointer.locatorInfo?.transitCdnKey).isEqualTo("transit-object")
    assertThat(pointer.blurHash).isNotNull()
    assertThat(pointer.incrementalMac).isNotNull()
  }

  @Test
  fun `ordinary transit attachment still exports its transit locator`() {
    val pointer = attachment(
      transferState = AttachmentTable.TRANSFER_PROGRESS_DONE,
      dataHash = null,
      archiveState = AttachmentTable.ArchiveTransferState.NONE
    ).toRemoteFilePointer(backupMode = BackupMode.REMOTE)

    assertThat(pointer.locatorInfo?.transitCdnKey).isEqualTo("transit-object")
    assertThat(pointer.locatorInfo?.encryptedDigest).isEqualTo(ENCRYPTED_DIGEST.toByteString())
    assertThat(pointer.locatorInfo?.plaintextHash).isNull()
    assertThat(pointer.locatorInfo?.localKey).isNull()
  }

  @Test
  fun `media only message remains in archive after its final attachment is removed`() {
    val item = requireNotNull(exportMessage(body = null))
    val message = requireNotNull(item.standardMessage)

    assertThat(item.authorId).isEqualTo(2L)
    assertThat(item.chatId).isEqualTo(10L)
    assertThat(item.dateSent).isEqualTo(1_000L)
    assertThat(message.text).isNull()
    assertThat(message.attachments).hasSize(1)
    assertThat(message.attachments.single().pointer?.locatorInfo).isEqualTo(FilePointer.LocatorInfo())
    assertThat(message.attachments.single().wasDownloaded).isFalse()
  }

  @Test
  fun `message text is exported unchanged alongside the removed attachment marker`() {
    val body = "Keep the full text, including\nline breaks and emoji 🐕."
    val item = requireNotNull(exportMessage(body))
    val message = requireNotNull(item.standardMessage)

    assertThat(message.text?.body).isEqualTo(body)
    assertThat(message.attachments).hasSize(1)
    assertThat(message.attachments.single().pointer?.locatorInfo).isEqualTo(FilePointer.LocatorInfo())
  }

  private fun exportMessage(body: String?): ChatItem? {
    val db = mockk<SignalDatabase>(relaxed = true)
    every { db.mentionTable.getMentionsForMessages(any()) } returns emptyMap()
    every { db.reactionTable.getReactionsForMessages(any()) } returns emptyMap()
    every { db.attachmentTable.getAttachmentsForMessagesArchive(any()) } returns mapOf(1L to listOf(attachment()))
    every { db.groupReceiptTable.getGroupReceiptInfoForMessages(any()) } returns emptyMap()
    every { db.pollTable.getPollsForMessages(any(), any()) } returns emptyMap()

    val exportState = ExportState(backupTime = 2_000, backupMode = BackupMode.LOCAL, selfRecipientId = RecipientId.from(1)).apply {
      recipientIds += setOf(1L, 2L)
      contactRecipientIds += setOf(1L, 2L)
      threadIds += 10L
      threadIdToRecipientId[10L] = 2L
      recipientIdToE164[2L] = 1_555_000_0002L
    }

    return ChatItemArchiveExporter(
      db = db,
      selfRecipientId = RecipientId.from(1),
      noteToSelfThreadId = 99L,
      backupStartTime = 2_000,
      batchSize = 100,
      exportState = exportState,
      cursorGenerator = { _, _ -> MatrixCursor(arrayOf(MessageTable.ID)) }
    ).use { exporter ->
      exporter.records = linkedMapOf(1L to message(body))
      exporter.next()
    }
  }

  private fun message(body: String?) = BackupMessageRecord(
    id = 1L,
    dateSent = 1_000,
    dateReceived = 1_001,
    dateServer = 1_000,
    type = MessageTypes.BASE_INBOX_TYPE or MessageTypes.SECURE_MESSAGE_BIT or MessageTypes.PUSH_MESSAGE_BIT,
    threadId = 10L,
    body = body,
    bodyRanges = null,
    fromRecipientId = 2L,
    toRecipientId = 1L,
    expiresIn = 0,
    expireStarted = 0,
    sealedSender = false,
    linkPreview = null,
    sharedContacts = null,
    quoteTargetSentTimestamp = 0,
    quoteAuthor = 0,
    quoteBody = null,
    quoteMissing = false,
    quoteBodyRanges = null,
    quoteType = 0,
    originalMessageId = null,
    parentStoryId = 0,
    latestRevisionId = null,
    hasDeliveryReceipt = false,
    hasReadReceipt = false,
    viewed = false,
    receiptTimestamp = 0,
    read = true,
    networkFailureRecipientIds = emptySet(),
    identityMismatchRecipientIds = emptySet(),
    baseType = MessageTypes.BASE_INBOX_TYPE,
    messageExtras = null,
    viewOnce = false,
    pinnedAt = 0,
    pinnedUntil = 0,
    deletedBy = null,
    messageExtrasSize = 0
  )

  private fun attachment(
    transferState: Int = AttachmentTable.TRANSFER_REMOVED_LOCALLY,
    dataHash: String? = Base64.encodeWithPadding(PLAINTEXT_HASH),
    archiveState: AttachmentTable.ArchiveTransferState = AttachmentTable.ArchiveTransferState.FINISHED
  ) = DatabaseAttachment(
    attachmentId = AttachmentId(1L),
    mmsId = 1L,
    hasData = true,
    hasThumbnail = true,
    contentType = "image/jpeg",
    transferProgress = transferState,
    size = 1_000L,
    fileName = "holiday.jpg",
    cdn = Cdn.CDN_3,
    location = "transit-object",
    key = Base64.encodeWithPadding(REMOTE_KEY),
    digest = ENCRYPTED_DIGEST,
    incrementalDigest = byteArrayOf(1, 2, 3),
    incrementalMacChunkSize = 1024,
    fastPreflightId = null,
    voiceNote = false,
    borderless = false,
    videoGif = false,
    width = 320,
    height = 240,
    quote = false,
    caption = "Keep this caption",
    stickerLocator = null,
    blurHash = BlurHash.parseOrNull("LEHV6nWB2yk8pyo0adR*.7kCMdnj"),
    audioHash = AudioHash.fromSenderProvided("audio/aac", byteArrayOf(1, 2, 3), 1.5f),
    transformProperties = null,
    displayOrder = 0,
    uploadTimestamp = 1_000,
    dataHash = dataHash,
    archiveCdn = 3,
    thumbnailRestoreState = AttachmentTable.ThumbnailRestoreState.FINISHED,
    archiveTransferState = archiveState,
    archiveThumbnailTransferState = AttachmentTable.ArchiveTransferState.FINISHED,
    uuid = null,
    quoteTargetContentType = null,
    metadata = AttachmentMetadata(LocalBackupKey(LOCAL_KEY))
  )

  companion object {
    private val REMOTE_KEY = ByteArray(64) { 1 }
    private val LOCAL_KEY = ByteArray(64) { 2 }
    private val PLAINTEXT_HASH = ByteArray(32) { 3 }
    private val ENCRYPTED_DIGEST = ByteArray(32) { 4 }

    @BeforeClass
    @JvmStatic
    fun setUpClass() {
      Log.initialize(SystemOutLogger())
    }
  }
}
