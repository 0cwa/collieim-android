package org.thoughtcrime.securesms.jobs

import android.app.Application
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.util.crypto.AttachmentSecret
import org.signal.protos.resumableuploads.ResumableUpload
import org.thoughtcrime.securesms.jobmanager.Job
import org.thoughtcrime.securesms.jobs.protos.AttachmentUploadJobData
import org.thoughtcrime.securesms.jobs.protos.UploadAttachmentToArchiveJobData
import java.io.ByteArrayInputStream
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AttachmentUploadSnapshotTest {
  @get:Rule val folder = TemporaryFolder()
  private fun snapshots() = AttachmentUploadSnapshot(folder.root, AttachmentSecret(ByteArray(32), ByteArray(32), ByteArray(32) { 7 }))

  @Test fun samePublishedBytesAreUsedForBothPassesAndRetry() {
    val snapshots = snapshots()
    var source = "first attachment".toByteArray()
    var opens = 0
    val identity = snapshots.create(source.size.toLong(), "source", byteArrayOf(1)) { opens++; ByteArrayInputStream(source) }
    source = "other attachment".toByteArray()
    assertEquals(1, opens)
    assertTrue(snapshots.valid(identity))
    assertArrayEquals("first attachment".toByteArray(), snapshots.open(identity).use { it.readBytes() })
    assertArrayEquals("first attachment".toByteArray(), snapshots.open(identity).use { it.readBytes() })
    assertTrue(snapshots.valid(identity))
    snapshots.delete(identity)
    assertFalse(snapshots.valid(identity))
  }

  @Test fun changedAndMissingFilesCannotResume() {
    val snapshots = snapshots()
    val id = snapshots.create(3, "source", byteArrayOf(1)) { ByteArrayInputStream(byteArrayOf(1, 2, 3)) }
    val file = folder.root.listFiles()!!.single()
    file.appendBytes(byteArrayOf(4))
    assertFalse(snapshots.valid(id))
    assertFalse(snapshots.valid(id.copy(id = "../../outside")))
    file.delete()
    assertFalse(snapshots.valid(id))
  }

  @Test fun shortLongAndThrowingSourcesLeaveNoSnapshot() {
    val snapshots = snapshots()
    val sources = listOf({ ByteArrayInputStream(byteArrayOf(1)) }, { ByteArrayInputStream(byteArrayOf(1, 2, 3)) }, { throw IOException("cancelled") })
    for (source in sources) {
      try { snapshots.create(2, "source", byteArrayOf(1), source = source); fail("expected IOException") } catch (_: IOException) { }
      assertTrue(folder.root.listFiles()!!.isEmpty())
    }
  }

  @Test fun replacedGenerationDoesNotMatchOldIdentity() {
    val snapshots = snapshots()
    val old = snapshots.create(3, "source", byteArrayOf(1)) { ByteArrayInputStream(byteArrayOf(1, 2, 3)) }
    snapshots.delete(old)
    val new = snapshots.create(3, "source", byteArrayOf(1)) { ByteArrayInputStream(byteArrayOf(4, 5, 6)) }
    assertNotEquals(old.id, new.id)
    assertFalse(snapshots.valid(old))
    assertTrue(snapshots.valid(new))
  }
  @Test fun prepareResumesOnlyTheIntactSerializedPair() {
    val snapshots = snapshots()
    val old = snapshots.create(3, "source", byteArrayOf(1)) { ByteArrayInputStream(byteArrayOf(1, 2, 3)) }
    val spec = ResumableUpload()
    val resumed = snapshots.prepare(spec, old, 3, "source", byteArrayOf(1), { fail("must not recapture"); ByteArrayInputStream(byteArrayOf()) }, { false })
    assertSame(spec, resumed.spec)
    assertEquals(old, resumed.identity)
    snapshots.delete(old)
    val replacement = snapshots.prepare(spec, old, 3, "source", byteArrayOf(1), { ByteArrayInputStream(byteArrayOf(4, 5, 6)) }, { false })
    assertNull(replacement.spec)
    assertNotEquals(old.id, replacement.identity.id)
    val crashRestart = snapshots.prepare(spec, old, 3, "source", byteArrayOf(1), { ByteArrayInputStream(byteArrayOf(7, 8, 9)) }, { false })
    assertNull(crashRestart.spec)
    assertNotEquals(replacement.identity.id, crashRestart.identity.id)
    val legacy = snapshots.prepare(spec, null, 3, "source", byteArrayOf(1), { ByteArrayInputStream(byteArrayOf(1, 2, 3)) }, { false })
    assertNull(legacy.spec)
  }

  @Test fun cancellationRemovesPartialSnapshot() {
    val snapshots = snapshots()
    try { snapshots.create(3, "source", byteArrayOf(1), { true }) { ByteArrayInputStream(byteArrayOf(1, 2, 3)) }; fail("expected cancellation") } catch (_: IOException) { }
    assertTrue(folder.root.listFiles()!!.isEmpty())
  }

  @Test fun actualJobFactoryAndSerializationPreserveSpecAndSnapshotIdentity() {
    val spec = ResumableUpload()
    val original = AttachmentUploadJobData(attachmentId = 7, uploadSpec = spec, snapshotId = "01234567-89ab-4def-8abc-0123456789ab", snapshotLength = 3, snapshotDigest = "digest", snapshotSourcePath = "source", snapshotSourceRandom = okio.ByteString.of(1), snapshotPlaintextDigest = "plain")
    val job = AttachmentUploadJob.Factory().create(Job.Parameters.Builder().build(), original.encode())
    assertEquals(original, AttachmentUploadJobData.ADAPTER.decode(job.serialize()))
    val legacy = AttachmentUploadJobData(attachmentId = 7, uploadSpec = spec)
    val legacyJob = AttachmentUploadJob.Factory().create(Job.Parameters.Builder().build(), legacy.encode())
    assertEquals(legacy, AttachmentUploadJobData.ADAPTER.decode(legacyJob.serialize()))
    val queued = AttachmentUploadJobData(attachmentId = 7, snapshotSourcePath = "source", snapshotSourceRandom = okio.ByteString.of(1))
    val queuedJob = AttachmentUploadJob.Factory().create(Job.Parameters.Builder().build(), queued.encode())
    assertEquals(queued, AttachmentUploadJobData.ADAPTER.decode(queuedJob.serialize()))
  }

  @Test fun actualJobCleanupRemovesSnapshotAndClearsSerializedIdentity() {
    val snapshots = snapshots()
    val identity = snapshots.create(3, "source", byteArrayOf(1)) { ByteArrayInputStream(byteArrayOf(1, 2, 3)) }
    val data = AttachmentUploadJobData(attachmentId = 7, snapshotId = identity.id, snapshotLength = identity.length, snapshotDigest = identity.digest, snapshotSourcePath = identity.sourcePath, snapshotSourceRandom = okio.ByteString.of(*identity.sourceRandom), snapshotPlaintextDigest = identity.plaintextDigest)
    val job = AttachmentUploadJob.Factory().create(Job.Parameters.Builder().build(), data.encode())
    job.releaseSnapshot(snapshots)
    assertFalse(snapshots.valid(identity))
    val after = AttachmentUploadJobData.ADAPTER.decode(job.serialize())
    assertNull(after.snapshotId)
    assertNull(after.snapshotLength)
    assertNull(after.snapshotDigest)
  }

  @Test fun staleSweepOnlyDeletesRecognizedSnapshotFiles() {
    val snapshots = snapshots()
    val stale = java.io.File(folder.root, "attachment-upload-01234567-89ab-4def-8abc-0123456789ab")
    val unrelated = java.io.File(folder.root, "attachment-upload-user-data")
    stale.writeText("stale")
    unrelated.writeText("preserve")
    assertTrue(stale.setLastModified(1))
    assertTrue(unrelated.setLastModified(1))
    snapshots.create(1, "source", byteArrayOf(1)) { ByteArrayInputStream(byteArrayOf(1)) }
    assertFalse(stale.exists())
    assertTrue(unrelated.exists())
  }

  @Test fun changedSourcePathOrRandomDiscardsOldSpec() {
    val snapshots = snapshots()
    val spec = ResumableUpload()
    val first = snapshots.create(3, "old-path", byteArrayOf(1)) { ByteArrayInputStream(byteArrayOf(1, 2, 3)) }
    val changedPath = snapshots.prepare(spec, first, 3, "new-path", byteArrayOf(1), { ByteArrayInputStream(byteArrayOf(4, 5, 6)) }, { false })
    assertNull(changedPath.spec)
    val changedRandom = snapshots.prepare(spec, changedPath.identity, 3, "new-path", byteArrayOf(2), { ByteArrayInputStream(byteArrayOf(7, 8, 9)) }, { false })
    assertNull(changedRandom.spec)
    assertNotEquals(changedPath.identity.id, changedRandom.identity.id)
  }

  @Test fun actualArchiveJobFactoryPreservesSpecAndSnapshotIdentity() {
    val original = UploadAttachmentToArchiveJobData(attachmentId = 7, uploadSpec = ResumableUpload(), canReuseUpload = true, snapshotId = "01234567-89ab-4def-8abc-0123456789ab", snapshotLength = 3, snapshotDigest = "digest", snapshotSourcePath = "source", snapshotSourceRandom = okio.ByteString.of(1), snapshotPlaintextDigest = "plain")
    val job = UploadAttachmentToArchiveJob.Factory().create(Job.Parameters.Builder().build(), original.encode())
    assertEquals(original, UploadAttachmentToArchiveJobData.ADAPTER.decode(job.serialize()))
    val legacy = UploadAttachmentToArchiveJobData(attachmentId = 7, uploadSpec = ResumableUpload(), canReuseUpload = true)
    val legacyJob = UploadAttachmentToArchiveJob.Factory().create(Job.Parameters.Builder().build(), legacy.encode())
    assertEquals(legacy, UploadAttachmentToArchiveJobData.ADAPTER.decode(legacyJob.serialize()))
  }

  @Test fun missingSnapshotBeforeEitherArchiveReadInvalidatesSpecAndSelectsFreshIv() {
    for (stage in listOf("checksum", "upload")) {
      val snapshots = snapshots()
      val old = snapshots.create(3, "source", byteArrayOf(1)) { ByteArrayInputStream(byteArrayOf(1, 2, 3)) }
      val oldIv = ByteArray(16) { 7 }
      var spec: ResumableUpload? = ResumableUpload()
      var cleaned = false
      snapshots.delete(old)
      val read = readArchiveSnapshot(snapshots, old, { spec = null; cleaned = true }) { input -> input.use { it.readBytes() } }
      assertNull(stage, read)
      assertTrue(stage, cleaned)
      val next = snapshots.prepare(spec, old, 3, "source", byteArrayOf(1), { ByteArrayInputStream(byteArrayOf(4, 5, 6)) }, { false })
      assertNull(stage, next.spec)
      assertFalse(stage, freshOrResumeIv(null).contentEquals(oldIv))
      snapshots.delete(next.identity)
    }
  }

  @Test fun lengthMismatchReportsActualLength() {
    val snapshots = snapshots()
    for ((expected, bytes) in listOf(4L to byteArrayOf(1, 2), 2L to byteArrayOf(1, 2, 3, 4), 3L to byteArrayOf(), 0L to byteArrayOf(1, 2), 0L to byteArrayOf())) {
      try { snapshots.create(expected, "source", byteArrayOf(1)) { ByteArrayInputStream(bytes) }; fail("expected mismatch") }
      catch (e: AttachmentUploadSnapshot.LengthMismatch) { assertEquals(bytes.size.toLong(), e.actualLength) }
    }
  }

  @Test fun waveformSourceChangeIsRetryableForUploadDependency() {
    assertTrue(isAudioSourceRetryable(org.thoughtcrime.securesms.audio.AudioSourceChangedException()))
    assertFalse(isAudioSourceRetryable(IOException("decoder failure")))
  }

  @Test fun ivSelectionReusesAnExistingSpecAndGeneratesANewIvForAFreshSpec() {
    val previous = ByteArray(16) { 7 }
    val spec = org.whispersystems.signalservice.internal.push.http.ResumableUploadSpec(ByteArray(64), previous, "cdn", 3, "url", Long.MAX_VALUE, emptyMap())
    assertArrayEquals(previous, freshOrResumeIv(spec))
    assertFalse(freshOrResumeIv(null).contentEquals(previous))
  }

  @Test fun missingVisualHashSnapshotAfterBodyOpenStaysRetryableAndClosesBody() {
    var closed = false
    val body = object : ByteArrayInputStream(byteArrayOf(1)) { override fun close() { closed = true; super.close() } }
    val error = java.io.FileNotFoundException("snapshot evicted")
    try { withUploadInput(body) { throw error }; fail("expected snapshot error") } catch (e: IOException) {
      assertSame(error, e)
      assertTrue(isAttachmentUploadRetryable(e))
    }
    assertTrue(closed)
  }

  @Test fun archiveResumeRequiresTheSameRemoteKey() {
    val first = ByteArray(64) { 1 }
    val other = ByteArray(64) { 2 }
    val spec = ResumableUpload(secretKey = okio.ByteString.of(*first))
    assertTrue(archiveSpecMatchesRemoteKey(spec, first))
    assertFalse(archiveSpecMatchesRemoteKey(spec, other))
    assertTrue(archiveSpecMatchesRemoteKey(null, other))
  }

  @Test fun archiveCopyJobPersistsProgressHandoffToken() {
    val original = org.thoughtcrime.securesms.jobs.protos.CopyAttachmentToArchiveJobData(attachmentId = 7, progressToken = "handoff")
    val job = CopyAttachmentToArchiveJob.Factory().create(Job.Parameters.Builder().build(), original.encode())
    assertEquals(original, org.thoughtcrime.securesms.jobs.protos.CopyAttachmentToArchiveJobData.ADAPTER.decode(job.serialize()))
  }

}
