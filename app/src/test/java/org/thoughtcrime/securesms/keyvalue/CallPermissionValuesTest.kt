/*
 * Copyright 2026 Collie contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.keyvalue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.signal.storage.keyvalue.KeyValueDataSet
import org.signal.storage.keyvalue.KeyValuePersistenceException
import org.signal.storage.keyvalue.KeyValuePersistentStorage
import org.signal.storage.keyvalue.KeyValueStore
import org.thoughtcrime.securesms.calls.permissions.CallContext
import org.thoughtcrime.securesms.calls.permissions.CallPermission
import org.thoughtcrime.securesms.calls.permissions.CallPolicyCodec
import org.thoughtcrime.securesms.calls.permissions.CallPolicyState
import org.thoughtcrime.securesms.testutil.SnapshotKeyValuePersistentStorage
import java.io.File
import java.io.IOException
import java.util.UUID

/** Exercises the production KeyValueStore and filesystem journal, with disk data separate from the cache. */
class CallPermissionValuesTest {
  @get:Rule
  val temporary = TemporaryFolder()

  private val backing = SnapshotKeyValuePersistentStorage()
  private val account = UUID.randomUUID().toString()

  @Test
  fun `a genuine new installation preserves ordinary calling without a policy write`() {
    val directory = temporary.newFolder()
    val values = open(directory)

    val state = values.read()

    assertFalse(state.allCallingDisabled)
    assertEquals(CallPermission.ALLOW, state.directDefault)
    assertEquals(CallPermission.ALLOW, state.groupDefault)
    assertEquals(CallPermission.INHERIT, state.override(CallContext.DIRECT, 12L))
    assertTrue(backing.getDataSet().values.isEmpty())
    assertTrue(directory.listFiles()!!.isEmpty())
  }

  @Test
  fun `calling policy is excluded from backup and device-transfer key lists`() {
    val values = open(temporary.newFolder())
    values.update { it.copy(account = account, allCallingDisabled = true) }

    assertTrue(values.keysToIncludeInBackup.isEmpty())
    assertTrue(backing.getDataSet().containsKey(CallPermissionValues.RECORD))
    assertTrue(backing.lastWriteKeys.all { it.startsWith(CallPermissionValues.KEY_PREFIX) })
  }

  @Test
  fun `durable restrictions and genuine inheritance survive a new store and cache`() {
    val directory = temporary.newFolder()
    open(directory).update {
      it.copy(account = account, directDefault = CallPermission.BLOCK)
        .withOverride(CallContext.DIRECT, 12L, CallPermission.ALLOW)
        .withOverride(CallContext.GROUP, 24L, CallPermission.BLOCK)
    }

    val restarted = open(directory).read()
    assertEquals(account, restarted.account)
    assertNotNull(restarted.installation)
    assertFalse(restarted.allCallingDisabled)
    assertEquals(CallPermission.BLOCK, restarted.directDefault)
    assertEquals(CallPermission.ALLOW, restarted.groupDefault)
    assertTrue(restarted.allowsIncoming(CallContext.DIRECT, 12L))
    assertFalse(restarted.allowsIncoming(CallContext.DIRECT, 13L))
    assertEquals(CallPermission.INHERIT, restarted.override(CallContext.DIRECT, 13L))
    assertFalse(restarted.allowsIncoming(CallContext.GROUP, 24L))
    assertFalse(marker(directory).exists())
    assertEquals(restarted, open(directory).read())
  }

  @Test
  fun `returning to device default removes the grant rather than copying ALLOW`() {
    val directory = temporary.newFolder()
    val values = open(directory)
    values.update { it.withOverride(CallContext.DIRECT, 12L, CallPermission.ALLOW) }
    values.update { it.withOverride(CallContext.DIRECT, 12L, CallPermission.INHERIT) }
    values.update { it.copy(directDefault = CallPermission.BLOCK) }

    val restarted = open(directory).read()
    assertEquals(CallPermission.INHERIT, restarted.override(CallContext.DIRECT, 12L))
    assertFalse(restarted.allowsIncoming(CallContext.DIRECT, 12L))
    assertFalse(restarted.overrides.containsKey("DIRECT:12"))
  }

  @Test
  fun `a copied policy database without its installation anchor cannot import grants`() {
    val originalDirectory = temporary.newFolder()
    open(originalDirectory).update { it.copy(account = account).withOverride(CallContext.DIRECT, 12L, CallPermission.ALLOW) }
    val original = open(originalDirectory).read()
    val copiedBacking = SnapshotKeyValuePersistentStorage(backing.getDataSet())
    val restoredDirectory = temporary.newFolder()

    assertRestricted(open(restoredDirectory, copiedBacking).read())
    val restarted = open(restoredDirectory, copiedBacking).read()
    assertRestricted(restarted)
    assertNotEquals(original.installation, restarted.installation)
    assertTrue("The original installation remains unchanged", open(originalDirectory).read().allowsIncoming(CallContext.DIRECT, 12L))
  }

  @Test
  fun `a mismatched or damaged anchor cannot preserve a restored exception`() {
    for (anchor in listOf(UUID.randomUUID().toString(), "damaged anchor")) {
      val directory = temporary.newFolder()
      val storage = SnapshotKeyValuePersistentStorage()
      open(directory, storage).update { it.withOverride(CallContext.GROUP, 24L, CallPermission.ALLOW) }
      File(directory, "call-permission-installation").writeText(anchor)

      assertRestricted(open(directory, storage).read())
      assertRestricted(open(directory, storage).read())
    }
  }

  @Test
  fun `a legacy unbound record is restricted rather than treated as a fresh installation`() {
    val directory = temporary.newFolder()
    val data = KeyValueDataSet().apply {
      putString(CallPermissionValues.RECORD, CallPolicyCodec.encode(CallPolicyState(account = account)))
    }
    val storage = SnapshotKeyValuePersistentStorage(data)

    assertRestricted(open(directory, storage).read())
    assertRestricted(open(directory, storage).read())
  }

  @Test
  fun `losing the record after this installation configured permissions cannot widen them`() {
    val directory = temporary.newFolder()
    open(directory).update { it.copy(allCallingDisabled = true) }
    backing.removePersistedKey(CallPermissionValues.RECORD)

    assertRestricted(open(directory).read())
    assertRestricted(open(directory).read())
  }

  @Test
  fun `failed restriction write leaves a journal that blocks calls after restart`() {
    val directory = temporary.newFolder()
    val values = open(directory)
    values.update { it.copy(account = account) }
    backing.nextWriteFailure = IllegalStateException("disk full")

    assertThrows(KeyValuePersistenceException::class.java) {
      values.update { it.withOverride(CallContext.DIRECT, 12L, CallPermission.BLOCK) }
    }
    assertTrue(marker(directory).isFile)
    assertEquals(CallPermission.ALLOW, CallPolicyCodec.decode(backing.getDataSet().getNonNullString(CallPermissionValues.RECORD)).directDefault)

    assertRestricted(open(directory).read())
    assertFalse(marker(directory).exists())
    assertRestricted(open(directory).read())
  }

  @Test
  fun `incomplete relaxation also recovers restricted even if the database write reached disk`() {
    val directory = temporary.newFolder()
    val values = open(directory)
    values.update { it.copy(allCallingDisabled = true) }
    values.update { it.copy(allCallingDisabled = false).withOverride(CallContext.DIRECT, 12L, CallPermission.ALLOW) }
    // The process stopped after persistence but before clearing its journal.
    marker(directory).writeText("pending")

    assertRestricted(open(directory).read())
    assertRestricted(open(directory).read())
  }

  @Test
  fun `failed recovery remains unavailable and keeps the journal for another restart`() {
    val directory = temporary.newFolder()
    open(directory).update { it.copy(account = account) }
    marker(directory).writeText("pending")
    backing.nextWriteFailure = IllegalStateException("disk still full")

    assertThrows(KeyValuePersistenceException::class.java) { open(directory).read() }
    assertTrue(marker(directory).exists())
    assertRestricted(open(directory).read())
  }

  @Test
  fun `malformed or unknown policy data never falls back to ordinary defaults`() {
    for (malformed in listOf("", "2\n-\n-\n0\nALLOW\nALLOW", "1\n-\n-\n0\nINHERIT\nALLOW", "broken record")) {
      val storage = SnapshotKeyValuePersistentStorage(KeyValueDataSet().apply { putString(CallPermissionValues.RECORD, malformed) })
      val values = open(temporary.newFolder(), storage)

      assertThrows(RuntimeException::class.java) { values.read() }
      assertThrows(RuntimeException::class.java) { values.update { it.copy(allCallingDisabled = false) } }
      assertEquals(malformed, storage.getDataSet().getNonNullString(CallPermissionValues.RECORD))
    }
  }

  @Test
  fun `unavailable backing storage cannot masquerade as an empty policy`() {
    val unavailable = object : KeyValuePersistentStorage {
      override fun writeDataSet(dataSet: KeyValueDataSet, removes: Collection<String>) = error("unavailable")
      override fun getDataSet(): KeyValueDataSet = error("unavailable")
    }

    assertThrows(IllegalStateException::class.java) { open(temporary.newFolder(), unavailable).read() }
  }

  @Test
  fun `unwritable installation recovery cannot return an allowed policy`() {
    val directory = temporary.newFolder()
    open(directory).update { it.copy(account = account) }
    val installation = File(directory, "call-permission-installation")
    assertTrue(installation.delete())
    assertTrue(installation.mkdir())

    assertThrows(IOException::class.java) { open(directory).read() }
  }

  private fun open(directory: File, storage: KeyValuePersistentStorage = backing): CallPermissionValues =
    CallPermissionValues(KeyValueStore(storage) { it.run() }, directory)

  private fun marker(directory: File): File = File(directory, "call-permission-write-pending")

  private fun assertRestricted(state: CallPolicyState) {
    assertTrue(state.allCallingDisabled)
    assertEquals(CallPermission.BLOCK, state.directDefault)
    assertEquals(CallPermission.BLOCK, state.groupDefault)
    assertTrue(state.overrides.isEmpty())
    assertFalse(state.allowsIncoming(CallContext.DIRECT, 12L))
    assertFalse(state.allowsIncoming(CallContext.GROUP, 24L))
  }
}
