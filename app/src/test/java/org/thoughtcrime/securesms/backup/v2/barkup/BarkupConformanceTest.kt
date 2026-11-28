/*
 * Copyright 2026 Collie contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package org.thoughtcrime.securesms.backup.v2.barkup

import android.app.Application
import android.content.Context
import androidx.documentfile.provider.DocumentFile
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import okhttp3.OkHttpClient
import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.TemporaryFolder
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.archive.local.ArchivedFilesReader
import org.signal.archive.local.proto.Metadata
import org.signal.archive.proto.AccountData
import org.signal.archive.proto.BackupInfo
import org.signal.archive.proto.Frame
import org.signal.archive.stream.EncryptedBackupWriter
import org.signal.core.models.backup.BackupId
import org.signal.core.models.backup.MediaName
import org.signal.core.models.backup.MessageBackupKey
import org.signal.core.util.CryptoUtil
import org.signal.core.util.Util
import org.signal.core.util.concurrent.LimitedWorker
import org.thoughtcrime.securesms.backup.v2.local.ArchiveFileSystem
import org.thoughtcrime.securesms.backup.v2.local.LocalArchiver
import org.thoughtcrime.securesms.backup.v2.local.SnapshotFileSystem
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.net.Networking
import org.thoughtcrime.securesms.net.SocksProxy
import org.thoughtcrime.securesms.testutil.MockAppDependenciesRule
import org.whispersystems.signalservice.api.crypto.AttachmentCipherInputStream
import org.whispersystems.signalservice.api.crypto.AttachmentCipherInputStream.IntegrityCheck
import org.whispersystems.signalservice.api.crypto.AttachmentCipherOutputStream
import org.whispersystems.signalservice.api.crypto.AttachmentCipherStreamUtil
import org.whispersystems.signalservice.internal.crypto.PaddingInputStream
import java.io.File
import java.io.IOException
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class BarkupConformanceTest {
  @get:Rule val temp = TemporaryFolder()
  @get:Rule val dependencies = MockAppDependenciesRule()

  @Test
  fun `deferred cleanup cannot remove a replacement enrollment archive`() {
    val archive = BarkupArchive(temp.newFolder())
    archive.prepare("new-scope", "new-enrollment", emptyMap(), {}, byteArrayOf(1)) { main, _ -> main.write(1) }
    archive.discardEnrollment("old-enrollment")
    assertTrue(archive.hasPending("new-scope"))
    archive.discardEnrollment("new-enrollment")
    assertFalse(archive.hasPending("new-scope"))
  }

  @Test(timeout = 20000)
  fun `exceptions in attachment workers fail the export without hanging or creating a ready archive`() {
    for (failure in listOf(IOException("broken read"), IllegalStateException("broken write"))) {
      val directory = temp.newFolder()
      val archive = BarkupArchive(directory)
      val executor = Executors.newFixedThreadPool(4)
      try {
        assertThrows(IOException::class.java) {
          archive.prepare("scope", "enrollment", emptyMap(), {}, byteArrayOf(1)) { _, sink ->
            LimitedWorker.execute(executor, 4, (0..7).toList()) { index ->
              sink.add(emptyMap(), {}, { index.toString().padStart(64, '0') }, { 1L }) { throw failure }
            }
          }
        }
        assertFalse(archive.hasPending("scope"))
        assertFalse(File(directory, "preparing").exists())
      } finally {
        executor.shutdownNow()
      }
    }
  }

  @Test
  fun `real Barkup keeps one-use pairing and resumes a finalized Android V2 archive across a lost response`() {
    // Optional isolated daemon fixture: storage/ is the daemon's fresh data directory,
    // tls/ca.pem trusts its local HTTPS proxy, and offer.json is a new pair-create offer.
    val controlPath = System.getenv("COLLIE_BARKUP_TEST_DIR")
    assumeNotNull(controlPath)
    val control = File(checkNotNull(controlPath))
    val context = ApplicationProvider.getApplicationContext<Context>()
    every { AppDependencies.blobs.forNonAutoEncryptingInitializedSessionOnDisk(any()) } answers {
      File(temp.newFolder(), "snapshot")
    }

    val previousNetwork = Networking.isEnabled
    val previousProxy = Networking.socksProxy
    Networking.isEnabled = true
    Networking.socksProxy = null
    try {
      val http = localTlsClient(File(control, "tls/ca.pem"))
      val offer = BarkupOffer.parse(File(control, "offer.json").readText())
      val pairer = BarkupClient(offer.api, http = http)
      val credentials = pairer.redeem(offer)
      assertEquals(403, assertThrows(BarkupHttpException::class.java) { pairer.redeem(offer) }.status)
      val client = BarkupClient(offer.api, credentials.token, http)
      assertTrue(client.snapshots().isEmpty())

      val key = MessageBackupKey(Util.getSecretBytes(32))
      val backupId = BackupId(Util.getSecretBytes(16))
      val plain = ByteArray(5 * 1024 * 1024) { (it % 251).toByte() }
      val hash = CryptoUtil.sha256(plain)
      val mediaKey = Util.getSecretBytes(64)
      val mediaName = MediaName.forLocalBackupFilename(hash, mediaKey).name
      val length = AttachmentCipherStreamUtil.getCiphertextLength(PaddingInputStream.getPaddedSize(plain.size.toLong()))
      val directory = temp.newFolder()
      val scope = "test-enrollment"
      val archive = BarkupArchive(directory)
      archive.prepare(scope, "enrollment", emptyMap(), {}, metadata(key, backupId)) { main, sink ->
        EncryptedBackupWriter.createForLocalOrLinking(key = key, backupId = backupId, outputStream = main, append = { main.write(it) }).use { writer ->
          writer.write(BackupInfo(version = 1, backupTimeMs = 1000L))
          writer.write(Frame(account = AccountData(username = "test-user")))
        }
        repeat(2) {
          sink.add(emptyMap(), {}, { mediaName }, { length }) { output ->
            PaddingInputStream(plain.inputStream(), plain.size.toLong()).use { padded ->
              AttachmentCipherOutputStream(mediaKey, null, output).use { padded.copyTo(it) }
            }
          }
        }
      }

      var patchResponseDropped = false
      val interrupted = http.newBuilder().addInterceptor { chain ->
        val response = chain.proceed(chain.request())
        if (!patchResponseDropped && response.code == 204 && chain.request().method == "PATCH") {
          patchResponseDropped = true
          response.close()
          throw IOException("Lost PATCH response after the daemon committed the chunk")
        }
        response
      }.build()
      assertThrows(IOException::class.java) { archive.publish(scope, BarkupClient(offer.api, credentials.token, interrupted)) {} }
      assertTrue(patchResponseDropped)
      assertTrue(client.snapshots().isEmpty())
      assertFalse(File(directory, "pending/files").isFile)

      val resumeOffsets = mutableListOf<String>()
      var mediaCommitResponseDropped = false
      var finalizeResponseDropped = false
      val observing = http.newBuilder().addInterceptor { chain ->
        val response = chain.proceed(chain.request())
        if (chain.request().method == "HEAD") response.header("Upload-Offset")?.let(resumeOffsets::add)
        if (!mediaCommitResponseDropped && response.code == 201 && chain.request().url.encodedPath.endsWith("/commit")) {
          mediaCommitResponseDropped = true
          response.close()
          throw IOException("Lost media commit response after the daemon stored the file")
        }
        if (!finalizeResponseDropped && response.code == 201 && chain.request().url.encodedPath.endsWith("/finalize")) {
          finalizeResponseDropped = true
          response.close()
          throw IOException("Lost finalize response after the server published")
        }
        response
      }.build()
      val resumed = BarkupClient(offer.api, credentials.token, observing)
      assertThrows(IOException::class.java) { BarkupArchive(directory).publish(scope, resumed) {} }
      assertTrue(mediaCommitResponseDropped)
      assertTrue((4 * 1024 * 1024).toString() in resumeOffsets)
      assertTrue(client.snapshots().isEmpty())
      assertFalse(File(directory, "pending/files").isFile)
      assertThrows(IOException::class.java) { BarkupArchive(directory).publish(scope, resumed) {} }
      assertTrue(finalizeResponseDropped)
      BarkupArchive(directory).publish(scope, resumed) {}
      val timestamp = client.snapshots().single()
      val storedArchive = File(control, "storage/SignalBackups")
      val archiveRoot = checkNotNull(ArchiveFileSystem.openForRestore(context, DocumentFile.fromFile(storedArchive)))
      val snapshotInfo = archiveRoot.listSnapshots().single()
      assertEquals("signal-backup-$timestamp", snapshotInfo.name)
      assertTrue(LocalArchiver.canDecryptMainArchive(SnapshotFileSystem(context, snapshotInfo.file), key))
      assertFalse(LocalArchiver.canDecryptMainArchive(SnapshotFileSystem(context, snapshotInfo.file), MessageBackupKey(Util.getSecretBytes(32))))

      val files = checkNotNull(SnapshotFileSystem.filesInputStream(context, snapshotInfo.file))
      assertEquals(listOf(mediaName), ArchivedFilesReader(files).use { it.asSequence().map { frame -> frame.mediaName }.toList() })
      val saved = File(storedArchive, "files/${mediaName.take(2)}/$mediaName")
      assertArrayEquals(File(directory, "pending/media/$mediaName").readBytes(), saved.readBytes())
      AttachmentCipherInputStream.createForAttachment(
        file = saved,
        plaintextLength = plain.size.toLong(),
        combinedKeyMaterial = mediaKey,
        integrityCheck = IntegrityCheck.forPlaintextHash(hash),
        incrementalDigest = null,
        incrementalMacChunkSize = 0
      ).use { assertArrayEquals(plain, it.readBytes()) }

      Networking.isEnabled = false
      assertThrows(IOException::class.java) { BarkupClient(offer.api, credentials.token, localTlsClient(File(control, "tls/ca.pem"))).snapshots() }
      Networking.isEnabled = true
      Networking.socksProxy = SocksProxy("127.0.0.1", SocksProxy.INVALID_PORT)
      assertThrows(IOException::class.java) { BarkupClient(offer.api, credentials.token, localTlsClient(File(control, "tls/ca.pem"))).snapshots() }
    } finally {
      Networking.isEnabled = previousNetwork
      Networking.socksProxy = previousProxy
    }
  }

  private fun localTlsClient(certificate: File): OkHttpClient {
    val ca = certificate.inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) }
    val keys = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null); setCertificateEntry("barkup-test", ca) }
    val manager = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(keys) }
      .trustManagers.single() as X509TrustManager
    val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(manager), null) }
    return BarkupClient.newHttpClient().newBuilder().sslSocketFactory(context.socketFactory, manager).build()
  }

  private fun metadata(key: MessageBackupKey, backupId: BackupId): ByteArray {
    val iv = Util.getSecretBytes(12)
    val encryptedId = Cipher.getInstance("AES/CTR/NoPadding").run {
      init(Cipher.ENCRYPT_MODE, SecretKeySpec(key.deriveLocalBackupMetadataKey(), "AES"), IvParameterSpec(iv + ByteArray(4)))
      doFinal(backupId.value)
    }
    return Metadata(version = 1, backupId = Metadata.EncryptedBackupId(iv = iv.toByteString(), encryptedId = encryptedId.toByteString())).encode()
  }
}
