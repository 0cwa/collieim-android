package org.thoughtcrime.securesms.jobmanager.migrations

import org.thoughtcrime.securesms.jobmanager.JobMigration
import org.thoughtcrime.securesms.jobs.MessageFetchJob

/** Moves persisted UnifiedPush fetches out of the ordinary fetch factory's admission limit. */
class UnifiedPushMessageFetchJobMigration : JobMigration(13) {
  override fun migrate(jobData: JobData): JobData {
    return if (jobData.factoryKey == MessageFetchJob.KEY && MessageFetchJob.isUnifiedPushSerializedData(jobData.data)) {
      jobData.withFactoryKey(MessageFetchJob.UNIFIED_PUSH_KEY)
    } else {
      jobData
    }
  }
}
