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
import org.signal.storage.keyvalue.KeyValueDataSet
import org.signal.storage.keyvalue.KeyValueStore
import org.signal.storage.keyvalue.MockKeyValuePersistentStorage
import org.thoughtcrime.securesms.backup.v2.importer.ChatItemArchiveImporter
import org.thoughtcrime.securesms.backup.v2.processor.ChatItemArchiveProcessor
import org.thoughtcrime.securesms.crypto.ProfileKeyUtil
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.keyvalue.UnifiedPushValues
import org.thoughtcrime.securesms.recipients.LiveRecipientCache
import org.thoughtcrime.securesms.recipients.Recipient
import org.signal.core.models.recipients.RecipientId
import org.thoughtcrime.securesms.testutil.DirectExecutor
import org.thoughtcrime.securesms.testutil.MockAppDependenciesRule
import org.thoughtcrime.securesms.testutil.MockSignalStoreRule
import org.thoughtcrime.securesms.testutil.SignalDatabaseRule
import org.thoughtcrime.securesms.testutil.SystemOutLogger
import org.thoughtcrime.securesms.util.ProfileUtil
import im.molly.unifiedpush.lifecycle.UnifiedPushLifecycle
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE)
class BackupAliasImportTest {
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
  private lateinit var values: UnifiedPushValues

  @Before fun setup() {
    Log.initialize(SystemOutLogger())
    UnifiedPushLifecycle.resetForTests()
    values = UnifiedPushValues(KeyValueStore(MockKeyValuePersistentStorage.withDataSet(KeyValueDataSet()), DirectExecutor()))
    every { SignalStore.unifiedpush } returns values
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
    UnifiedPushLifecycle.resetForTests()
  }

  @Test fun `staged import merges contact aliases without changing real self`() {
    every { ACI.parseOrNull(any<ByteArray>()) } returns replacement
    every { ACI.parseOrNull(null as ByteArray?) } returns null
    every { reader.hasNext() } returnsMany listOf(true, true, true, true, true, true, false)
    val frames = listOf(
      Frame(recipient = ArchiveRecipient(id = 17, self = Self())),
      Frame(recipient = ArchiveRecipient(id = 18, contact = Contact(e164 = 15551234567L))),
      Frame(chat = Chat(id = 30, recipientId = 18)),
      Frame(recipient = ArchiveRecipient(id = 19, contact = Contact(aci = ByteArray(16) { 2 }.toByteString()))),
      Frame(chat = Chat(id = 31, recipientId = 19)),
      Frame(recipient = ArchiveRecipient(id = 20, contact = Contact(aci = ByteArray(16) { 2 }.toByteString(), e164 = 15551234567L)))
    )
    var frameIndex = 0
    var oldPhoneId: RecipientId? = null
    every { reader.next() } answers {
      if (frameIndex == 5) {
        oldPhoneId = SignalDatabase.recipients.getByE164("+15551234567").get()
        assertEquals(oldPhoneId, Recipient.live(oldPhoneId!!).resolve().id)
        val db = SignalDatabase.writableDatabase
        val phoneThread = db.rawQuery("SELECT _id FROM thread WHERE recipient_id = ?", arrayOf(oldPhoneId!!.serialize())).readToSingleInt()
        db.execSQL("INSERT INTO message (_id, date_sent, date_received, thread_id, from_recipient_id, to_recipient_id, type) VALUES (997, 1000, 1000, ?, ?, ?, 20)", arrayOf(phoneThread, oldPhoneId!!.toLong(), 1))
        db.execSQL("INSERT INTO mention (_id, thread_id, message_id, recipient_id, range_start, range_length) VALUES (997, ?, 997, ?, 0, 1)", arrayOf(phoneThread, oldPhoneId!!.toLong()))
      }
      frames[frameIndex++]
    }
    val result = BackupRepository.importLocal({ byteArrayOf().inputStream() }, 0, BackupRepository.SelfData(expected, null, null, profileKey), backupId, backupKey)
    assertTrue(result is ImportResult.Success)
    val self = (result as ImportResult.Success).selfRecipientId
    assertEquals(self, Recipient.self().id)
    assertEquals(expected, SignalDatabase.recipients.getRecord(self).aci)
    assertEquals(2, SignalDatabase.writableDatabase.rawQuery("SELECT COUNT(*) FROM recipient", null).readToSingleInt())
    val contactByAci = SignalDatabase.recipients.getByAci(replacement).get()
    val contactByNumber = SignalDatabase.recipients.getByE164("+15551234567").get()
    assertEquals(contactByAci, contactByNumber)
    assertTrue(oldPhoneId != null && oldPhoneId != contactByAci)
    assertEquals(contactByAci, SignalDatabase.recipients.getRecord(oldPhoneId!!).id)
    assertEquals(contactByAci, Recipient.live(oldPhoneId!!).resolve().id)
    val db = SignalDatabase.writableDatabase
    assertEquals(contactByAci.toLong(), db.rawQuery("SELECT recipient_id FROM mention WHERE _id = 997", null).readToSingleInt().toLong())
    assertEquals(db.rawQuery("SELECT thread_id FROM message WHERE _id = 997", null).readToSingleInt(), db.rawQuery("SELECT thread_id FROM mention WHERE _id = 997", null).readToSingleInt())
    for (name in listOf("mention_recipient_id_thread_id_index", "message_thread_story_parent_story_scheduled_date_latest_revision_id_index", "message_thread_unread_count_index")) {
      assertEquals(1, db.rawQuery("SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name = ?", arrayOf(name)).readToSingleInt())
    }
    assertTrue(contactByAci != self)
    verify(exactly = 6) { reader.next() }
  }

  @Test fun `alias import recovers when destination indexes are missing`() {
    for (name in listOf("mention_recipient_id_thread_id_index", "message_thread_story_parent_story_scheduled_date_latest_revision_id_index", "message_thread_unread_count_index")) {
      SignalDatabase.writableDatabase.execSQL("DROP INDEX $name")
    }
    `staged import merges contact aliases without changing real self`()
  }

  @Test fun `import rejects a mismatched self before committing SQL`() {
    val db = SignalDatabase.writableDatabase
    db.execSQL("INSERT INTO recipient (_id, aci) VALUES (73, 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa')")
    val originalSelf = Recipient.self().id
    assertEquals(73L, originalSelf.toLong())
    var checks = 0
    every { reader.hasNext() } answers {
      if (checks++ == 0) {
        true
      } else {
        assertTrue(db.inTransaction())
        db.execSQL("UPDATE recipient SET aci = ? WHERE _id = ?", arrayOf(replacement.toString(), Recipient.self().id.toLong()))
        false
      }
    }
    val failure = assertThrows(IllegalStateException::class.java) {
      BackupRepository.importLocal({ byteArrayOf().inputStream() }, 0, BackupRepository.SelfData(expected, null, null, profileKey), backupId, backupKey)
    }
    assertTrue(failure.message?.contains("Imported backup self") == true)
    assertEquals(expected, SignalDatabase.recipients.getRecord(originalSelf).aci)
    assertEquals(originalSelf, Recipient.self().id)
  }
}
