/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package org.thoughtcrime.securesms.jobs

import android.text.TextUtils
import kotlinx.coroutines.runBlocking
import okhttp3.internal.http2.StreamResetException
import org.signal.core.models.database.AttachmentId
import org.signal.core.util.Base64
import org.signal.core.util.Util
import org.signal.core.util.crypto.AttachmentSecretProvider
import org.signal.core.util.crypto.ModernDecryptingPartInputStream
import okio.ByteString.Companion.toByteString
import org.signal.core.util.concurrent.SignalExecutors
import org.signal.core.util.eventbus.EventBus
import org.signal.core.util.inRoundedDays
import org.signal.core.util.logging.Log
import org.signal.core.util.readLength
import org.signal.libsignal.net.RequestResult
import org.signal.libsignal.net.RetryLaterException
import org.signal.libsignal.net.UploadTooLargeException
import org.signal.network.api.AttachmentUploadResult
import org.signal.network.api.CdnApi
import org.signal.protos.resumableuploads.ResumableUpload
import org.signal.remoteconfig.RemoteConfig
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.attachments.Attachment
import org.thoughtcrime.securesms.attachments.AttachmentUploadUtil
import org.thoughtcrime.securesms.attachments.DatabaseAttachment
import org.thoughtcrime.securesms.backup.v2.BackupRepository
import org.thoughtcrime.securesms.crypto.AppAttachmentSecretStore
import org.thoughtcrime.securesms.database.AttachmentTable
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.events.PartProgressEvent
import org.thoughtcrime.securesms.jobmanager.Job
import org.thoughtcrime.securesms.jobmanager.impl.NetworkConstraint
import org.thoughtcrime.securesms.jobmanager.persistence.JobSpec
import org.thoughtcrime.securesms.jobs.protos.AttachmentUploadJobData
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.mms.PartAuthority
import org.thoughtcrime.securesms.net.NotPushRegisteredException
import org.thoughtcrime.securesms.net.SignalNetwork
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.service.AttachmentProgressService
import org.thoughtcrime.securesms.transport.UndeliverableMessageException
import org.thoughtcrime.securesms.util.MediaUtil
import org.thoughtcrime.securesms.util.MessageUtil
import org.whispersystems.signalservice.api.crypto.AttachmentCipherStreamUtil
import org.whispersystems.signalservice.api.messages.AttachmentTransferProgress
import org.whispersystems.signalservice.api.messages.SignalServiceAttachment
import org.whispersystems.signalservice.api.messages.SignalServiceAttachmentStream
import org.whispersystems.signalservice.internal.crypto.PaddingInputStream
import org.whispersystems.signalservice.internal.push.http.ResumableUploadSpec
import java.io.File
import java.io.IOException
import java.net.ProtocolException
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.toJavaDuration

/**
 * Uploads an attachment without alteration.
 *
 * Queue [AttachmentCompressionJob] before to compress.
 */
class AttachmentUploadJob private constructor(
  parameters: Parameters,
  private val attachmentId: AttachmentId,
  private var uploadSpec: ResumableUpload?,
  private var snapshot: AttachmentUploadSnapshot.Identity?,
  private var owner: Pair<String, ByteArray>?,
  private var ownerIdentity: String?,
  private var captureOwner: Boolean
) : BaseJob(parameters) {
  private var attemptedSource: AttachmentTable.DataFileInfo? = null

  companion object {
    const val KEY = "AttachmentUploadJobV3"

    private val TAG = Log.tag(AttachmentUploadJob::class.java)

    private val NETWORK_RESET_THRESHOLD = 1.minutes.inWholeMilliseconds

    val UPLOAD_REUSE_THRESHOLD = 3.days.inWholeMilliseconds

    @JvmStatic
    val maxPlaintextSize: Long
      get() {
        return AttachmentCipherStreamUtil.getMaxPlaintextSizeForCiphertext(RemoteConfig.maxAttachmentSizeBytes)
      }

    @JvmStatic
    fun jobSpecMatchesAttachmentId(jobSpec: JobSpec, attachmentId: AttachmentId): Boolean {
      if (KEY != jobSpec.factoryKey) {
        return false
      }
      val serializedData = jobSpec.serializedData ?: return false
      val data = AttachmentUploadJobData.ADAPTER.decode(serializedData)
      val parsed = AttachmentId(data.attachmentId)
      return attachmentId == parsed
    }
  }

  constructor(attachmentId: AttachmentId) : this(
    Parameters.Builder()
      .addConstraint(NetworkConstraint.KEY)
      .setLifespan(TimeUnit.DAYS.toMillis(1))
      .setMaxAttempts(Parameters.UNLIMITED)
      .build(),
    attachmentId,
    null,
    null,
    null,
    null,
    true
  )

  override fun serialize(): ByteArray {
    if (captureOwner) {
      val candidate = SignalDatabase.attachments.getUploadCandidate(attachmentId)
      owner = candidate?.info?.let { it.file.absolutePath to it.random }
      ownerIdentity = candidate?.let { SignalDatabase.attachments.uploadOwnerToken(it) }
      captureOwner = false
    }
    return AttachmentUploadJobData(
      attachmentId = attachmentId.id,
      uploadSpec = uploadSpec,
      snapshotId = snapshot?.id,
      snapshotLength = snapshot?.length,
      snapshotDigest = snapshot?.digest,
      snapshotSourcePath = snapshot?.sourcePath ?: owner?.first,
      snapshotSourceRandom = (snapshot?.sourceRandom ?: owner?.second)?.toByteString(),
      snapshotPlaintextDigest = snapshot?.plaintextDigest,
      ownerIdentity = ownerIdentity
    ).encode()
  }

  override fun getFactoryKey(): String = KEY

  override fun shouldTrace(): Boolean = true

  override fun onAdded() {
    Log.i(TAG, "onAdded() $attachmentId")

    val database = SignalDatabase.attachments
    val attachment = database.getAttachment(attachmentId)

    if (attachment == null) {
      Log.w(TAG, "Could not fetch attachment from database.")
      return
    }

    val pending = attachment.transferState != AttachmentTable.TRANSFER_PROGRESS_DONE && attachment.transferState != AttachmentTable.TRANSFER_PROGRESS_PERMANENT_FAILURE

    if (pending) {
      Log.i(TAG, "onAdded() Marking attachment progress as 'started'")
      ownerIdentity?.let { database.markTransferStateIfOwnerMatches(attachmentId, it, AttachmentTable.TRANSFER_PROGRESS_STARTED) }
    }
  }

  @Throws(Exception::class)
  public override fun onRun() {
    if (!Recipient.self().isRegistered) {
      throw NotPushRegisteredException()
    }

    // A dependency may explicitly hand off the transformed source owner. On
    // retries, use the serialized owner refreshed after our own guarded writes.
    val expectedOwner = if (runAttempt == 0 && inputData != null) inputData!!.toString(Charsets.UTF_8) else ownerIdentity
    ownerIdentity = null
    val current = SignalDatabase.attachments.getUploadCandidate(attachmentId) ?: throw InvalidAttachmentException("Cannot find the specified attachment.")
    if (expectedOwner != null && SignalDatabase.attachments.uploadOwnerToken(current) == expectedOwner) ownerIdentity = expectedOwner
    validateUploadSize(current.attachment)
    // A recent remote object can be completed by exact candidate CAS even when
    // ownership was lost; no failure callback may act on that newer row.
    if (reuseRecentUpload(current, requireComplete = true)) return
    if (expectedOwner == null || !expectedOwner.startsWith("attachment-owner-v2:")) throw IOException("Upload has no valid owner")
    var candidate = SignalDatabase.attachments.prepareUploadIfOwned(attachmentId, expectedOwner) ?: throw IOException("Attachment changed before upload began")
    val databaseAttachment = candidate.attachment
    attemptedSource = candidate.info
    ownerIdentity = SignalDatabase.attachments.uploadOwnerToken(candidate)
    candidate.info?.let { owner = it.file.absolutePath to it.random }

    validateUploadSize(databaseAttachment)
    val timeSinceUpload = System.currentTimeMillis() - databaseAttachment.uploadTimestamp
    if (reuseRecentUpload(candidate)) return
    if (databaseAttachment.uploadTimestamp > 0) {
      Log.i(TAG, "[$attachmentId] This file was previously-uploaded, but too long ago to be re-used. Age: $timeSinceUpload ms (${timeSinceUpload.milliseconds.inRoundedDays()} days)")
      if (databaseAttachment.archiveTransferState != AttachmentTable.ArchiveTransferState.NONE &&
        databaseAttachment.archiveTransferState != AttachmentTable.ArchiveTransferState.FINISHED &&
        databaseAttachment.archiveTransferState != AttachmentTable.ArchiveTransferState.PERMANENT_FAILURE) {
        candidate = SignalDatabase.attachments.clearArchiveDataIfUnchanged(candidate) ?: throw IOException("Attachment changed before archive reset")
        ownerIdentity = SignalDatabase.attachments.uploadOwnerToken(candidate)
      }
    }

    if (uploadSpec != null && System.currentTimeMillis() > uploadSpec!!.timeout) {
      Log.w(TAG, "[$attachmentId] Upload spec expired! Clearing.")
      uploadSpec = null
    }

    Log.i(TAG, "[$attachmentId] Uploading attachment for message ${databaseAttachment.mmsId}")
    try {
      val snapshots = snapshots()
      val sourceInfo = candidate.info ?: throw InvalidAttachmentException("Attachment has no local data")
      val prepared = try { snapshots.prepare(uploadSpec, snapshot, sourceInfo.length, sourceInfo.file.absolutePath, sourceInfo.random, {
        ModernDecryptingPartInputStream.createFor(
          AttachmentSecretProvider.getInstance(context, AppAttachmentSecretStore).getOrCreateAttachmentSecret(), sourceInfo.random, sourceInfo.file, 0
        )
      }, { isCanceled }) } catch (e: AttachmentUploadSnapshot.LengthMismatch) {
        uploadSpec = null
        snapshots.delete(snapshot)
        snapshot = null
        val repaired = SignalDatabase.attachments.repairUploadLengthIfCandidateUnchanged(candidate, e.actualLength)
        if (repaired != null) {
          candidate = repaired
          ownerIdentity = SignalDatabase.attachments.uploadOwnerToken(repaired)
        }
        if (repaired != null && e.actualLength == 0L) throw InvalidAttachmentException(e)
        throw e
      }
      uploadSpec = prepared.spec
      snapshot = prepared.identity
      val stable = prepared.identity
      val existingSpec = uploadSpec?.let { ResumableUploadSpec.from(it) }

      val ciphertextLength = AttachmentCipherStreamUtil.getCiphertextLength(PaddingInputStream.getPaddedSize(stable.length))

      val uploadForm = if (existingSpec == null) {
        when (val result = SignalNetwork.attachmentApi.getAttachmentV4UploadForm(ciphertextLength)) {
          is RequestResult.Success -> result.result
          is RequestResult.NonSuccess -> throw result.error
          is RequestResult.RetryableNetworkError -> throw RetryLaterException(result.retryAfter ?: defaultBackoff().milliseconds.toJavaDuration())
          is RequestResult.ApplicationError -> throw result.cause
        }
      } else {
        null
      }

      val key = existingSpec?.attachmentKey ?: Base64.decode(databaseAttachment.remoteKey!!)
      val iv = freshOrResumeIv(existingSpec)

      val checksumSha256 = if (existingSpec == null) {
        snapshots.open(stable).use { stream ->
          AttachmentUploadUtil.computeCiphertextChecksum(key, iv, stream, stable.length)
        }
      } else {
        null
      }

      getAttachmentNotificationIfNeeded(databaseAttachment).use { notification ->
        buildAttachmentStream(databaseAttachment, notification, snapshots.open(stable), stable.length) { snapshots.visualHash(stable, databaseAttachment.contentType) }.use { localAttachment ->
          val result = runBlocking {
            SignalNetwork.cdnService.uploadAttachment(
              form = uploadForm,
              key = key,
              iv = iv,
              checksumSha256 = checksumSha256,
              attachmentStream = localAttachment,
              existingSpec = existingSpec,
              onSpecCreated = { spec -> uploadSpec = spec.toProto() }
            )
          }

          val uploadResult: AttachmentUploadResult = when (result) {
            is RequestResult.Success -> result.result
            is RequestResult.NonSuccess -> throw handleUploadError(result.error)
            is RequestResult.RetryableNetworkError -> throw result.networkError
            is RequestResult.ApplicationError -> throw result.cause
          }

          if (!SignalDatabase.attachments.finalizeAttachmentAfterSnapshotUpload(candidate, uploadResult, stable.plaintextDigest)) {
            uploadSpec = null
            snapshots.delete(snapshot)
            snapshot = null
            throw IOException("Attachment changed during upload")
          }
          if (SignalStore.backup.backsUpMedia) {
            val messageId = SignalDatabase.attachments.getMessageId(databaseAttachment.attachmentId)
            when {
              messageId == AttachmentTable.PREUPLOAD_MESSAGE_ID -> {
                Log.i(TAG, "[$attachmentId] Avoid uploading preuploaded attachments to archive. Skipping.")
              }
              SignalDatabase.messages.isStory(messageId) -> {
                Log.i(TAG, "[$attachmentId] Attachment is a story. Skipping.")
              }
              SignalDatabase.messages.isViewOnce(messageId) -> {
                Log.i(TAG, "[$attachmentId] Attachment is view-once. Skipping.")
              }
              SignalDatabase.messages.willMessageExpireBeforeCutoff(messageId) -> {
                Log.i(TAG, "[$attachmentId] Message will expire within 24hrs. Skipping.")
              }
              databaseAttachment.contentType == MediaUtil.LONG_TEXT -> {
                Log.i(TAG, "[$attachmentId] Long text attachment. Skipping.")
              }
              SignalStore.account.isLinkedDevice -> {
                Log.i(TAG, "[$attachmentId] Linked device. Skipping archive.")
              }
              else -> {
                Log.i(TAG, "[$attachmentId] Enqueuing job to copy to archive.")
                AppDependencies.jobManager.add(CopyAttachmentToArchiveJob(attachmentId))
              }
            }
          }
        }
      }
      releaseSnapshot(snapshots)
      uploadSpec = null
    } catch (e: StreamResetException) {
      val lastReset = SignalStore.misc.lastNetworkResetDueToStreamResets
      val now = System.currentTimeMillis()

      if (lastReset > now || lastReset + NETWORK_RESET_THRESHOLD > now) {
        Log.w(TAG, "Our existing connections is getting repeatedly denied by the server, reset network to establish new connections")
        AppDependencies.resetNetwork(true)
        SignalStore.misc.lastNetworkResetDueToStreamResets = now
      } else {
        Log.i(TAG, "Stream reset during upload, not resetting network yet, last reset: $lastReset")
      }

      resetProgressListeners(databaseAttachment)

      throw e
    } catch (e: IOException) {
      if (e is ProtocolException || e.cause is ProtocolException) {
        Log.w(TAG, "[$attachmentId] Length may be incorrect. Recalculating.", e)
        val actualLength = SignalDatabase.attachments.getAttachmentStream(attachmentId, 0).use { it.readLength() }
        if (actualLength != databaseAttachment.size) {
          Log.w(TAG, "[$attachmentId] Length was incorrect! Will update. Previous: ${databaseAttachment.size}, Newly-Calculated: $actualLength")
          SignalDatabase.attachments.repairUploadLengthIfCandidateUnchanged(candidate, actualLength)?.let {
            candidate = it
            ownerIdentity = SignalDatabase.attachments.uploadOwnerToken(it)
          }
          uploadSpec = null
          releaseSnapshot(snapshots())
        } else {
          Log.i(TAG, "[$attachmentId] Length was correct. No action needed. Will retry.")
        }
      }

      resetProgressListeners(databaseAttachment)

      throw e
    }
  }

  private fun handleUploadError(error: CdnApi.UploadError): IOException {
    return when (error) {
      is CdnApi.UploadError.ResumeLocationInvalid -> {
        Log.w(TAG, "[$attachmentId] Resume location invalid. Clearing upload spec.")
        uploadSpec = null
        IOException("Resume location invalid")
      }
      is CdnApi.UploadError.InvalidRequest -> {
        Log.w(TAG, "[$attachmentId] Failed to upload due to a 400. Clearing upload spec.")
        uploadSpec = null
        IOException("Upload rejected with a 400")
      }
      is CdnApi.UploadError.TooLarge -> UploadTooLargeException("Upload rejected as too large")
      is CdnApi.UploadError.ChecksumMismatch -> {
        Log.w(TAG, "[$attachmentId] Upload failed due to a checksum mismatch. Clearing upload spec.")
        uploadSpec = null
        IOException("Upload rejected due to a checksum mismatch")
      }
      is CdnApi.UploadError.RateLimited -> RetryLaterException(error.retryAfter?.toJavaDuration() ?: defaultBackoff().milliseconds.toJavaDuration())
    }
  }

  private fun validateUploadSize(attachment: DatabaseAttachment) {
    if (MediaUtil.isLongTextType(attachment.contentType) && attachment.size > MessageUtil.MAX_TOTAL_BODY_SIZE_BYTES) {
      throw UndeliverableMessageException("Long text attachment is too long! Max size: ${MessageUtil.MAX_TOTAL_BODY_SIZE_BYTES} bytes, Actual size: ${attachment.size} bytes.")
    }
  }

  private fun reuseRecentUpload(candidate: AttachmentTable.UploadCandidate, requireComplete: Boolean = false): Boolean {
    val attachment = candidate.attachment
    val age = System.currentTimeMillis() - attachment.uploadTimestamp
    if (age >= UPLOAD_REUSE_THRESHOLD || TextUtils.isEmpty(attachment.remoteLocation)) return false
    if (requireComplete) {
      val key = attachment.remoteKey ?: return false
      val validKey = try { Base64.decode(key).size == 64 } catch (_: IOException) { false } catch (_: IllegalArgumentException) { false }
      if (!validKey || attachment.remoteDigest?.size != 32) return false
    }
    Log.i(TAG, "[$attachmentId] Reusing an upload from $age ms ago.")
    if (!SignalDatabase.attachments.markUploadedIfUnchanged(candidate)) throw IOException("Attachment changed before upload reuse")
    if (SignalStore.account.isPrimaryDevice && BackupRepository.shouldCopyAttachmentToArchive(attachment.attachmentId, attachment.mmsId)) {
      AppDependencies.jobManager.add(CopyAttachmentToArchiveJob(attachmentId))
    }
    if (snapshot != null) releaseSnapshot(snapshots())
    return true
  }

  private fun getAttachmentNotificationIfNeeded(attachment: Attachment): AttachmentProgressService.Controller? {
    return if (attachment.size >= AttachmentUploadUtil.FOREGROUND_LIMIT_BYTES) {
      AttachmentProgressService.start(context, context.getString(R.string.AttachmentUploadJob_uploading_media))
    } else {
      null
    }
  }

  private fun resetProgressListeners(attachment: DatabaseAttachment) {
    EventBus.default.postSticky(PartProgressEvent(attachment, PartProgressEvent.Type.NETWORK, 0, -1))
  }

  override fun onFailure() {
    if (snapshot != null) releaseSnapshot(snapshots())
    ownerIdentity?.let { SignalDatabase.attachments.markTransferStateIfOwnerMatches(attachmentId, it, AttachmentTable.TRANSFER_PROGRESS_FAILED) }
  }

  override fun getNextRunAttemptBackoff(pastAttemptCount: Int, exception: java.lang.Exception): Long {
    if (exception is RetryLaterException && exception.duration != null) {
      return exception.duration.toMillis()
    }

    return super.getNextRunAttemptBackoff(pastAttemptCount, exception)
  }

  override fun onShouldRetry(exception: Exception): Boolean {
    return isAttachmentUploadRetryable(exception)
  }

  internal fun releaseSnapshot(store: AttachmentUploadSnapshot) {
    store.delete(snapshot)
    snapshot = null
  }

  private fun snapshots(): AttachmentUploadSnapshot = AttachmentUploadSnapshot(
    File(context.cacheDir, "attachment-upload-snapshots"),
    AttachmentSecretProvider.getInstance(context, AppAttachmentSecretStore).getOrCreateAttachmentSecret()
  )

  @Throws(InvalidAttachmentException::class)
  private fun buildAttachmentStream(attachment: Attachment, notification: AttachmentProgressService.Controller?, input: java.io.InputStream, length: Long, visualHash: () -> String?): SignalServiceAttachmentStream {
    if (attachment.uri == null || length == 0L) {
      input.close()
      throw InvalidAttachmentException(IOException("Outgoing attachment has no data!"))
    }

    return withUploadInput(input) {
      AttachmentUploadUtil.buildSignalServiceAttachmentStream(
        context = context,
        attachment = attachment,
        cancellationSignal = { isCanceled },
        inputStream = input,
        inputLength = length,
        snapshotVisualHash = visualHash,
        progressListener = object : SignalServiceAttachment.ProgressListener {
          override fun onAttachmentProgress(progress: AttachmentTransferProgress) {
            SignalExecutors.BOUNDED_IO.execute {
              EventBus.default.postSticky(PartProgressEvent(attachment, PartProgressEvent.Type.NETWORK, progress))
              notification?.updateProgress(progress.value)
            }
          }

          override fun shouldCancel(): Boolean {
            return isCanceled
          }
        }
      )
    }
  }

  private inner class InvalidAttachmentException : Exception {
    constructor(message: String?) : super(message)
    constructor(e: Exception?) : super(e)
  }

  class Factory : Job.Factory<AttachmentUploadJob?> {
    override fun create(parameters: Parameters, serializedData: ByteArray?): AttachmentUploadJob {
      val data = AttachmentUploadJobData.ADAPTER.decode(serializedData!!)
      return AttachmentUploadJob(
        parameters = parameters,
        attachmentId = AttachmentId(data.attachmentId),
        data.uploadSpec,
        if (data.snapshotId != null && data.snapshotLength != null && data.snapshotDigest != null && data.snapshotSourcePath != null && data.snapshotSourceRandom != null && data.snapshotPlaintextDigest != null)
          AttachmentUploadSnapshot.Identity(data.snapshotId, data.snapshotLength, data.snapshotDigest, data.snapshotSourcePath, data.snapshotSourceRandom.toByteArray(), data.snapshotPlaintextDigest) else null,
        if (data.snapshotSourcePath != null && data.snapshotSourceRandom != null) data.snapshotSourcePath to data.snapshotSourceRandom.toByteArray() else null,
        data.ownerIdentity,
        false
      )
    }
  }
}

internal fun isAttachmentUploadRetryable(exception: Exception): Boolean =
  exception is IOException && exception !is NotPushRegisteredException && exception !is UploadTooLargeException

internal fun <T> withUploadInput(input: java.io.InputStream, action: () -> T): T = try {
  action()
} catch (e: Throwable) {
  try { input.close() } catch (closeError: IOException) { e.addSuppressed(closeError) }
  throw e
}
