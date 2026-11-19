package org.thoughtcrime.securesms.jobmanager

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.thoughtcrime.securesms.jobs.GenerateAudioWaveFormJob
import org.thoughtcrime.securesms.testing.SignalActivityRule

@RunWith(AndroidJUnit4::class)
class AttachmentHandoffTest {
  @get:Rule val harness = SignalActivityRule(othersCount = 1)

  @Test fun audioJobForwardsExactDependencyOwnerOnSuccess() {
    val owner = "attachment-owner-v2:synthetic".toByteArray()
    val parameters = Job.Parameters.Builder().setInputData(owner).build()
    val data = JsonJobData.Builder().putLong("part_row_id", Long.MAX_VALUE).serialize()
    val job = GenerateAudioWaveFormJob.Factory().create(parameters, data)
    val result = job.run()
    assertTrue(result.isSuccess)
    assertArrayEquals(owner, result.outputData)
  }
}
