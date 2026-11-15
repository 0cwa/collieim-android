package org.thoughtcrime.securesms.database

import android.content.Context
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.FlakyTest
import androidx.test.platform.app.InstrumentationRegistry
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotEmpty
import assertk.assertions.isNotEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.signal.core.database.update
import org.signal.core.models.backup.MediaName
import org.signal.core.models.database.AttachmentId
import org.signal.core.models.media.SentMediaQuality
import org.signal.core.models.media.TransformProperties
import org.signal.network.api.AttachmentUploadResult
import org.signal.core.util.Base64
import org.signal.core.util.Base64.decodeBase64OrThrow
import org.signal.core.util.copyTo
import org.signal.core.util.stream.NullOutputStream
import org.thoughtcrime.securesms.attachments.ArchivedAttachment
import org.thoughtcrime.securesms.attachments.Attachment
import org.thoughtcrime.securesms.attachments.PointerAttachment
import org.thoughtcrime.securesms.attachments.UriAttachment
import org.thoughtcrime.securesms.backup.v2.ArchivedMediaObject
import org.thoughtcrime.securesms.database.model.databaseprotos.AudioWaveFormData
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.mms.IncomingMessage
import org.thoughtcrime.securesms.mms.MediaStream
import org.thoughtcrime.securesms.testing.SignalActivityRule
import org.thoughtcrime.securesms.util.MediaUtil
import org.whispersystems.signalservice.api.crypto.AttachmentCipherOutputStream
import org.whispersystems.signalservice.api.crypto.NoCipherOutputStream
import org.whispersystems.signalservice.api.messages.SignalServiceAttachmentPointer
import org.whispersystems.signalservice.api.messages.SignalServiceAttachmentRemoteId
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Optional
import java.util.UUID
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@RunWith(AndroidJUnit4::class)
class AttachmentTableTest {

  companion object {
    private const val TEST_PAGE_SIZE = 3
  }

  @get:Rule
  val harness = SignalActivityRule(othersCount = 10)

  @Before
  fun setUp() {
    SignalDatabase.attachments.deleteAllAttachments()
  }

  @Test
  fun givenABlob_whenIInsert2AttachmentsForPreUpload_thenIExpectDistinctIdsButSameFileName() {
    val blob = AppDependencies.blobs.forData(byteArrayOf(1, 2, 3, 4, 5)).createForSingleSessionInMemory()
    val highQualityProperties = createHighQualityTransformProperties()
    val highQualityImage = createAttachment(1, blob, highQualityProperties)
    val attachment = SignalDatabase.attachments.insertAttachmentForPreUpload(highQualityImage)
    val attachment2 = SignalDatabase.attachments.insertAttachmentForPreUpload(highQualityImage)

    assertNotEquals(attachment2.attachmentId, attachment.attachmentId)
    assertEquals(attachment2.fileName, attachment.fileName)
  }

  @Test
  fun transformedFileKeepsOldReadersStableAndFinalizationRejectsStaleVersion() {
    val original = byteArrayOf(1, 2, 3, 4, 5)
    val blob = AppDependencies.blobs.forData(original).createForSingleSessionInMemory()
    val source = createAttachment(1, blob, createHighQualityTransformProperties())
    val first = SignalDatabase.attachments.insertAttachmentForPreUpload(source)
    val second = SignalDatabase.attachments.insertAttachmentForPreUpload(source)
    val old = SignalDatabase.attachments.getDataFileInfo(first.attachmentId)!!
    val oldCandidate = SignalDatabase.attachments.getUploadCandidate(first.attachmentId)!!
    val shared = SignalDatabase.attachments.getDataFileInfo(second.attachmentId)!!
    assertEquals(old.file, shared.file)
    val oldStream = SignalDatabase.attachments.getAttachmentStream(first.attachmentId, 0)
    val replacement = byteArrayOf(8, 7, 6)
    SignalDatabase.attachments.updateAttachmentData(first, createMediaStream(replacement))
    val current = SignalDatabase.attachments.getDataFileInfo(first.attachmentId)!!
    val currentShared = SignalDatabase.attachments.getDataFileInfo(second.attachmentId)!!
    assertNotEquals(old.file, current.file)
    assertEquals(old.file, currentShared.file)
    assertThat(oldStream.use { it.readBytes().toList() }).isEqualTo(original.toList())
    assertThat(SignalDatabase.attachments.getAttachmentStream(first.attachmentId, 0).use { it.readBytes().toList() }).isEqualTo(replacement.toList())
    val result = AttachmentUploadResult(SignalServiceAttachmentRemoteId.V4("test"), 3, ByteArray(64), ByteArray(32), null, 0, 3, 1, null, null)
    assertThat(SignalDatabase.attachments.finalizeAttachmentAfterSnapshotUpload(oldCandidate, result, "old")).isFalse()
    assertThat(SignalDatabase.attachments.finalizeAttachmentAfterSnapshotUpload(SignalDatabase.attachments.getUploadCandidate(first.attachmentId)!!, result, "current")).isTrue()
    assertEquals("current", SignalDatabase.attachments.getDataFileInfo(first.attachmentId)!!.hashEnd)
    assertNotEquals("current", SignalDatabase.attachments.getDataFileInfo(second.attachmentId)!!.hashEnd)
  }

  @Test
  fun lateTransformDoesNotReplaceAlreadyFinalizedUpload() {
    val original = byteArrayOf(1, 2, 3, 4, 5)
    val blob = AppDependencies.blobs.forData(original).createForSingleSessionInMemory()
    val attachment = SignalDatabase.attachments.insertAttachmentForPreUpload(createAttachment(1, blob, createHighQualityTransformProperties()))
    val version = SignalDatabase.attachments.getDataFileInfo(attachment.attachmentId)!!
    val result = AttachmentUploadResult(SignalServiceAttachmentRemoteId.V4("test"), 3, ByteArray(64), ByteArray(32), null, 0, 5, 1, null, null)
    assertThat(SignalDatabase.attachments.finalizeAttachmentAfterSnapshotUpload(SignalDatabase.attachments.getUploadCandidate(attachment.attachmentId)!!, result, "original")).isTrue()
    SignalDatabase.attachments.updateAttachmentData(attachment, createMediaStream(byteArrayOf(8, 7, 6)))
    val after = SignalDatabase.attachments.getDataFileInfo(attachment.attachmentId)!!
    assertEquals(version.file, after.file)
    assertEquals("original", after.hashEnd)
    assertThat(SignalDatabase.attachments.getAttachmentStream(attachment.attachmentId, 0).use { it.readBytes().toList() }).isEqualTo(original.toList())
  }

  @Test
  fun intentionalTransformOfCompletedRowInvalidatesOldRemoteMetadata() {
    val blob = AppDependencies.blobs.forData(byteArrayOf(1, 2, 3, 4, 5)).createForSingleSessionInMemory()
    val attachment = SignalDatabase.attachments.insertAttachmentForPreUpload(createAttachment(1, blob, createHighQualityTransformProperties()))
    val before = SignalDatabase.attachments.getDataFileInfo(attachment.attachmentId)!!
    val result = AttachmentUploadResult(SignalServiceAttachmentRemoteId.V4("test"), 3, ByteArray(64), ByteArray(32), null, 0, 5, 1, null, null)
    assertThat(SignalDatabase.attachments.finalizeAttachmentAfterSnapshotUpload(SignalDatabase.attachments.getUploadCandidate(attachment.attachmentId)!!, result, "original")).isTrue()
    val completed = SignalDatabase.attachments.getAttachment(attachment.attachmentId)!!
    assertEquals(AttachmentTable.TRANSFER_PROGRESS_DONE, completed.transferState)
    SignalDatabase.attachments.updateAttachmentData(completed, createMediaStream(byteArrayOf(8, 7, 6)))
    val after = SignalDatabase.attachments.getAttachment(attachment.attachmentId)!!
    assertEquals(AttachmentTable.TRANSFER_PROGRESS_STARTED, after.transferState)
    assertThat(after.remoteLocation).isNull()
    assertThat(after.remoteDigest).isNull()
    assertThat(after.dataHash).isNull()
    assertNotEquals(before.file, SignalDatabase.attachments.getDataFileInfo(attachment.attachmentId)!!.file)
  }

  @Test
  fun snapshotFinalizationUpdatesOnlyInitiatingRow() {
    val blob = AppDependencies.blobs.forData(byteArrayOf(1, 2, 3, 4, 5)).createForSingleSessionInMemory()
    val source = createAttachment(1, blob, createHighQualityTransformProperties())
    val first = SignalDatabase.attachments.insertAttachmentForPreUpload(source)
    val second = SignalDatabase.attachments.insertAttachmentForPreUpload(source)
    val version = SignalDatabase.attachments.getDataFileInfo(first.attachmentId)!!
    assertEquals(version.file, SignalDatabase.attachments.getDataFileInfo(second.attachmentId)!!.file)
    val result = AttachmentUploadResult(SignalServiceAttachmentRemoteId.V4("test"), 3, ByteArray(64), ByteArray(32), null, 0, 5, 1, null, null)
    assertThat(SignalDatabase.attachments.finalizeAttachmentAfterSnapshotUpload(SignalDatabase.attachments.getUploadCandidate(first.attachmentId)!!, result, "hash")).isTrue()
    assertEquals("hash", SignalDatabase.attachments.getDataFileInfo(first.attachmentId)!!.hashEnd)
    assertNotEquals("hash", SignalDatabase.attachments.getDataFileInfo(second.attachmentId)!!.hashEnd)
  }

  @Test
  fun staleArchiveCopyCannotMarkNewMediaIdentityFinished() {
    val blob = AppDependencies.blobs.forData(byteArrayOf(1, 2, 3, 4, 5)).createForSingleSessionInMemory()
    val attachment = SignalDatabase.attachments.insertAttachmentForPreUpload(createAttachment(1, blob, createHighQualityTransformProperties()))
    val old = SignalDatabase.attachments.getDataFileInfo(attachment.attachmentId)!!
    val result = AttachmentUploadResult(SignalServiceAttachmentRemoteId.V4("test"), 3, ByteArray(64), ByteArray(32), null, 0, 5, 1, null, null)
    assertThat(SignalDatabase.attachments.finalizeAttachmentAfterSnapshotUpload(SignalDatabase.attachments.getUploadCandidate(attachment.attachmentId)!!, result, "old-hash")).isTrue()
    val oldKey = SignalDatabase.attachments.getAttachment(attachment.attachmentId)!!.remoteKey!!
    val completed = SignalDatabase.attachments.getAttachment(attachment.attachmentId)!!
    SignalDatabase.attachments.updateAttachmentData(completed, createMediaStream(byteArrayOf(8, 7, 6)))
    val next = SignalDatabase.attachments.getDataFileInfo(attachment.attachmentId)!!
    assertThat(SignalDatabase.attachments.finalizeAttachmentAfterSnapshotUpload(SignalDatabase.attachments.getUploadCandidate(attachment.attachmentId)!!, result, "new-hash")).isTrue()
    SignalDatabase.attachments.setArchiveCdnForIdentity("old-hash", oldKey, 3)
    assertNotEquals(AttachmentTable.ArchiveTransferState.FINISHED, SignalDatabase.attachments.getAttachment(attachment.attachmentId)!!.archiveTransferState)
    SignalDatabase.attachments.setArchiveCdnForIdentity("new-hash", oldKey, 3)
    assertEquals(AttachmentTable.ArchiveTransferState.FINISHED, SignalDatabase.attachments.getAttachment(attachment.attachmentId)!!.archiveTransferState)
    assertThat(SignalDatabase.attachments.setArchiveStateIfSourceUnchanged(attachment.attachmentId, next, AttachmentTable.ArchiveTransferState.TEMPORARY_FAILURE)).isFalse()
    assertThat(SignalDatabase.attachments.markArchivePendingIfEligible(attachment.attachmentId, AttachmentTable.ArchiveTransferState.COPY_PENDING)).isFalse()
  }

  @Test
  fun stalePendingTransformCannotOverwriteNewVersionAfterDoneToStartedTransition() {
    val blob = AppDependencies.blobs.forData(byteArrayOf(1, 2, 3, 4, 5)).createForSingleSessionInMemory()
    val attachment = SignalDatabase.attachments.insertAttachmentForPreUpload(createAttachment(1, blob, createHighQualityTransformProperties()))
    val old = SignalDatabase.attachments.getDataFileInfo(attachment.attachmentId)!!
    val result = AttachmentUploadResult(SignalServiceAttachmentRemoteId.V4("test"), 3, ByteArray(64), ByteArray(32), null, 0, 5, 1, null, null)
    SignalDatabase.attachments.updateAttachmentDataForTest(attachment, createMediaStream(byteArrayOf(9, 9, 9))) {
      assertThat(SignalDatabase.attachments.finalizeAttachmentAfterSnapshotUpload(SignalDatabase.attachments.getUploadCandidate(attachment.attachmentId)!!, result, "old")).isTrue()
      val done = SignalDatabase.attachments.getAttachment(attachment.attachmentId)!!
      SignalDatabase.attachments.updateAttachmentData(done, createMediaStream(byteArrayOf(7, 7, 7)))
    }
    val after = SignalDatabase.attachments.getAttachment(attachment.attachmentId)!!
    assertEquals(AttachmentTable.TRANSFER_PROGRESS_STARTED, after.transferState)
    assertThat(SignalDatabase.attachments.getAttachmentStream(attachment.attachmentId, 0).use { it.readBytes().toList() }).isEqualTo(listOf<Byte>(7, 7, 7))
  }

  @Test
  fun archiveStateCompletionChecksInitiatingRowNotOnlyAnOldAlias() {
    val blob = AppDependencies.blobs.forData(byteArrayOf(1, 2, 3, 4, 5)).createForSingleSessionInMemory()
    val source = createAttachment(1, blob, createHighQualityTransformProperties())
    val first = SignalDatabase.attachments.insertAttachmentForPreUpload(source)
    val alias = SignalDatabase.attachments.insertAttachmentForPreUpload(source)
    val old = SignalDatabase.attachments.getDataFileInfo(first.attachmentId)!!
    val result = AttachmentUploadResult(SignalServiceAttachmentRemoteId.V4("test"), 3, ByteArray(64), ByteArray(32), null, 0, 5, 1, null, null)
    assertThat(SignalDatabase.attachments.finalizeAttachmentAfterSnapshotUpload(SignalDatabase.attachments.getUploadCandidate(first.attachmentId)!!, result, "old")).isTrue()
    assertThat(SignalDatabase.attachments.finalizeAttachmentAfterSnapshotUpload(SignalDatabase.attachments.getUploadCandidate(alias.attachmentId)!!, result, "old")).isTrue()
    val done = SignalDatabase.attachments.getAttachment(first.attachmentId)!!
    val key = done.remoteKey!!
    SignalDatabase.attachments.updateAttachmentData(done, createMediaStream(byteArrayOf(7, 7, 7)))
    SignalDatabase.attachments.setArchiveCdnForIdentity("old", key, 3)
    assertThat(SignalDatabase.attachments.setArchiveTransferState(first.attachmentId, key, "old", AttachmentTable.ArchiveTransferState.FINISHED, notify = false)).isFalse()
    assertThat(SignalDatabase.attachments.setArchiveTransferStateFailureForIdentity(first.attachmentId, key, "old", AttachmentTable.ArchiveTransferState.TEMPORARY_FAILURE)).isFalse()
    assertNotEquals(AttachmentTable.ArchiveTransferState.FINISHED, SignalDatabase.attachments.getAttachment(first.attachmentId)!!.archiveTransferState)
    assertEquals(AttachmentTable.ArchiveTransferState.FINISHED, SignalDatabase.attachments.getAttachment(alias.attachmentId)!!.archiveTransferState)
  }

  @Test
  fun archiveStateCompletionDoesNotUpdateAliasWithDifferentMediaIdentity() {
    val blob = AppDependencies.blobs.forData(byteArrayOf(1, 2, 3, 4, 5)).createForSingleSessionInMemory()
    val source = createAttachment(1, blob, createHighQualityTransformProperties())
    val first = SignalDatabase.attachments.insertAttachmentForPreUpload(source)
    val alias = SignalDatabase.attachments.insertAttachmentForPreUpload(source)
    val version = SignalDatabase.attachments.getDataFileInfo(first.attachmentId)!!
    val result = AttachmentUploadResult(SignalServiceAttachmentRemoteId.V4("test"), 3, ByteArray(64), ByteArray(32), null, 0, 5, 1, null, null)
    assertThat(SignalDatabase.attachments.finalizeAttachmentAfterSnapshotUpload(SignalDatabase.attachments.getUploadCandidate(first.attachmentId)!!, result, "old")).isTrue()
    assertThat(SignalDatabase.attachments.finalizeAttachmentAfterSnapshotUpload(SignalDatabase.attachments.getUploadCandidate(alias.attachmentId)!!, result, "old")).isTrue()
    val key = SignalDatabase.attachments.getAttachment(first.attachmentId)!!.remoteKey!!
    SignalDatabase.rawDatabase.update(AttachmentTable.TABLE_NAME)
      .values(AttachmentTable.DATA_HASH_END to "other")
      .where("${AttachmentTable.ID} = ?", alias.attachmentId.id)
      .run()
    assertThat(SignalDatabase.attachments.setArchiveTransferState(first.attachmentId, key, "old", AttachmentTable.ArchiveTransferState.FINISHED, notify = false)).isTrue()
    assertNotEquals(AttachmentTable.ArchiveTransferState.FINISHED, SignalDatabase.attachments.getAttachment(alias.attachmentId)!!.archiveTransferState)
  }

  @Test
  fun transformCannotAdoptNewVersionPublishedBeforeItsUpdateCall() {
    val blob = AppDependencies.blobs.forData(byteArrayOf(1, 2, 3, 4, 5)).createForSingleSessionInMemory()
    val attachment = SignalDatabase.attachments.insertAttachmentForPreUpload(createAttachment(1, blob, createHighQualityTransformProperties()))
    val source = SignalDatabase.attachments.getDataFileInfo(attachment.attachmentId)!!
    val result = AttachmentUploadResult(SignalServiceAttachmentRemoteId.V4("test"), 3, ByteArray(64), ByteArray(32), null, 0, 5, 1, null, null)
    assertThat(SignalDatabase.attachments.finalizeAttachmentAfterSnapshotUpload(SignalDatabase.attachments.getUploadCandidate(attachment.attachmentId)!!, result, "old")).isTrue()
    val done = SignalDatabase.attachments.getAttachment(attachment.attachmentId)!!
    assertThat(SignalDatabase.attachments.updateAttachmentData(done, createMediaStream(byteArrayOf(7, 7, 7)))).isTrue()
    assertThat(SignalDatabase.attachments.updateAttachmentData(attachment, createMediaStream(byteArrayOf(9, 9, 9)), source, false)).isFalse()
    assertThat(SignalDatabase.attachments.getAttachmentStream(attachment.attachmentId, 0).use { it.readBytes().toList() }).isEqualTo(listOf<Byte>(7, 7, 7))
  }

  @Test
  fun reuseAndLengthRepairRejectChangedSourceVersion() {
    val blob = AppDependencies.blobs.forData(byteArrayOf(1, 2, 3, 4, 5)).createForSingleSessionInMemory()
    val attachment = SignalDatabase.attachments.insertAttachmentForPreUpload(createAttachment(1, blob, createHighQualityTransformProperties()))
    val old = SignalDatabase.attachments.getDataFileInfo(attachment.attachmentId)!!
    val result = AttachmentUploadResult(SignalServiceAttachmentRemoteId.V4("test"), 3, ByteArray(64), ByteArray(32), null, 0, 5, 1, null, null)
    assertThat(SignalDatabase.attachments.finalizeAttachmentAfterSnapshotUpload(SignalDatabase.attachments.getUploadCandidate(attachment.attachmentId)!!, result, "old")).isTrue()
    val candidate = SignalDatabase.attachments.getUploadCandidate(attachment.attachmentId)!!
    val captured = candidate.attachment
    assertThat(SignalDatabase.attachments.markUploadedIfUnchanged(candidate)).isTrue()
    assertThat(SignalDatabase.attachments.markTransferStartedIfOwned(attachment.attachmentId, old.file.absolutePath, old.random)).isFalse()
    assertThat(SignalDatabase.attachments.markTransferFailedIfSourceUnchanged(attachment.attachmentId, old)).isFalse()
    SignalDatabase.attachments.updateAttachmentData(captured, createMediaStream(byteArrayOf(7, 7, 7)))
    assertThat(SignalDatabase.attachments.markUploadedIfUnchanged(candidate)).isFalse()
    assertThat(SignalDatabase.attachments.markTransferStartedIfOwned(attachment.attachmentId, old.file.absolutePath, old.random)).isFalse()
    assertThat(SignalDatabase.attachments.markTransferFailedIfSourceUnchanged(attachment.attachmentId, old)).isFalse()
    assertThat(SignalDatabase.attachments.repairUploadLengthIfCandidateUnchanged(candidate, 9)).isNull()
    assertThat(SignalDatabase.attachments.setArchiveStateIfSourceUnchanged(attachment.attachmentId, old, AttachmentTable.ArchiveTransferState.PERMANENT_FAILURE)).isFalse()
    val current = SignalDatabase.attachments.getDataFileInfo(attachment.attachmentId)!!
    assertEquals(3, current.length)
    assertThat(SignalDatabase.attachments.repairUploadLengthIfCandidateUnchanged(SignalDatabase.attachments.getUploadCandidate(attachment.attachmentId)!!, 4)).isNotNull()
    assertEquals(4, SignalDatabase.attachments.getDataFileInfo(attachment.attachmentId)!!.length)
  }

  @Test
  fun pinnedImageStreamAndVideoDataSourceRetainCapturedVersion() {
    val original = byteArrayOf(1, 2, 3, 4, 5)
    val blob = AppDependencies.blobs.forData(original).createForSingleSessionInMemory()
    val attachment = SignalDatabase.attachments.insertAttachmentForPreUpload(createAttachment(1, blob, createHighQualityTransformProperties()))
    val source = SignalDatabase.attachments.getDataFileInfo(attachment.attachmentId)!!
    val factory = SignalDatabase.attachments.inputStreamFactoryFor(source)
    val mediaSource = SignalDatabase.attachments.mediaDataSourceFor(source)
    SignalDatabase.attachments.updateAttachmentData(attachment, createMediaStream(byteArrayOf(7, 7, 7)))
    assertThat(factory.create().use { it.readBytes().toList() }).isEqualTo(original.toList())
    assertThat(factory.create().use { it.readBytes().toList() }).isEqualTo(original.toList())
    val buffer = ByteArray(original.size)
    assertEquals(original.size, mediaSource.readAt(0, buffer, 0, buffer.size))
    assertThat(buffer.toList()).isEqualTo(original.toList())
    mediaSource.close()
    assertThat(SignalDatabase.attachments.isDataFileVersionCurrent(attachment.attachmentId, source)).isFalse()
    assertThat(source.file.delete()).isTrue()
    try { factory.create().close(); org.junit.Assert.fail("removed source must not reopen") } catch (_: java.io.IOException) { }
    assertThat(org.thoughtcrime.securesms.audio.AudioWaveForms.sourceChangedOnDecodeForTest(attachment.attachmentId, source)).isTrue()
  }

  @Test
  fun audioHashWriteRejectsAChangedSourceVersion() {
    val blob = AppDependencies.blobs.forData(byteArrayOf(1, 2, 3, 4, 5)).createForSingleSessionInMemory()
    val attachment = SignalDatabase.attachments.insertAttachmentForPreUpload(createAttachment(1, blob, createHighQualityTransformProperties()))
    val old = SignalDatabase.attachments.getDataFileInfo(attachment.attachmentId)!!
    SignalDatabase.attachments.updateAttachmentData(attachment, createMediaStream(byteArrayOf(7, 7, 7)))
    assertThat(SignalDatabase.attachments.writeAudioHashIfUnchanged(attachment.attachmentId, old, AudioWaveFormData())).isFalse()
    val current = SignalDatabase.attachments.getDataFileInfo(attachment.attachmentId)!!
    assertThat(SignalDatabase.attachments.writeAudioHashIfUnchanged(attachment.attachmentId, current, AudioWaveFormData())).isTrue()
  }

  @Test
  fun recentRemoteOnlyReuseSucceedsButChangedRemoteIdentityFails() {
    val blob = AppDependencies.blobs.forData(byteArrayOf(1, 2, 3, 4, 5)).createForSingleSessionInMemory()
    val attachment = SignalDatabase.attachments.insertAttachmentForPreUpload(createAttachment(1, blob, createHighQualityTransformProperties()))
    val source = SignalDatabase.attachments.getDataFileInfo(attachment.attachmentId)!!
    val result = AttachmentUploadResult(SignalServiceAttachmentRemoteId.V4("test"), 3, ByteArray(64), ByteArray(32), null, 0, 5, System.currentTimeMillis(), null, null)
    assertThat(SignalDatabase.attachments.finalizeAttachmentAfterSnapshotUpload(SignalDatabase.attachments.getUploadCandidate(attachment.attachmentId)!!, result, "hash")).isTrue()
    SignalDatabase.rawDatabase.update(AttachmentTable.TABLE_NAME)
      .values(AttachmentTable.DATA_FILE to null, AttachmentTable.DATA_RANDOM to null)
      .where("${AttachmentTable.ID} = ?", attachment.attachmentId.id).run()
    val candidate = SignalDatabase.attachments.getUploadCandidate(attachment.attachmentId)!!
    assertThat(candidate.info).isNull()
    assertThat(SignalDatabase.attachments.markUploadedIfUnchanged(candidate)).isTrue()
    SignalDatabase.rawDatabase.update(AttachmentTable.TABLE_NAME)
      .values(AttachmentTable.REMOTE_LOCATION to "changed")
      .where("${AttachmentTable.ID} = ?", attachment.attachmentId.id).run()
    assertThat(SignalDatabase.attachments.markUploadedIfUnchanged(candidate)).isFalse()
  }

  @Test
  fun glideImageCompressionReadsCapturedFileVersion() {
    fun png(color: Int): ByteArray {
      val bitmap = android.graphics.Bitmap.createBitmap(8, 8, android.graphics.Bitmap.Config.ARGB_8888)
      bitmap.eraseColor(color)
      val output = ByteArrayOutputStream()
      bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)
      bitmap.recycle()
      return output.toByteArray()
    }
    val red = 0xffff0000.toInt()
    val blue = 0xff0000ff.toInt()
    val blob = AppDependencies.blobs.forData(png(red)).createForSingleSessionInMemory()
    val attachment = SignalDatabase.attachments.insertAttachmentForPreUpload(createAttachment(1, blob, createHighQualityTransformProperties(), "image/png"))
    val source = SignalDatabase.attachments.getDataFileInfo(attachment.attachmentId)!!
    val pinned = SignalDatabase.attachments.inputStreamFactoryFor(source)
    SignalDatabase.attachments.updateAttachmentData(attachment, createMediaStream(png(blue)))
    val result = org.thoughtcrime.securesms.util.ImageCompressionUtil.compressWithinConstraints(InstrumentationRegistry.getInstrumentation().targetContext, "image/png", pinned, 32, 1024 * 1024, 100)!!
    val decoded = android.graphics.BitmapFactory.decodeByteArray(result.data, 0, result.data.size)
    assertEquals(red, decoded.getPixel(0, 0))
    decoded.recycle()
  }

  @Test
  fun finalizationDoesNotOverwriteIndependentlyFinalizedSameFileAlias() {
    val blob = AppDependencies.blobs.forData(byteArrayOf(1, 2, 3, 4, 5)).createForSingleSessionInMemory()
    val source = createAttachment(1, blob, createHighQualityTransformProperties())
    val first = SignalDatabase.attachments.insertAttachmentForPreUpload(source)
    val alias = SignalDatabase.attachments.insertAttachmentForPreUpload(source)
    val firstCandidate = SignalDatabase.attachments.getUploadCandidate(first.attachmentId)!!
    val aliasResult = AttachmentUploadResult(SignalServiceAttachmentRemoteId.V4("alias"), 3, ByteArray(64) { 2 }, ByteArray(32), null, 0, 5, 1, null, null)
    assertThat(SignalDatabase.attachments.finalizeAttachmentAfterSnapshotUpload(SignalDatabase.attachments.getUploadCandidate(alias.attachmentId)!!, aliasResult, "hash")).isTrue()
    val aliasKey = SignalDatabase.attachments.getAttachment(alias.attachmentId)!!.remoteKey!!
    SignalDatabase.attachments.setArchiveCdnForIdentity("hash", aliasKey, 3)
    val firstResult = AttachmentUploadResult(SignalServiceAttachmentRemoteId.V4("first"), 3, ByteArray(64) { 1 }, ByteArray(32), null, 0, 5, 1, null, null)
    assertThat(SignalDatabase.attachments.finalizeAttachmentAfterSnapshotUpload(firstCandidate, firstResult, "hash")).isTrue()
    val after = SignalDatabase.attachments.getAttachment(alias.attachmentId)!!
    assertEquals(aliasKey, after.remoteKey)
    assertEquals("alias", after.remoteLocation)
    assertEquals(AttachmentTable.ArchiveTransferState.FINISHED, after.archiveTransferState)
  }

  @Test
  fun staleSameSourceRemoteIdentityCannotFinalizeOrResetArchive() {
    val blob = AppDependencies.blobs.forData(byteArrayOf(1, 2, 3, 4, 5)).createForSingleSessionInMemory()
    val attachment = SignalDatabase.attachments.insertAttachmentForPreUpload(createAttachment(1, blob, createHighQualityTransformProperties()))
    val old = SignalDatabase.attachments.getUploadCandidate(attachment.attachmentId)!!
    val owner = SignalDatabase.attachments.uploadOwnerToken(old)
    val firstResult = AttachmentUploadResult(SignalServiceAttachmentRemoteId.V4("new"), 3, ByteArray(64) { 2 }, ByteArray(32), null, 0, 5, 1, null, null)
    assertThat(SignalDatabase.attachments.finalizeAttachmentAfterSnapshotUpload(old, firstResult, "hash")).isTrue()
    val current = SignalDatabase.attachments.getUploadCandidate(attachment.attachmentId)!!
    val key = current.attachment.remoteKey!!
    SignalDatabase.attachments.setArchiveCdnForIdentity("hash", key, 3)
    assertThat(SignalDatabase.attachments.finalizeAttachmentAfterSnapshotUpload(old, firstResult, "hash")).isFalse()
    assertThat(SignalDatabase.attachments.clearArchiveDataIfUnchanged(old)).isNull()
    assertThat(SignalDatabase.attachments.setArchiveStateIfCandidateUnchanged(old, AttachmentTable.ArchiveTransferState.TEMPORARY_FAILURE)).isFalse()
    assertThat(SignalDatabase.attachments.markTransferStateIfOwnerMatches(attachment.attachmentId, owner, AttachmentTable.TRANSFER_PROGRESS_FAILED)).isFalse()
    assertEquals(AttachmentTable.ArchiveTransferState.FINISHED, SignalDatabase.attachments.getAttachment(attachment.attachmentId)!!.archiveTransferState)
  }

  @Test
  fun ownershipHandoffFollowsOnlyGuardedTransformAndRejectsNewRemoteIdentity() {
    val blob = AppDependencies.blobs.forData(byteArrayOf(1, 2, 3, 4, 5)).createForSingleSessionInMemory()
    val attachment = SignalDatabase.attachments.insertAttachmentForPreUpload(createAttachment(1, blob, createHighQualityTransformProperties()))
    val source = SignalDatabase.attachments.getDataFileInfo(attachment.attachmentId)!!
    val submission = SignalDatabase.attachments.uploadOwnerToken(SignalDatabase.attachments.getUploadCandidate(attachment.attachmentId)!!)
    val handoff = SignalDatabase.attachments.updateAttachmentDataIfOwned(attachment, createMediaStream(byteArrayOf(7, 7, 7)), source, false, submission, Long.MAX_VALUE)!!
    assertNotEquals(submission, handoff)
    assertThat(SignalDatabase.attachments.prepareUploadIfOwned(attachment.attachmentId, submission)).isNull()
    SignalDatabase.rawDatabase.update(AttachmentTable.TABLE_NAME).values(AttachmentTable.REMOTE_LOCATION to "new-location").where("${AttachmentTable.ID} = ?", attachment.attachmentId.id).run()
    assertThat(SignalDatabase.attachments.prepareUploadIfOwned(attachment.attachmentId, handoff)).isNull()
    assertThat(SignalDatabase.attachments.markTransferStateIfOwnerMatches(attachment.attachmentId, handoff, AttachmentTable.TRANSFER_PROGRESS_FAILED)).isFalse()
  }

  @Test
  fun archivePendingTransitionTouchesOnlyInitiatingAlias() {
    val blob = AppDependencies.blobs.forData(byteArrayOf(1, 2, 3, 4, 5)).createForSingleSessionInMemory()
    val source = createAttachment(1, blob, createHighQualityTransformProperties())
    val first = SignalDatabase.attachments.insertAttachmentForPreUpload(source)
    val alias = SignalDatabase.attachments.insertAttachmentForPreUpload(source)
    assertEquals(SignalDatabase.attachments.getDataFileInfo(first.attachmentId)!!.file, SignalDatabase.attachments.getDataFileInfo(alias.attachmentId)!!.file)
    assertThat(SignalDatabase.attachments.markArchivePendingIfEligible(first.attachmentId, AttachmentTable.ArchiveTransferState.UPLOAD_IN_PROGRESS)).isTrue()
    assertEquals(AttachmentTable.ArchiveTransferState.UPLOAD_IN_PROGRESS, SignalDatabase.attachments.getAttachment(first.attachmentId)!!.archiveTransferState)
    assertEquals(AttachmentTable.ArchiveTransferState.NONE, SignalDatabase.attachments.getAttachment(alias.attachmentId)!!.archiveTransferState)
  }

  @Test
  fun actualUploadJobRejectsNewRemoteIdentityBeforeKeyOrFailureWrite() {
    val blob = AppDependencies.blobs.forData(byteArrayOf(1, 2, 3, 4, 5)).createForSingleSessionInMemory()
    val attachment = SignalDatabase.attachments.insertAttachmentForPreUpload(createAttachment(1, blob, createHighQualityTransformProperties()))
    val owner = SignalDatabase.attachments.uploadOwnerToken(SignalDatabase.attachments.getUploadCandidate(attachment.attachmentId)!!)
    assertThat(SignalDatabase.attachments.markTransferStateIfOwnerMatches(attachment.attachmentId, owner, AttachmentTable.TRANSFER_PROGRESS_STARTED)).isTrue()
    val data = org.thoughtcrime.securesms.jobs.protos.AttachmentUploadJobData(attachmentId = attachment.attachmentId.id, ownerIdentity = owner)
    val job = org.thoughtcrime.securesms.jobs.AttachmentUploadJob.Factory().create(org.thoughtcrime.securesms.jobmanager.Job.Parameters.Builder().build(), data.encode())
    SignalDatabase.rawDatabase.update(AttachmentTable.TABLE_NAME).values(AttachmentTable.REMOTE_LOCATION to "new-location").where("${AttachmentTable.ID} = ?", attachment.attachmentId.id).run()
    val keyBefore = SignalDatabase.attachments.getAttachment(attachment.attachmentId)!!.remoteKey
    try {
      job.onRun()
      throw AssertionError("Expected stale owner rejection before upload")
    } catch (_: java.io.IOException) { }
    job.onFailure()
    val current = SignalDatabase.attachments.getAttachment(attachment.attachmentId)!!
    assertEquals("new-location", current.remoteLocation)
    assertEquals(keyBefore, current.remoteKey)
    assertEquals(AttachmentTable.TRANSFER_PROGRESS_STARTED, current.transferState)
  }

  @Test
  fun actualUploadJobCompletesNewRecentRemoteWithoutClaimingFailureOwnership() {
    val blob = AppDependencies.blobs.forData(byteArrayOf(1, 2, 3, 4, 5)).createForSingleSessionInMemory()
    val attachment = SignalDatabase.attachments.insertAttachmentForPreUpload(createAttachment(1, blob, createHighQualityTransformProperties()))
    val owner = SignalDatabase.attachments.uploadOwnerToken(SignalDatabase.attachments.getUploadCandidate(attachment.attachmentId)!!)
    val data = org.thoughtcrime.securesms.jobs.protos.AttachmentUploadJobData(attachmentId = attachment.attachmentId.id, ownerIdentity = owner)
    val job = org.thoughtcrime.securesms.jobs.AttachmentUploadJob.Factory().create(org.thoughtcrime.securesms.jobmanager.Job.Parameters.Builder().build(), data.encode())
    SignalDatabase.rawDatabase.update(AttachmentTable.TABLE_NAME).values(AttachmentTable.REMOTE_LOCATION to "recent-object", AttachmentTable.REMOTE_KEY to Base64.encodeWithPadding(ByteArray(64) { 4 }), AttachmentTable.REMOTE_DIGEST to ByteArray(32) { 5 }, AttachmentTable.UPLOAD_TIMESTAMP to System.currentTimeMillis()).where("${AttachmentTable.ID} = ?", attachment.attachmentId.id).run()
    job.onRun()
    assertEquals(AttachmentTable.TRANSFER_PROGRESS_DONE, SignalDatabase.attachments.getAttachment(attachment.attachmentId)!!.transferState)
    job.onFailure()
    val after = SignalDatabase.attachments.getAttachment(attachment.attachmentId)!!
    assertEquals("recent-object", after.remoteLocation)
    assertEquals(AttachmentTable.TRANSFER_PROGRESS_DONE, after.transferState)
  }

  @Test
  fun restoredArchiveProgressFinishesOnEarlySuccessfulExit() {
    val blob = AppDependencies.blobs.forData(byteArrayOf(1, 2, 3, 4, 5)).createForSingleSessionInMemory()
    val attachment = SignalDatabase.attachments.insertAttachmentForPreUpload(createAttachment(1, blob, createHighQualityTransformProperties()))
    val progress = org.thoughtcrime.securesms.backup.ArchiveUploadProgress.onAttachmentStarted(attachment.attachmentId, 5)
    val previousTier = SignalStore.backup.backupTier
    try {
      SignalStore.backup.backupTier = null
      val data = org.thoughtcrime.securesms.jobs.protos.UploadAttachmentToArchiveJobData(attachmentId = attachment.attachmentId.id, canReuseUpload = true, progressToken = progress)
      val job = org.thoughtcrime.securesms.jobs.UploadAttachmentToArchiveJob.Factory().create(org.thoughtcrime.securesms.jobmanager.Job.Parameters.Builder().build(), data.encode())
      assertThat(job.run().isSuccess).isTrue()
      assertThat(org.thoughtcrime.securesms.backup.ArchiveUploadProgress.onAttachmentFinishedIfOwned(attachment.attachmentId, progress)).isFalse()
    } finally {
      SignalStore.backup.backupTier = previousTier
    }
  }

  @Test
  fun restoredArchiveFailureUsesRepairedOwnerAndFinishesItsProgress() {
    val blob = AppDependencies.blobs.forData(byteArrayOf(1, 2, 3, 4, 5)).createForSingleSessionInMemory()
    val attachment = SignalDatabase.attachments.insertAttachmentForPreUpload(createAttachment(1, blob, createHighQualityTransformProperties()))
    assertThat(SignalDatabase.attachments.markArchivePendingIfEligible(attachment.attachmentId, AttachmentTable.ArchiveTransferState.UPLOAD_IN_PROGRESS)).isTrue()
    val captured = SignalDatabase.attachments.getUploadCandidate(attachment.attachmentId)!!
    val repaired = SignalDatabase.attachments.repairUploadLengthIfCandidateUnchanged(captured, 4)!!
    val owner = SignalDatabase.attachments.uploadOwnerToken(repaired)
    val progress = org.thoughtcrime.securesms.backup.ArchiveUploadProgress.onAttachmentStarted(attachment.attachmentId, 4)
    val data = org.thoughtcrime.securesms.jobs.protos.UploadAttachmentToArchiveJobData(attachmentId = attachment.attachmentId.id, canReuseUpload = true, ownerIdentity = owner, progressToken = progress)
    val job = org.thoughtcrime.securesms.jobs.UploadAttachmentToArchiveJob.Factory().create(org.thoughtcrime.securesms.jobmanager.Job.Parameters.Builder().build(), data.encode())
    job.onFailure()
    assertEquals(AttachmentTable.ArchiveTransferState.TEMPORARY_FAILURE, SignalDatabase.attachments.getAttachment(attachment.attachmentId)!!.archiveTransferState)
    assertThat(org.thoughtcrime.securesms.backup.ArchiveUploadProgress.onAttachmentFinishedIfOwned(attachment.attachmentId, progress)).isFalse()
  }

  @Test
  fun ownerRecoveryAcceptsOnlyNullToValidKeyInitialization() {
    val blob = AppDependencies.blobs.forData(byteArrayOf(1, 2, 3, 4, 5)).createForSingleSessionInMemory()
    val attachment = SignalDatabase.attachments.insertAttachmentForPreUpload(createAttachment(1, blob, createHighQualityTransformProperties()))
    SignalDatabase.rawDatabase.update(AttachmentTable.TABLE_NAME).values(AttachmentTable.REMOTE_KEY to null, AttachmentTable.REMOTE_LOCATION to null, AttachmentTable.REMOTE_DIGEST to null).where("${AttachmentTable.ID} = ?", attachment.attachmentId.id).run()
    val before = SignalDatabase.attachments.getUploadCandidate(attachment.attachmentId)!!
    val owner = SignalDatabase.attachments.uploadOwnerToken(before)
    SignalDatabase.rawDatabase.update(AttachmentTable.TABLE_NAME).values(AttachmentTable.REMOTE_KEY to Base64.encodeWithPadding(ByteArray(64) { 7 })).where("${AttachmentTable.ID} = ?", attachment.attachmentId.id).run()
    assertThat(SignalDatabase.attachments.prepareUploadIfOwned(attachment.attachmentId, owner)).isNotNull()
    val ownedKey = SignalDatabase.attachments.uploadOwnerToken(SignalDatabase.attachments.getUploadCandidate(attachment.attachmentId)!!)
    SignalDatabase.rawDatabase.update(AttachmentTable.TABLE_NAME).values(AttachmentTable.REMOTE_KEY to Base64.encodeWithPadding(ByteArray(64) { 8 })).where("${AttachmentTable.ID} = ?", attachment.attachmentId.id).run()
    assertThat(SignalDatabase.attachments.prepareUploadIfOwned(attachment.attachmentId, ownedKey)).isNull()
    SignalDatabase.rawDatabase.update(AttachmentTable.TABLE_NAME).values(AttachmentTable.REMOTE_LOCATION to "different").where("${AttachmentTable.ID} = ?", attachment.attachmentId.id).run()
    assertThat(SignalDatabase.attachments.prepareUploadIfOwned(attachment.attachmentId, owner)).isNull()
    SignalDatabase.rawDatabase.update(AttachmentTable.TABLE_NAME).values(AttachmentTable.REMOTE_KEY to Base64.encodeWithPadding(ByteArray(3)), AttachmentTable.REMOTE_LOCATION to null).where("${AttachmentTable.ID} = ?", attachment.attachmentId.id).run()
    assertThat(SignalDatabase.attachments.prepareUploadIfOwned(attachment.attachmentId, owner)).isNull()
  }

  @Test
  fun inactiveOwnerCannotFailSameSourceWithNewRemoteIdentity() {
    val blob = AppDependencies.blobs.forData(byteArrayOf(1, 2, 3, 4, 5)).createForSingleSessionInMemory()
    val attachment = SignalDatabase.attachments.insertAttachmentForPreUpload(createAttachment(1, blob, createHighQualityTransformProperties()))
    val old = SignalDatabase.attachments.getUploadCandidate(attachment.attachmentId)!!
    val token = SignalDatabase.attachments.uploadOwnerToken(old)
    assertThat(SignalDatabase.attachments.markTransferStateIfOwnerMatches(attachment.attachmentId, token, AttachmentTable.TRANSFER_PROGRESS_STARTED)).isTrue()
    SignalDatabase.rawDatabase.update(AttachmentTable.TABLE_NAME)
      .values(AttachmentTable.REMOTE_LOCATION to "new-location")
      .where("${AttachmentTable.ID} = ?", attachment.attachmentId.id).run()
    assertThat(SignalDatabase.attachments.markTransferStateIfOwnerMatches(attachment.attachmentId, token, AttachmentTable.TRANSFER_PROGRESS_FAILED)).isFalse()
    val current = SignalDatabase.attachments.getAttachment(attachment.attachmentId)!!
    assertEquals("new-location", current.remoteLocation)
    assertEquals(AttachmentTable.TRANSFER_PROGRESS_STARTED, current.transferState)
  }

  @Test
  fun staleCopyFailureDoesNotResetAReuploadedSameMediaAtANewLocation() {
    val blob = AppDependencies.blobs.forData(byteArrayOf(1, 2, 3, 4, 5)).createForSingleSessionInMemory()
    val attachment = SignalDatabase.attachments.insertAttachmentForPreUpload(createAttachment(1, blob, createHighQualityTransformProperties()))
    val initial = SignalDatabase.attachments.getUploadCandidate(attachment.attachmentId)!!
    val result = AttachmentUploadResult(SignalServiceAttachmentRemoteId.V4("old"), 3, ByteArray(64) { 2 }, ByteArray(32), null, 0, 5, 1, null, null)
    assertThat(SignalDatabase.attachments.finalizeAttachmentAfterSnapshotUpload(initial, result, "hash")).isTrue()
    val oldCopy = SignalDatabase.attachments.getUploadCandidate(attachment.attachmentId)!!
    SignalDatabase.rawDatabase.update(AttachmentTable.TABLE_NAME)
      .values(AttachmentTable.REMOTE_LOCATION to "new-location", AttachmentTable.UPLOAD_TIMESTAMP to 2L)
      .where("${AttachmentTable.ID} = ?", attachment.attachmentId.id).run()
    assertThat(SignalDatabase.attachments.setArchiveStateIfCandidateUnchanged(oldCopy, AttachmentTable.ArchiveTransferState.NONE)).isFalse()
    assertEquals("new-location", SignalDatabase.attachments.getAttachment(attachment.attachmentId)!!.remoteLocation)
  }

  @Test
  fun zeroLengthMetadataRepairsOnlyCapturedCandidateAndEmptySourceCanBecomeTerminal() {
    val blob = AppDependencies.blobs.forData(byteArrayOf(1, 2, 3, 4, 5)).createForSingleSessionInMemory()
    val first = SignalDatabase.attachments.insertAttachmentForPreUpload(createAttachment(1, blob, createHighQualityTransformProperties()))
    SignalDatabase.rawDatabase.update(AttachmentTable.TABLE_NAME).values(AttachmentTable.DATA_SIZE to 0L).where("${AttachmentTable.ID} = ?", first.attachmentId.id).run()
    val zero = SignalDatabase.attachments.getUploadCandidate(first.attachmentId)!!
    val repaired = SignalDatabase.attachments.repairUploadLengthIfCandidateUnchanged(zero, 5)!!
    assertEquals(5, repaired.attachment.size)
    assertThat(SignalDatabase.attachments.repairUploadLengthIfCandidateUnchanged(zero, 0)).isNull()
    val empty = SignalDatabase.attachments.repairUploadLengthIfCandidateUnchanged(repaired, 0)!!
    assertThat(SignalDatabase.attachments.setArchiveStateIfCandidateUnchanged(empty, AttachmentTable.ArchiveTransferState.PERMANENT_FAILURE)).isTrue()
    assertEquals(AttachmentTable.ArchiveTransferState.PERMANENT_FAILURE, SignalDatabase.attachments.getAttachment(first.attachmentId)!!.archiveTransferState)
  }

  @Test
  fun staleTransformMarkDoesNotChangeTheNewSource() {
    val blob = AppDependencies.blobs.forData(byteArrayOf(1, 2, 3, 4, 5)).createForSingleSessionInMemory()
    val attachment = SignalDatabase.attachments.insertAttachmentForPreUpload(createAttachment(1, blob, createHighQualityTransformProperties()))
    val old = SignalDatabase.attachments.getDataFileInfo(attachment.attachmentId)!!
    assertThat(SignalDatabase.attachments.updateAttachmentData(attachment, createMediaStream(byteArrayOf(7, 7, 7)), old, false)).isTrue()
    val current = SignalDatabase.attachments.getDataFileInfo(attachment.attachmentId)!!
    assertThat(SignalDatabase.attachments.markAttachmentAsTransformedIfUnchanged(attachment.attachmentId, old, true)).isFalse()
    assertEquals(current.transformProperties, SignalDatabase.attachments.getDataFileInfo(attachment.attachmentId)!!.transformProperties)
    assertThat(SignalDatabase.attachments.markAttachmentAsTransformedIfUnchanged(attachment.attachmentId, current, true)).isTrue()
  }

  @Test
  fun rejectedUnchangedVideoIsNotMarkedTransformed() {
    val blob = AppDependencies.blobs.forData(byteArrayOf(1, 2, 3, 4, 5)).createForSingleSessionInMemory()
    val attachment = SignalDatabase.attachments.insertAttachmentForPreUpload(createAttachment(1, blob, createHighQualityTransformProperties()))
    val source = SignalDatabase.attachments.getDataFileInfo(attachment.attachmentId)!!
    assertThat(source.transformProperties.shouldSkipTransform()).isFalse()
    val owner = SignalDatabase.attachments.uploadOwnerToken(SignalDatabase.attachments.getUploadCandidate(attachment.attachmentId)!!)
    try {
      org.thoughtcrime.securesms.jobs.AttachmentCompressionJob.markUnchangedVideoIfValid(SignalDatabase.attachments, attachment, source, false, owner)
      throw AssertionError("Expected constraints rejection")
    } catch (_: org.thoughtcrime.securesms.transport.UndeliverableMessageException) { }
    assertThat(SignalDatabase.attachments.getDataFileInfo(attachment.attachmentId)!!.transformProperties.shouldSkipTransform()).isFalse()
    assertThat(org.thoughtcrime.securesms.jobs.AttachmentCompressionJob.markUnchangedVideoIfValid(SignalDatabase.attachments, attachment, source, true, owner)).isNotNull()
    assertThat(SignalDatabase.attachments.getDataFileInfo(attachment.attachmentId)!!.transformProperties.shouldSkipTransform()).isTrue()
  }

  @Test
  fun oversizedTranscodedVideoDoesNotPublishOrMarkTheOldSource() {
    val blob = AppDependencies.blobs.forData(byteArrayOf(1, 2, 3, 4, 5)).createForSingleSessionInMemory()
    val attachment = SignalDatabase.attachments.insertAttachmentForPreUpload(createAttachment(1, blob, createHighQualityTransformProperties()))
    val source = SignalDatabase.attachments.getDataFileInfo(attachment.attachmentId)!!
    val owner = SignalDatabase.attachments.uploadOwnerToken(SignalDatabase.attachments.getUploadCandidate(attachment.attachmentId)!!)
    val parts = InstrumentationRegistry.getInstrumentation().targetContext.getDir("parts", Context.MODE_PRIVATE)
    val before = parts.listFiles()!!.map { it.name }.toSet()
    try {
      SignalDatabase.attachments.updateAttachmentDataIfOwned(attachment, createMediaStream(byteArrayOf(7, 7, 7)), source, true, owner, 2)
      throw AssertionError("Expected output size rejection")
    } catch (_: org.thoughtcrime.securesms.mms.MmsException) { }
    val after = SignalDatabase.attachments.getDataFileInfo(attachment.attachmentId)!!
    assertEquals(source.file, after.file)
    assertThat(after.transformProperties.shouldSkipTransform()).isFalse()
    assertEquals(before, parts.listFiles()!!.map { it.name }.toSet())
  }

  @Test
  fun failedTransformDeletesUnpublishedFiles() {
    val blob = AppDependencies.blobs.forData(byteArrayOf(1, 2, 3, 4, 5)).createForSingleSessionInMemory()
    val attachment = SignalDatabase.attachments.insertAttachmentForPreUpload(createAttachment(1, blob, createHighQualityTransformProperties()))
    val parts = InstrumentationRegistry.getInstrumentation().targetContext.getDir("parts", Context.MODE_PRIVATE)
    val before = parts.listFiles()!!.map { it.name }.toSet()
    val failing = object : java.io.InputStream() {
      override fun read(): Int = throw java.io.IOException("test read failure")
    }
    try {
      SignalDatabase.attachments.updateAttachmentData(attachment, MediaStream(failing, MediaUtil.IMAGE_JPEG, 2, 2))
      throw AssertionError("Expected write failure")
    } catch (_: org.thoughtcrime.securesms.mms.MmsException) {
      // The source and both private unpublished files must remain unchanged/absent.
    }
    assertEquals(before, parts.listFiles()!!.map { it.name }.toSet())
    try {
      SignalDatabase.attachments.updateAttachmentDataForTest(attachment, createMediaStream(byteArrayOf(9))) { throw IllegalStateException("before commit") }
      throw AssertionError("Expected publication failure")
    } catch (_: IllegalStateException) {
      // The completed but unpublished destination must also be removed.
    }
    assertEquals(before, parts.listFiles()!!.map { it.name }.toSet())
  }

  @FlakyTest
  @Test
  fun givenABlobAndDifferentTransformQuality_whenIInsert2AttachmentsForPreUpload_thenIExpectDifferentFileInfos() {
    val blob = AppDependencies.blobs.forData(byteArrayOf(1, 2, 3, 4, 5)).createForSingleSessionInMemory()
    val highQualityProperties = createHighQualityTransformProperties()
    val highQualityImage = createAttachment(1, blob, highQualityProperties)
    val lowQualityImage = createAttachment(1, blob, TransformProperties.empty())
    val attachment = SignalDatabase.attachments.insertAttachmentForPreUpload(highQualityImage)
    val attachment2 = SignalDatabase.attachments.insertAttachmentForPreUpload(lowQualityImage)

    SignalDatabase.attachments.updateAttachmentData(
      attachment,
      createMediaStream(byteArrayOf(1, 2, 3, 4, 5))
    )

    SignalDatabase.attachments.updateAttachmentData(
      attachment2,
      createMediaStream(byteArrayOf(1, 2, 3))
    )

    val attachment1Info = SignalDatabase.attachments.getDataFileInfo(attachment.attachmentId)
    val attachment2Info = SignalDatabase.attachments.getDataFileInfo(attachment2.attachmentId)

    assertNotEquals(attachment1Info, attachment2Info)
  }

  @FlakyTest
  @Ignore("test is flaky")
  @Test
  fun givenIdenticalAttachmentsInsertedForPreUpload_whenIUpdateAttachmentDataAndSpecifyOnlyModifyThisAttachment_thenIExpectDifferentFileInfos() {
    val blob = AppDependencies.blobs.forData(byteArrayOf(1, 2, 3, 4, 5)).createForSingleSessionInMemory()
    val highQualityProperties = createHighQualityTransformProperties()
    val highQualityImage = createAttachment(1, blob, highQualityProperties)
    val attachment = SignalDatabase.attachments.insertAttachmentForPreUpload(highQualityImage)
    val attachment2 = SignalDatabase.attachments.insertAttachmentForPreUpload(highQualityImage)

    SignalDatabase.attachments.updateAttachmentData(
      attachment,
      createMediaStream(byteArrayOf(1, 2, 3, 4, 5))
    )

    SignalDatabase.attachments.updateAttachmentData(
      attachment2,
      createMediaStream(byteArrayOf(1, 2, 3, 4))
    )

    val attachment1Info = SignalDatabase.attachments.getDataFileInfo(attachment.attachmentId)
    val attachment2Info = SignalDatabase.attachments.getDataFileInfo(attachment2.attachmentId)

    assertNotEquals(attachment1Info, attachment2Info)
  }

  /**
   * Given: A previous attachment and two pre-upload attachments with the same data but different transform properties (standard and high).
   *
   * When changing content of standard pre-upload attachment to match pre-existing attachment
   *
   * Then update standard pre-upload attachment to match previous attachment, do not update high pre-upload attachment, and do
   * not delete shared pre-upload uri from disk as it is still being used by the high pre-upload attachment.
   */
  @Test
  fun doNotDeleteDedupedFileIfUsedByAnotherAttachmentWithADifferentTransformProperties() {
    // GIVEN
    val uncompressData = byteArrayOf(1, 2, 3, 4, 5)
    val compressedData = byteArrayOf(1, 2, 3)

    val blobUncompressed = AppDependencies.blobs.forData(uncompressData).createForSingleSessionInMemory()

    val previousAttachment = createAttachment(1, AppDependencies.blobs.forData(compressedData).createForSingleSessionInMemory(), TransformProperties.empty())
    val previousDatabaseAttachmentId: AttachmentId = SignalDatabase.attachments.insertAttachmentsForMessage(1, listOf(previousAttachment), emptyList()).values.first()

    val standardQualityPreUpload = createAttachment(1, blobUncompressed, TransformProperties.empty())
    val standardDatabaseAttachment = SignalDatabase.attachments.insertAttachmentForPreUpload(standardQualityPreUpload)

    val highQualityPreUpload = createAttachment(1, blobUncompressed, transformPropertiesForSentMediaQuality(Optional.empty(), SentMediaQuality.HIGH))
    val highDatabaseAttachment = SignalDatabase.attachments.insertAttachmentForPreUpload(highQualityPreUpload)

    // WHEN
    SignalDatabase.attachments.updateAttachmentData(standardDatabaseAttachment, createMediaStream(compressedData))

    // THEN
    val previousInfo = SignalDatabase.attachments.getDataFileInfo(previousDatabaseAttachmentId)!!
    val standardInfo = SignalDatabase.attachments.getDataFileInfo(standardDatabaseAttachment.attachmentId)!!
    val highInfo = SignalDatabase.attachments.getDataFileInfo(highDatabaseAttachment.attachmentId)!!

    assertNotEquals(standardInfo, highInfo)
    assertThat(highInfo.file).isNotEqualTo(standardInfo.file)
    assertThat(highInfo.file.exists()).isEqualTo(true)
  }

  /**
   * Given: Three pre-upload attachments with the same data but different transform properties (1x standard and 2x high).
   *
   * When inserting content of high pre-upload attachment.
   *
   * Then do not deduplicate with standard pre-upload attachment, but do deduplicate second high insert.
   */
  @Test
  fun doNotDedupedFileIfUsedByAnotherAttachmentWithADifferentTransformProperties() {
    // GIVEN
    val uncompressData = byteArrayOf(1, 2, 3, 4, 5)
    val blobUncompressed = AppDependencies.blobs.forData(uncompressData).createForSingleSessionInMemory()

    val standardQualityPreUpload = createAttachment(1, blobUncompressed, TransformProperties.empty())
    val standardDatabaseAttachment = SignalDatabase.attachments.insertAttachmentForPreUpload(standardQualityPreUpload)

    // WHEN
    val highQualityPreUpload = createAttachment(1, blobUncompressed, transformPropertiesForSentMediaQuality(Optional.empty(), SentMediaQuality.HIGH))
    val highDatabaseAttachment = SignalDatabase.attachments.insertAttachmentForPreUpload(highQualityPreUpload)

    val secondHighQualityPreUpload = createAttachment(1, blobUncompressed, transformPropertiesForSentMediaQuality(Optional.empty(), SentMediaQuality.HIGH))
    val secondHighDatabaseAttachment = SignalDatabase.attachments.insertAttachmentForPreUpload(secondHighQualityPreUpload)

    // THEN
    val standardInfo = SignalDatabase.attachments.getDataFileInfo(standardDatabaseAttachment.attachmentId)!!
    val highInfo = SignalDatabase.attachments.getDataFileInfo(highDatabaseAttachment.attachmentId)!!
    val secondHighInfo = SignalDatabase.attachments.getDataFileInfo(secondHighDatabaseAttachment.attachmentId)!!

    assertThat(highInfo.file).isNotEqualTo(standardInfo.file)
    assertThat(secondHighInfo.file).isEqualTo(highInfo.file)
    assertThat(standardInfo.file.exists()).isEqualTo(true)
    assertThat(highInfo.file.exists()).isEqualTo(true)
  }

  @Test
  fun finalizeAttachmentAfterUpload_preservesAudioWaveForm() {
    val blob = AppDependencies.blobs.forData(byteArrayOf(1, 2, 3, 4, 5)).createForSingleSessionInMemory()
    val attachment = createAttachment(1, blob, TransformProperties.empty(), contentType = MediaUtil.AUDIO_AAC)
    val attachmentId = SignalDatabase.attachments.insertAttachmentsForMessage(-1L, listOf(attachment), emptyList()).values.first()

    SignalDatabase.attachments.writeAudioHash(attachmentId, AudioWaveFormData(durationUs = 5_000_000, waveForm = byteArrayOf(1, 2, 3).toByteString()))

    SignalDatabase.attachments.finalizeAttachmentAfterUpload(attachmentId, AttachmentTableTestUtil.createUploadResult(attachmentId))

    val audioHash = SignalDatabase.attachments.getAttachment(attachmentId)!!.audioHash
    assertThat(audioHash).isNotNull()
    assertThat(audioHash!!.waveFormBytes!!.toList()).isEqualTo(listOf<Byte>(1, 2, 3))
  }

  @Test
  fun finalizeAttachmentAfterUpload_writesBlurHashWhenPresent() {
    val blob = AppDependencies.blobs.forData(byteArrayOf(1, 2, 3, 4, 5)).createForSingleSessionInMemory()
    val attachment = createAttachment(1, blob, TransformProperties.empty())
    val attachmentId = SignalDatabase.attachments.insertAttachmentsForMessage(-1L, listOf(attachment), emptyList()).values.first()

    SignalDatabase.attachments.finalizeAttachmentAfterUpload(attachmentId, AttachmentTableTestUtil.createUploadResult(attachmentId, blurHash = "LEHV6nWB2yk8pyo0adR*"))

    assertThat(SignalDatabase.attachments.getAttachment(attachmentId)!!.blurHash!!.hash).isEqualTo("LEHV6nWB2yk8pyo0adR*")
  }

  @Test
  fun resetArchiveTransferStateByPlaintextHashAndRemoteKey_singleMatch() {
    // Given an attachment with some plaintextHash+remoteKey
    val blob = AppDependencies.blobs.forData(byteArrayOf(1, 2, 3, 4, 5)).createForSingleSessionInMemory()
    val attachment = createAttachment(1, blob, TransformProperties.empty())
    val attachmentId = SignalDatabase.attachments.insertAttachmentsForMessage(-1L, listOf(attachment), emptyList()).values.first()
    SignalDatabase.attachments.finalizeAttachmentAfterUpload(attachmentId, AttachmentTableTestUtil.createUploadResult(attachmentId))
    SignalDatabase.attachments.setArchiveTransferState(attachmentId, AttachmentTable.ArchiveTransferState.FINISHED)

    // Reset the transfer state by plaintextHash+remoteKey
    val inserted = SignalDatabase.attachments.getAttachment(attachmentId)!!
    SignalDatabase.attachments.resetArchiveTransferStateByPlaintextHashAndRemoteKeyIfNecessary(
      listOf(AttachmentTable.MediaNameParts(plaintextHash = inserted.dataHash!!, remoteKey = inserted.remoteKey!!))
    )

    // Verify it's been reset
    assertThat(SignalDatabase.attachments.getAttachment(attachmentId)!!.archiveTransferState).isEqualTo(AttachmentTable.ArchiveTransferState.NONE)
  }

  @Test
  fun resetArchiveTransferStateByPlaintextHashAndRemoteKey_batchedMatch() {
    // Given two archive-finished attachments that still have their local data
    val attachmentIds = listOf(byteArrayOf(1, 2, 3, 4, 5), byteArrayOf(6, 7, 8, 9, 10)).mapIndexed { index, data ->
      val blob = AppDependencies.blobs.forData(data).createForSingleSessionInMemory()
      val attachment = createAttachment(index + 1L, blob, TransformProperties.empty())
      val attachmentId = SignalDatabase.attachments.insertAttachmentsForMessage(-1L, listOf(attachment), emptyList()).values.first()
      SignalDatabase.attachments.finalizeAttachmentAfterUpload(attachmentId, AttachmentTableTestUtil.createUploadResult(attachmentId))
      SignalDatabase.attachments.setArchiveTransferState(attachmentId, AttachmentTable.ArchiveTransferState.FINISHED)
      attachmentId
    }

    val mediaNames = attachmentIds.map { attachmentId ->
      val attachment = SignalDatabase.attachments.getAttachment(attachmentId)!!
      AttachmentTable.MediaNameParts(plaintextHash = attachment.dataHash!!, remoteKey = attachment.remoteKey!!)
    }

    val results = SignalDatabase.attachments.resetArchiveTransferStateByPlaintextHashAndRemoteKeyIfNecessary(mediaNames)

    // Both are reported as reset, and both actually are
    assertThat(results).containsExactly(AttachmentTable.ArchiveTransferStateResetResult.RESET, AttachmentTable.ArchiveTransferStateResetResult.RESET)
    attachmentIds.forEach { attachmentId ->
      assertThat(SignalDatabase.attachments.getAttachment(attachmentId)!!.archiveTransferState).isEqualTo(AttachmentTable.ArchiveTransferState.NONE)
    }
  }

  @Test
  fun resetArchiveTransferStateForLocalBackupMedia_onlyResetsLocalBackupMedia() {
    // Given one archive-finished attachment restored from a local backup, and one that wasn't
    val localBackupMessageId = SignalDatabase.messages.insertMessageInbox(createIncomingMessage(serverTime = 0.days, attachment = createArchivedAttachment(localBackupKey = Random.nextBytes(32)))).map { it.messageId }.get()
    val localBackupAttachmentId = SignalDatabase.attachments.getAttachmentsForMessage(localBackupMessageId).first().attachmentId

    val nonLocalBackupMessageId = SignalDatabase.messages.insertMessageInbox(createIncomingMessage(serverTime = 1.days, attachment = createArchivedAttachment())).map { it.messageId }.get()
    val nonLocalBackupAttachmentId = SignalDatabase.attachments.getAttachmentsForMessage(nonLocalBackupMessageId).first().attachmentId

    SignalDatabase.attachments.setArchiveTransferState(localBackupAttachmentId, AttachmentTable.ArchiveTransferState.FINISHED)
    SignalDatabase.attachments.setArchiveTransferState(nonLocalBackupAttachmentId, AttachmentTable.ArchiveTransferState.FINISHED)

    val resetCount = SignalDatabase.attachments.resetArchiveTransferStateForLocalBackupMedia()

    // Only the local-backup attachment is reset
    assertThat(resetCount).isEqualTo(1)
    assertThat(SignalDatabase.attachments.getAttachment(localBackupAttachmentId)!!.archiveTransferState).isEqualTo(AttachmentTable.ArchiveTransferState.NONE)
    assertThat(SignalDatabase.attachments.getAttachment(nonLocalBackupAttachmentId)!!.archiveTransferState).isEqualTo(AttachmentTable.ArchiveTransferState.FINISHED)
  }

  @Test
  fun given10NewerAnd10OlderAttachments_whenIGetEachBatch_thenIExpectProperBucketing() {
    val now = System.currentTimeMillis().milliseconds
    val attachments = (0 until 20).map {
      createArchivedAttachment()
    }

    val newMessages = attachments.take(10).mapIndexed { index, attachment ->
      createIncomingMessage(serverTime = now - index.seconds, attachment = attachment)
    }

    val twoMonthsAgo = now - 60.days
    val oldMessages = attachments.drop(10).mapIndexed { index, attachment ->
      createIncomingMessage(serverTime = twoMonthsAgo - index.seconds, attachment = attachment)
    }

    (newMessages + oldMessages).forEach {
      SignalDatabase.messages.insertMessageInbox(it)
    }

    val firstAttachmentsToDownload = SignalDatabase.attachments.getLast30DaysOfRestorableAttachments(500)
    val nextAttachmentsToDownload = SignalDatabase.attachments.getOlderRestorableAttachments(500)

    assertThat(firstAttachmentsToDownload).hasSize(10)
    val resultNewMessages = SignalDatabase.messages.getMessages(firstAttachmentsToDownload.map { it.mmsId })
    resultNewMessages.forEach {
      assertThat(it.serverTimestamp.milliseconds >= now - 30.days).isTrue()
    }

    assertThat(nextAttachmentsToDownload).hasSize(10)
    val resultOldMessages = SignalDatabase.messages.getMessages(nextAttachmentsToDownload.map { it.mmsId })
    resultOldMessages.forEach {
      assertThat(it.serverTimestamp.milliseconds < now - 30.days).isTrue()
    }
  }

  @Test
  fun givenAnAttachmentWithAMessageThatExpiresIn5Minutes_whenIGetAttachmentsThatNeedArchiveUpload_thenIDoNotExpectThatAttachment() {
    // GIVEN
    val uncompressData = byteArrayOf(1, 2, 3, 4, 5)
    val blobUncompressed = AppDependencies.blobs.forData(uncompressData).createForSingleSessionInMemory()
    val attachment = createAttachment(1, blobUncompressed, TransformProperties.empty())
    val message = createIncomingMessage(serverTime = 0.days, attachment = attachment, expiresIn = 5.minutes)
    val messageId = SignalDatabase.messages.insertMessageInbox(message).map { it.messageId }.get()
    SignalDatabase.attachments.setArchiveTransferState(AttachmentId(1L), AttachmentTable.ArchiveTransferState.NONE)
    SignalDatabase.attachments.setTransferState(messageId, AttachmentId(1L), AttachmentTable.TRANSFER_PROGRESS_DONE)
    SignalDatabase.attachments.finalizeAttachmentAfterUpload(AttachmentId(1L), AttachmentTableTestUtil.createUploadResult(AttachmentId(1L)))

    // WHEN
    val attachments = SignalDatabase.attachments.getAttachmentsThatNeedArchiveUpload()

    // THEN
    assertThat(attachments).isEmpty()
  }

  @Test
  fun givenAnAttachmentWithAMessageThatExpiresIn5Days_whenIGetAttachmentsThatNeedArchiveUpload_thenIDoExpectThatAttachment() {
    // GIVEN
    val uncompressData = byteArrayOf(1, 2, 3, 4, 5)
    val blobUncompressed = AppDependencies.blobs.forData(uncompressData).createForSingleSessionInMemory()
    val attachment = createAttachment(1, blobUncompressed, TransformProperties.empty())
    val message = createIncomingMessage(serverTime = 0.days, attachment = attachment, expiresIn = 5.days)
    val messageId = SignalDatabase.messages.insertMessageInbox(message).map { it.messageId }.get()
    SignalDatabase.attachments.setArchiveTransferState(AttachmentId(1L), AttachmentTable.ArchiveTransferState.NONE)
    SignalDatabase.attachments.setTransferState(messageId, AttachmentId(1L), AttachmentTable.TRANSFER_PROGRESS_DONE)
    SignalDatabase.attachments.finalizeAttachmentAfterUpload(AttachmentId(1L), AttachmentTableTestUtil.createUploadResult(AttachmentId(1L)))

    // WHEN
    val attachments = SignalDatabase.attachments.getAttachmentsThatNeedArchiveUpload()

    // THEN
    assertThat(attachments).isNotEmpty()
  }

  @Test
  fun givenAnAttachmentWithAMessageWithExpirationStartedThatExpiresIn5Days_whenIGetAttachmentsThatNeedArchiveUpload_thenIDoExpectThatAttachment() {
    // GIVEN
    val uncompressData = byteArrayOf(1, 2, 3, 4, 5)
    val blobUncompressed = AppDependencies.blobs.forData(uncompressData).createForSingleSessionInMemory()
    val attachment = createAttachment(1, blobUncompressed, TransformProperties.empty())
    val message = createIncomingMessage(serverTime = 0.days, attachment = attachment, expiresIn = 5.days)
    val messageId = SignalDatabase.messages.insertMessageInbox(message).map { it.messageId }.get()
    SignalDatabase.messages.markExpireStarted(messageId)
    SignalDatabase.attachments.setArchiveTransferState(AttachmentId(1L), AttachmentTable.ArchiveTransferState.NONE)
    SignalDatabase.attachments.setTransferState(messageId, AttachmentId(1L), AttachmentTable.TRANSFER_PROGRESS_DONE)
    SignalDatabase.attachments.finalizeAttachmentAfterUpload(AttachmentId(1L), AttachmentTableTestUtil.createUploadResult(AttachmentId(1L)))

    // WHEN
    val attachments = SignalDatabase.attachments.getAttachmentsThatNeedArchiveUpload()

    // THEN
    assertThat(attachments).isNotEmpty()
  }

  @Test
  fun givenAnAttachmentWithALongTextAttachment_whenIGetAttachmentsThatNeedArchiveUpload_thenIDoNotExpectThatAttachment() {
    // GIVEN
    val uncompressData = byteArrayOf(1, 2, 3, 4, 5)
    val blobUncompressed = AppDependencies.blobs.forData(uncompressData).createForSingleSessionInMemory()
    val attachment = createAttachment(1, blobUncompressed, TransformProperties.empty(), contentType = MediaUtil.LONG_TEXT)
    val message = createIncomingMessage(serverTime = 0.days, attachment = attachment)
    val messageId = SignalDatabase.messages.insertMessageInbox(message).map { it.messageId }.get()
    SignalDatabase.attachments.setArchiveTransferState(AttachmentId(1L), AttachmentTable.ArchiveTransferState.NONE)
    SignalDatabase.attachments.setTransferState(messageId, AttachmentId(1L), AttachmentTable.TRANSFER_PROGRESS_DONE)
    SignalDatabase.attachments.finalizeAttachmentAfterUpload(AttachmentId(1L), AttachmentTableTestUtil.createUploadResult(AttachmentId(1L)))

    // WHEN
    val attachments = SignalDatabase.attachments.getAttachmentsThatNeedArchiveUpload()

    // THEN
    assertThat(attachments).isEmpty()
  }

  /**
   * There's a race condition where the following was happening:
   *
   * 1. Receive attachment A
   * 2. Download attachment A
   * 3. Enqueue copy to archive job for A (old media name)
   * 4. Receive attachment B that is identical to A
   * 5. Dedupe B with A's data file but update A to match B's "newer" remote key
   * 6. Enqueue copy to archive job for B (new media name)
   * 7. Copy to archive for A succeeds for old media name, updating A and B to FINISHED
   * 8. Copy to archive for B for new media name early aborts because B is already marked FINISHED
   *
   * THe problem is Step 7 because it's marking attachments as archived but under the old media and not the new media name.
   *
   * This tests recreates the flow but ensures Step 7 doesn't mark A and B as finished so that Step 8 will not early abort and copy
   * B over with the new media name.
   */
  @Test
  fun givenAnDuplicateAttachmentPriorToCopyToArchive_whenICopyFirstAttachmentToArchive_thenIDoNotExpectBothAttachmentsToChangeArchiveStateToFinished() {
    val data = byteArrayOf(1, 2, 3, 4, 5)

    val attachment1 = createAttachmentPointer("remote-key-1".toByteArray(), data.size)
    val attachment2 = createAttachmentPointer("remote-key-2".toByteArray(), data.size)

    // Insert Message 1
    val message1Result = SignalDatabase.messages.insertMessageInbox(createIncomingMessage(serverTime = 0.days, attachment = attachment1)).get()
    val message1Id = message1Result.messageId
    val attachment1Id = message1Result.insertedAttachments!![attachment1]!!
    // AttachmentDownloadJob#onAdded
    SignalDatabase.attachments.setTransferState(message1Id, attachment1Id, AttachmentTable.TRANSFER_PROGRESS_STARTED)

    // Insert Message 2
    val message2Result = SignalDatabase.messages.insertMessageInbox(createIncomingMessage(serverTime = 1.days, attachment = attachment2)).get()
    val message2Id = message2Result.messageId
    val attachment2Id = message2Result.insertedAttachments!![attachment2]!!
    // AttachmentDownloadJob#onAdded
    SignalDatabase.attachments.setTransferState(message2Id, attachment2Id, AttachmentTable.TRANSFER_PROGRESS_STARTED)

    // Finalize Attachment 1 download
    SignalDatabase.attachments.finalizeAttachmentAfterDownload(message1Id, attachment1Id, ByteArrayInputStream(data))
    // CopyAttachmentToArchiveJob#onAdded
    SignalDatabase.attachments.setArchiveTransferState(attachment1Id, AttachmentTable.ArchiveTransferState.COPY_PENDING)

    // Verify Attachment 1 data matches original Attachment 1 data from insert
    var dbAttachment1 = SignalDatabase.attachments.getAttachment(attachment1Id)!!
    assertThat(dbAttachment1.archiveTransferState).isEqualTo(AttachmentTable.ArchiveTransferState.COPY_PENDING)
    assertThat(dbAttachment1.remoteKey).isEqualTo(Base64.encodeWithPadding("remote-key-1".toByteArray()))

    val attachment1InitialRemoteKey = dbAttachment1.remoteKey!!
    val attachment1InitialPlaintextHash = dbAttachment1.dataHash!!

    // Finalize Attachment 2
    SignalDatabase.attachments.finalizeAttachmentAfterDownload(message2Id, attachment2Id, ByteArrayInputStream(data))

    // Verify Attachment 1 data matches Attachment 2 data from insert and dedupe in finalize
    dbAttachment1 = SignalDatabase.attachments.getAttachment(attachment1Id)!!
    var dbAttachment2 = SignalDatabase.attachments.getAttachment(attachment2Id)!!
    assertThat(dbAttachment1.archiveTransferState).isEqualTo(AttachmentTable.ArchiveTransferState.NONE)
    assertThat(dbAttachment2.archiveTransferState).isEqualTo(AttachmentTable.ArchiveTransferState.NONE)
    assertThat(dbAttachment1.remoteKey).isEqualTo(dbAttachment2.remoteKey)
    assertThat(dbAttachment1.dataHash).isEqualTo(dbAttachment2.dataHash)

    val attachment2InitialRemoteKey = dbAttachment2.remoteKey!!
    val attachment2InitialPlaintextHash = dbAttachment2.dataHash!!

    // "Finish" Copy to Archive for Attachment 1
    SignalDatabase.attachments.setArchiveTransferState(attachment1Id, attachment1InitialRemoteKey, attachment1InitialPlaintextHash, AttachmentTable.ArchiveTransferState.FINISHED)

    dbAttachment1 = SignalDatabase.attachments.getAttachment(attachment1Id)!!
    dbAttachment2 = SignalDatabase.attachments.getAttachment(attachment2Id)!!

    // Verify Attachment 1 and 2 are not updated as FINISHED since Attachment 1's media name parts have changed
    assertThat(dbAttachment1.archiveTransferState).isEqualTo(AttachmentTable.ArchiveTransferState.NONE)
    assertThat(dbAttachment2.archiveTransferState).isEqualTo(AttachmentTable.ArchiveTransferState.NONE)

    // "Finish" Copy to Archive for Attachment 2
    SignalDatabase.attachments.setArchiveTransferState(attachment2Id, attachment2InitialRemoteKey, attachment2InitialPlaintextHash, AttachmentTable.ArchiveTransferState.FINISHED)

    dbAttachment1 = SignalDatabase.attachments.getAttachment(attachment1Id)!!
    dbAttachment2 = SignalDatabase.attachments.getAttachment(attachment2Id)!!

    // Verify Attachment 1 and 2 are updated as FINISHED
    assertThat(dbAttachment1.archiveTransferState).isEqualTo(AttachmentTable.ArchiveTransferState.FINISHED)
    assertThat(dbAttachment2.archiveTransferState).isEqualTo(AttachmentTable.ArchiveTransferState.FINISHED)
  }

  @Test
  fun givenLocalBackupRestore_whenIFinalizeAttachment_thenIExpectArchiveStateNoneSoItGetsUploaded() {
    val data = byteArrayOf(1, 2, 3, 4, 5)
    val attachment = createAttachmentPointer("remote-key-1".toByteArray(), data.size)

    val messageResult = SignalDatabase.messages.insertMessageInbox(createIncomingMessage(serverTime = 0.days, attachment = attachment)).get()
    val attachmentId = messageResult.insertedAttachments!![attachment]!!
    SignalDatabase.attachments.setTransferState(messageResult.messageId, attachmentId, AttachmentTable.TRANSFER_PROGRESS_STARTED)

    // Data is restored from a local backup file, not the archive CDN
    SignalDatabase.attachments.finalizeAttachmentAfterDownload(messageResult.messageId, attachmentId, ByteArrayInputStream(data), archiveRestore = true, restoredFromArchiveCdn = false)

    val result = SignalDatabase.attachments.getAttachment(attachmentId)!!
    assertThat(result.archiveTransferState).isEqualTo(AttachmentTable.ArchiveTransferState.NONE)
    assertThat(result.archiveCdn).isNull()
  }

  @Test
  fun givenPermanentlyFailedThumbnail_whenIFinalizeAttachment_thenIExpectThumbnailStateNoneSoItCanBeArchivedAgain() {
    val data = byteArrayOf(1, 2, 3, 4, 5)
    val attachment = createAttachmentPointer("remote-key-1".toByteArray(), data.size)

    val messageResult = SignalDatabase.messages.insertMessageInbox(createIncomingMessage(serverTime = 0.days, attachment = attachment)).get()
    val attachmentId = messageResult.insertedAttachments!![attachment]!!
    SignalDatabase.attachments.setTransferState(messageResult.messageId, attachmentId, AttachmentTable.TRANSFER_PROGRESS_STARTED)

    setDataHashEnd(attachmentId, "kMDoNIQjPGiuKzZHUCcLBiIF5wsvE7dqLKvvKG1JLmc=")
    SignalDatabase.attachments.setArchiveThumbnailTransferState(attachmentId, AttachmentTable.ArchiveTransferState.PERMANENT_FAILURE)

    SignalDatabase.attachments.finalizeAttachmentAfterDownload(messageResult.messageId, attachmentId, ByteArrayInputStream(data), archiveRestore = true, restoredFromArchiveCdn = true)

    // Local bytes are back, so the thumbnail is eligible for archiving again
    assertThat(SignalDatabase.attachments.getArchiveThumbnailTransferState(attachmentId)).isEqualTo(AttachmentTable.ArchiveTransferState.NONE)
  }

  @Test
  fun givenPermanentlyFailedThumbnail_whenIFinalizeAnOrdinaryDownload_thenIExpectTheMarkToSurvive() {
    val data = byteArrayOf(1, 2, 3, 4, 5)
    val attachment = createAttachmentPointer("remote-key-1".toByteArray(), data.size)

    val messageResult = SignalDatabase.messages.insertMessageInbox(createIncomingMessage(serverTime = 0.days, attachment = attachment)).get()
    val attachmentId = messageResult.insertedAttachments!![attachment]!!
    SignalDatabase.attachments.setTransferState(messageResult.messageId, attachmentId, AttachmentTable.TRANSFER_PROGRESS_STARTED)
    SignalDatabase.attachments.setArchiveThumbnailTransferState(attachmentId, AttachmentTable.ArchiveTransferState.PERMANENT_FAILURE)

    SignalDatabase.attachments.finalizeAttachmentAfterDownload(messageResult.messageId, attachmentId, ByteArrayInputStream(data))

    assertThat(SignalDatabase.attachments.getArchiveThumbnailTransferState(attachmentId)).isEqualTo(AttachmentTable.ArchiveTransferState.PERMANENT_FAILURE)
  }

  @Test
  fun givenFinishedThumbnail_whenIFinalizeAttachment_thenIExpectThumbnailStateUntouched() {
    val data = byteArrayOf(1, 2, 3, 4, 5)
    val attachment = createAttachmentPointer("remote-key-1".toByteArray(), data.size)

    val messageResult = SignalDatabase.messages.insertMessageInbox(createIncomingMessage(serverTime = 0.days, attachment = attachment)).get()
    val attachmentId = messageResult.insertedAttachments!![attachment]!!
    SignalDatabase.attachments.setTransferState(messageResult.messageId, attachmentId, AttachmentTable.TRANSFER_PROGRESS_STARTED)
    SignalDatabase.attachments.setArchiveThumbnailTransferState(attachmentId, AttachmentTable.ArchiveTransferState.FINISHED)

    SignalDatabase.attachments.finalizeAttachmentAfterDownload(messageResult.messageId, attachmentId, ByteArrayInputStream(data), archiveRestore = true, restoredFromArchiveCdn = true)

    // Only PERMANENT_FAILURE is lifted, an already-archived thumbnail keeps its state
    assertThat(SignalDatabase.attachments.getArchiveThumbnailTransferState(attachmentId)).isEqualTo(AttachmentTable.ArchiveTransferState.FINISHED)
  }

  @Test
  fun givenArchiveCdnRestore_whenIFinalizeAttachment_thenIExpectArchiveStateFinished() {
    val data = byteArrayOf(1, 2, 3, 4, 5)
    val attachment = createAttachmentPointer("remote-key-1".toByteArray(), data.size)

    val messageResult = SignalDatabase.messages.insertMessageInbox(createIncomingMessage(serverTime = 0.days, attachment = attachment)).get()
    val attachmentId = messageResult.insertedAttachments!![attachment]!!
    SignalDatabase.attachments.setTransferState(messageResult.messageId, attachmentId, AttachmentTable.TRANSFER_PROGRESS_STARTED)

    // Data is restored directly from the archive CDN
    SignalDatabase.attachments.finalizeAttachmentAfterDownload(messageResult.messageId, attachmentId, ByteArrayInputStream(data), archiveRestore = true, restoredFromArchiveCdn = true)

    val result = SignalDatabase.attachments.getAttachment(attachmentId)!!
    assertThat(result.archiveTransferState).isEqualTo(AttachmentTable.ArchiveTransferState.FINISHED)
  }

  @Test
  fun givenAttachmentsWithMatchingMediaId_whenISetArchiveFinishedForMatchingMediaObjects_thenIExpectThoseAttachmentsToBeMarkedFinished() {
    // GIVEN
    val data1 = byteArrayOf(1, 2, 3, 4, 5)
    val data2 = byteArrayOf(6, 7, 8, 9, 10)
    val data3 = byteArrayOf(11, 12, 13, 14, 15)

    val attachment1 = createAttachmentPointer("remote-key-1".toByteArray(), data1.size)
    val attachment2 = createAttachmentPointer("remote-key-2".toByteArray(), data2.size)
    val attachment3 = createAttachmentPointer("remote-key-3".toByteArray(), data3.size)

    // Insert messages with attachments
    val message1Result = SignalDatabase.messages.insertMessageInbox(createIncomingMessage(serverTime = 0.days, attachment = attachment1)).get()
    val attachment1Id = message1Result.insertedAttachments!![attachment1]!!
    SignalDatabase.attachments.setTransferState(message1Result.messageId, attachment1Id, AttachmentTable.TRANSFER_PROGRESS_STARTED)
    SignalDatabase.attachments.finalizeAttachmentAfterDownload(message1Result.messageId, attachment1Id, ByteArrayInputStream(data1))

    val message2Result = SignalDatabase.messages.insertMessageInbox(createIncomingMessage(serverTime = 1.days, attachment = attachment2)).get()
    val attachment2Id = message2Result.insertedAttachments!![attachment2]!!
    SignalDatabase.attachments.setTransferState(message2Result.messageId, attachment2Id, AttachmentTable.TRANSFER_PROGRESS_STARTED)
    SignalDatabase.attachments.finalizeAttachmentAfterDownload(message2Result.messageId, attachment2Id, ByteArrayInputStream(data2))

    val message3Result = SignalDatabase.messages.insertMessageInbox(createIncomingMessage(serverTime = 2.days, attachment = attachment3)).get()
    val attachment3Id = message3Result.insertedAttachments!![attachment3]!!
    SignalDatabase.attachments.setTransferState(message3Result.messageId, attachment3Id, AttachmentTable.TRANSFER_PROGRESS_STARTED)
    SignalDatabase.attachments.finalizeAttachmentAfterDownload(message3Result.messageId, attachment3Id, ByteArrayInputStream(data3))

    // Ensure attachments are in NONE state
    assertThat(SignalDatabase.attachments.getAttachment(attachment1Id)!!.archiveTransferState).isEqualTo(AttachmentTable.ArchiveTransferState.NONE)
    assertThat(SignalDatabase.attachments.getAttachment(attachment2Id)!!.archiveTransferState).isEqualTo(AttachmentTable.ArchiveTransferState.NONE)
    assertThat(SignalDatabase.attachments.getAttachment(attachment3Id)!!.archiveTransferState).isEqualTo(AttachmentTable.ArchiveTransferState.NONE)

    // Build media objects only for attachments 1 and 2 (not 3)
    val dbAttachment1 = SignalDatabase.attachments.getAttachment(attachment1Id)!!
    val dbAttachment2 = SignalDatabase.attachments.getAttachment(attachment2Id)!!

    val mediaId1 = MediaName.fromPlaintextHashAndRemoteKey(
      dbAttachment1.dataHash!!.decodeBase64OrThrow(),
      dbAttachment1.remoteKey!!.decodeBase64OrThrow()
    ).toMediaId(SignalStore.backup.mediaRootBackupKey).encode()

    val mediaId2 = MediaName.fromPlaintextHashAndRemoteKey(
      dbAttachment2.dataHash!!.decodeBase64OrThrow(),
      dbAttachment2.remoteKey!!.decodeBase64OrThrow()
    ).toMediaId(SignalStore.backup.mediaRootBackupKey).encode()

    val archivedMediaObjects = setOf(
      ArchivedMediaObject(mediaId = mediaId1, cdn = 5),
      ArchivedMediaObject(mediaId = mediaId2, cdn = 6)
    )

    // WHEN
    val updatedCount = SignalDatabase.attachments.setArchiveFinishedForMatchingMediaObjects(archivedMediaObjects)

    // THEN
    assertThat(updatedCount).isEqualTo(2)

    val resultAttachment1 = SignalDatabase.attachments.getAttachment(attachment1Id)!!
    val resultAttachment2 = SignalDatabase.attachments.getAttachment(attachment2Id)!!
    val resultAttachment3 = SignalDatabase.attachments.getAttachment(attachment3Id)!!

    // Attachments 1 and 2 should be FINISHED with their respective CDNs
    assertThat(resultAttachment1.archiveTransferState).isEqualTo(AttachmentTable.ArchiveTransferState.FINISHED)
    assertThat(resultAttachment1.archiveCdn).isEqualTo(5)

    assertThat(resultAttachment2.archiveTransferState).isEqualTo(AttachmentTable.ArchiveTransferState.FINISHED)
    assertThat(resultAttachment2.archiveCdn).isEqualTo(6)

    // Attachment 3 should still be NONE (not in the set)
    assertThat(resultAttachment3.archiveTransferState).isEqualTo(AttachmentTable.ArchiveTransferState.NONE)
  }

  @Test
  fun givenEmptyMediaObjectsSet_whenISetArchiveFinishedForMatchingMediaObjects_thenIExpectZeroUpdates() {
    // GIVEN
    val data = byteArrayOf(1, 2, 3, 4, 5)
    val attachment = createAttachmentPointer("remote-key-1".toByteArray(), data.size)

    val messageResult = SignalDatabase.messages.insertMessageInbox(createIncomingMessage(serverTime = 0.days, attachment = attachment)).get()
    val attachmentId = messageResult.insertedAttachments!![attachment]!!
    SignalDatabase.attachments.setTransferState(messageResult.messageId, attachmentId, AttachmentTable.TRANSFER_PROGRESS_STARTED)
    SignalDatabase.attachments.finalizeAttachmentAfterDownload(messageResult.messageId, attachmentId, ByteArrayInputStream(data))

    // WHEN
    val updatedCount = SignalDatabase.attachments.setArchiveFinishedForMatchingMediaObjects(emptySet())

    // THEN
    assertThat(updatedCount).isEqualTo(0)
    assertThat(SignalDatabase.attachments.getAttachment(attachmentId)!!.archiveTransferState).isEqualTo(AttachmentTable.ArchiveTransferState.NONE)
  }

  @Test
  fun givenAlreadyFinishedAttachment_whenISetArchiveFinishedForMatchingMediaObjects_thenIExpectNoUpdate() {
    // GIVEN
    val data = byteArrayOf(1, 2, 3, 4, 5)
    val attachment = createAttachmentPointer("remote-key-1".toByteArray(), data.size)

    val messageResult = SignalDatabase.messages.insertMessageInbox(createIncomingMessage(serverTime = 0.days, attachment = attachment)).get()
    val attachmentId = messageResult.insertedAttachments!![attachment]!!
    SignalDatabase.attachments.setTransferState(messageResult.messageId, attachmentId, AttachmentTable.TRANSFER_PROGRESS_STARTED)
    SignalDatabase.attachments.finalizeAttachmentAfterDownload(messageResult.messageId, attachmentId, ByteArrayInputStream(data))

    // Mark as already FINISHED
    SignalDatabase.attachments.setArchiveTransferState(attachmentId, AttachmentTable.ArchiveTransferState.FINISHED)

    val dbAttachment = SignalDatabase.attachments.getAttachment(attachmentId)!!
    val mediaId = MediaName.fromPlaintextHashAndRemoteKey(
      dbAttachment.dataHash!!.decodeBase64OrThrow(),
      dbAttachment.remoteKey!!.decodeBase64OrThrow()
    ).toMediaId(SignalStore.backup.mediaRootBackupKey).encode()

    val archivedMediaObjects = setOf(
      ArchivedMediaObject(mediaId = mediaId, cdn = 5)
    )

    // WHEN
    val updatedCount = SignalDatabase.attachments.setArchiveFinishedForMatchingMediaObjects(archivedMediaObjects)

    // THEN - should not update since already FINISHED
    assertThat(updatedCount).isEqualTo(0)
  }

  /**
   * Offloading deletes the only local copy, so it must be gated on evidence that came from the server. Being in the snapshot is our own bookkeeping. Only
   * last_seen_on_remote_snapshot_version is the part the CDN told us, so only it can authorize the delete.
   */
  @Test
  fun givenAnOffloadCandidateConfirmedOnTheCdn_whenIOptimize_thenIExpectItOffloaded() {
    val attachmentId = seedOffloadCandidate()
    val snapshotVersion = commitSnapshotFor(attachmentId, markSeenOnRemote = true)

    SignalDatabase.attachments.markEligibleAttachmentsAsOptimized(lastCompletedCrawlVersion = snapshotVersion, minimumAge = 30.days, now = pastTheOffloadWindow())

    assertThat(SignalDatabase.attachments.getAttachment(attachmentId)!!.transferState).isEqualTo(AttachmentTable.TRANSFER_RESTORE_OFFLOADED)
  }

  @Test
  fun givenAnOffloadCandidateNeverSeenOnTheCdn_whenIOptimize_thenIExpectItLeftAlone() {
    val attachmentId = seedOffloadCandidate()
    val snapshotVersion = commitSnapshotFor(attachmentId, markSeenOnRemote = false)

    SignalDatabase.attachments.markEligibleAttachmentsAsOptimized(lastCompletedCrawlVersion = snapshotVersion, minimumAge = 30.days, now = pastTheOffloadWindow())

    assertThat(SignalDatabase.attachments.getAttachment(attachmentId)!!.transferState).isEqualTo(AttachmentTable.TRANSFER_PROGRESS_DONE)
  }

  @Test
  fun givenNoCompletedReconciliation_whenIOptimize_thenIExpectNothingOffloaded() {
    val attachmentId = seedOffloadCandidate()
    commitSnapshotFor(attachmentId, markSeenOnRemote = true)

    SignalDatabase.attachments.markEligibleAttachmentsAsOptimized(lastCompletedCrawlVersion = -1, minimumAge = 30.days, now = pastTheOffloadWindow())

    assertThat(SignalDatabase.attachments.getAttachment(attachmentId)!!.transferState).isEqualTo(AttachmentTable.TRANSFER_PROGRESS_DONE)
  }

  /**
   * Coverage across pages: if only the first page were processed, the overflow would silently never be reclaimed.
   */
  @Test
  fun givenMoreConfirmedCandidatesThanOnePage_whenIOptimize_thenIExpectEveryPageOffloaded() {
    val attachmentIds = List(TEST_PAGE_SIZE * 3) { seedOffloadCandidate() }
    val snapshotVersion = commitSnapshotForAll(attachmentIds, markSeenOnRemote = true)

    SignalDatabase.attachments.markEligibleAttachmentsAsOptimized(lastCompletedCrawlVersion = snapshotVersion, minimumAge = 30.days, now = pastTheOffloadWindow(), pageSize = TEST_PAGE_SIZE)

    assertThat(attachmentIds.count { isOffloaded(it) }).isEqualTo(attachmentIds.size)
  }

  /**
   * Termination when nothing is offloadable. Unconfirmed candidates stay eligible forever, so a loop that ran until the candidate query drained would spin here
   * and this test would hang rather than fail. Only the advancing id cursor ends it.
   */
  @Test
  fun givenMoreUnconfirmedCandidatesThanOnePage_whenIOptimize_thenIExpectItToTerminateAndOffloadNothing() {
    val attachmentIds = List(TEST_PAGE_SIZE * 3) { seedOffloadCandidate() }
    val snapshotVersion = commitSnapshotForAll(attachmentIds, markSeenOnRemote = false)

    SignalDatabase.attachments.markEligibleAttachmentsAsOptimized(lastCompletedCrawlVersion = snapshotVersion, minimumAge = 30.days, now = pastTheOffloadWindow(), pageSize = TEST_PAGE_SIZE)

    assertThat(attachmentIds.count { isOffloaded(it) }).isEqualTo(0)
  }

  /**
   * Stickers come back from their pack rather than the archive, so they are never offloaded no matter what the CDN confirms.
   */
  @Test
  fun givenAStickerOffloadCandidate_whenIOptimize_thenIExpectItLeftAlone() {
    val attachmentId = seedOffloadCandidate()
    val snapshotVersion = commitSnapshotFor(attachmentId, markSeenOnRemote = true)
    markAsSticker(attachmentId)

    SignalDatabase.attachments.markEligibleAttachmentsAsOptimized(lastCompletedCrawlVersion = snapshotVersion, minimumAge = 30.days, now = pastTheOffloadWindow())

    assertThat(isOffloaded(attachmentId)).isFalse()
  }

  /**
   * Offloading an image with no thumbnail would leave nothing at all to render in the conversation, so visual media has to keep a local thumbnail to qualify.
   */
  @Test
  fun givenAnImageOffloadCandidateWithNoThumbnail_whenIOptimize_thenIExpectItLeftAlone() {
    val attachmentId = seedOffloadCandidate(contentType = MediaUtil.IMAGE_JPEG)
    val snapshotVersion = commitSnapshotFor(attachmentId, markSeenOnRemote = true)

    SignalDatabase.attachments.markEligibleAttachmentsAsOptimized(lastCompletedCrawlVersion = snapshotVersion, minimumAge = 30.days, now = pastTheOffloadWindow())

    assertThat(isOffloaded(attachmentId)).isFalse()
  }

  /**
   * Media the user just pulled back down would otherwise be offloaded again immediately, undoing the restore they asked for.
   */
  @Test
  fun givenARecentlyRestoredOffloadCandidate_whenIOptimize_thenIExpectItLeftAlone() {
    val attachmentId = seedOffloadCandidate()
    val snapshotVersion = commitSnapshotFor(attachmentId, markSeenOnRemote = true)
    val evaluatedAt = pastTheOffloadWindow()
    markRestoredAt(attachmentId, evaluatedAt)

    SignalDatabase.attachments.markEligibleAttachmentsAsOptimized(lastCompletedCrawlVersion = snapshotVersion, minimumAge = 30.days, now = evaluatedAt)

    assertThat(isOffloaded(attachmentId)).isFalse()
  }

  @Test
  fun givenAnOffloadCandidateOnATooRecentMessage_whenIOptimize_thenIExpectItLeftAlone() {
    val attachmentId = seedOffloadCandidate()
    val snapshotVersion = commitSnapshotFor(attachmentId, markSeenOnRemote = true)

    SignalDatabase.attachments.markEligibleAttachmentsAsOptimized(lastCompletedCrawlVersion = snapshotVersion, minimumAge = 30.days, now = System.currentTimeMillis())

    assertThat(isOffloaded(attachmentId)).isFalse()
  }

  /** Far enough ahead of the seeded message that both the offload age and the 7-day offload-restore window are satisfied. */
  private fun pastTheOffloadWindow(): Long = System.currentTimeMillis() + 60.days.inWholeMilliseconds

  /** A full locator, because a partial one makes reading the attachment back blow up on the sticker fields. */
  private fun markAsSticker(attachmentId: AttachmentId) {
    SignalDatabase.rawDatabase
      .update(AttachmentTable.TABLE_NAME)
      .values(
        AttachmentTable.STICKER_ID to 7,
        AttachmentTable.STICKER_PACK_ID to "aa1111bbcc2222ddee3333ff44445555",
        AttachmentTable.STICKER_PACK_KEY to "YWJjZGVmZ2hpamtsbW5vcHFyc3R1dnd4eXoxMjM0NTY3ODkwYWI=",
        AttachmentTable.STICKER_EMOJI to ":)"
      )
      .where("${AttachmentTable.ID} = ?", attachmentId.id)
      .run()
  }

  private fun markRestoredAt(attachmentId: AttachmentId, restoredAt: Long) {
    SignalDatabase.rawDatabase
      .update(AttachmentTable.TABLE_NAME)
      .values(AttachmentTable.OFFLOAD_RESTORED_AT to restoredAt)
      .where("${AttachmentTable.ID} = ?", attachmentId.id)
      .run()
  }

  private fun setDataHashEnd(attachmentId: AttachmentId, hashEnd: String) {
    SignalDatabase.rawDatabase
      .update(AttachmentTable.TABLE_NAME)
      .values(AttachmentTable.DATA_HASH_END to hashEnd)
      .where("${AttachmentTable.ID} = ?", attachmentId.id)
      .run()
  }

  /** Defaults to a non-media content type, which keeps the candidate eligible without also having to generate a thumbnail file. */
  private fun seedOffloadCandidate(contentType: String = "application/pdf"): AttachmentId {
    val data = byteArrayOf(1, 2, 3, 4, 5)
    val attachment = createAttachmentPointer(Random.nextBytes(32), data.size, contentType = contentType)
    val messageResult = SignalDatabase.messages.insertMessageInbox(createIncomingMessage(serverTime = System.currentTimeMillis().milliseconds, attachment = attachment)).get()
    val attachmentId = messageResult.insertedAttachments!![attachment]!!

    SignalDatabase.attachments.setTransferState(messageResult.messageId, attachmentId, AttachmentTable.TRANSFER_PROGRESS_STARTED)
    SignalDatabase.attachments.finalizeAttachmentAfterDownload(messageResult.messageId, attachmentId, ByteArrayInputStream(data))
    SignalDatabase.attachments.setArchiveTransferState(attachmentId, AttachmentTable.ArchiveTransferState.FINISHED)

    return attachmentId
  }

  /**
   * Commits every entry in a single snapshot version. Calling [commitSnapshotFor] in a loop would not work: each commit bumps the version, leaving all but the
   * last entry below MAX_VERSION and therefore unconfirmable.
   */
  private fun commitSnapshotForAll(attachmentIds: List<AttachmentId>, markSeenOnRemote: Boolean): Long {
    val mediaIds: MutableList<String> = mutableListOf()
    val entries: MutableList<BackupMediaSnapshotTable.MediaEntry> = mutableListOf()

    for (attachmentId in attachmentIds) {
      val attachment = SignalDatabase.attachments.getAttachment(attachmentId)!!
      val plaintextHash = attachment.dataHash!!.decodeBase64OrThrow()
      val remoteKey = attachment.remoteKey!!.decodeBase64OrThrow()
      val mediaId = MediaName.fromPlaintextHashAndRemoteKey(plaintextHash, remoteKey).toMediaId(SignalStore.backup.mediaRootBackupKey).encode()

      mediaIds += mediaId
      entries += BackupMediaSnapshotTable.MediaEntry(mediaId = mediaId, cdn = 3, plaintextHash = plaintextHash, remoteKey = remoteKey, isThumbnail = false)
    }

    SignalDatabase.backupMediaSnapshots.writePendingMediaEntries(entries)
    SignalDatabase.backupMediaSnapshots.commitPendingRows()

    val snapshotVersion = SignalDatabase.backupMediaSnapshots.getCurrentSnapshotVersion()
    if (markSeenOnRemote) {
      SignalDatabase.backupMediaSnapshots.markSeenOnRemote(mediaIds, snapshotVersion)
    }

    return snapshotVersion
  }

  private fun isOffloaded(attachmentId: AttachmentId): Boolean {
    return SignalDatabase.attachments.getAttachment(attachmentId)!!.transferState == AttachmentTable.TRANSFER_RESTORE_OFFLOADED
  }

  private fun commitSnapshotFor(attachmentId: AttachmentId, markSeenOnRemote: Boolean): Long {
    val attachment = SignalDatabase.attachments.getAttachment(attachmentId)!!
    val plaintextHash = attachment.dataHash!!.decodeBase64OrThrow()
    val remoteKey = attachment.remoteKey!!.decodeBase64OrThrow()
    val mediaId = MediaName.fromPlaintextHashAndRemoteKey(plaintextHash, remoteKey).toMediaId(SignalStore.backup.mediaRootBackupKey).encode()

    SignalDatabase.backupMediaSnapshots.writePendingMediaEntries(
      listOf(BackupMediaSnapshotTable.MediaEntry(mediaId = mediaId, cdn = 3, plaintextHash = plaintextHash, remoteKey = remoteKey, isThumbnail = false))
    )
    SignalDatabase.backupMediaSnapshots.commitPendingRows()

    val snapshotVersion = SignalDatabase.backupMediaSnapshots.getCurrentSnapshotVersion()
    if (markSeenOnRemote) {
      SignalDatabase.backupMediaSnapshots.markSeenOnRemote(listOf(mediaId), snapshotVersion)
    }

    return snapshotVersion
  }

  private fun createIncomingMessage(
    serverTime: Duration,
    attachment: Attachment,
    expiresIn: Duration = Duration.ZERO
  ): IncomingMessage {
    return IncomingMessage(
      type = MessageType.NORMAL,
      from = harness.others[0],
      body = null,
      expiresIn = expiresIn.inWholeMilliseconds,
      sentTimeMillis = serverTime.inWholeMilliseconds,
      serverTimeMillis = serverTime.inWholeMilliseconds,
      receivedTimeMillis = serverTime.inWholeMilliseconds,
      attachments = listOf(attachment)
    )
  }

  private fun createAttachmentPointer(key: ByteArray, size: Int, contentType: String = MediaUtil.IMAGE_JPEG): Attachment {
    return PointerAttachment.forPointer(
      pointer = Optional.of(
        SignalServiceAttachmentPointer(
          cdnNumber = 3,
          remoteId = SignalServiceAttachmentRemoteId.V4("asdf"),
          contentType = contentType,
          key = key,
          size = Optional.of(size),
          preview = Optional.empty(),
          width = 2,
          height = 2,
          digest = Optional.of(byteArrayOf()),
          incrementalDigest = Optional.empty(),
          incrementalMacChunkSize = 0,
          fileName = Optional.of("file.jpg"),
          voiceNote = false,
          isBorderless = false,
          isGif = false,
          caption = Optional.empty(),
          blurHash = Optional.empty(),
          uploadTimestamp = 0,
          uuid = null
        )
      )
    ).get()
  }

  private fun createArchivedAttachment(localBackupKey: ByteArray? = null): Attachment {
    return ArchivedAttachment(
      contentType = "image/jpeg",
      size = 1024,
      cdn = 3,
      uploadTimestamp = 0,
      key = Random.nextBytes(8),
      cdnKey = "password",
      archiveCdn = 3,
      plaintextHash = Random.nextBytes(8),
      incrementalMac = Random.nextBytes(8),
      incrementalMacChunkSize = 8,
      width = 100,
      height = 100,
      caption = null,
      blurHash = null,
      voiceNote = false,
      borderless = false,
      stickerLocator = null,
      gif = false,
      quote = false,
      quoteTargetContentType = null,
      uuid = UUID.randomUUID(),
      fileName = null,
      localBackupKey = localBackupKey
    )
  }

  private fun createAttachment(id: Long, uri: Uri, transformProperties: TransformProperties, contentType: String = MediaUtil.IMAGE_JPEG): UriAttachment {
    return UriAttachmentBuilder.build(
      id,
      uri = uri,
      contentType = contentType,
      transformProperties = transformProperties
    )
  }

  private fun createHighQualityTransformProperties(): TransformProperties {
    return transformPropertiesForSentMediaQuality(Optional.empty(), SentMediaQuality.HIGH)
  }

  private fun createMediaStream(byteArray: ByteArray): MediaStream {
    return MediaStream(byteArray.inputStream(), MediaUtil.IMAGE_JPEG, 2, 2)
  }

  private fun getDigest(ciphertext: ByteArray): ByteArray {
    val digestStream = NoCipherOutputStream(NullOutputStream)
    ciphertext.inputStream().copyTo(digestStream)
    return digestStream.transmittedDigest
  }

  private fun encryptPrePaddedBytes(plaintext: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
    val outputStream = ByteArrayOutputStream()
    val cipherStream = AttachmentCipherOutputStream(key, iv, outputStream)
    plaintext.inputStream().copyTo(cipherStream)

    return outputStream.toByteArray()
  }

  private fun getTempFile(): File {
    val dir = InstrumentationRegistry.getInstrumentation().targetContext.getDir("temp", Context.MODE_PRIVATE)
    dir.mkdir()
    return File.createTempFile("transfer", ".mms", dir)
  }
}
