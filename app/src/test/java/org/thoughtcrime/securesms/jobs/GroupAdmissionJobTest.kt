/*
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.jobs

import android.app.Application
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import okio.ByteString.Companion.toByteString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.models.recipients.RecipientId
import org.signal.protos.resumableuploads.ResumableUpload
import org.thoughtcrime.securesms.groups.v2.admission.GroupAdmission
import org.thoughtcrime.securesms.groups.v2.admission.GroupAdmissionSendGate
import org.thoughtcrime.securesms.jobmanager.Job
import org.thoughtcrime.securesms.jobmanager.JsonJobData
import org.thoughtcrime.securesms.jobs.protos.AttachmentUploadJobData

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class GroupAdmissionJobTest {

  private val recipientId = RecipientId.from(23)

  @Before
  fun setUp() {
    mockkStatic(GroupAdmission::class)
    every { GroupAdmission.isHeld(recipientId) } returns true
  }

  @After
  fun tearDown() {
    unmockkStatic(GroupAdmission::class)
  }

  @Test
  fun queuedUploadRetainsRecipientAndOwnerAcrossFactoryRoundTrip() {
    val original = AttachmentUploadJobData(
      attachmentId = 7,
      uploadSpec = ResumableUpload(),
      snapshotId = "01234567-89ab-4def-8abc-0123456789ab",
      snapshotLength = 3,
      snapshotDigest = "digest",
      snapshotSourcePath = "source",
      snapshotSourceRandom = byteArrayOf(1, 2, 3).toByteString(),
      snapshotPlaintextDigest = "plain",
      ownerIdentity = "attachment-owner-v2:example",
      admissionRecipientId = recipientId.toLong()
    )
    val restored = AttachmentUploadJob.Factory().create(Job.Parameters.Builder().build(), original.encode())
    assertEquals(original, AttachmentUploadJobData.ADAPTER.decode(restored.serialize()))
  }

  @Test
  fun queuedPreUploadStopsBeforeAccountDatabaseOrNetworkAccessWhenGroupBecomesHeld() {
    // No application/database/network fixtures are initialized. A stale queued upload must stop
    // on the persisted destination before it can inspect or upload the pre-upload attachment.
    val data = AttachmentUploadJobData(attachmentId = 7, admissionRecipientId = recipientId.toLong())
    val restored = AttachmentUploadJob.Factory().create(Job.Parameters.Builder().build(), data.encode())

    val result = restored.run()

    assertTrue(result.isFailure)
    assertFalse(result.isRetry)
    assertEquals("FAILURE", result.toString())
    verify(exactly = 1) { GroupAdmission.isHeld(recipientId) }
  }

  @Test
  fun legacyUploadPayloadRemainsReadableWithoutInventingARecipient() {
    val original = AttachmentUploadJobData(attachmentId = 7, ownerIdentity = "existing-owner")
    val restored = AttachmentUploadJob.Factory().create(Job.Parameters.Builder().build(), original.encode())
    assertEquals(original, AttachmentUploadJobData.ADAPTER.decode(restored.serialize()))
  }

  @Test
  fun queuedCallUpdateStopsBeforeAccountDatabaseOrNetworkAccessWhenGroupBecomesHeld() {
    val data = JsonJobData.Builder()
      .putString("recipient_id", recipientId.serialize())
      .putString("era_id", "era")
      .putString("recipients", RecipientId.toSerializedList(listOf(RecipientId.from(24))))
      .putInt("initial_recipient_count", 1)
      .serialize()
    val restored = GroupCallUpdateSendJob.Factory().create(Job.Parameters.Builder().build(), data)

    assertTrue(restored.run().isSuccess)
    verify(exactly = 1) { GroupAdmission.isHeld(recipientId) }
  }

  @Test
  fun policyRejectionIsAnOrdinaryFailureEvenWhenAJobRetriesIoErrors() {
    var retryPolicyWasCalled = false
    val job = object : BaseJob(Job.Parameters.Builder().build()) {
      override fun getFactoryKey(): String = "test"
      override fun serialize(): ByteArray? = null
      override fun onRun() = throw GroupAdmissionSendGate.HeldGroupException()
      override fun onShouldRetry(e: Exception): Boolean {
        retryPolicyWasCalled = true
        return true
      }
      override fun onFailure() = Unit
    }

    val result = job.run()

    assertTrue(result.isFailure)
    assertFalse(result.isRetry)
    assertEquals("FAILURE", result.toString())
    assertFalse(retryPolicyWasCalled)
  }
}
