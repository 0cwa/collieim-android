package org.thoughtcrime.securesms.backup.v2

import android.app.Application
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.spyk
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import io.mockk.verify
import okio.ByteString.Companion.toByteString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.archive.proto.BackupInfo
import org.signal.archive.proto.Contact
import org.signal.archive.proto.Chat
import org.signal.archive.proto.Frame
import org.signal.archive.proto.Recipient as ArchiveRecipient
import org.signal.archive.proto.Self
import org.signal.archive.stream.EncryptedBackupReader
import org.signal.archive.stream.PlainTextBackupWriter
import org.signal.core.database.forceForeignKeyConstraintsEnabled
import org.signal.core.models.ServiceId.ACI
import org.signal.core.models.backup.BackupId
import org.signal.core.models.backup.MessageBackupKey
import org.signal.core.util.logging.Log
import org.signal.core.util.readToSingleInt
import org.signal.libsignal.messagebackup.BackupForwardSecrecyToken
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.zkgroup.profiles.ProfileKey
import org.thoughtcrime.securesms.backup.v2.importer.ChatItemArchiveImporter
import org.thoughtcrime.securesms.backup.v2.processor.ChatItemArchiveProcessor
import org.thoughtcrime.securesms.crypto.ProfileKeyUtil
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.recipients.LiveRecipientCache
import org.thoughtcrime.securesms.recipients.Recipient
import org.signal.core.models.recipients.RecipientId
import org.thoughtcrime.securesms.testutil.DirectExecutor
import org.thoughtcrime.securesms.testutil.MockAppDependenciesRule
import org.thoughtcrime.securesms.testutil.MockSignalStoreRule
import org.thoughtcrime.securesms.testutil.SignalDatabaseRule
import org.thoughtcrime.securesms.testutil.SystemOutLogger
import org.thoughtcrime.securesms.util.ProfileUtil
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE)
class BackupRollbackCleanupIsolationTest {
  private val store = MockSignalStoreRule()
  private val dependencies = MockAppDependenciesRule()
  private val database = SignalDatabaseRule()
  @get:Rule val rules: RuleChain = RuleChain.outerRule(store).around(dependencies).around(database)
  private val expected = mockk<ACI>()
  private val replacement = mockk<ACI>()
  private val profileKey = mockk<ProfileKey>()
  private val reader = mockk<EncryptedBackupReader>(relaxed = true)
  private val backupKey = mockk<MessageBackupKey>()
  private val backupId = BackupId(ByteArray(16))

  @Before fun setup() {
    Log.initialize(SystemOutLogger())
    every { SignalStore.account.aci } returns expected
    every { SignalStore.account.pni } returns null
    every { SignalStore.account.e164 } returns null
    every { SignalStore.account.isRegistered } returns true
    every { SignalStore.account.hasAciIdentityKey() } returns true
    every { SignalStore.account.deviceId } returns 1
    every { SignalStore.account.servicePassword } returns "synthetic-test-password"
    val identity = mockk<IdentityKeyPair>()
    val publicKey = mockk<IdentityKey>()
    every { SignalStore.account.aciIdentityKey } returns identity
    every { identity.publicKey } returns publicKey
    every { publicKey.serialize() } returns byteArrayOf(1, 2, 3)
    every { SignalStore.registration.isRegistrationComplete } returns false
    every { SignalStore.account.isUnauthorizedReceived } returns false
    every { SignalStore.account.isLinkedDevice } returns false
    every { SignalStore.settings.isPreferSystemContactPhotos } returns false
    every { SignalStore.settings.isReadReceiptsEnabled } returns false
    every { SignalStore.releaseChannel.releaseChannelRecipientId } returns null
    justRun { SignalStore.releaseChannel.clearReleaseChannelRecipientId() }
    every { SignalStore.svr.pin } returns null
    every { SignalStore.backup.hasInvalidBackupVersion = any() } answers { }
    every { SignalStore.backup.mediaRootBackupKey = any() } answers { }
    every { SignalStore.backup.firstAppVersion = any() } answers { }
    every { SignalStore.internal.importedBackupDebugInfo = any() } answers { }
    every { expected.toString() } returns "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    every { expected.toByteArray() } returns ByteArray(16) { 1 }
    every { replacement.toString() } returns "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
    every { replacement.toByteArray() } returns ByteArray(16) { 2 }
    every { profileKey.serialize() } returns ByteArray(32) { 7 }
    every { reader.getHeader() } returns BackupInfo(version = 1, backupTimeMs = 1000, mediaRootBackupKey = ByteArray(32).toByteString())
    every { reader.hasNext() } returnsMany listOf(true, false)
    every { reader.next() } returns Frame(recipient = ArchiveRecipient(id = 17, self = Self()))
    mockkObject(ACI.Companion)
    every { ACI.parseOrNull("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa") } returns expected
    every { ACI.parseOrNull("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb") } returns replacement
    mockkStatic(AppDependencies::class)
    every { AppDependencies.recipientCache } returns LiveRecipientCache(DirectExecutor())
    mockkStatic(ProfileKeyUtil::class)
    every { ProfileKeyUtil.profileKeyOrNull(any<String>()) } returns profileKey
    mockkStatic(ProfileUtil::class)
    justRun { ProfileUtil.handleSelfProfileKeyChange() }
    mockkObject(ChatItemArchiveProcessor)
    every { ChatItemArchiveProcessor.beginImport(any()) } returns mockk<ChatItemArchiveImporter>(relaxed = true)
    mockkObject(EncryptedBackupReader.Companion)
    every { EncryptedBackupReader.createForLocalOrLinking(backupKey, backupId, any(), any()) } returns reader
  }

  @After fun cleanup() {
    unmockkObject(EncryptedBackupReader.Companion)
    unmockkObject(ChatItemArchiveProcessor)
    unmockkStatic(ProfileUtil::class)
    unmockkStatic(ProfileKeyUtil::class)
    unmockkStatic(AppDependencies::class)
    unmockkObject(ACI.Companion)
  }

  @Test fun `failed import restores the prior self cache after SQL rollback`() {
    val db = SignalDatabase.writableDatabase
    db.execSQL("INSERT INTO recipient (_id, aci) VALUES (73, 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa')")
    val original = Recipient.self().id
    assertEquals(73L, original.toLong())
    every { reader.next() } answers {
      assertEquals(1L, Recipient.self().id.toLong())
      throw java.io.IOException("synthetic frame failure")
    }
    assertThrows(java.io.IOException::class.java) {
      BackupRepository.importLocal({ byteArrayOf().inputStream() }, 0, BackupRepository.SelfData(expected, null, null, profileKey), backupId, backupKey)
    }
    assertEquals(expected, SignalDatabase.recipients.getRecord(original).aci)
    assertEquals(original, Recipient.self().id)
  }

  @Test fun `rollback cleanup failure is suppressed and foreign keys are restored`() {
    val primary = java.io.IOException("synthetic frame failure")
    val cleanup = IllegalStateException("synthetic cleanup failure")
    every { reader.next() } throws primary
    every { SignalStore.releaseChannel.clearReleaseChannelRecipientId() } throws cleanup
    val thrown = assertThrows(java.io.IOException::class.java) {
      BackupRepository.importLocal({ byteArrayOf().inputStream() }, 0, BackupRepository.SelfData(expected, null, null, profileKey), backupId, backupKey)
    }
    assertTrue(thrown === primary)
    assertTrue(thrown.suppressed.contains(cleanup))
    assertEquals(1, SignalDatabase.writableDatabase.rawQuery("PRAGMA foreign_keys", null).readToSingleInt())
  }

  @Test fun `end transaction failure does not suppress primary failure or skip foreign keys`() {
    val real = SignalDatabase.writableDatabase
    val wrapped = spyk(real)
    val primary = java.io.IOException("synthetic frame failure")
    val endFailure = IllegalStateException("synthetic end failure")
    every { SignalDatabase.writableDatabase } returns wrapped
    every { wrapped.endTransaction() } answers {
      real.endTransaction()
      throw endFailure
    }
    every { reader.next() } throws primary
    val thrown = assertThrows(java.io.IOException::class.java) {
      BackupRepository.importLocal({ byteArrayOf().inputStream() }, 0, BackupRepository.SelfData(expected, null, null, profileKey), backupId, backupKey)
    }
    assertTrue(thrown === primary)
    assertTrue(thrown.suppressed.contains(endFailure))
    assertEquals(1, real.rawQuery("PRAGMA foreign_keys", null).readToSingleInt())
  }

  @Test fun `end failure before rollback keeps the primary failure and attempts cleanup`() {
    val real = SignalDatabase.writableDatabase
    val wrapped = spyk(real)
    val primary = java.io.IOException("synthetic frame failure")
    val endFailure = IllegalStateException("synthetic end failure before rollback")
    every { SignalDatabase.writableDatabase } returns wrapped
    every { wrapped.endTransaction() } throws endFailure
    every { reader.next() } throws primary
    try {
      val thrown = assertThrows(java.io.IOException::class.java) {
        BackupRepository.importLocal({ byteArrayOf().inputStream() }, 0, BackupRepository.SelfData(expected, null, null, profileKey), backupId, backupKey)
      }
      assertTrue(thrown === primary)
      assertTrue(thrown.suppressed.contains(endFailure))
      assertTrue(real.inTransaction())
      verify(exactly = 1) { wrapped.endTransaction() }
      verify(atLeast = 1) { wrapped.setForeignKeyConstraintsEnabled(true) }
    } finally {
      if (real.inTransaction()) real.endTransaction()
      real.forceForeignKeyConstraintsEnabled(true)
    }
  }


  @Test fun `end failure after inner rollback does not end caller outer transaction`() {
    val real = SignalDatabase.writableDatabase
    real.beginTransaction()
    val wrapped = spyk(real)
    val primary = java.io.IOException("synthetic frame failure")
    val endFailure = IllegalStateException("synthetic failure after inner end")
    every { SignalDatabase.writableDatabase } returns wrapped
    every { wrapped.endTransaction() } answers {
      real.endTransaction()
      throw endFailure
    }
    every { reader.next() } throws primary
    try {
      val thrown = assertThrows(java.io.IOException::class.java) {
        BackupRepository.importLocal({ byteArrayOf().inputStream() }, 0, BackupRepository.SelfData(expected, null, null, profileKey), backupId, backupKey)
      }
      assertTrue(thrown === primary)
      assertTrue(thrown.suppressed.contains(endFailure))
      assertTrue(real.inTransaction())
      verify(exactly = 1) { wrapped.endTransaction() }
    } finally {
      if (real.inTransaction()) real.endTransaction()
      real.forceForeignKeyConstraintsEnabled(true)
    }
  }

  @Test fun `foreign key setup failure does not end caller transaction`() {
    val real = SignalDatabase.writableDatabase
    real.beginTransaction()
    val wrapped = spyk(real)
    val setupFailure = UnsupportedOperationException("synthetic foreign key setup failure")
    every { SignalDatabase.writableDatabase } returns wrapped
    every { wrapped.setForeignKeyConstraintsEnabled(false) } throws setupFailure
    try {
      val thrown = assertThrows(UnsupportedOperationException::class.java) {
        BackupRepository.importLocal({ byteArrayOf().inputStream() }, 0, BackupRepository.SelfData(expected, null, null, profileKey), backupId, backupKey)
      }
      assertTrue(thrown === setupFailure)
      assertTrue("The caller still owns its transaction", real.inTransaction())
      verify(exactly = 0) { wrapped.endTransaction() }
    } finally {
      if (real.inTransaction()) real.endTransaction()
      real.forceForeignKeyConstraintsEnabled(true)
    }
  }

  @Test fun `failed transaction start restores foreign keys without ending a transaction`() {
    val real = SignalDatabase.writableDatabase
    val wrapped = spyk(real)
    val startFailure = UnsupportedOperationException("synthetic transaction start failure")
    every { SignalDatabase.writableDatabase } returns wrapped
    every { wrapped.beginTransaction() } throws startFailure
    val thrown = assertThrows(UnsupportedOperationException::class.java) {
      BackupRepository.importLocal({ byteArrayOf().inputStream() }, 0, BackupRepository.SelfData(expected, null, null, profileKey), backupId, backupKey)
    }
    assertTrue(thrown === startFailure)
    assertFalse(real.inTransaction())
    verify(exactly = 0) { wrapped.endTransaction() }
    assertEquals(1, real.rawQuery("PRAGMA foreign_keys", null).readToSingleInt())
  }
}
