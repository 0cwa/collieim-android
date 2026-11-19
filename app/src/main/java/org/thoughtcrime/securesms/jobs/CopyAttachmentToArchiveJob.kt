package org.thoughtcrime.securesms.jobs

import arrow.core.Either
import kotlinx.coroutines.runBlocking
import org.signal.core.models.backup.MediaName
import org.signal.core.models.database.AttachmentId
import org.signal.core.util.Base64.decodeBase64
import org.signal.core.util.ByteSize
import org.signal.core.util.bytes
import org.signal.core.util.logging.Log
import org.signal.core.util.logging.logW
import org.signal.network.service.ArchiveError
import org.signal.remoteconfig.RemoteConfig
import org.thoughtcrime.securesms.attachments.Cdn
import org.thoughtcrime.securesms.attachments.DatabaseAttachment
import org.thoughtcrime.securesms.backup.ArchiveUploadProgress
import org.thoughtcrime.securesms.backup.v2.ArchiveDatabaseExecutor
import org.thoughtcrime.securesms.backup.v2.BackupRepository
import org.thoughtcrime.securesms.backup.v2.hadIntegrityCheckPerformed
import org.thoughtcrime.securesms.database.AttachmentTable
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.jobmanager.CoroutineJob
import org.thoughtcrime.securesms.jobmanager.Job
import org.thoughtcrime.securesms.jobmanager.impl.NetworkConstraint
import org.thoughtcrime.securesms.jobmanager.impl.NoRemoteArchiveGarbageCollectionPendingConstraint
import org.thoughtcrime.securesms.jobs.protos.CopyAttachmentToArchiveJobData
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.util.MediaUtil
import java.util.concurrent.TimeUnit

/**
 * Copies and re-encrypts attachments from the attachment cdn to the archive cdn.
 * If it's discovered that the attachment no longer exists on the attachment cdn, this job will schedule a re-upload via [UploadAttachmentToArchiveJob].
 *
 * This job runs at high priority within its queue, which it shares with [UploadAttachmentToArchiveJob]. Therefore, copies are given priority over new uploads,
 * which allows the two-part archive upload process to finish quickly.
 */
class CopyAttachmentToArchiveJob private constructor(private val attachmentId: AttachmentId, private val progressToken: String?, parameters: Parameters) : CoroutineJob(parameters) {
  private var attemptedCandidate: AttachmentTable.UploadCandidate? = null

  companion object {
    private val TAG = Log.tag(CopyAttachmentToArchiveJob::class.java)

    const val KEY = "CopyAttachmentToArchiveJob"

    /** CDNs that we can copy data from */
    val ALLOWED_SOURCE_CDNS = setOf(Cdn.CDN_2, Cdn.CDN_3)
  }

  constructor(attachmentId: AttachmentId) : this(attachmentId, null)

  constructor(attachmentId: AttachmentId, progressToken: String?) : this(
    attachmentId = attachmentId,
    progressToken = progressToken,
    parameters = Parameters.Builder()
      .addConstraint(NetworkConstraint.KEY)
      .addConstraint(NoRemoteArchiveGarbageCollectionPendingConstraint.KEY)
      .setLifespan(TimeUnit.DAYS.toMillis(1))
      .setMaxAttempts(Parameters.UNLIMITED)
      .setQueue(UploadAttachmentToArchiveJob.QUEUES.random())
      .setQueuePriority(Parameters.PRIORITY_HIGH)
      .setGlobalPriority(Parameters.PRIORITY_LOW)
      .build()
  )

  override fun serialize(): ByteArray = CopyAttachmentToArchiveJobData(
    attachmentId = attachmentId.id,
    progressToken = progressToken
  ).encode()

  override fun getFactoryKey(): String = KEY

  override fun onAdded() {
    val transferStatus = SignalDatabase.attachments.getArchiveTransferState(attachmentId) ?: return

    if (transferStatus == AttachmentTable.ArchiveTransferState.NONE || transferStatus == AttachmentTable.ArchiveTransferState.UPLOAD_IN_PROGRESS) {
      Log.d(TAG, "[$attachmentId] Updating archive transfer state to ${AttachmentTable.ArchiveTransferState.COPY_PENDING}")
      SignalDatabase.attachments.markArchivePendingIfEligible(attachmentId, AttachmentTable.ArchiveTransferState.COPY_PENDING)
    }
  }

  override suspend fun doRun(): Result {
    val result = runCopy()
    if (!result.isRetry) progressToken?.let { ArchiveUploadProgress.onAttachmentFinishedIfOwned(attachmentId, it) }
    return result
  }

  private suspend fun runCopy(): Result {
    if (SignalStore.account.isLinkedDevice) {
      Log.w(TAG, "[$attachmentId] Linked devices don't backup media. Skipping.")
      SignalDatabase.attachments.getUploadCandidate(attachmentId)?.let { setArchiveStateIfUnchanged(it, AttachmentTable.ArchiveTransferState.NONE) }
      return Result.success()
    }

    if (!SignalStore.backup.backsUpMedia) {
      Log.w(TAG, "[$attachmentId] This user does not back up media. Skipping.")
      return Result.success()
    }

    if (SignalStore.backup.isNotEnoughRemoteStorageSpace) {
      Log.w(TAG, "[$attachmentId] Already marked as out of remote storage space. Failing.")
      return Result.failure()
    }

    val captured = SignalDatabase.attachments.getUploadCandidate(attachmentId)
    val attachment: DatabaseAttachment? = captured?.attachment

    if (attachment == null) {
      Log.w(TAG, "[$attachmentId] Attachment no longer exists! Skipping.")
      return Result.failure()
    }

    attemptedCandidate = captured

    val mediaIdLog: String = if (RemoteConfig.internalUser && attachment.remoteKey != null && attachment.dataHash != null) {
      val mediaId = MediaName.fromPlaintextHashAndRemoteKey(attachment.dataHash.decodeBase64()!!, attachment.remoteKey.decodeBase64()!!).toMediaId(SignalStore.backup.mediaRootBackupKey)
      "[$mediaId]"
    } else {
      ""
    }

    if (attachment.archiveTransferState == AttachmentTable.ArchiveTransferState.FINISHED) {
      Log.i(TAG, "[$attachmentId]$mediaIdLog Already finished. Skipping.")
      return if (SignalDatabase.attachments.archiveCandidateUnchanged(captured!!)) Result.success() else Result.retry(defaultBackoff())
    }

    if (attachment.archiveTransferState == AttachmentTable.ArchiveTransferState.PERMANENT_FAILURE) {
      Log.i(TAG, "[$attachmentId]$mediaIdLog Already marked as a permanent failure. Skipping.")
      return if (SignalDatabase.attachments.archiveCandidateUnchanged(captured!!)) Result.failure() else Result.retry(defaultBackoff())
    }

    if (SignalDatabase.messages.isStory(attachment.mmsId)) {
      Log.i(TAG, "[$attachmentId]$mediaIdLog Attachment is a story. Resetting transfer state to none and skipping.")
      if (!setArchiveStateIfUnchanged(captured!!, AttachmentTable.ArchiveTransferState.NONE)) return Result.retry(defaultBackoff())
      return Result.success()
    }

    if (SignalDatabase.messages.isViewOnce(attachment.mmsId)) {
      Log.i(TAG, "[$attachmentId]$mediaIdLog Attachment is view-once. Resetting transfer state to none and skipping.")
      if (!setArchiveStateIfUnchanged(captured!!, AttachmentTable.ArchiveTransferState.NONE)) return Result.retry(defaultBackoff())
      return Result.success()
    }

    if (SignalDatabase.messages.willMessageExpireBeforeCutoff(attachment.mmsId)) {
      Log.i(TAG, "[$attachmentId]$mediaIdLog Message will expire in less than 24 hours. Resetting transfer state to none and skipping.")
      if (!setArchiveStateIfUnchanged(captured!!, AttachmentTable.ArchiveTransferState.NONE)) return Result.retry(defaultBackoff())
      return Result.success()
    }

    if (attachment.contentType == MediaUtil.LONG_TEXT) {
      Log.i(TAG, "[$attachmentId]$mediaIdLog Attachment is long text. Resetting transfer state to none and skipping.")
      if (!setArchiveStateIfUnchanged(captured!!, AttachmentTable.ArchiveTransferState.NONE)) return Result.retry(defaultBackoff())
      return Result.success()
    }

    if (!attachment.hadIntegrityCheckPerformed()) {
      Log.w(TAG, "[$attachmentId]$mediaIdLog Attachment has not had its integrity check performed yet (transferState: ${attachment.transferState}). Resetting transfer state to none and skipping.")
      if (!setArchiveStateIfUnchanged(captured!!, AttachmentTable.ArchiveTransferState.NONE)) return Result.retry(defaultBackoff())
      return Result.success()
    }

    if (attachment.cdn !in ALLOWED_SOURCE_CDNS) {
      Log.i(TAG, "[$attachmentId]$mediaIdLog Attachment CDN (${attachment.cdn}) is not in allowed source CDNs. Enqueueing an upload job instead.")
      if (!setArchiveStateIfUnchanged(captured!!, AttachmentTable.ArchiveTransferState.NONE)) return Result.retry(defaultBackoff())
      AppDependencies.jobManager.add(UploadAttachmentToArchiveJob(attachmentId, canReuseUpload = false))
      return Result.success()
    }

    if (attachment.remoteLocation == null) {
      Log.i(TAG, "[$attachmentId]$mediaIdLog Attachment has no remote location. Enqueueing an upload job instead.")
      if (!setArchiveStateIfUnchanged(captured!!, AttachmentTable.ArchiveTransferState.NONE)) return Result.retry(defaultBackoff())
      AppDependencies.jobManager.add(UploadAttachmentToArchiveJob(attachmentId, canReuseUpload = false))
      return Result.success()
    }

    if (attachment.archiveTransferState == AttachmentTable.ArchiveTransferState.NONE) {
      Log.i(TAG, "[$attachmentId]$mediaIdLog Not marked as pending copy. Enqueueing an upload job instead.")
      AppDependencies.jobManager.add(UploadAttachmentToArchiveJob(attachmentId))
      return Result.success()
    }

    if (isCanceled) {
      Log.w(TAG, "[$attachmentId]$mediaIdLog Canceled. Refusing to proceed.")
      return Result.failure()
    }

    val result = when (val archiveResult = BackupRepository.copyAttachmentToArchive(attachment)) {
      is Either.Right -> {
        Log.i(TAG, "[$attachmentId]$mediaIdLog Successfully copied the archive tier.")
        Result.success()
      }

      is Either.Left -> {
        when (val error = archiveResult.value) {
          is ArchiveError.NetworkError -> {
            Log.w(TAG, "[$attachmentId]$mediaIdLog Encountered a retryable network error.", error.exception)
            Result.retry(defaultBackoff())
          }
          is ArchiveError.CopyMediaError.WrongSourceLength -> {
            Log.w(TAG, "[$attachmentId]$mediaIdLog Something is invalid about our request. Possibly the length. Scheduling a re-upload.")
            if (!setArchiveStateIfUnchanged(captured!!, AttachmentTable.ArchiveTransferState.NONE)) return Result.retry(defaultBackoff())
            AppDependencies.jobManager.add(UploadAttachmentToArchiveJob(attachmentId, canReuseUpload = false))
            // Returning immediately to avoid marking the attachment as finished.
            return Result.success()
          }
          is ArchiveError.CopyMediaError.SourceNotFound -> {
            Log.w(TAG, "[$attachmentId]$mediaIdLog The attachment no longer exists on the transit tier. Scheduling a re-upload.")
            if (!setArchiveStateIfUnchanged(captured!!, AttachmentTable.ArchiveTransferState.NONE)) return Result.retry(defaultBackoff())
            AppDependencies.jobManager.add(UploadAttachmentToArchiveJob(attachmentId, canReuseUpload = false))
            // Returning immediately to avoid marking the attachment as finished.
            return Result.success()
          }
          is ArchiveError.CopyMediaError.OutOfRemoteSpace -> {
            Log.w(TAG, "[$attachmentId]$mediaIdLog Insufficient storage space! Can't upload!")
            val remoteStorageQuota = getServerQuota() ?: return Result.retry(defaultBackoff()).logW(TAG, "[$attachmentId] Failed to fetch server quota! Retrying.")

            if (SignalDatabase.attachments.getPaidEstimatedArchiveMediaSize() > remoteStorageQuota.inWholeBytes) {
              BackupRepository.markOutOfRemoteStorageSpaceError()
              return Result.failure()
            }

            Log.i(TAG, "[$attachmentId]$mediaIdLog Remote storage is full, but our local state indicates that once we reconcile our storage, we should have enough. Enqueuing the reconciliation job and retrying.")
            SignalStore.backup.remoteStorageGarbageCollectionPending = true
            ArchiveAttachmentReconciliationJob.enqueueIfRetryAllowed(forced = true)

            Result.retry(defaultBackoff())
          }
          is ArchiveError.CredentialError.RateLimited -> {
            Log.w(TAG, "[$attachmentId]$mediaIdLog Rate limit exceeded. Retrying.")
            Result.retry(error.retryAfter?.inWholeMilliseconds ?: defaultBackoff())
          }
          is ArchiveError.CredentialError.ZkVerificationFailed -> {
            Log.w(TAG, "[$attachmentId]$mediaIdLog Encountered a verification failure when trying to upload! Retrying.")
            Result.retry(defaultBackoff())
          }
          is ArchiveError.ApplicationError -> {
            Log.w(TAG, "[$attachmentId]$mediaIdLog Encountered a fatal error when trying to upload!")
            Result.fatalFailure(RuntimeException(error.exception))
          }
          // Note that a copy is anonymous, so libsignal reports a lack of entitlement as an unauthorized credential too. Either way a retry will re-establish
          // the credential, and the parent handler is what reacts to a downgraded tier.
          is ArchiveError.CredentialError.Unauthorized,
          is ArchiveError.CredentialError.NotFound,
          is ArchiveError.CredentialError.InvalidRequest -> {
            Log.w(TAG, "[$attachmentId]$mediaIdLog Got back ${error::class.simpleName}. Retrying.")
            Result.retry(defaultBackoff())
          }
        }
      }
    }

    if (result.isSuccess) {
      Log.d(TAG, "[$attachmentId]$mediaIdLog Updating archive transfer state to ${AttachmentTable.ArchiveTransferState.FINISHED}")
      val updated = ArchiveDatabaseExecutor.runBlocking {
        val matched = SignalDatabase.attachments.setArchiveTransferState(attachmentId, attachment.remoteKey!!, attachment.dataHash!!, AttachmentTable.ArchiveTransferState.FINISHED, notify = false)
        if (matched) ArchiveDatabaseExecutor.throttledNotifyAttachmentObservers()
        matched
      }
      if (!updated) {
        Log.i(TAG, "[$attachmentId] Attachment changed during archive copy; retrying.")
        return Result.retry(defaultBackoff())
      }

      if (!isCanceled) {
        if (!attachment.quote) {
          ArchiveThumbnailUploadJob.enqueueIfNecessary(attachmentId)
        } else {
          Log.d(TAG, "[$attachmentId]$mediaIdLog Refusing to enqueue thumb for quote attachment.")
        }
      } else {
        Log.d(TAG, "[$attachmentId]$mediaIdLog Refusing to enqueue thumb for canceled upload.")
      }

      progressToken?.let { ArchiveUploadProgress.onAttachmentFinishedIfOwned(attachmentId, it) }
      SignalStore.backup.archiveAttachmentReconciliationAttempts = 0
    }

    return result
  }

  private fun getServerQuota(): ByteSize? {
    return runBlocking {
      BackupRepository.getPaidType().successOrThrow().storageAllowanceBytes?.bytes
    }
  }

  override fun onFailure() {
    val state = if (isCanceled) AttachmentTable.ArchiveTransferState.COPY_PENDING else AttachmentTable.ArchiveTransferState.TEMPORARY_FAILURE
    attemptedCandidate?.let { setArchiveStateIfUnchanged(it, state) }
    progressToken?.let { ArchiveUploadProgress.onAttachmentFinishedIfOwned(attachmentId, it) }
  }

  private fun setArchiveStateIfUnchanged(candidate: AttachmentTable.UploadCandidate, state: AttachmentTable.ArchiveTransferState): Boolean =
    ArchiveDatabaseExecutor.runBlocking {
      val changed = SignalDatabase.attachments.setArchiveStateIfCandidateUnchanged(candidate, state)
      if (changed) ArchiveDatabaseExecutor.throttledNotifyAttachmentObservers()
      changed
    }

  class Factory : Job.Factory<CopyAttachmentToArchiveJob> {
    override fun create(parameters: Parameters, serializedData: ByteArray?): CopyAttachmentToArchiveJob {
      val jobData = CopyAttachmentToArchiveJobData.ADAPTER.decode(serializedData!!)
      return CopyAttachmentToArchiveJob(
        attachmentId = AttachmentId(jobData.attachmentId),
        progressToken = jobData.progressToken,
        parameters = parameters
      )
    }
  }
}
