/*
 * Copyright 2026 Collie Messenger
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.backup.v2.stream

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test
import org.signal.archive.stream.EncryptedBackupReader
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.FilterInputStream
import java.util.Random
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class AuthenticatedPlaintextExportTest {
  private val aesKey = ByteArray(32) { it.toByte() }
  private val macKey = ByteArray(32) { (it + 32).toByte() }
  private val original = byteArrayOf(0, 1, 2, 127, -1, 42)

  private fun encryptedBackup(plaintext: ByteArray = original): ByteArray {
    val compressed = ByteArrayOutputStream().apply {
      GZIPOutputStream(this).use { it.write(plaintext) }
    }.toByteArray()
    val iv = ByteArray(16) { (it + 1).toByte() }
    val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
      init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(iv))
    }
    val content = iv + cipher.doFinal(compressed)
    val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(macKey, "HmacSHA256")) }
    return content + mac.doFinal(content)
  }

  private fun export(bytes: ByteArray, output: ByteArrayOutputStream) {
    EncryptedBackupReader.copyAuthenticatedPlaintext(aesKey, macKey, bytes.size.toLong(), { bytes.inputStream() }, output)
  }

  @Test fun exportsExactAuthenticatedBytes() {
    val output = ByteArrayOutputStream()
    export(encryptedBackup(), output)
    assertArrayEquals(original, output.toByteArray())
  }

  @Test fun exportsFromStreamPositionedAfterPrefix() {
    val prefix = byteArrayOf(9, 8, 7, 6)
    val encrypted = encryptedBackup()
    val file = prefix + encrypted
    val output = ByteArrayOutputStream()
    EncryptedBackupReader.copyAuthenticatedPlaintext(aesKey, macKey, encrypted.size.toLong(), {
      file.inputStream().apply { check(skip(prefix.size.toLong()) == prefix.size.toLong()) }
    }, output)
    assertArrayEquals(original, output.toByteArray())
  }

  @Test fun authenticatesUsingBoundedReads() {
    val plaintext = ByteArray(128 * 1024).also { Random(1).nextBytes(it) }
    val encrypted = encryptedBackup(plaintext)
    var streamsOpened = 0
    val output = ByteArrayOutputStream()
    EncryptedBackupReader.copyAuthenticatedPlaintext(aesKey, macKey, encrypted.size.toLong(), {
      streamsOpened++
      object : FilterInputStream(encrypted.inputStream()) {
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
          check(length <= 8192) { "Unbounded read requested: $length" }
          return super.read(buffer, offset, length)
        }
      }
    }, output)
    assertEquals(2, streamsOpened)
    assertArrayEquals(plaintext, output.toByteArray())
  }

  @Test fun leavesOutputOwnershipWithCaller() {
    val output = object : ByteArrayOutputStream() {
      var closed = false
      override fun close() {
        closed = true
        super.close()
      }
    }
    export(encryptedBackup(), output)
    assertFalse(output.closed)
    val corrupt = encryptedBackup().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
    try {
      export(corrupt, output)
      fail("Modified backup was accepted")
    } catch (expected: IOException) {
      assertFalse(output.closed)
    }
  }

  @Test fun rejectsChangedIvBeforeWriting() = assertTamperRejected(0)
  @Test fun rejectsChangedCiphertextBeforeWriting() = assertTamperRejected(16)
  @Test fun rejectsChangedMacBeforeWriting() = assertTamperRejected(-1)

  private fun assertTamperRejected(index: Int) {
    val bytes = encryptedBackup()
    val offset = if (index < 0) bytes.lastIndex else index
    bytes[offset] = (bytes[offset].toInt() xor 1).toByte()
    val output = ByteArrayOutputStream()
    try {
      export(bytes, output)
      fail("Modified backup was accepted")
    } catch (expected: IOException) {
      assertEquals(0, output.size())
    }
  }
}
