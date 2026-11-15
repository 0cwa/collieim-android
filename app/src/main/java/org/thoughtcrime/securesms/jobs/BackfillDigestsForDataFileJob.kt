/*
 * Copyright 2024 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.jobs

import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.jobmanager.Job
import org.thoughtcrime.securesms.jobs.protos.BackfillDigestsForDataFileJobData

/**
 * Backfills missing remote keys for attachments that share a data file. Existing keys can identify different live or archived media and are left unchanged.
 * A remote digest is only valid for the exact ciphertext and IV produced by an upload, so this job does not synthesize or update digests.
 */
class BackfillDigestsForDataFileJob private constructor(
  private val dataFile: String,
  params: Parameters
) : Job(params) {

  companion object {
    private val TAG = Log.tag(BackfillDigestsForDataFileJob::class)
    const val KEY = "BackfillDigestsForDataFileJob"
    const val QUEUE = "BackfillDigestJob"
  }

  constructor(dataFile: String) : this(
    dataFile = dataFile,
    params = Parameters.Builder()
      .setQueue(QUEUE)
      .setMaxAttempts(3)
      .setLifespan(Parameters.IMMORTAL)
      .build()
  )

  override fun serialize(): ByteArray {
    return BackfillDigestsForDataFileJobData(dataFile = dataFile).encode()
  }

  override fun getFactoryKey(): String = KEY

  override fun run(): Result {
    SignalDatabase.attachments.backfillMissingRemoteKeysForDataFile(dataFile)
    return Result.success()
  }

  override fun onFailure() {
    Log.w(TAG, "Failed to backfill remote keys for file $dataFile!")
  }

  class Factory : Job.Factory<BackfillDigestsForDataFileJob> {
    override fun create(parameters: Parameters, serializedData: ByteArray?): BackfillDigestsForDataFileJob {
      val dataFile = (BackfillDigestsForDataFileJobData.ADAPTER.decode(serializedData!!).dataFile)
      return BackfillDigestsForDataFileJob(dataFile, parameters)
    }
  }
}
