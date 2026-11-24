/*
 * Copyright 2025 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.database

import android.app.Application
import android.content.ContentValues
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import io.mockk.every
import io.mockk.slot
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.database.update
import org.signal.core.models.database.AttachmentId
import org.signal.core.util.Base64
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.jobmanager.Job
import org.thoughtcrime.securesms.jobs.CopyAttachmentToArchiveJob
import org.thoughtcrime.securesms.jobs.UploadAttachmentToArchiveJob
import org.thoughtcrime.securesms.jobs.protos.CopyAttachmentToArchiveJobData
import org.thoughtcrime.securesms.jobs.protos.UploadAttachmentToArchiveJobData
import org.thoughtcrime.securesms.testutil.MockAppDependenciesRule
import org.thoughtcrime.securesms.testutil.SignalDatabaseRule
import org.thoughtcrime.securesms.testutil.SystemOutLogger
import java.util.UUID

@Suppress("ClassName")
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class AttachmentTableTest_createRemoteKeyForAttachmentsThatNeedArchiveUpload {

  @get:Rule val signalDatabaseRule = SignalDatabaseRule()

  @get:Rule val applicationDependencies = MockAppDependenciesRule()

  companion object {
    @BeforeClass
    @JvmStatic
    fun setUpClass() {
      Log.initialize(SystemOutLogger())
    }
  }

  @Test
  fun whenNoEligibleAttachments_returnsZero() {
    val result = SignalDatabase.attachments.createRemoteKeyForAttachmentsThatNeedArchiveUpload()
    assertThat(result.totalCount).isEqualTo(0)
  }

  @Test
  fun whenAttachmentHasArchiveTransferStateInProgress_returnsZero() {
    val attachmentId = insertWithData()
    SignalDatabase.attachments.setArchiveTransferState(attachmentId, AttachmentTable.ArchiveTransferState.UPLOAD_IN_PROGRESS)

    val result = SignalDatabase.attachments.createRemoteKeyForAttachmentsThatNeedArchiveUpload()
    assertThat(result.totalCount).isEqualTo(0)
  }

  @Test
  fun whenAttachmentMissingDataFile_returnsZero() {
    val attachmentId = insertWithoutData() // No data file set

    val result = SignalDatabase.attachments.createRemoteKeyForAttachmentsThatNeedArchiveUpload()
    assertThat(result.totalCount).isEqualTo(0)
  }

  @Test
  fun whenTransferStateNotDone_returnsZero() {
    val attachmentId = insertWithData()
    SignalDatabase.attachments.setTransferState(1L, attachmentId, 1) // Not TRANSFER_PROGRESS_DONE (0)

    val result = SignalDatabase.attachments.createRemoteKeyForAttachmentsThatNeedArchiveUpload()
    assertThat(result.totalCount).isEqualTo(0)
  }

  @Test
  fun whenAttachmentAlreadyHasRemoteKey_returnsZero() {
    val attachmentId = insertWithData()
    // Set a remote key
    val remoteKey = Base64.encodeWithPadding(byteArrayOf(1, 2, 3, 4))
    setRemoteKey(attachmentId, remoteKey)

    val result = SignalDatabase.attachments.createRemoteKeyForAttachmentsThatNeedArchiveUpload()
    assertThat(result.totalCount).isEqualTo(0)
  }

  @Test
  fun whenOneEligibleAttachment_returnsOneAndCreatesRemoteKey() {
    val attachmentId = insertWithData()

    // Verify attachment has no remote key initially
    val attachmentBefore = SignalDatabase.attachments.getAttachment(attachmentId)
    assertThat(attachmentBefore?.remoteKey).isNull()

    val result = SignalDatabase.attachments.createRemoteKeyForAttachmentsThatNeedArchiveUpload()
    assertThat(result.totalCount).isEqualTo(1)

    // Verify remote key was created
    val attachmentAfter = SignalDatabase.attachments.getAttachment(attachmentId)
    assertThat(attachmentAfter?.remoteKey).isNotNull()
  }

  @Test
  fun whenMultipleEligibleAttachments_returnsCorrectCountAndCreatesKeys() {
    val attachmentId1 = insertWithData()
    val attachmentId2 = insertWithData()
    val attachmentId3 = insertWithData()

    // Verify all attachments have no remote keys initially
    assertThat(SignalDatabase.attachments.getAttachment(attachmentId1)?.remoteKey).isNull()
    assertThat(SignalDatabase.attachments.getAttachment(attachmentId2)?.remoteKey).isNull()
    assertThat(SignalDatabase.attachments.getAttachment(attachmentId3)?.remoteKey).isNull()

    val result = SignalDatabase.attachments.createRemoteKeyForAttachmentsThatNeedArchiveUpload()
    assertThat(result.totalCount).isEqualTo(3)

    // Verify all remote keys were created
    assertThat(SignalDatabase.attachments.getAttachment(attachmentId1)?.remoteKey).isNotNull()
    assertThat(SignalDatabase.attachments.getAttachment(attachmentId2)?.remoteKey).isNotNull()
    assertThat(SignalDatabase.attachments.getAttachment(attachmentId3)?.remoteKey).isNotNull()
  }

  @Test
  fun whenMixedScenarios_returnsCorrectCount() {
    // Eligible attachment - has data file, transfer done, archive state NONE, no remote key
    val eligibleAttachmentId = insertWithData()

    // Ineligible - has remote key already
    val attachmentWithKeyId = insertWithData()
    setRemoteKey(attachmentWithKeyId, Base64.encodeWithPadding(byteArrayOf(1, 2, 3, 4)))

    // Ineligible - archive transfer state is not NONE
    val inProgressAttachmentId = insertWithData()
    SignalDatabase.attachments.setArchiveTransferState(inProgressAttachmentId, AttachmentTable.ArchiveTransferState.UPLOAD_IN_PROGRESS)

    // Ineligible - no data file
    val noDataFileAttachmentId = insertWithoutData()

    val result = SignalDatabase.attachments.createRemoteKeyForAttachmentsThatNeedArchiveUpload()
    assertThat(result.totalCount).isEqualTo(1)

    // Verify only the eligible attachment got a remote key
    assertThat(SignalDatabase.attachments.getAttachment(eligibleAttachmentId)?.remoteKey).isNotNull()
    assertThat(SignalDatabase.attachments.getAttachment(attachmentWithKeyId)?.remoteKey).isNotNull() // Already had one
    assertThat(SignalDatabase.attachments.getAttachment(inProgressAttachmentId)?.remoteKey).isNull()
    assertThat(SignalDatabase.attachments.getAttachment(noDataFileAttachmentId)?.remoteKey).isNull()
  }

  @Test
  fun whenCalledTwice_secondCallReturnsZero() {
    val attachmentId = insertWithData()

    // First call should create remote key
    val firstResult = SignalDatabase.attachments.createRemoteKeyForAttachmentsThatNeedArchiveUpload()
    assertThat(firstResult.totalCount).isEqualTo(1)

    // Second call should find no eligible attachments since remote key now exists
    val secondResult = SignalDatabase.attachments.createRemoteKeyForAttachmentsThatNeedArchiveUpload()
    assertThat(secondResult.totalCount).isEqualTo(0)

    // Verify attachment still has remote key
    assertThat(SignalDatabase.attachments.getAttachment(attachmentId)?.remoteKey).isNotNull()
  }

  @Test
  fun archivePendingTransitionKeepsItsOwnerAndRejectsAReplacedDataFile() {
    val attachmentId = insertWithData()
    val initial = SignalDatabase.attachments.getUploadCandidate(attachmentId)!!
    val owner = SignalDatabase.attachments.archiveOwnerToken(initial)

    assertThat(SignalDatabase.attachments.markArchivePendingIfOwnerMatches(attachmentId, owner, AttachmentTable.ArchiveTransferState.UPLOAD_IN_PROGRESS)).isTrue()
    val pending = SignalDatabase.attachments.getUploadCandidate(attachmentId)!!
    assertThat(SignalDatabase.attachments.archiveOwnerToken(pending)).isEqualTo(owner)

    SignalDatabase.attachments.writableDatabase.update(AttachmentTable.TABLE_NAME)
      .values(AttachmentTable.DATA_RANDOM to "replacement-random".toByteArray())
      .where("${AttachmentTable.ID} = ?", attachmentId.id)
      .run()

    assertThat(SignalDatabase.attachments.getArchiveCandidateIfOwnerMatches(attachmentId, owner)).isNull()
    assertThat(SignalDatabase.attachments.markArchivePendingIfOwnerMatches(attachmentId, owner, AttachmentTable.ArchiveTransferState.COPY_PENDING)).isFalse()
    assertThat(SignalDatabase.attachments.getArchiveTransferState(attachmentId)).isEqualTo(AttachmentTable.ArchiveTransferState.UPLOAD_IN_PROGRESS)
  }

  @Test
  fun archiveCopyFallbackKeepsItsOwnerAcrossReplacementAndFactoryRestore() {
    val id = insertWithData()
    val captured = SignalDatabase.attachments.getUploadCandidate(id)!!
    val owner = SignalDatabase.attachments.archiveOwnerToken(captured)
    assertThat(SignalDatabase.attachments.markArchivePendingIfOwnerMatches(id, owner, AttachmentTable.ArchiveTransferState.COPY_PENDING)).isTrue()
    val copying = SignalDatabase.attachments.getUploadCandidate(id)!!
    assertThat(SignalDatabase.attachments.setArchiveStateIfCandidateUnchanged(copying, AttachmentTable.ArchiveTransferState.NONE)).isTrue()

    replaceVersion(id, changePath = true)
    val successor = UploadAttachmentToArchiveJob(id, canReuseUpload = false, expectedOwner = owner)
    val serialized = successor.serialize()
    assertThat(UploadAttachmentToArchiveJobData.ADAPTER.decode(serialized).ownerIdentity).isEqualTo(owner)
    val restored = UploadAttachmentToArchiveJob.Factory().create(Job.Parameters.Builder().build(), serialized)
    restored.onAdded()
    assertThat(SignalDatabase.attachments.getArchiveTransferState(id)).isEqualTo(AttachmentTable.ArchiveTransferState.NONE)
    assertThat(restored.run().isSuccess).isTrue()
    assertThat(SignalDatabase.attachments.getArchiveTransferState(id)).isEqualTo(AttachmentTable.ArchiveTransferState.NONE)
    assertThat(SignalDatabase.attachments.getAttachment(id)!!.remoteKey).isNull()
  }

  @Test
  fun legacyUploadPayloadIsReissuedWithCurrentAttachmentOwner() {
    val id = insertWithData()
    val owner = SignalDatabase.attachments.archiveOwnerToken(SignalDatabase.attachments.getUploadCandidate(id)!!)
    val legacy = UploadAttachmentToArchiveJobData(attachmentId = id.id, canReuseUpload = false)
    val job = UploadAttachmentToArchiveJob.Factory().create(Job.Parameters.Builder().build(), legacy.encode())
    val enqueued = slot<Job>()

    every { AppDependencies.jobManager.add(capture(enqueued)) } returns Unit

    assertThat(job.run().isSuccess).isTrue()
    verify(exactly = 1) { AppDependencies.jobManager.add(any<UploadAttachmentToArchiveJob>()) }
    assertThat(UploadAttachmentToArchiveJobData.ADAPTER.decode(enqueued.captured.serialize()!!).ownerIdentity).isEqualTo(owner)
  }

  @Test
  fun legacyCopyPayloadIsReissuedWithCurrentAttachmentOwner() {
    val id = insertWithData()
    val owner = SignalDatabase.attachments.archiveOwnerToken(SignalDatabase.attachments.getUploadCandidate(id)!!)
    val legacy = CopyAttachmentToArchiveJobData(attachmentId = id.id)
    val job = CopyAttachmentToArchiveJob.Factory().create(Job.Parameters.Builder().build(), legacy.encode())
    val enqueued = slot<Job>()

    every { AppDependencies.jobManager.add(capture(enqueued)) } returns Unit

    assertThat(job.run().isSuccess).isTrue()
    verify(exactly = 1) { AppDependencies.jobManager.add(any<CopyAttachmentToArchiveJob>()) }
    assertThat(CopyAttachmentToArchiveJobData.ADAPTER.decode(enqueued.captured.serialize()!!).ownerIdentity).isEqualTo(owner)
  }

  @Test
  fun whenAnotherUploadSetsKeyAfterSelection_preservesItsRemoteIdentityAndDoesNotCountWrite() {
    val id = insertWithData()
    val establishedKey = Base64.encodeWithPadding(ByteArray(64) { 7 })
    val digest = byteArrayOf(3, 2, 1)
    afterSelection("Missing key. Assigning new one.", {
      SignalDatabase.attachments.writableDatabase.update(AttachmentTable.TABLE_NAME)
        .values(
          AttachmentTable.REMOTE_KEY to establishedKey,
          AttachmentTable.REMOTE_LOCATION to "completed-upload",
          AttachmentTable.REMOTE_DIGEST to digest
        )
        .where("${AttachmentTable.ID} = ?", id.id).run()
    }) {
      val result = SignalDatabase.attachments.createRemoteKeyForAttachmentsThatNeedArchiveUpload()
      assertThat(result.totalCount).isEqualTo(0)
      assertThat(result.notQuoteOrStickerDupeNotFoundCount).isEqualTo(0)
    }
    val row = SignalDatabase.attachments.getAttachment(id)!!
    assertThat(row.remoteKey).isEqualTo(establishedKey)
    assertThat(row.remoteLocation).isEqualTo("completed-upload")
    assertThat(row.remoteDigest.contentEquals(digest)).isTrue()
  }

  @Test
  fun whenBulkDonorTargetChangesFileAfterSelection_doesNotAdoptRemoteIdentity() {
    assertBulkDonorVersionChangeIsRejected(changePath = true)
  }

  @Test
  fun whenBulkDonorTargetChangesRandomAfterSelection_doesNotAdoptRemoteIdentity() {
    assertBulkDonorVersionChangeIsRejected(changePath = false)
  }

  @Test
  fun whenBulkGeneratedKeyTargetChangesVersionAfterSelection_doesNotCountWrite() {
    val id = insertWithData()
    afterSelection("Missing key. Assigning new one.", {
      SignalDatabase.attachments.writableDatabase.update(AttachmentTable.TABLE_NAME)
        .values(AttachmentTable.DATA_RANDOM to byteArrayOf(5, 4, 3))
        .where("${AttachmentTable.ID} = ?", id.id).run()
    }) {
      assertThat(SignalDatabase.attachments.createRemoteKeyForAttachmentsThatNeedArchiveUpload().totalCount).isEqualTo(0)
    }
    assertThat(SignalDatabase.attachments.getAttachment(id)!!.remoteKey).isNull()
  }

  @Test
  fun whenDirectKeyHelperTargetChangesVersionAfterSelection_rejectsEveryAdoptionBranch() {
    for (donor in listOf("full", "key-only", "none")) {
      for (changePath in listOf(true, false)) {
        val path = "/shared/${UUID.randomUUID()}"
        if (donor != "none") {
          insertAttachmentDirectly(
            path, AttachmentTable.TRANSFER_PROGRESS_DONE, AttachmentTable.ArchiveTransferState.FINISHED.value,
            Base64.encodeWithPadding(ByteArray(64) { 8 }),
            remoteLocation = if (donor == "full") "donor-location" else null,
            remoteDigest = if (donor == "full") byteArrayOf(1, 2, 3) else null
          )
        }
        val id = insertWithData(path)
        val message = when (donor) {
          "full" -> "Found duplicate with full remote data."
          "key-only" -> "Found duplicate with key only."
          else -> "Missing key. Assigning new one."
        }
        afterSelection(message, { replaceVersion(id, changePath) }) {
          SignalDatabase.attachments.createRemoteKeyIfNecessary(id)
        }
        val row = SignalDatabase.attachments.getAttachment(id)!!
        assertThat(row.remoteKey).isNull()
        assertThat(row.remoteLocation).isNull()
        assertThat(row.remoteDigest).isNull()
      }
    }
  }

  private fun assertBulkDonorVersionChangeIsRejected(changePath: Boolean) {
    val path = "/shared/${UUID.randomUUID()}"
    insertAttachmentDirectly(
      path, AttachmentTable.TRANSFER_PROGRESS_DONE, AttachmentTable.ArchiveTransferState.FINISHED.value,
      Base64.encodeWithPadding(ByteArray(64) { 9 }), remoteLocation = "donor-location", remoteDigest = byteArrayOf(2, 3, 4)
    )
    val id = insertWithData(path)
    afterSelection("Missing key but found same data file", { replaceVersion(id, changePath) }) {
      val result = SignalDatabase.attachments.createRemoteKeyForAttachmentsThatNeedArchiveUpload()
      assertThat(result.totalCount).isEqualTo(0)
      assertThat(result.notQuoteOrStickerDupeFoundCount).isEqualTo(0)
    }
    val row = SignalDatabase.attachments.getAttachment(id)!!
    assertThat(row.remoteKey).isNull()
    assertThat(row.remoteLocation).isNull()
    assertThat(row.remoteDigest).isNull()
  }

  private fun replaceVersion(id: AttachmentId, changePath: Boolean) {
    val values = ContentValues().apply {
      if (changePath) put(AttachmentTable.DATA_FILE, "/replacement/${UUID.randomUUID()}")
      else put(AttachmentTable.DATA_RANDOM, byteArrayOf(4, 5, 6))
    }
    SignalDatabase.attachments.writableDatabase.update(AttachmentTable.TABLE_NAME).values(values)
      .where("${AttachmentTable.ID} = ?", id.id).run()
  }

  private fun afterSelection(message: String, replacement: () -> Unit, action: () -> Unit) {
    // Use the existing pre-write diagnostic to reproduce a writer arriving after the initial read.
    var replaced = false
    mockkStatic(Log::class)
    try {
      every { Log.w(any<String>(), match<String> { it.contains(message) }) } answers {
        if (!replaced) {
          replaced = true
          replacement()
        }
        Unit
      }
      action()
      assertThat(replaced).isTrue()
    } finally {
      unmockkStatic(Log::class)
    }
  }

  @Test
  fun whenMatchingDataFileAndHashExists_reusesRemoteKey() {
    val dataFile = "/shared/path/attachment.jpg"
    val dataHashEnd = "shared_hash_end"
    val existingRemoteKey = Base64.encodeWithPadding(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))

    // Create source attachment with remote key, location, digest
    val sourceAttachmentId = insertAttachmentDirectly(
      dataFile = dataFile,
      transferState = AttachmentTable.TRANSFER_PROGRESS_DONE,
      archiveTransferState = AttachmentTable.ArchiveTransferState.FINISHED.value,
      remoteKey = existingRemoteKey,
      dataHashEnd = dataHashEnd,
      remoteLocation = "cdn-location-123",
      remoteDigest = byteArrayOf(9, 10, 11, 12)
    )

    // Create target attachment with same data file and hash but no remote key
    val targetAttachmentId = insertAttachmentDirectly(
      dataFile = dataFile,
      transferState = AttachmentTable.TRANSFER_PROGRESS_DONE,
      archiveTransferState = AttachmentTable.ArchiveTransferState.NONE.value,
      remoteKey = null,
      dataHashEnd = dataHashEnd
    )

    val result = SignalDatabase.attachments.createRemoteKeyForAttachmentsThatNeedArchiveUpload()
    assertThat(result.totalCount).isEqualTo(1)

    // Verify target attachment reused the remote key, location, and digest
    val targetAttachment = SignalDatabase.attachments.getAttachment(targetAttachmentId)!!
    assertThat(targetAttachment.remoteKey).isEqualTo(existingRemoteKey)
    assertThat(targetAttachment.remoteLocation).isEqualTo("cdn-location-123")
    assertThat(targetAttachment.remoteDigest.contentEquals(byteArrayOf(9, 10, 11, 12))).isTrue()
  }

  @Test
  fun whenMultipleMatchesExist_reusesFromLatestMatch() {
    val dataFile = "/shared/path/attachment.jpg"
    val firstRemoteKey = Base64.encodeWithPadding(byteArrayOf(1, 2, 3, 4))
    val secondRemoteKey = Base64.encodeWithPadding(byteArrayOf(5, 6, 7, 8))

    // Create first source attachment
    val firstSourceId = insertAttachmentDirectly(
      dataFile = dataFile,
      transferState = AttachmentTable.TRANSFER_PROGRESS_DONE,
      archiveTransferState = AttachmentTable.ArchiveTransferState.FINISHED.value,
      remoteKey = firstRemoteKey,
      remoteLocation = "first-location",
      remoteDigest = byteArrayOf(9, 10, 11, 12)
    )

    // Create second source attachment (inserted later)
    val secondSourceId = insertAttachmentDirectly(
      dataFile = dataFile,
      transferState = AttachmentTable.TRANSFER_PROGRESS_DONE,
      archiveTransferState = AttachmentTable.ArchiveTransferState.FINISHED.value,
      remoteKey = secondRemoteKey,
      remoteLocation = "second-location",
      remoteDigest = byteArrayOf(13, 14, 15, 16)
    )

    // Create target attachment
    val targetAttachmentId = insertAttachmentDirectly(
      dataFile = dataFile,
      transferState = AttachmentTable.TRANSFER_PROGRESS_DONE,
      archiveTransferState = AttachmentTable.ArchiveTransferState.NONE.value,
      remoteKey = null
    )

    val result = SignalDatabase.attachments.createRemoteKeyForAttachmentsThatNeedArchiveUpload()
    assertThat(result.totalCount).isEqualTo(1)

    // Verify target attachment reused from the first match (by ID order desc)
    val targetAttachment = SignalDatabase.attachments.getAttachment(targetAttachmentId)!!
    assertThat(targetAttachment.remoteKey).isEqualTo(secondRemoteKey)
    assertThat(targetAttachment.remoteLocation).isEqualTo("second-location")
    assertThat(targetAttachment.remoteDigest.contentEquals(byteArrayOf(13, 14, 15, 16))).isTrue()
  }

  @Test
  fun whenSourceHasNoRemoteData_generatesNewKey() {
    val dataFile = "/shared/path/attachment.jpg"

    // Create source attachment without remote key (should not be used for reuse)
    val sourceAttachmentId = insertAttachmentDirectly(
      dataFile = dataFile,
      transferState = AttachmentTable.TRANSFER_PROGRESS_DONE,
      archiveTransferState = AttachmentTable.ArchiveTransferState.NONE.value,
      remoteKey = null
    )

    // Create target attachment
    val targetAttachmentId = insertAttachmentDirectly(
      dataFile = dataFile,
      transferState = AttachmentTable.TRANSFER_PROGRESS_DONE,
      archiveTransferState = AttachmentTable.ArchiveTransferState.NONE.value,
      remoteKey = null
    )

    val result = SignalDatabase.attachments.createRemoteKeyForAttachmentsThatNeedArchiveUpload()
    assertThat(result.totalCount).isEqualTo(2) // Both should get new keys

    // Verify both attachments got new keys
    assertThat(SignalDatabase.attachments.getAttachment(sourceAttachmentId)?.remoteKey).isNotNull()
    assertThat(SignalDatabase.attachments.getAttachment(targetAttachmentId)?.remoteKey).isNotNull()
  }

  @Test
  fun whenTwoAttachmentsShareDataFileAndBothNeedNewKey_bothGetSameKey() {
    val sharedDataFile = "/shared/path/attachment.jpg"

    // Create two attachments sharing the same data file, neither with a remote key
    val attachmentId1 = insertAttachmentDirectly(
      dataFile = sharedDataFile,
      transferState = AttachmentTable.TRANSFER_PROGRESS_DONE,
      archiveTransferState = AttachmentTable.ArchiveTransferState.NONE.value,
      remoteKey = null
    )

    val attachmentId2 = insertAttachmentDirectly(
      dataFile = sharedDataFile,
      transferState = AttachmentTable.TRANSFER_PROGRESS_DONE,
      archiveTransferState = AttachmentTable.ArchiveTransferState.NONE.value,
      remoteKey = null
    )

    // Verify neither has a remote key initially
    assertThat(SignalDatabase.attachments.getAttachment(attachmentId1)?.remoteKey).isNull()
    assertThat(SignalDatabase.attachments.getAttachment(attachmentId2)?.remoteKey).isNull()

    val result = SignalDatabase.attachments.createRemoteKeyForAttachmentsThatNeedArchiveUpload()
    assertThat(result.totalCount).isEqualTo(2)

    // Verify both attachments got the SAME remote key
    val attachment1 = SignalDatabase.attachments.getAttachment(attachmentId1)!!
    val attachment2 = SignalDatabase.attachments.getAttachment(attachmentId2)!!

    assertThat(attachment1.remoteKey).isNotNull()
    assertThat(attachment2.remoteKey).isNotNull()
    assertThat(attachment1.remoteKey).isEqualTo(attachment2.remoteKey)
  }

  /**
   * Creates an attachment that meets all criteria for archive upload:
   * - ARCHIVE_TRANSFER_STATE = NONE (0)
   * - DATA_FILE is not null
   * - TRANSFER_STATE = TRANSFER_PROGRESS_DONE (0)
   * - REMOTE_KEY is null
   */
  fun insertWithData(dataFile: String = "/fake/path/attachment-${UUID.randomUUID()}.jpg"): AttachmentId {
    return insertAttachmentDirectly(
      dataFile = dataFile,
      transferState = AttachmentTable.TRANSFER_PROGRESS_DONE,
      archiveTransferState = AttachmentTable.ArchiveTransferState.NONE.value,
      remoteKey = null
    )
  }

  /**
   * Creates an attachment without a data file (ineligible for archive upload)
   */
  fun insertWithoutData(): AttachmentId {
    return insertAttachmentDirectly(
      dataFile = null,
      transferState = AttachmentTable.TRANSFER_PROGRESS_DONE,
      archiveTransferState = AttachmentTable.ArchiveTransferState.NONE.value,
      remoteKey = null
    )
  }

  /**
   * Directly inserts an attachment with minimal required columns for testing
   */
  private fun insertAttachmentDirectly(
    dataFile: String?,
    transferState: Int,
    archiveTransferState: Int,
    remoteKey: String?,
    dataHashEnd: String? = null,
    remoteLocation: String? = null,
    remoteDigest: ByteArray? = null
  ): AttachmentId {
    val db = SignalDatabase.attachments.writableDatabase

    val values = ContentValues().apply {
      put(AttachmentTable.DATA_FILE, dataFile)
      put(AttachmentTable.DATA_RANDOM, dataFile?.toByteArray())
      put(AttachmentTable.TRANSFER_STATE, transferState)
      put(AttachmentTable.ARCHIVE_TRANSFER_STATE, archiveTransferState)
      put(AttachmentTable.REMOTE_KEY, remoteKey)
      put(AttachmentTable.DATA_HASH_END, dataHashEnd)
      put(AttachmentTable.REMOTE_LOCATION, remoteLocation)
      put(AttachmentTable.REMOTE_DIGEST, remoteDigest)
    }

    val id = db.insert(AttachmentTable.TABLE_NAME, null, values)
    return AttachmentId(id)
  }

  private fun setRemoteKey(attachmentId: AttachmentId, remoteKey: String) {
    SignalDatabase.attachments.writableDatabase
      .update(AttachmentTable.TABLE_NAME)
      .values(AttachmentTable.REMOTE_KEY to remoteKey)
      .where("${AttachmentTable.ID} = ?", attachmentId.id)
      .run()
  }
}
