/*
 * Copyright 2026 Collie contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package org.thoughtcrime.securesms.backup.v2.barkup

import android.util.AtomicFile
import org.signal.archive.local.ArchivedFilesWriter
import org.signal.archive.local.proto.FilesFrame
import org.signal.core.models.backup.MediaName
import org.signal.core.util.StreamUtil
import org.thoughtcrime.securesms.backup.v2.BackupRepository
import org.thoughtcrime.securesms.backup.v2.local.LocalArchiver
import org.thoughtcrime.securesms.backup.v2.local.LocalArchiver.cipherLength
import org.thoughtcrime.securesms.backup.v2.local.SnapshotFileSystem
import org.thoughtcrime.securesms.database.SignalDatabase
import org.whispersystems.signalservice.api.crypto.AttachmentCipherOutputStream
import org.whispersystems.signalservice.internal.crypto.PaddingInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.time.Clock
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/** One durable, private V2 archive. Upload IDs are only ever reused with these exact ciphertext files. */
internal class BarkupArchive(private val root: File, private val clock: Clock = Clock.systemUTC()) {
  companion object {
    private val TIMESTAMP = DateTimeFormatter.ofPattern("uuuu-MM-dd-HH-mm-ss", Locale.US).withZone(ZoneOffset.UTC)
    private val MEMBERS = listOf(SnapshotFileSystem.METADATA_NAME, SnapshotFileSystem.MAIN_NAME, SnapshotFileSystem.FILES_NAME)
  }

  private val pending = File(root, "pending")
  private val preparing = File(root, "preparing")

  fun hasPending(scope: String): Boolean {
    return File(pending, "scope").takeIf(File::isFile)?.readText() == scope
  }

  fun clear() {
    if (!root.deleteRecursively()) throw IOException("Unable to remove the Barkup archive")
  }

  fun discardEnrollment(enrollmentId: String) {
    for (directory in listOf(preparing, pending)) {
      val owner = File(directory, "owner").takeIf(File::isFile)?.readText()
      if (owner == enrollmentId && !directory.deleteRecursively()) throw IOException("Unable to discard the Barkup archive")
    }
  }

  fun prepare(scope: String, enrollmentId: String, remote: Map<String, Long>, checkActive: () -> Unit) {
    if (hasPending(scope)) return

    SignalDatabase.attachments.createRemoteKeyForAttachmentsThatNeedArchiveUpload()
    SignalDatabase.attachmentMetadata.insertNewKeysForExistingAttachments()

    prepare(scope, enrollmentId, remote, checkActive, LocalArchiver.encodeMetadata()) { main, sink ->
      BackupRepository.exportForLocalBackup(
        main,
        localBackupProgressEmitter = null,
        cancellationSignal = { Thread.currentThread().isInterrupted || runCatching(checkActive).isFailure },
        recordSizeEstimate = false
      ) { attachment, source ->
        sink.add(
          remote,
          checkActive,
          name = { MediaName.forLocalBackupFilename(attachment.plaintextHash, attachment.localBackupKey.key).name },
          length = { attachment.cipherLength }
        ) { output ->
          val input = source() ?: throw IOException("Missing Barkup attachment")
          PaddingInputStream(input, attachment.size).use { padded ->
            AttachmentCipherOutputStream(attachment.localBackupKey.key, null, output).use { ciphertext ->
              val copied = StreamUtil.copy(padded, ciphertext, false, false)
              if (copied != PaddingInputStream.getPaddedSize(attachment.size)) throw IOException("Incomplete Barkup attachment")
            }
          }
        }
      }
    }
  }

  /** Shared by the live export and the conformance fixture's Android V2 writer. */
  internal fun prepare(
    scope: String,
    enrollmentId: String,
    remote: Map<String, Long>,
    checkActive: () -> Unit,
    metadata: ByteArray,
    exportMain: (OutputStream, MediaSink) -> Unit
  ) {
    if (hasPending(scope)) return
    clear()
    checkActive()
    try {
      val media = File(preparing, "media")
      if (!media.mkdirs()) throw IOException("Unable to create the Barkup archive")
      durableWrite(File(preparing, "owner")) { it.write(enrollmentId.toByteArray(Charsets.US_ASCII)) }
      durableWrite(File(preparing, MEMBERS[0])) { it.write(metadata) }

      val sink = MediaSink(media)
      durableWrite(File(preparing, MEMBERS[1])) { exportMain(KeepOpen(it), sink) }
      sink.checkFailure()
      checkActive()
      durableWrite(File(preparing, "plan")) { output ->
        output.bufferedWriter(Charsets.US_ASCII).let { writer ->
          sink.plan.toSortedMap().forEach { (name, length) ->
            writer.append(name).append(' ').append(length.toString()).append('\n')
          }
          writer.flush()
        }
      }
      durableWrite(File(preparing, "scope")) { it.write(scope.toByteArray(Charsets.US_ASCII)) }
      if (!preparing.renameTo(pending)) throw IOException("Unable to commit the Barkup archive")
    } finally {
      preparing.deleteRecursively()
    }
  }

  fun publish(scope: String, client: BarkupClient, checkActive: () -> Unit) {
    if (!hasPending(scope)) throw IOException("Missing Barkup archive")
    checkActive()
    val timestamp = timestamp()
    val attempt = File(pending, "remote-$timestamp")
    val claimed = File(attempt, "claimed")
    val members = MEMBERS.associateWith { File(pending, it) }

    try {
      if (members.values.all(File::isFile) && client.matchesSnapshot(timestamp, members)) return
      if (!claimed.isFile) {
        client.beginSnapshot(timestamp)
        mark(claimed)
      }

      val serverMedia = client.mediaInventory()
      val manifest = File(pending, SnapshotFileSystem.FILES_NAME)
      val plan = File(pending, "plan")
      plan.forEachLine(Charsets.US_ASCII) { line ->
        checkActive()
        val name = line.substringBefore(' ')
        val length = line.substringAfter(' ', "").toLongOrNull()
        if (!BARKUP_MEDIA_NAME.matches(name) || length == null || length <= 0L) throw IOException("Invalid Barkup media plan")
        val ciphertext = File(pending, "media/$name")
        val done = File(pending, "confirmed/$name")
        if (serverMedia[name] == length && (!ciphertext.isFile || done.isFile)) return@forEachLine
        if (!ciphertext.isFile) throw BarkupMissingMedia()
        if (ciphertext.length() != length) throw IOException("Invalid staged Barkup media")
        client.uploadMedia(name, ciphertext, FileSession(File(pending, "uploads/$name")))
        mark(done)
      }

      // The manifest is created only after all its media objects have been confirmed.
      if (!manifest.isFile) {
        val temporaryManifest = File(pending, "files.tmp")
        durableWrite(temporaryManifest) { output ->
          ArchivedFilesWriter(KeepOpen(output)).use { writer ->
            plan.forEachLine(Charsets.US_ASCII) { writer.write(FilesFrame(mediaName = it.substringBefore(' '))) }
          }
        }
        if (!temporaryManifest.renameTo(manifest)) throw IOException("Unable to commit the Barkup manifest")
      }
      for ((member, file) in members) {
        val done = File(attempt, "$member.done")
        if (!done.isFile) {
          client.uploadMember(timestamp, member, file, FileSession(File(attempt, "$member.session")))
          mark(done)
        }
      }

      checkActive()
      client.finalizeSnapshot(timestamp, members)
    } catch (error: BarkupHttpException) {
      // A lost begin or session creation response can leave an unowned or blocked timestamp.
      if (error.status == 404 || error.status == 409 || error.status == 422) {
        val next = TIMESTAMP.format(clock.instant())
        if (next > timestamp) {
          atomicText(File(pending, "timestamp"), next)
          attempt.deleteRecursively()
        }
      }
      throw error
    }
  }

  private fun timestamp(): String {
    val file = File(pending, "timestamp")
    val existing = if (file.isFile) AtomicFile(file).readFully().toString(Charsets.US_ASCII) else null
    if (existing != null) {
      if (!BARKUP_TIMESTAMP.matches(existing)) throw IOException("Invalid saved Barkup snapshot timestamp")
      return existing
    }
    return TIMESTAMP.format(clock.instant()).also { atomicText(file, it) }
  }

  internal class MediaSink(private val media: File) {
    private val failure = AtomicReference<Throwable?>()
    val plan = ConcurrentHashMap<String, Long>()

    /** Every exception is contained until BackupRepository's workers have all returned. */
    fun add(remote: Map<String, Long>, checkActive: () -> Unit, name: () -> String, length: () -> Long, encrypt: (OutputStream) -> Unit) {
      try {
        if (failure.get() != null) return
        checkActive()
        val mediaName = name()
        val expected = length()
        if (!BARKUP_MEDIA_NAME.matches(mediaName) || expected <= 0L) throw IOException("Invalid Barkup attachment")
        val previous = plan.putIfAbsent(mediaName, expected)
        if (previous != null && previous != expected) throw IOException("Inconsistent Barkup attachment length")
        if (previous != null) return

        val remoteLength = remote[mediaName]
        if (remoteLength == expected) return
        if (remoteLength != null) throw IOException("Barkup has conflicting media")
        val ciphertext = File(media, mediaName)
        durableWrite(ciphertext) { encrypt(KeepOpen(it)) }
        if (ciphertext.length() != expected) throw IOException("Invalid Barkup ciphertext length")
      } catch (error: Throwable) {
        failure.compareAndSet(null, error)
      }
    }

    fun checkFailure() {
      val error = failure.get() ?: return
      if (error is BarkupInactive) throw error
      throw IOException("Unable to stage a Barkup attachment", error)
    }
  }

  private class FileSession(private val file: File) : BarkupUploadSession {
    override fun read(): String? {
      return if (file.isFile) AtomicFile(file).readFully().toString(Charsets.US_ASCII) else null
    }

    override fun save(id: String) {
      atomicText(file, id)
    }

    override fun clear() {
      AtomicFile(file).delete()
    }
  }

  /** Export and cipher writers close their stream before the enclosing write syncs it. */
  private class KeepOpen(private val delegate: OutputStream) : OutputStream() {
    override fun write(value: Int) = delegate.write(value)
    override fun write(bytes: ByteArray, off: Int, len: Int) = delegate.write(bytes, off, len)
    override fun flush() = delegate.flush()
  }
}

private fun durableWrite(file: File, write: (FileOutputStream) -> Unit) {
  FileOutputStream(file).use { output ->
    write(output)
    output.fd.sync()
  }
}

private fun atomicText(file: File, text: String) {
  val atomic = AtomicFile(file)
  val stream = atomic.startWrite()
  try {
    stream.write(text.toByteArray(Charsets.US_ASCII))
    atomic.finishWrite(stream)
  } catch (error: Throwable) {
    atomic.failWrite(stream)
    throw error
  }
}

private fun mark(file: File) = atomicText(file, "1")

internal class BarkupInactive : IOException("Barkup is no longer enabled for this account")
internal class BarkupMissingMedia : IOException("Previously present Barkup media must be exported again")
