/*
 * Copyright 2026 Collie Messenger
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.backup.v2

import org.signal.core.util.logging.Log
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream

private const val TAG = "EncryptedBackupSnapshot"

/** Stages one encrypted provider stream so authentication and decryption see the same bytes. */
internal fun <T> withEncryptedBackupSnapshot(
  snapshot: File,
  length: Long,
  inputStreamFactory: () -> InputStream,
  action: (File) -> T
): T {
  if (length < 0) throw IOException("Invalid encrypted backup length: $length")
  if (!snapshot.createNewFile()) throw IOException("Encrypted backup snapshot already exists")
  try {
    inputStreamFactory().use { input ->
      snapshot.outputStream().use { output ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var remaining = length
        while (remaining > 0) {
          val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
          if (read < 0) throw EOFException("Encrypted backup shorter than expected")
          if (read == 0) throw IOException("Encrypted backup stream made no progress")
          output.write(buffer, 0, read)
          remaining -= read
        }
        if (input.read() != -1) throw IOException("Encrypted backup longer than expected")
      }
    }
    return action(snapshot)
  } finally {
    if (!snapshot.delete()) Log.w(TAG, "Unable to delete encrypted backup snapshot")
  }
}
