/*
 * Copyright 2024 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.jobs

import arrow.core.Either
import org.signal.core.models.backup.MediaName
import org.signal.core.models.database.AttachmentId
import org.signal.core.util.Base64
import org.signal.core.util.Base64.decodeBase64
import org.signal.core.util.Util
import org.signal.core.util.inRoundedDays
import org.signal.core.util.isNotNullOrBlank
import org.signal.core.util.logging.Log
import org.signal.core.util.readLength
import org.signal.libsignal.net.RequestResult
import org.signal.network.api.AttachmentUploadResult
import org.signal.network.api.CdnApi
import org.signal.network.service.ArchiveError
import org.signal.protos.resumableuploads.ResumableUpload
import org.signal.core.util.crypto.AttachmentSecretProvider
import org.signal.core.util.crypto.ModernDecryptingPartInputStream
import org.thoughtcrime.securesms.crypto.AppAttachmentSecretStore
import okio.ByteString.Companion.toByteString
import java.io.File
import org.signal.remoteconfig.RemoteConfig
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.attachments.AttachmentUploadUtil
import org.thoughtcrime.securesms.attachments.DatabaseAttachment
import org.thoughtcrime.securesms.backup.ArchiveUploadProgress
import org.thoughtcrime.securesms.backup.v2.ArchiveDatabaseExecutor
import org.thoughtcrime.securesms.database.AttachmentTable
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.jobmanager.CoroutineJob
import org.thoughtcrime.securesms.jobmanager.Job
import org.thoughtcrime.securesms.jobmanager.impl.BackupMessagesConstraint
import org.thoughtcrime.securesms.jobs.protos.UploadAttachmentToArchiveJobData
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.mms.PartAuthority
import org.thoughtcrime.securesms.net.SignalNetwork
import org.thoughtcrime.securesms.service.AttachmentProgressService
import org.thoughtcrime.securesms.util.MediaUtil
import org.whispersystems.signalservice.api.crypto.AttachmentCipherStreamUtil
import org.whispersystems.signalservice.api.messages.AttachmentTransferProgress
import org.whispersystems.signalservice.api.messages.SignalServiceAttachment
import org.whispersystems.signalservice.internal.crypto.PaddingInputStream
import org.whispersystems.signalservice.internal.push.AttachmentUploadForm
import org.whispersystems.signalservice.internal.push.http.ResumableUploadSpec
import java.io.FileNotFoundException
import java.io.IOException
import java.net.ProtocolException
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds

/**
 * Given an attachmentId, this will upload the corresponding attachment to the archive cdn.
 * To do this, it must first upload it to the attachment cdn, and then copy it to the archive cdn.
 */
class UploadAttachmentToArchiveJob private constructor(
  private val attachmentId: AttachmentId,
  private var uploadSpec: ResumableUpload?,
  private var snapshot: AttachmentUploadSnapshot.Identity?,
  private var canReuseUpload: Boolean,
  parameters: Parameters
) : CoroutineJob(parameters) {
  private var attemptedCandidate: AttachmentTable.UploadCandidate? = null
  private var progressToken: String? = null
  private var ownerIdentity: String? = null
  private var progressHandedOff = false

  companion object {
    private val TAG = Log.tag(UploadAttachmentToArchiveJob::class)
    const val KEY = "UploadAttachmentToArchiveJob"

    /** A set of possible queues this job may use. The number of queues determines the parallelism. */
    val QUEUES = setOf(
      "ArchiveAttachmentJobs_01",
      "ArchiveAttachmentJobs_02",
      "ArchiveAttachmentJobs_03",
      "ArchiveAttachmentJobs_04",
      "ArchiveAttachmentJobs_05",
      "ArchiveAttachmentJobs_06",
      "ArchiveAttachmentJobs_07",
      "ArchiveAttachmentJobs_08",
      "ArchiveAttachmentJobs_09",
      "ArchiveAttachmentJobs_10",
      "ArchiveAttachmentJobs_11",
      "ArchiveAttachmentJobs_12"
    )
  }

  @JvmOverloads
  constructor(attachmentId: AttachmentId, canReuseUpload: Boolean = true, expectedOwner: String? = null) : this(
    attachmentId = attachmentId,
    uploadSpec = null,
    snapshot = null,
    canReuseUpload = canReuseUpload,
    parameters = Parameters.Builder()
      .addConstraint(BackupMessagesConstraint.KEY)
      .setLifespan(30.days.inWholeMilliseconds)
      .setMaxAttempts(Parameters.UNLIMITED)
      .setQueue(QUEUES.random())
      .setGlobalPriority(Parameters.PRIORITY_LOW)
      .build()
  ) {
    ownerIdentity = expectedOwner ?: SignalDatabase.attachments.getUploadCandidate(attachmentId)?.let(SignalDatabase.attachments::archiveOwnerToken)
  }

  override fun serialize(): ByteArray = UploadAttachmentToArchiveJobData(
    attachmentId = attachmentId.id,
    uploadSpec = uploadSpec,
    canReuseUpload = canReuseUpload,
    snapshotId = snapshot?.id,
    snapshotLength = snapshot?.length,
    snapshotDigest = snapshot?.digest,
    snapshotSourcePath = snapshot?.sourcePath,
    snapshotSourceRandom = snapshot?.sourceRandom?.toByteString(),
    snapshotPlaintextDigest = snapshot?.plaintextDigest,
    ownerIdentity = ownerIdentity,
    progressToken = progressToken
  ).encode()

  override fun getFactoryKey(): String = KEY

  override fun onAdded() {
    val owner = ownerIdentity ?: return
    val marked = ArchiveDatabaseExecutor.runBlocking {
      SignalDatabase.attachments.markArchivePendingIfOwnerMatches(attachmentId, owner, AttachmentTable.ArchiveTransferState.UPLOAD_IN_PROGRESS)
    }
    if (marked) {
      Log.d(TAG, "[$attachmentId] Updating archive transfer state to ${AttachmentTable.ArchiveTransferState.UPLOAD_IN_PROGRESS}")
    }
  }

  override suspend fun doRun(): Result {
    progressHandedOff = false
    val result = runUpload()
    if (!result.isRetry) {
      releaseSnapshot()
      if (!progressHandedOff || isCanceled) finishOwnedProgress()
    }
    return result
  }

  private suspend fun runUpload(): Result {
    val expectedOwner = ownerIdentity
    val candidate = if (expectedOwner == null) {
      SignalDatabase.attachments.getUploadCandidate(attachmentId)
    } else {
      SignalDatabase.attachments.getArchiveCandidateIfOwnerMatches(attachmentId, expectedOwner)
    }
    val attachment: DatabaseAttachment? = candidate?.attachment

    if (attachment == null) {
      if (expectedOwner != null) {
        Log.i(TAG, "[$attachmentId] Attachment changed since this archive job was created; retiring stale work.")
        finishOwnedProgress()
        releaseSnapshot()
        return Result.success()
      }
      Log.w(TAG, "[$attachmentId] Attachment no longer exists! Skipping.")
      return Result.failure()
    }

    val captured = candidate!!
    if (expectedOwner == null) {
      // Persist the owner in this job's retry payload before letting it upload again.
      ownerIdentity = SignalDatabase.attachments.archiveOwnerToken(captured)
      canReuseUpload = false
      uploadSpec = null
      finishOwnedProgress()
      releaseSnapshot()
      onAdded()
      return Result.retry(0)
    }
    attemptedCandidate = captured

    if (SignalStore.account.isLinkedDevice) {
      Log.w(TAG, "[$attachmentId] Linked devices don't backup media. Skipping.")
      ArchiveDatabaseExecutor.runBlocking { SignalDatabase.attachments.setArchiveStateIfCandidateUnchanged(captured, AttachmentTable.ArchiveTransferState.NONE) }
      return Result.success()
    }

    if (!SignalStore.backup.backsUpMedia) {
      Log.w(TAG, "[$attachmentId] This user does not back up media. Skipping.")
      ArchiveDatabaseExecutor.runBlocking { SignalDatabase.attachments.setArchiveStateIfCandidateUnchanged(captured, AttachmentTable.ArchiveTransferState.NONE) }
      return Result.success()
    }

    val mediaIdLog: String = if (RemoteConfig.internalUser && attachment.remoteKey != null && attachment.dataHash != null) {
      val mediaId = MediaName.fromPlaintextHashAndRemoteKey(attachment.dataHash.decodeBase64()!!, attachment.remoteKey.decodeBase64()!!).toMediaId(SignalStore.backup.mediaRootBackupKey)
      "[$mediaId]"
    } else {
      ""
    }

    if (attachment.archiveTransferState == AttachmentTable.ArchiveTransferState.FINISHED) {
      Log.i(TAG, "[$attachmentId]$mediaIdLog Already finished. Skipping.")
      return if (SignalDatabase.attachments.archiveCandidateUnchanged(captured)) Result.success() else Result.retry(defaultBackoff())
    }

    if (attachment.archiveTransferState == AttachmentTable.ArchiveTransferState.PERMANENT_FAILURE) {
      Log.i(TAG, "[$attachmentId]$mediaIdLog Already marked as a permanent failure. Skipping.")
      return if (SignalDatabase.attachments.archiveCandidateUnchanged(captured)) Result.failure() else Result.retry(defaultBackoff())
    }

    if (attachment.uri == null) {
      Log.w(TAG, "[$attachmentId]$mediaIdLog Attachment has no uri! Cannot upload.")
      val marked = ArchiveDatabaseExecutor.runBlocking { SignalDatabase.attachments.setArchiveStateIfCandidateUnchanged(captured, AttachmentTable.ArchiveTransferState.PERMANENT_FAILURE) }
      return if (marked) Result.failure() else Result.retry(defaultBackoff())
    }


    if (attachment.archiveTransferState == AttachmentTable.ArchiveTransferState.COPY_PENDING) {
      Log.i(TAG, "[$attachmentId]$mediaIdLog Already marked as pending transfer. Enqueueing a copy job just in case.")
      AppDependencies.jobManager.add(CopyAttachmentToArchiveJob(attachment.attachmentId, progressToken, expectedOwner))
      progressHandedOff = true
      return Result.success()
    }

    if (SignalDatabase.messages.isStory(attachment.mmsId)) {
      Log.i(TAG, "[$attachmentId]$mediaIdLog Attachment is a story. Resetting transfer state to none and skipping.")
      val marked = ArchiveDatabaseExecutor.runBlocking { SignalDatabase.attachments.setArchiveStateIfCandidateUnchanged(captured, AttachmentTable.ArchiveTransferState.NONE) }
      return if (marked) Result.success() else Result.retry(defaultBackoff())
    }

    if (SignalDatabase.messages.isViewOnce(attachment.mmsId)) {
      Log.i(TAG, "[$attachmentId]$mediaIdLog Attachment is a view-once. Resetting transfer state to none and skipping.")
      val marked = ArchiveDatabaseExecutor.runBlocking { SignalDatabase.attachments.setArchiveStateIfCandidateUnchanged(captured, AttachmentTable.ArchiveTransferState.NONE) }
      return if (marked) Result.success() else Result.retry(defaultBackoff())
    }

    if (SignalDatabase.messages.willMessageExpireBeforeCutoff(attachment.mmsId)) {
      Log.i(TAG, "[$attachmentId]$mediaIdLog Message will expire within 24 hours. Resetting transfer state to none and skipping.")
      val marked = ArchiveDatabaseExecutor.runBlocking { SignalDatabase.attachments.setArchiveStateIfCandidateUnchanged(captured, AttachmentTable.ArchiveTransferState.NONE) }
      return if (marked) Result.success() else Result.retry(defaultBackoff())
    }

    if (attachment.contentType == MediaUtil.LONG_TEXT) {
      Log.i(TAG, "[$attachmentId]$mediaIdLog Attachment is long text. Resetting transfer state to none and skipping.")
      val marked = ArchiveDatabaseExecutor.runBlocking { SignalDatabase.attachments.setArchiveStateIfCandidateUnchanged(captured, AttachmentTable.ArchiveTransferState.NONE) }
      return if (marked) Result.success() else Result.retry(defaultBackoff())
    }

    if (attachment.remoteKey == null || attachment.remoteKey.isBlank()) {
      Log.w(TAG, "[$attachmentId]$mediaIdLog Attachment is missing remote key! Cannot upload.")
      return Result.failure()
    }

    val timeSinceUpload = System.currentTimeMillis() - attachment.uploadTimestamp
    if (canReuseUpload && timeSinceUpload > 0 && timeSinceUpload < AttachmentUploadJob.UPLOAD_REUSE_THRESHOLD && attachment.remoteLocation.isNotNullOrBlank()) {
      Log.i(TAG, "We can copy an already-uploaded file. It was uploaded $timeSinceUpload ms (${timeSinceUpload.milliseconds.inRoundedDays()} days) ago. Skipping.")
      AppDependencies.jobManager.add(CopyAttachmentToArchiveJob(attachment.attachmentId, null, expectedOwner))
      return Result.success()
    }

    if (uploadSpec != null && System.currentTimeMillis() > uploadSpec!!.timeout) {
      Log.w(TAG, "[$attachmentId]$mediaIdLog Upload spec expired! Clearing.")
      uploadSpec = null
    }

    val snapshots = snapshots()
    val sourceInfo = captured.info ?: return Result.failure()
    val remoteKey = Base64.decode(attachment.remoteKey!!)
    if (!archiveSpecMatchesRemoteKey(uploadSpec, remoteKey)) {
      uploadSpec = null
      releaseSnapshot()
    }
    val prepared = try {
      snapshots.prepare(uploadSpec, snapshot, sourceInfo.length, sourceInfo.file.absolutePath, sourceInfo.random, {
        ModernDecryptingPartInputStream.createFor(
          AttachmentSecretProvider.getInstance(context, AppAttachmentSecretStore).getOrCreateAttachmentSecret(), sourceInfo.random, sourceInfo.file, 0
        )
      }, { isCanceled })
    } catch (e: AttachmentUploadSnapshot.LengthMismatch) {
      uploadSpec = null
      snapshots.delete(snapshot)
      snapshot = null
      val repaired = SignalDatabase.attachments.repairUploadLengthIfCandidateUnchanged(captured, e.actualLength)
      if (repaired != null) {
        attemptedCandidate = repaired
        ownerIdentity = SignalDatabase.attachments.archiveOwnerToken(repaired)
      }
      if (repaired != null && e.actualLength == 0L) {
        val marked = ArchiveDatabaseExecutor.runBlocking { SignalDatabase.attachments.setArchiveStateIfCandidateUnchanged(repaired, AttachmentTable.ArchiveTransferState.PERMANENT_FAILURE) }
        return if (marked) Result.failure() else Result.retry(defaultBackoff())
      }
      return Result.retry(defaultBackoff())
    } catch (e: IOException) {
      Log.w(TAG, "[$attachmentId] Could not capture an upload snapshot.", e)
      return Result.retry(defaultBackoff())
    }
    uploadSpec = prepared.spec
    snapshot = prepared.identity
    val stable = prepared.identity
    val existingSpec = uploadSpec?.let { ResumableUploadSpec.from(it) }

    val ciphertextLength = AttachmentCipherStreamUtil.getCiphertextLength(PaddingInputStream.getPaddedSize(stable.length))

    val form: AttachmentUploadForm? = if (existingSpec == null) {
      when (val formResult = SignalNetwork.archiveService.getMediaUploadForm(ciphertextLength)) {
        is Either.Right -> formResult.value
        is Either.Left -> return when (val error = formResult.value) {
          is ArchiveError.ApplicationError -> {
            Log.w(TAG, "[$attachmentId]$mediaIdLog Failed to get upload form due to an application error.", error.exception)
            Result.retry(defaultBackoff())
          }
          is ArchiveError.NetworkError -> {
            Log.w(TAG, "[$attachmentId]$mediaIdLog Encountered a transient network error getting upload form.")
            Result.retry(defaultBackoff())
          }
          is ArchiveError.CredentialError.RateLimited -> {
            Log.w(TAG, "[$attachmentId]$mediaIdLog Rate limited when getting upload form.")
            Result.retry(error.retryAfter?.inWholeMilliseconds ?: defaultBackoff())
          }
          is ArchiveError.UploadFormError.TooLarge -> {
            Log.w(TAG, "[$attachmentId]$mediaIdLog Media is too large to upload to the archive. Marking as a permanent failure.")
            val marked = ArchiveDatabaseExecutor.runBlocking { SignalDatabase.attachments.setArchiveStateIfCandidateUnchanged(captured, AttachmentTable.ArchiveTransferState.PERMANENT_FAILURE) }
            if (marked) Result.failure() else Result.retry(defaultBackoff())
          }
          is ArchiveError.CredentialError.Unauthorized,
          is ArchiveError.CredentialError.NotFound,
          is ArchiveError.CredentialError.InvalidRequest,
          is ArchiveError.CredentialError.ZkVerificationFailed -> {
            Log.w(TAG, "[$attachmentId]$mediaIdLog Failed to get upload form: ${error::class.simpleName}")
            Result.retry(defaultBackoff())
          }
        }
      }
    } else {
      null
    }

    val key = existingSpec?.attachmentKey ?: remoteKey
    val iv = freshOrResumeIv(existingSpec)

    val checksumSha256 = if (existingSpec == null) {
      readArchiveSnapshot(snapshots, stable, { e ->
        uploadSpec = null
        releaseSnapshot()
        Log.w(TAG, "[$attachmentId] Upload snapshot unavailable; retrying with a fresh spec.", e)
      }) { stream -> stream.use { AttachmentUploadUtil.computeCiphertextChecksum(key, iv, it, stable.length) } }
        ?: return Result.retry(defaultBackoff())
    } else null

    val progressServiceController = if (attachment.size >= AttachmentUploadUtil.FOREGROUND_LIMIT_BYTES) {
      AttachmentProgressService.start(context, context.getString(R.string.UploadAttachmentToArchiveJob_uploading_media))
    } else {
      null
    }

    val token = ArchiveUploadProgress.onAttachmentStarted(attachmentId, attachment.size)
    progressToken = token

    val attachmentStream = readArchiveSnapshot(snapshots, stable, { e ->
      progressServiceController?.close()
      finishOwnedProgress()
      uploadSpec = null
      releaseSnapshot()
      Log.w(TAG, "[$attachmentId] Upload snapshot unavailable; retrying with a fresh spec.", e)
    }) { input ->
      AttachmentUploadUtil.buildSignalServiceAttachmentStream(
        context = context,
        attachment = attachment,
        inputStream = input,
        inputLength = stable.length,
        snapshotVisualHash = { snapshots.visualHash(stable, attachment.contentType) },
        cancellationSignal = { this.isCanceled },
        progressListener = object : SignalServiceAttachment.ProgressListener {
          override fun onAttachmentProgress(progress: AttachmentTransferProgress) {
            ArchiveUploadProgress.onAttachmentProgressIfOwned(attachmentId, token, progress.transmitted.inWholeBytes)
            progressServiceController?.updateProgress(progress.value)
          }

          override fun shouldCancel() = this@UploadAttachmentToArchiveJob.isCanceled
        }
      )
    } ?: return Result.retry(defaultBackoff())

    Log.d(TAG, "[$attachmentId]$mediaIdLog Beginning upload...")
    progressServiceController.use {
      val uploadResult: AttachmentUploadResult = attachmentStream.use { stream ->
        when (
          val result = SignalNetwork.cdnService.uploadAttachment(
            form = form,
            key = key,
            iv = iv,
            checksumSha256 = checksumSha256,
            attachmentStream = stream,
            existingSpec = existingSpec,
            onSpecCreated = { spec -> uploadSpec = spec.toProto() }
          )
        ) {
          is RequestResult.Success -> result.result
          is RequestResult.ApplicationError -> throw result.cause
          is RequestResult.RetryableNetworkError -> {
            Log.w(TAG, "[$attachmentId]$mediaIdLog Failed to upload due to network error.", result.networkError)

            if (result.networkError is ProtocolException || result.networkError.cause is ProtocolException) {
              Log.w(TAG, "[$attachmentId]$mediaIdLog Length may be incorrect. Recalculating.", result.networkError)

              val actualLength = SignalDatabase.attachments.getAttachmentStream(attachmentId, 0)
                .use { it.readLength() }
              if (actualLength != attachment.size) {
                Log.w(TAG, "[$attachmentId]$mediaIdLog Length was incorrect! Will update. Previous: ${attachment.size}, Newly-Calculated: $actualLength", result.networkError)
                val repaired = ArchiveDatabaseExecutor.runBlocking {
                  SignalDatabase.attachments.repairUploadLengthIfCandidateUnchanged(captured, actualLength)
                }
                if (repaired != null) {
                  attemptedCandidate = repaired
                  ownerIdentity = SignalDatabase.attachments.archiveOwnerToken(repaired)
                }
                uploadSpec = null
                releaseSnapshot()
              } else {
                Log.i(TAG, "[$attachmentId]$mediaIdLog Length was correct. No action needed. Will retry.")
              }
            }

            return Result.retry(defaultBackoff())
          }

          is RequestResult.NonSuccess -> {
            when (val error = result.error) {
              is CdnApi.UploadError.ResumeLocationInvalid -> {
                Log.w(TAG, "[$attachmentId]$mediaIdLog Resume location is invalid. Clearing upload spec before retrying.")
                uploadSpec = null
              }
              is CdnApi.UploadError.InvalidRequest -> {
                Log.w(TAG, "[$attachmentId]$mediaIdLog 400 likely means bad resumable state. Clearing upload spec before retrying.")
                uploadSpec = null
              }
              is CdnApi.UploadError.TooLarge -> {
                Log.w(TAG, "[$attachmentId]$mediaIdLog 413 means the attachment was too large. We've seen this happen with frankenstein imports using third party tools. This can never succeed.")
                val marked = ArchiveDatabaseExecutor.runBlocking { SignalDatabase.attachments.setArchiveStateIfCandidateUnchanged(captured, AttachmentTable.ArchiveTransferState.PERMANENT_FAILURE) }
                uploadSpec = null
                return if (marked) Result.failure() else Result.retry(defaultBackoff())
              }
              is CdnApi.UploadError.ChecksumMismatch -> {
                Log.w(TAG, "[$attachmentId]$mediaIdLog Upload failed due to a checksum mismatch. Clearing upload spec before retrying.")
                uploadSpec = null
              }
              is CdnApi.UploadError.RateLimited -> {
                Log.w(TAG, "[$attachmentId]$mediaIdLog Rate limited when uploading.")
                return Result.retry(error.retryAfter?.inWholeMilliseconds ?: defaultBackoff())
              }
            }
            return Result.retry(defaultBackoff())
          }
        }
      }

      Log.d(TAG, "[$attachmentId]$mediaIdLog Upload complete!")
      val finalizedCandidate = ArchiveDatabaseExecutor.runBlocking {
        SignalDatabase.attachments.finalizeAttachmentAfterSnapshotUploadAndGetCandidate(captured, uploadResult, stable.plaintextDigest)
      }
      if (finalizedCandidate == null) {
        uploadSpec = null
        releaseSnapshot()
        finishOwnedProgress()
        return Result.retry(defaultBackoff())
      }
      ownerIdentity = SignalDatabase.attachments.archiveOwnerToken(finalizedCandidate)
    }

    if (!isCanceled) {
      AppDependencies.jobManager.add(CopyAttachmentToArchiveJob(attachment.attachmentId, progressToken, ownerIdentity!!))
      progressHandedOff = true
    } else {
      Log.d(TAG, "[$attachmentId]$mediaIdLog Job was canceled. Skipping copy job.")
    }

    return Result.success()
  }

  private fun snapshots(): AttachmentUploadSnapshot = AttachmentUploadSnapshot(
    File(context.cacheDir, "attachment-upload-snapshots"),
    AttachmentSecretProvider.getInstance(context, AppAttachmentSecretStore).getOrCreateAttachmentSecret()
  )

  internal fun releaseSnapshot() {
    if (snapshot != null) snapshots().delete(snapshot)
    snapshot = null
  }

  override fun onFailure() {
    releaseSnapshot()
    val candidate = attemptedCandidate
    val state = if (isCanceled) AttachmentTable.ArchiveTransferState.NONE else AttachmentTable.ArchiveTransferState.TEMPORARY_FAILURE
    if (candidate != null) {
      ArchiveDatabaseExecutor.runBlocking { SignalDatabase.attachments.setArchiveStateIfCandidateUnchanged(candidate, state) }
    } else {
      ownerIdentity?.let { owner -> ArchiveDatabaseExecutor.runBlocking { SignalDatabase.attachments.setArchiveStateIfOwnerMatches(attachmentId, owner, state) } }
    }
    finishOwnedProgress()
  }

  private fun finishOwnedProgress() {
    progressToken?.let { ArchiveUploadProgress.onAttachmentFinishedIfOwned(attachmentId, it) }
    progressToken = null
  }

  class Factory : Job.Factory<UploadAttachmentToArchiveJob> {
    override fun create(parameters: Parameters, serializedData: ByteArray?): UploadAttachmentToArchiveJob {
      val data = UploadAttachmentToArchiveJobData.ADAPTER.decode(serializedData!!)
      return UploadAttachmentToArchiveJob(
        attachmentId = AttachmentId(data.attachmentId),
        uploadSpec = data.uploadSpec,
        snapshot = if (data.snapshotId != null && data.snapshotLength != null && data.snapshotDigest != null && data.snapshotSourcePath != null && data.snapshotSourceRandom != null && data.snapshotPlaintextDigest != null)
          AttachmentUploadSnapshot.Identity(data.snapshotId, data.snapshotLength, data.snapshotDigest, data.snapshotSourcePath, data.snapshotSourceRandom.toByteArray(), data.snapshotPlaintextDigest) else null,
        canReuseUpload = data.canReuseUpload == true,
        parameters = parameters
      ).also {
        it.ownerIdentity = data.ownerIdentity
        it.progressToken = data.progressToken
      }
    }
  }
}

/** A missing or unreadable snapshot invalidates the old resumable spec before retry. */
internal fun <T : Any> readArchiveSnapshot(store: AttachmentUploadSnapshot, identity: AttachmentUploadSnapshot.Identity, onFailure: (IOException) -> Unit, read: (java.io.InputStream) -> T): T? {
  return try {
    val input = store.open(identity)
    try { read(input) } catch (e: Throwable) {
      try { input.close() } catch (closeError: IOException) { e.addSuppressed(closeError) }
      throw e
    }
  } catch (e: IOException) {
    onFailure(e)
    null
  }
}

internal fun archiveSpecMatchesRemoteKey(spec: ResumableUpload?, key: ByteArray): Boolean =
  spec == null || java.security.MessageDigest.isEqual(spec.secretKey.toByteArray(), key)
