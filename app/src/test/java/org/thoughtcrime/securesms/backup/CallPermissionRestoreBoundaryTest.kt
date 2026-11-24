/* SPDX-License-Identifier: AGPL-3.0-only */
package org.thoughtcrime.securesms.backup

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import io.mockk.verify
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.database.SqlUtil
import org.signal.core.models.ServiceId
import org.signal.core.util.logging.Log
import org.signal.storage.keyvalue.KeyValueDatabase
import org.thoughtcrime.securesms.calls.permissions.CallContext
import org.thoughtcrime.securesms.calls.permissions.CallPermission
import org.thoughtcrime.securesms.calls.permissions.CallPermissions
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.testutil.MockSignalStoreRule
import org.thoughtcrime.securesms.testutil.SystemOutLogger
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.UUID

/** Real encrypted v1 input and durable policy, with database transaction boundaries controlled. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class CallPermissionRestoreBoundaryTest {
  @get:Rule
  val store = MockSignalStoreRule()

  private val recipientDatabase = mockk<SQLiteDatabase>(relaxed = true)
  private val rawKeyValueDatabase = mockk<SQLiteDatabase>(relaxed = true)
  private val keyValueDatabase = mockk<KeyValueDatabase>()
  private val account = ServiceId.ACI.from(UUID.fromString("10000000-0000-0000-0000-000000000001"))
  private var recipientWriterHeld = false
  private var keyValueWriterHeld = false

  @Before
  fun setUp() {
    Log.initialize(SystemOutLogger())
    CallPermissions.setRevocationListener(Runnable {})
    mockkObject(KeyValueDatabase.Companion, SqlUtil)
    mockkStatic(KeyValueDatabase::class, AppDependencies::class, SqlUtil::class)
    every { SignalStore.account.aci } returns account
    justRun { SignalStore.blockUntilAllWritesFinished() }
    every { AppDependencies.application } returns ApplicationProvider.getApplicationContext()
    every { KeyValueDatabase.getInstance(any()) } returns keyValueDatabase
    every { keyValueDatabase.sqlCipherDatabase } returns rawKeyValueDatabase
    every { recipientDatabase.beginTransaction() } answers { recipientWriterHeld = true }
    every { recipientDatabase.endTransaction() } answers { recipientWriterHeld = false }
    every { rawKeyValueDatabase.beginTransaction() } answers {
      assertTrue("Recipient replacement excludes settings writes", recipientWriterHeld)
      // Reset must finish before this database writer could block synchronous policy persistence.
      assertReset()
      keyValueWriterHeld = true
    }
    every { rawKeyValueDatabase.endTransaction() } answers { keyValueWriterHeld = false }
    every { SqlUtil.getAllTriggers(recipientDatabase) } answers {
      assertTrue(recipientWriterHeld && keyValueWriterHeld)
      assertReset()
      emptyList()
    }
    every { SqlUtil.getAllTables(recipientDatabase) } returns emptyList()
    every { SqlUtil.getForeignKeyViolations(recipientDatabase, null) } returns emptyList()
    every { SqlUtil.tableExists(recipientDatabase, any()) } returns false
    store.callPermissions.update {
      it.copy(account = account.toString(), directDefault = CallPermission.BLOCK)
        .withOverride(CallContext.DIRECT, 73, CallPermission.ALLOW)
        .withOverride(CallContext.GROUP, 74, CallPermission.BLOCK)
    }
  }

  @After
  fun tearDown() {
    CallPermissions.endAllSessions()
    unmockkStatic(KeyValueDatabase::class, AppDependencies::class, SqlUtil::class)
    unmockkObject(KeyValueDatabase.Companion, SqlUtil)
  }

  @Test
  fun `v1 restore durably resets obsolete grants before replacing recipient tables`() {
    restore(endFrame = true)

    assertReset()
    assertFalse(recipientWriterHeld || keyValueWriterHeld)
    verify(exactly = 1) { recipientDatabase.setTransactionSuccessful() }
    verify(exactly = 1) { rawKeyValueDatabase.setTransactionSuccessful() }
  }

  @Test
  fun `v1 archive failure rolls back databases without restoring obsolete call grants`() {
    assertThrows(IOException::class.java) { restore(endFrame = false) }

    assertReset()
    assertFalse(recipientWriterHeld || keyValueWriterHeld)
    verify(exactly = 0) { recipientDatabase.setTransactionSuccessful() }
    verify(exactly = 0) { rawKeyValueDatabase.setTransactionSuccessful() }
  }

  private fun restore(endFrame: Boolean) {
    val output = ByteArrayOutputStream()
    val passphrase = "123456789012345678901234567890"
    val writer = BackupFrameOutputStream(output, passphrase)
    if (endFrame) writer.writeEnd()
    writer.close()
    FullBackupImporter.importFile(
      ApplicationProvider.getApplicationContext(),
      mockk(),
      recipientDatabase,
      output.toByteArray().inputStream(),
      passphrase,
      false
    )
  }

  private fun assertReset() {
    val policy = store.callPermissions.read()
    assertTrue(policy.overrides.isEmpty())
    assertEquals(CallPermission.BLOCK, policy.directDefault)
    assertEquals(CallPermission.BLOCK, policy.groupDefault)
    assertFalse(policy.allCallingDisabled)
  }
}
