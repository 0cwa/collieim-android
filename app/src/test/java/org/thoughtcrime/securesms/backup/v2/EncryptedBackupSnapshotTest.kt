/*
 * Copyright 2026 Collie Messenger
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.backup.v2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.signal.archive.proto.AccountData
import org.signal.archive.proto.BackupInfo
import org.signal.archive.proto.Frame
import org.signal.archive.stream.EncryptedBackupReader
import org.signal.archive.stream.EncryptedBackupWriter
import org.signal.core.models.ServiceId.ACI
import org.signal.core.models.backup.MessageBackupKey
import org.signal.core.util.Util
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.UUID

class EncryptedBackupSnapshotTest {
  @get:Rule val temporary = TemporaryFolder()

  @Test
  fun `snapshot prevents a provider changing bytes between authentication and decryption`() {
    val key = MessageBackupKey(Util.getSecretBytes(32))
    val aci = ACI.from(UUID.randomUUID())
    val encrypted = backup(key, aci)
    val altered = encrypted.copyOf().apply { this[0] = (this[0].toInt() xor 1).toByte() }
    var opens = 0
    val changingProvider = { if (++opens <= 2) encrypted.inputStream() else altered.inputStream() }

    assertThrows(IOException::class.java) {
      EncryptedBackupReader.createForLocalOrLinking(key, aci, encrypted.size.toLong(), changingProvider).use { it.asSequence().toList() }
    }
    assertEquals(3, opens)

    opens = 0
    val snapshot = File(temporary.root, "backup.enc")
    val names = withEncryptedBackupSnapshot(snapshot, encrypted.size.toLong(), changingProvider) { file ->
      EncryptedBackupReader.createForLocalOrLinking(key, aci, encrypted.size.toLong(), file::inputStream).use { reader ->
        reader.asSequence().map { it.account?.username }.toList()
      }
    }
    assertEquals(listOf("authenticated"), names)
    assertEquals(1, opens)
    assertFalse(snapshot.exists())
  }

  @Test
  fun `invalid MAC is rejected before frames and snapshot is removed`() {
    val key = MessageBackupKey(Util.getSecretBytes(32))
    val aci = ACI.from(UUID.randomUUID())
    val encrypted = backup(key, aci).apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
    val snapshot = File(temporary.root, "bad.enc")
    var framesRead = 0

    assertThrows(IOException::class.java) {
      withEncryptedBackupSnapshot(snapshot, encrypted.size.toLong(), { encrypted.inputStream() }) { file ->
        EncryptedBackupReader.createForLocalOrLinking(key, aci, encrypted.size.toLong(), file::inputStream).use { reader ->
          reader.asSequence().forEach { framesRead++ }
        }
      }
    }
    assertEquals(0, framesRead)
    assertFalse(snapshot.exists())
  }

  @Test
  fun `unexpected lengths are rejected and snapshots removed`() {
    val data = byteArrayOf(1, 2, 3)
    for (length in listOf(2L, 4L)) {
      val snapshot = File(temporary.root, "length-$length.enc")
      assertThrows(IOException::class.java) {
        withEncryptedBackupSnapshot(snapshot, length, { data.inputStream() }) { error("Unexpected snapshot") }
      }
      assertFalse(snapshot.exists())
    }
  }

  private fun backup(key: MessageBackupKey, aci: ACI): ByteArray {
    val output = ByteArrayOutputStream()
    EncryptedBackupWriter.createForLocalOrLinking(key, aci, output, append = { output.write(it) }).use { writer ->
      writer.write(BackupInfo(version = 1, backupTimeMs = 1000L))
      writer.write(Frame(account = AccountData(username = "authenticated")))
    }
    return output.toByteArray()
  }
}
