package org.thoughtcrime.securesms.backup.v2

import android.app.Application
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.unmockkObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.models.ServiceId.ACI
import org.signal.core.models.recipients.RecipientId
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.recipients.LiveRecipientCache
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.testutil.DirectExecutor
import org.thoughtcrime.securesms.testutil.MockAppDependenciesRule
import org.thoughtcrime.securesms.testutil.MockSignalStoreRule
import org.thoughtcrime.securesms.testutil.SignalDatabaseRule
import org.thoughtcrime.securesms.testutil.SystemOutLogger

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE)
class RealRecipientSelfLookupTest {
  private val store = MockSignalStoreRule()
  private val dependencies = MockAppDependenciesRule()
  private val database = SignalDatabaseRule()
  @get:Rule val rules: RuleChain = RuleChain.outerRule(store).around(dependencies).around(database)
  private val expected = mockk<ACI>()
  private val replacement = mockk<ACI>()

  @Before fun setup() {
    Log.initialize(SystemOutLogger())
    every { SignalStore.settings.isPreferSystemContactPhotos } returns false
    every { expected.toString() } returns "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    every { replacement.toString() } returns "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
    mockkObject(ACI.Companion)
    every { ACI.parseOrNull("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa") } returns expected
    every { ACI.parseOrNull("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb") } returns replacement
  }

  @After fun cleanup() = unmockkObject(ACI.Companion)

  @Test fun `real recipient lookup sees uncommitted self mismatch and SQL rolls back`() {
    val db = SignalDatabase.writableDatabase
    val selfId = RecipientId.from(42)
    db.execSQL("INSERT INTO recipient (_id, aci) VALUES (42, 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa')")
    assertSame(expected, SignalDatabase.recipients.getRecord(selfId).aci)
    assertThrows(IllegalStateException::class.java) {
      db.beginTransaction()
      try {
        db.execSQL("UPDATE recipient SET aci = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb' WHERE _id = 42")
        assertTrue(db.inTransaction())
        assertSame(replacement, SignalDatabase.recipients.getRecord(selfId).aci)
        BackupRepository.verifyImportedSelf(ImportResult.Success(1, selfId), expected)
        db.setTransactionSuccessful()
      } finally {
        db.endTransaction()
      }
    }
    assertSame(expected, SignalDatabase.recipients.getRecord(selfId).aci)
    assertEquals(42L, selfId.toLong())
  }

  @Test fun `current account mismatch rejects an otherwise matching imported self row`() {
    val db = SignalDatabase.writableDatabase
    db.execSQL("INSERT INTO recipient (_id, aci) VALUES (42, 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa')")
    db.execSQL("INSERT INTO recipient (_id, aci) VALUES (43, 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb')")
    every { SignalStore.account.aci } returns replacement
    every { SignalStore.account.e164 } returns null
    every { SignalStore.account.isRegistered } returns true
    every { SignalStore.account.isUnauthorizedReceived } returns false
    every { SignalStore.releaseChannel.releaseChannelRecipientId } returns null
    val realCache = LiveRecipientCache(DirectExecutor())
    mockkStatic(AppDependencies::class)
    try {
      every { AppDependencies.recipientCache } returns realCache
      val initialId = RecipientId.from(42)
      assertThrows(IllegalStateException::class.java) {
        BackupRepository.verifyImportedSelf(ImportResult.Success(1, initialId), expected, validateCurrentSelf = true)
      }
      assertEquals(43L, Recipient.self().id.toLong())
      assertSame(replacement, SignalDatabase.recipients.getRecord(Recipient.self().id).aci)
    } finally {
      unmockkStatic(AppDependencies::class)
    }
  }


  @Test fun `stale current-self cache is rejected even if account ACI matches`() {
    val db = SignalDatabase.writableDatabase
    db.execSQL("INSERT INTO recipient (_id, aci) VALUES (42, 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa')")
    db.execSQL("INSERT INTO recipient (_id, aci) VALUES (43, 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb')")
    var account = replacement
    every { SignalStore.account.aci } answers { account }
    every { SignalStore.account.e164 } returns null
    every { SignalStore.account.isRegistered } returns true
    every { SignalStore.account.isUnauthorizedReceived } returns false
    every { SignalStore.releaseChannel.releaseChannelRecipientId } returns null
    val realCache = LiveRecipientCache(DirectExecutor())
    mockkStatic(AppDependencies::class)
    try {
      every { AppDependencies.recipientCache } returns realCache
      assertEquals(43L, Recipient.self().id.toLong())
      account = expected
      val error = assertThrows(IllegalStateException::class.java) {
        BackupRepository.verifyImportedSelf(ImportResult.Success(1, RecipientId.from(42)), expected, validateCurrentSelf = true)
      }
      assertEquals("Current self does not match the imported account", error.message)
    } finally {
      unmockkStatic(AppDependencies::class)
    }
  }

}
