/*
 * Copyright 2026 Collie contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package org.thoughtcrime.securesms.jobs

import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import org.signal.core.util.concurrent.SignalExecutors
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.backup.v2.barkup.BarkupArchive
import org.thoughtcrime.securesms.backup.v2.barkup.BarkupClient
import org.thoughtcrime.securesms.backup.v2.barkup.BarkupHttpException
import org.thoughtcrime.securesms.backup.v2.barkup.BarkupInactive
import org.thoughtcrime.securesms.backup.v2.barkup.BarkupMissingMedia
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.flags.CollieFlags
import org.thoughtcrime.securesms.jobmanager.Job
import org.thoughtcrime.securesms.jobmanager.impl.NetworkConstraint
import org.thoughtcrime.securesms.keyvalue.CollieBarkupValues
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.service.KeyCachingService
import java.io.File
import java.io.IOException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Serializes database export with on-device backup. Network transfers and their retries use
 * a separate queue so an unavailable Barkup service cannot block scheduled on-device backups.
 */
class CollieRemoteArchiveJob private constructor(
  parameters: Parameters,
  private val enrollmentId: String,
  private val transfer: Boolean
) : Job(parameters) {
  @Volatile
  private var runningClient: BarkupClient? = null

  companion object {
    const val KEY = "CollieRemoteArchiveJob"
    const val TRANSFER_QUEUE = "__BARKUP_TRANSFER__"
    private val TAG = Log.tag(CollieRemoteArchiveJob::class)
    private val archiveLock = ReentrantLock()

    @Volatile
    private var archiveOwner: String? = null

    internal fun account(): String? {
      if (!CollieFlags.barkupRemoteBackup || !SignalStore.isInitialized || KeyCachingService.isLocked()) return null
      val account = SignalStore.account
      if (!account.isRegistered || account.isUnauthorizedReceived) return null
      return account.aci?.let { "${it.toString()}:${account.deviceId}" }
    }

    private fun activeEnrollment(): CollieBarkupValues.Enrollment? = account()?.let(SignalStore.collieBarkup::enrollment)

    @JvmStatic
    fun isEnabled(): Boolean = account()?.let { SignalStore.collieBarkup.enrollment(it) } != null

    @JvmStatic
    fun enqueue() {
      if (!CollieFlags.barkupRemoteBackup) return
      activeEnrollment()?.let { enqueue(it.id, false) }
    }

    private fun enqueue(id: String, transfer: Boolean) {
      val parameters = Parameters.Builder()
        .setQueue(if (transfer) TRANSFER_QUEUE else LocalBackupJob.QUEUE)
        // Allow one replacement to wait while a revoked enrollment's job leaves the queue.
        .setMaxInstancesForQueue(2)
        .setMaxAttempts(if (transfer) 5 else Parameters.UNLIMITED)
      if (transfer) parameters.addConstraint(NetworkConstraint.KEY)
      AppDependencies.jobManager.add(CollieRemoteArchiveJob(parameters.build(), id, transfer))
    }

    @JvmStatic
    fun disconnect(): Boolean {
      if (!CollieFlags.barkupRemoteBackup || !SignalStore.isInitialized) return true
      return try {
        SignalStore.collieBarkup.disable { revoked ->
          val jobs = AppDependencies.jobManager
          jobs.cancelInQueue(LocalBackupJob.QUEUE, KEY)
          jobs.cancelAllInQueue(TRANSFER_QUEUE)
          SignalExecutors.BOUNDED_IO.execute {
            archiveLock.withLock {
              try {
                archive().discardEnrollment(revoked)
              } catch (_: IOException) {
                Log.w(TAG, "Unable to discard staged Barkup files")
              }
            }
          }
        }
        true
      } catch (_: IOException) {
        Log.w(TAG, "Unable to remove Barkup enrollment")
        false
      }
    }

    private fun archive() = BarkupArchive(File(AppDependencies.application.noBackupFilesDir, "barkup"))

    private fun scope(enrollment: CollieBarkupValues.Enrollment): String {
      val backupId = SignalStore.backup.messageBackupKey.deriveBackupId(SignalStore.account.requireAci())
      return ("${enrollment.id}:${backupId.value.toByteString().hex()}").encodeUtf8().sha256().hex()
    }
  }

  override fun getFactoryKey(): String = KEY

  override fun serialize(): ByteArray = ("${if (transfer) "T" else "E"}$enrollmentId").toByteArray(Charsets.US_ASCII)

  override fun run(): Result {
    if (!CollieFlags.barkupRemoteBackup) return Result.success()
    val enrollment = activeEnrollment()?.takeIf { it.id == enrollmentId } ?: return Result.success()
    if (!archiveLock.tryLock()) {
      // A request for the archive already being uploaded is covered by that backup.
      if (!transfer && archiveOwner == enrollment.id) return Result.success()
      return Result.retry(if (transfer) defaultBackoff() else 1000)
    }

    try {
      archiveOwner = enrollment.id
      val entropyPool = SignalStore.account.accountEntropyPoolOrNull?.value ?: return Result.failure()
      val archiveScope = scope(enrollment)
      val checkActive = {
        if (isCanceled || activeEnrollment()?.id != enrollment.id || SignalStore.account.accountEntropyPoolOrNull?.value != entropyPool) throw BarkupInactive()
      }
      checkActive()
      val archive = archive()
      val client = BarkupClient(enrollment.api, enrollment.token, checkActive = checkActive)
      runningClient = client
      if (transfer) {
        if (!archive.hasPending(archiveScope)) return Result.success()
        archive.publish(archiveScope, client, checkActive)
        checkActive()
        archive.clear()
        SignalStore.collieBarkup.setStatus(enrollment, CollieBarkupValues.Status.READY, System.currentTimeMillis())
      } else {
        if (!archive.hasPending(archiveScope)) {
          SignalStore.collieBarkup.setStatus(enrollment, CollieBarkupValues.Status.WORKING)
          archive.prepare(archiveScope, enrollment.id, client.mediaInventory(), checkActive)
        }
        checkActive()
        enqueue(enrollment.id, true)
      }
      return Result.success()
    } catch (_: BarkupInactive) {
      return Result.success()
    } catch (_: BarkupMissingMedia) {
      archive().clear()
      enqueue(enrollment.id, false)
      return Result.success()
    } catch (error: IOException) {
      val status = (error as? BarkupHttpException)?.status
      Log.w(TAG, if (status != null) "Barkup request failed with HTTP $status" else "Barkup backup failed")
      return if (transfer && status != 401 && status != 403 && status != 507) Result.retry(defaultBackoff()) else Result.failure()
    } finally {
      runningClient = null
      archiveOwner = null
      archiveLock.unlock()
    }
  }

  override fun onCanceled() {
    runningClient?.cancel()
  }

  override fun onFailure() {
    if (!CollieFlags.barkupRemoteBackup || isCanceled) return
    val enrollment = activeEnrollment()?.takeIf { it.id == enrollmentId } ?: return
    SignalStore.collieBarkup.setStatus(enrollment, CollieBarkupValues.Status.FAILED)
  }

  class Factory : Job.Factory<CollieRemoteArchiveJob> {
    override fun create(parameters: Parameters, serializedData: ByteArray?): CollieRemoteArchiveJob {
      val state = serializedData?.toString(Charsets.US_ASCII).orEmpty()
      return CollieRemoteArchiveJob(parameters, state.drop(1), state.firstOrNull() == 'T')
    }
  }
}
