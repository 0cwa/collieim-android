/*
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.backup.v2

import android.app.Application
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import org.signal.archive.proto.BackupInfo
import org.signal.archive.stream.PlainTextBackupWriter
import org.signal.core.util.logging.Log
import org.signal.core.util.readToSingleInt
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.testutil.MockSignalStoreRule
import org.thoughtcrime.securesms.testutil.SignalDatabaseRule
import org.thoughtcrime.securesms.testutil.SystemOutLogger
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE)
class BackupImportTransactionOwnershipTest {
  private val store = MockSignalStoreRule()
  private val database = SignalDatabaseRule()

  @get:Rule
  val rules: RuleChain = RuleChain.outerRule(store).around(database)

  @Before
  fun setUp() {
    Log.initialize(SystemOutLogger())
  }

  @Test
  fun `foreign key setup failure does not end caller transaction`() {
    val real = SignalDatabase.writableDatabase
    val wrapped = spyk(real)
    val failure = UnsupportedOperationException("synthetic foreign key setup failure")
    every { SignalDatabase.writableDatabase } returns wrapped
    every { wrapped.setForeignKeyConstraintsEnabled(false) } throws failure

    real.beginTransaction()
    try {
      assertSame(failure, assertThrows(UnsupportedOperationException::class.java) { importHeader() })
      assertTrue(real.inTransaction())
      verify(exactly = 0) { wrapped.endTransaction() }
      verify(exactly = 0) { wrapped.setForeignKeyConstraintsEnabled(true) }
    } finally {
      if (real.inTransaction()) real.endTransaction()
    }
  }

  @Test
  fun `failed transaction start restores foreign keys without ending a transaction`() {
    val real = SignalDatabase.writableDatabase
    val wrapped = spyk(real)
    val failure = UnsupportedOperationException("synthetic transaction start failure")
    every { SignalDatabase.writableDatabase } returns wrapped
    every { wrapped.beginTransaction() } throws failure

    assertSame(failure, assertThrows(UnsupportedOperationException::class.java) { importHeader() })
    assertFalse(real.inTransaction())
    verify(exactly = 0) { wrapped.endTransaction() }
    verify(exactly = 1) { wrapped.setForeignKeyConstraintsEnabled(true) }
    assertEquals(1, real.rawQuery("PRAGMA foreign_keys", null).readToSingleInt())
  }

  private fun importHeader() {
    val output = ByteArrayOutputStream()
    PlainTextBackupWriter(output).use {
      it.write(BackupInfo(version = 1, backupTimeMs = 1000, mediaRootBackupKey = ByteArray(32).toByteString()))
    }
    val backup = output.toByteArray()
    BackupRepository.importPlaintextTest(
      length = backup.size.toLong(),
      inputStreamFactory = { backup.inputStream() },
      selfData = BackupRepository.SelfData(mockk(), null, null, mockk())
    )
  }
}
