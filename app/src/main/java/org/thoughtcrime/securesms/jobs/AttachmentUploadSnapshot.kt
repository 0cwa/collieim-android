/*
 * Copyright 2026 Collie Messenger
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package org.thoughtcrime.securesms.jobs

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import org.signal.blurhash.BlurHashEncoder
import org.thoughtcrime.securesms.util.MediaMetadataRetrieverUtil
import org.thoughtcrime.securesms.util.MediaUtil
import org.thoughtcrime.securesms.video.EncryptedMediaDataSource
import org.signal.core.util.Base64
import org.signal.core.util.crypto.AttachmentSecret
import org.signal.core.util.crypto.ModernDecryptingPartInputStream
import org.signal.core.util.crypto.ModernEncryptingPartOutputStream
import org.signal.protos.resumableuploads.ResumableUpload
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

/** A private, immutable encrypted copy used by both passes and all retries of one upload. */
internal class AttachmentUploadSnapshot(private val directory: File, private val secret: AttachmentSecret) {
  data class Identity(val id: String, val length: Long, val digest: String, val sourcePath: String, val sourceRandom: ByteArray, val plaintextDigest: String)

  private fun file(id: String): File {
    if (!ID.matches(id)) throw IOException("Invalid upload snapshot ID")
    return File(directory, PREFIX + id)
  }

  class LengthMismatch(val actualLength: Long) : IOException("Attachment length changed")

  data class Prepared(val identity: Identity, val spec: ResumableUpload?)

  fun prepare(spec: ResumableUpload?, identity: Identity?, length: Long, sourcePath: String, sourceRandom: ByteArray, source: () -> InputStream, cancelled: () -> Boolean): Prepared {
    if (spec != null && identity != null && identity.sourcePath == sourcePath && identity.sourceRandom.contentEquals(sourceRandom) && valid(identity)) return Prepared(identity, spec)
    delete(identity)
    return Prepared(create(length, sourcePath, sourceRandom, cancelled, source), null)
  }

  fun create(length: Long, sourcePath: String, sourceRandom: ByteArray, cancelled: () -> Boolean = { false }, source: () -> InputStream): Identity {
    if (length < 0) throw IOException("Invalid attachment length")
    if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create upload snapshot directory")
    // Stale crash leftovers are private encrypted bytes; deleting an active old snapshot is safe:
    // readers with open descriptors finish, and a later open/retry must restart with a new spec.
    val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(3)
    directory.listFiles()?.filter { file ->
      val name = file.name.removePrefix(PREFIX)
      file.name.startsWith(PREFIX) && (ID.matches(name) || TEMP.matches(name)) && file.isFile && !Files.isSymbolicLink(file.toPath()) && file.lastModified() < cutoff
    }?.forEach { it.delete() }
    val id = UUID.randomUUID().toString()
    val target = file(id)
    val temp = File.createTempFile(PREFIX, ".tmp", directory)
    try {
      val plaintextHash = MessageDigest.getInstance("SHA-256")
      val (_, encrypted) = ModernEncryptingPartOutputStream.createFor(secret, temp, true)
      encrypted.use { output ->
        source().use { input ->
          val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
          var remaining = length
          while (remaining > 0) {
            if (cancelled()) throw IOException("Upload cancelled")
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) throw LengthMismatch(length - remaining)
            if (read == 0) throw IOException("Attachment stream made no progress")
            output.write(buffer, 0, read)
            plaintextHash.update(buffer, 0, read)
            remaining -= read
          }
          if (cancelled()) throw IOException("Upload cancelled")
          if (input.read() != -1) {
            var actual = length + 1
            while (true) {
              if (cancelled()) throw IOException("Upload cancelled")
              val read = input.read(buffer)
              if (read < 0) break
              if (read == 0 || actual > Long.MAX_VALUE - read) throw IOException("Invalid attachment stream")
              actual += read
            }
            throw LengthMismatch(actual)
          }
          if (length == 0L) throw LengthMismatch(0)
        }
      }
      val digest = hash(temp)
      if (target.exists() || !temp.renameTo(target)) throw IOException("Cannot publish upload snapshot")
      return Identity(id, length, digest, sourcePath, sourceRandom.copyOf(), Base64.encodeWithPadding(plaintextHash.digest()))
    } finally {
      temp.delete()
    }
  }

  fun valid(identity: Identity): Boolean = try {
    // Local CTR encryption is unauthenticated: unchanged ciphertext is insufficient if the local secret changed.
    identity.length in 1..(Long.MAX_VALUE - 32) && file(identity.id).let {
      it.isFile && !Files.isSymbolicLink(it.toPath()) && it.length() == identity.length + 32 && hash(it) == identity.digest &&
        open(identity).use { plaintext -> hash(plaintext) == identity.plaintextDigest }
    }
  } catch (_: IOException) { false }

  fun open(identity: Identity): InputStream {
    val path = file(identity.id)
    if (Files.isSymbolicLink(path.toPath())) throw IOException("Invalid upload snapshot")
    return ModernDecryptingPartInputStream.createFor(secret, path, 0)
  }
  fun visualHash(identity: Identity, contentType: String?): String? {
    if (MediaUtil.isImageType(contentType)) return open(identity).use { BlurHashEncoder.encode(it) }
    if (!MediaUtil.isVideoType(contentType)) return null
    val path = file(identity.id)
    if (Files.isSymbolicLink(path.toPath())) throw IOException("Invalid upload snapshot")
    val data = EncryptedMediaDataSource.createForDiskBlob(secret, path)
    val retriever = MediaMetadataRetriever()
    try {
      MediaMetadataRetrieverUtil.setDataSource(retriever, data)
      val frame = retriever.getFrameAtTime(1000) ?: return null
      try {
        val thumb = Bitmap.createScaledBitmap(frame, 100, 100, false)
        try { return BlurHashEncoder.encode(thumb) } finally { if (thumb !== frame) thumb.recycle() }
      } finally { frame.recycle() }
    } finally {
      retriever.release()
      data.close()
    }
  }

  fun delete(identity: Identity?) { if (identity != null) try { file(identity.id).delete() } catch (_: IOException) {} }

  private fun hash(file: File): String = file.inputStream().use { hash(it) }

  private fun hash(input: InputStream): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
      val read = input.read(buffer)
      if (read < 0) break
      if (read == 0) throw IOException("Snapshot read made no progress")
      digest.update(buffer, 0, read)
    }
    return Base64.encodeWithPadding(digest.digest())
  }

  companion object {
    private const val PREFIX = "attachment-upload-"
    private val TEMP = Regex("[0-9]+\\.tmp")
    private val ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
  }
}

internal fun freshOrResumeIv(spec: org.whispersystems.signalservice.internal.push.http.ResumableUploadSpec?): ByteArray =
  spec?.attachmentIv ?: org.signal.core.util.Util.getSecretBytes(16)
