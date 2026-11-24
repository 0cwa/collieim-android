/* SPDX-License-Identifier: AGPL-3.0-only */
package org.thoughtcrime.securesms.calls.permissions

import android.app.Application
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import okio.ByteString.Companion.toByteString
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.archive.proto.CallLink
import org.signal.core.models.recipients.RecipientId
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.backup.v2.importer.CallLinkArchiveImporter
import org.thoughtcrime.securesms.database.CallLinkTable
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.storage.CallLinkRecordProcessor
import org.thoughtcrime.securesms.testutil.EmptyLogger
import org.whispersystems.signalservice.api.storage.SignalCallLinkRecord
import org.whispersystems.signalservice.api.storage.StorageId
import org.whispersystems.signalservice.internal.storage.protos.CallLinkRecord

/** Remote metadata remains available while calling is off, without native key parsing. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [35])
class CallLinkMetadataTest {
  private val links = mockk<CallLinkTable>()
  private val keyBytes = ByteArray(16) { (it + 1).toByte() }
  private val adminBytes = ByteArray(16) { 23 }
  private val recipientId = RecipientId.from(8)

  @Before
  fun setUp() {
    Log.initialize(EmptyLogger())
    mockkObject(SignalDatabase, CallPermissions)
    mockkStatic(AppDependencies::class, CallPermissions::class)
    every { SignalDatabase.callLinks } returns links
    every { CallPermissions.isCallingAllowed() } returns false
    every { AppDependencies.signalCallManager } throws AssertionError("Metadata must not obtain a native call manager")
  }

  @After
  fun tearDown() = unmockkAll()

  @Test
  fun `archive restoration preserves call link metadata while calling is disabled`() {
    val inserted = slot<CallLinkTable.CallLink>()
    every { links.insertCallLink(capture(inserted), any(), any()) } returns recipientId
    val archived = CallLink(
      rootKey = keyBytes.toByteString(),
      adminKey = adminBytes.toByteString(),
      name = "Weekend plans",
      restrictions = CallLink.Restrictions.ADMIN_APPROVAL,
      expirationMs = 100_000L
    )

    assertEquals(recipientId, CallLinkArchiveImporter.import(archived))
    assertArrayEquals(keyBytes, inserted.captured.credentials!!.linkKeyBytes)
    assertArrayEquals(adminBytes, inserted.captured.credentials!!.adminPassBytes)
    assertEquals("Weekend plans", inserted.captured.state.name)
    assertEquals(ManagedCallLinkRootKey.fromBytes(keyBytes)!!.roomId, inserted.captured.roomId)
    verify(exactly = 0) { AppDependencies.signalCallManager }
  }

  @Test
  fun `malformed archived link never reaches storage or native key parsing`() {
    assertNull(CallLinkArchiveImporter.import(CallLink(rootKey = byteArrayOf(1, 2).toByteString())))
    verify(exactly = 0) { links.insertCallLink(any(), any(), any()) }
    verify(exactly = 0) { AppDependencies.signalCallManager }
  }

  @Test
  fun `storage sync inserts the validated key and original metadata while calling is disabled`() {
    val key = slot<ManagedCallLinkRootKey>()
    val storageId = StorageId.forCallLink(ByteArray(16) { 7 })
    every { links.insertOrUpdateCallLinkByRootKey(capture(key), any(), any(), any()) } returns Unit
    val record = SignalCallLinkRecord(
      storageId,
      CallLinkRecord(rootKey = keyBytes.toByteString(), adminPasskey = adminBytes.toByteString())
    )

    CallLinkRecordProcessor().insertLocal(record)

    assertArrayEquals(keyBytes, key.captured.keyBytes)
    verify(exactly = 1) { links.insertOrUpdateCallLinkByRootKey(any(), adminBytes, 0L, storageId) }
    verify(exactly = 0) { AppDependencies.signalCallManager }
  }
}
