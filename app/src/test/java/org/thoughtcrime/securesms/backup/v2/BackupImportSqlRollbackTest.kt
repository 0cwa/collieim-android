package org.thoughtcrime.securesms.backup.v2

import android.app.Application
import im.molly.unifiedpush.lifecycle.UnifiedPushLifecycle
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.unmockkObject
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
import org.signal.core.database.getAllIndexDefinitions
import org.signal.core.database.getAllTableDefinitions
import org.signal.core.util.readToSingleInt
import org.signal.core.models.recipients.RecipientId
import org.signal.archive.proto.BackupInfo
import org.signal.archive.stream.PlainTextBackupWriter
import org.signal.archive.stream.EncryptedBackupReader
import org.signal.core.models.backup.MessageBackupKey
import okio.ByteString.Companion.toByteString
import org.signal.core.models.ServiceId.ACI
import org.signal.core.util.logging.Log
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.zkgroup.profiles.ProfileKey
import org.signal.storage.keyvalue.KeyValueDataSet
import org.signal.storage.keyvalue.KeyValueStore
import org.signal.storage.keyvalue.KeyValuePersistentStorage
import org.signal.storage.keyvalue.MockKeyValuePersistentStorage
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.database.RecipientTable
import org.thoughtcrime.securesms.database.model.RecipientRecord
import org.thoughtcrime.securesms.backup.v2.processor.ChatItemArchiveProcessor
import org.thoughtcrime.securesms.backup.v2.importer.ChatItemArchiveImporter
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.keyvalue.UnifiedPushValues
import org.thoughtcrime.securesms.testutil.DirectExecutor
import org.thoughtcrime.securesms.testutil.MockAppDependenciesRule
import org.thoughtcrime.securesms.testutil.MockSignalStoreRule
import org.thoughtcrime.securesms.testutil.SignalDatabaseRule
import org.thoughtcrime.securesms.testutil.SystemOutLogger
import java.io.File
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE)
class BackupImportSqlRollbackTest {
  private val store = MockSignalStoreRule()
  private val dependencies = MockAppDependenciesRule()
  private val database = SignalDatabaseRule()
  @get:Rule val rules: RuleChain = RuleChain.outerRule(store).around(dependencies).around(database)
  private lateinit var values: UnifiedPushValues
  private lateinit var persistent: KeyValuePersistentStorage
  private lateinit var aci: ACI
  private lateinit var currentSelf: Recipient

  @Before fun setup() {
    Log.initialize(SystemOutLogger())
    mockkStatic(Recipient::class)
    currentSelf = mockk<Recipient>()
    every { currentSelf.id } returns RecipientId.from(9)
    every { Recipient.self() } returns currentSelf
    persistent = MockKeyValuePersistentStorage.withDataSet(KeyValueDataSet())
    values = UnifiedPushValues(KeyValueStore(persistent, DirectExecutor()))
    every { SignalStore.unifiedpush } returns values
    every { SignalStore.backup.hasInvalidBackupVersion = any() } answers { }
    val identity = mockk<IdentityKeyPair>()
    val key = mockk<IdentityKey>()
    every { SignalStore.account.isRegistered } returns true
    every { SignalStore.account.hasAciIdentityKey() } returns true
    aci = mockk()
    every { aci.toString() } returns "00000000-0000-4000-8000-000000000001"
    every { SignalStore.account.aci } returns aci
    every { SignalStore.account.pni } returns null
    every { SignalStore.account.e164 } returns null
    every { SignalStore.account.deviceId } returns 1
    every { SignalStore.account.servicePassword } returns "synthetic-service-password"
    every { SignalStore.account.aciIdentityKey } returns identity
    every { identity.publicKey } returns key
    every { key.serialize() } returns byteArrayOf(1, 2, 3)
    UnifiedPushLifecycle.resetForTests()
  }

  @After fun cleanup() {
    unmockkStatic(Recipient::class)
    UnifiedPushLifecycle.resetForTests()
  }

  @Test fun `public import cancellation after index removal rolls back sqlite and retains marker`() {
    val fixture = listOf(File("src/androidTest/assets/backupTests/recipient_contacts_00.binproto"), File("app/src/androidTest/assets/backupTests/recipient_contacts_00.binproto")).first { it.isFile }.readBytes()
    val before = SignalDatabase.writableDatabase.getAllIndexDefinitions().map { it.name }.toSet()
    assertTrue(before.isNotEmpty())
    var checks = 0
    assertThrows(ImportRecoveryRequiredException::class.java) {
      BackupRepository.importPlaintextTest(fixture.size.toLong(), { fixture.inputStream() }, BackupRepository.SelfData(aci, null, null, mockk<ProfileKey>())) {
        checks++
        true
      }
    }
    assertEquals(1, checks)
    assertEquals(before, SignalDatabase.writableDatabase.getAllIndexDefinitions().map { it.name }.toSet())
    assertTrue(values.hasIncompleteAccountTransition)
    assertFalse(UnifiedPushLifecycle.isReady())
  }

  @Test fun `public import cancellation after table recreation rolls back schema and retains marker`() {
    val fixture = listOf(File("src/androidTest/assets/backupTests/recipient_contacts_00.binproto"), File("app/src/androidTest/assets/backupTests/recipient_contacts_00.binproto")).first { it.isFile }.readBytes()
    val tables = SignalDatabase.writableDatabase.getAllTableDefinitions().map { it.name }.toSet()
    val indexes = SignalDatabase.writableDatabase.getAllIndexDefinitions().map { it.name }.toSet()
    assertTrue(tables.isNotEmpty())
    var checks = 0
    assertThrows(ImportRecoveryRequiredException::class.java) {
      BackupRepository.importPlaintextTest(fixture.size.toLong(), { fixture.inputStream() }, BackupRepository.SelfData(aci, null, null, mockk<ProfileKey>())) {
        ++checks == 3
      }
    }
    assertEquals(3, checks)
    assertEquals(tables, SignalDatabase.writableDatabase.getAllTableDefinitions().map { it.name }.toSet())
    assertEquals(indexes, SignalDatabase.writableDatabase.getAllIndexDefinitions().map { it.name }.toSet())
    assertTrue(values.hasIncompleteAccountTransition)
    assertFalse(UnifiedPushLifecycle.isReady())
  }


  @Test fun `mismatched imported self aborts actual destructive SQL transaction`() {
    val db = SignalDatabase.writableDatabase
    db.execSQL("CREATE TABLE import_rollback_probe (value INTEGER NOT NULL)")
    db.execSQL("INSERT INTO import_rollback_probe (value) VALUES (41)")
    val bytes = ByteArrayOutputStream().also { output ->
      PlainTextBackupWriter(output).use { writer ->
        writer.write(BackupInfo(version = 1, backupTimeMs = 1000, mediaRootBackupKey = ByteArray(32).toByteString()))
      }
    }.toByteArray()
    val recipients = mockk<RecipientTable>(relaxed = true)
    val record = mockk<RecipientRecord>()
    val otherAci = mockk<ACI>()
    val selfId = RecipientId.from(9)
    every { recipients.getAndPossiblyMerge(any(), any(), any(), any(), any()) } returns selfId
    every { recipients.getRecord(selfId) } answers {
      assertTrue(db.inTransaction())
      assertEquals(0, db.rawQuery("SELECT COUNT(*) FROM import_rollback_probe", null).readToSingleInt())
      record
    }
    every { record.aci } returns otherAci
    every { record.id } returns selfId
    every { SignalStore.backup.mediaRootBackupKey = any() } answers { }
    mockkObject(SignalDatabase.Companion)
    mockkObject(ChatItemArchiveProcessor)
    try {
      every { SignalDatabase.instance } returns database.signalDatabase
      every { SignalDatabase.recipients } returns recipients
      every { ChatItemArchiveProcessor.beginImport(any()) } returns mockk<ChatItemArchiveImporter>(relaxed = true)
      val failure = assertThrows(ImportRecoveryRequiredException::class.java) {
        BackupRepository.importPlaintextTest(bytes.size.toLong(), { bytes.inputStream() }, BackupRepository.SelfData(aci, null, null, mockk<ProfileKey>()))
      }
      assertTrue("unexpected cause: ${failure.cause?.stackTraceToString()}", failure.cause is IllegalStateException && failure.cause?.message?.contains("Imported backup self") == true)
      assertEquals(41, db.rawQuery("SELECT value FROM import_rollback_probe", null).readToSingleInt())
      assertTrue(values.hasIncompleteAccountTransition)
      assertFalse(UnifiedPushLifecycle.isReady())
    } finally {
      unmockkObject(ChatItemArchiveProcessor)
      unmockkObject(SignalDatabase.Companion)
    }
  }


  @Test fun `matching imported self commits actual SQL transaction`() {
    val db = SignalDatabase.writableDatabase
    db.execSQL("CREATE TABLE import_success_probe (value INTEGER NOT NULL)")
    db.execSQL("INSERT INTO import_success_probe (value) VALUES (41)")
    val bytes = ByteArrayOutputStream().also { output ->
      PlainTextBackupWriter(output).use { writer ->
        writer.write(BackupInfo(version = 1, backupTimeMs = 1000, mediaRootBackupKey = ByteArray(32).toByteString()))
      }
    }.toByteArray()
    val recipients = mockk<RecipientTable>(relaxed = true)
    val record = mockk<RecipientRecord>()
    val selfId = RecipientId.from(9)
    every { recipients.getAndPossiblyMerge(any(), any(), any(), any(), any()) } returns selfId
    every { recipients.getRecord(selfId) } returns record
    every { record.aci } returns aci
    every { record.id } returns selfId
    every { SignalStore.backup.mediaRootBackupKey = any() } answers { }
    every { SignalStore.svr.pin } returns null
    every { SignalStore.account.isLinkedDevice } returns false
    every { SignalStore.backup.firstAppVersion = any() } answers { }
    every { SignalStore.internal.importedBackupDebugInfo = any() } answers { }
    every { SignalStore.releaseChannel.releaseChannelRecipientId } returns null
    mockkObject(SignalDatabase.Companion)
    mockkObject(ChatItemArchiveProcessor)
    try {
      every { SignalDatabase.instance } returns database.signalDatabase
      every { SignalDatabase.recipients } returns recipients
      every { ChatItemArchiveProcessor.beginImport(any()) } returns mockk<ChatItemArchiveImporter>(relaxed = true)
      val result = BackupRepository.importPlaintextTest(bytes.size.toLong(), { bytes.inputStream() }, BackupRepository.SelfData(aci, null, null, mockk<ProfileKey>()))
      assertTrue(result is ImportResult.Success)
      assertEquals(0, db.rawQuery("SELECT COUNT(*) FROM import_success_probe", null).readToSingleInt())
      assertFalse(values.hasIncompleteAccountTransition)
      assertTrue(UnifiedPushLifecycle.isReady())
    } finally {
      unmockkObject(ChatItemArchiveProcessor)
      unmockkObject(SignalDatabase.Companion)
    }
  }


  @Test fun `cancellation after SQL commit keeps recovery marker closed`() {
    val db = SignalDatabase.writableDatabase
    db.execSQL("CREATE TABLE committed_cancel_probe (value INTEGER NOT NULL)")
    db.execSQL("INSERT INTO committed_cancel_probe (value) VALUES (41)")
    val bytes = ByteArrayOutputStream().also { output ->
      PlainTextBackupWriter(output).use { writer ->
        writer.write(BackupInfo(version = 1, backupTimeMs = 1000, mediaRootBackupKey = ByteArray(32).toByteString()))
      }
    }.toByteArray()
    val recipients = mockk<RecipientTable>(relaxed = true)
    val record = mockk<RecipientRecord>()
    val selfId = RecipientId.from(9)
    every { recipients.getAndPossiblyMerge(any(), any(), any(), any(), any()) } returns selfId
    every { recipients.getRecord(selfId) } returns record
    every { record.aci } returns aci
    every { record.id } returns selfId
    every { SignalStore.backup.mediaRootBackupKey = any() } answers { }
    every { SignalStore.svr.pin } throws java.util.concurrent.CancellationException("synthetic post-commit cancellation")
    mockkObject(SignalDatabase.Companion)
    mockkObject(ChatItemArchiveProcessor)
    try {
      every { SignalDatabase.instance } returns database.signalDatabase
      every { SignalDatabase.recipients } returns recipients
      every { ChatItemArchiveProcessor.beginImport(any()) } returns mockk<ChatItemArchiveImporter>(relaxed = true)
      assertThrows(java.util.concurrent.CancellationException::class.java) {
        BackupRepository.importPlaintextTest(bytes.size.toLong(), { bytes.inputStream() }, BackupRepository.SelfData(aci, null, null, mockk<ProfileKey>()))
      }
      assertEquals(0, db.rawQuery("SELECT COUNT(*) FROM committed_cancel_probe", null).readToSingleInt())
      assertTrue(values.hasIncompleteAccountTransition)
      assertFalse(UnifiedPushLifecycle.isReady())
    } finally {
      unmockkObject(ChatItemArchiveProcessor)
      unmockkObject(SignalDatabase.Companion)
    }
  }


  @Test fun `linked import cancellation after SQL commit retains its durable route marker`() {
    val db = SignalDatabase.writableDatabase
    db.execSQL("CREATE TABLE linked_commit_probe (value INTEGER NOT NULL)")
    db.execSQL("INSERT INTO linked_commit_probe (value) VALUES (41)")
    val header = BackupInfo(version = 1, backupTimeMs = 1000, mediaRootBackupKey = ByteArray(32).toByteString())
    val reader = mockk<EncryptedBackupReader>(relaxed = true)
    val backupKey = mockk<MessageBackupKey>()
    val recipients = mockk<RecipientTable>(relaxed = true)
    val record = mockk<RecipientRecord>()
    val selfId = RecipientId.from(9)
    every { reader.getHeader() } returns header
    every { reader.hasNext() } returns false
    every { recipients.getAndPossiblyMerge(any(), any(), any(), any(), any()) } returns selfId
    every { recipients.getRecord(selfId) } returns record
    every { record.aci } returns aci
    every { record.id } returns selfId
    every { SignalStore.backup.mediaRootBackupKey = any() } answers { }
    every { SignalStore.svr.pin } throws java.util.concurrent.CancellationException("synthetic post-commit cancellation")
    mockkObject(SignalDatabase.Companion)
    mockkObject(ChatItemArchiveProcessor)
    mockkObject(EncryptedBackupReader.Companion)
    try {
      every { SignalDatabase.instance } returns database.signalDatabase
      every { SignalDatabase.recipients } returns recipients
      every { ChatItemArchiveProcessor.beginImport(any()) } returns mockk<ChatItemArchiveImporter>(relaxed = true)
      every { EncryptedBackupReader.createForLocalOrLinking(backupKey, aci, any(), any()) } returns reader
      assertThrows(java.util.concurrent.CancellationException::class.java) {
        BackupRepository.importLinkAndSyncSignalBackup(0, { byteArrayOf().inputStream() }, BackupRepository.SelfData(aci, null, null, mockk<ProfileKey>()), backupKey)
      }
      assertEquals(0, db.rawQuery("SELECT COUNT(*) FROM linked_commit_probe", null).readToSingleInt())
      assertTrue(values.hasIncompleteAccountTransition)
      assertTrue(values.hasIncompleteLinkRestore)
      val reloaded = UnifiedPushValues(KeyValueStore(persistent, DirectExecutor()))
      assertTrue(reloaded.hasIncompleteAccountTransition)
      assertTrue(reloaded.hasIncompleteLinkRestore)
      assertFalse(UnifiedPushLifecycle.isReady())
    } finally {
      unmockkObject(EncryptedBackupReader.Companion)
      unmockkObject(ChatItemArchiveProcessor)
      unmockkObject(SignalDatabase.Companion)
    }
  }


  @Test fun `mismatched current self aborts actual destructive SQL transaction`() {
    val db = SignalDatabase.writableDatabase
    db.execSQL("CREATE TABLE current_self_probe (value INTEGER NOT NULL)")
    db.execSQL("INSERT INTO current_self_probe (value) VALUES (41)")
    val bytes = ByteArrayOutputStream().also { output ->
      PlainTextBackupWriter(output).use { writer ->
        writer.write(BackupInfo(version = 1, backupTimeMs = 1000, mediaRootBackupKey = ByteArray(32).toByteString()))
      }
    }.toByteArray()
    val recipients = mockk<RecipientTable>(relaxed = true)
    val imported = mockk<RecipientRecord>()
    val other = mockk<RecipientRecord>()
    val initialId = RecipientId.from(9)
    val currentId = RecipientId.from(10)
    every { currentSelf.id } returns currentId
    every { recipients.getAndPossiblyMerge(any(), any(), any(), any(), any()) } returns initialId
    every { recipients.getRecord(initialId) } returns imported
    every { recipients.getRecord(currentId) } returns other
    every { imported.aci } returns aci
    every { imported.id } returns initialId
    every { other.aci } returns mockk()
    every { other.id } returns currentId
    every { SignalStore.backup.mediaRootBackupKey = any() } answers { }
    mockkObject(SignalDatabase.Companion)
    mockkObject(ChatItemArchiveProcessor)
    try {
      every { SignalDatabase.instance } returns database.signalDatabase
      every { SignalDatabase.recipients } returns recipients
      every { ChatItemArchiveProcessor.beginImport(any()) } returns mockk<ChatItemArchiveImporter>(relaxed = true)
      val failure = assertThrows(ImportRecoveryRequiredException::class.java) {
        BackupRepository.importPlaintextTest(bytes.size.toLong(), { bytes.inputStream() }, BackupRepository.SelfData(aci, null, null, mockk<ProfileKey>()))
      }
      assertTrue(failure.cause?.message?.contains("Current self does not match") == true)
      assertEquals(41, db.rawQuery("SELECT value FROM current_self_probe", null).readToSingleInt())
      assertTrue(values.hasIncompleteAccountTransition)
      assertFalse(UnifiedPushLifecycle.isReady())
    } finally {
      unmockkObject(ChatItemArchiveProcessor)
      unmockkObject(SignalDatabase.Companion)
    }
  }

}
