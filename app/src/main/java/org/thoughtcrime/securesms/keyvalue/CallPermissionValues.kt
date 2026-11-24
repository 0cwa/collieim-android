/* SPDX-License-Identifier: AGPL-3.0-only */
package org.thoughtcrime.securesms.keyvalue

import org.signal.storage.keyvalue.KeyValueStore
import org.signal.storage.keyvalue.SignalStoreValues
import org.thoughtcrime.securesms.calls.permissions.CallPermission
import org.thoughtcrime.securesms.calls.permissions.CallPolicyCodec
import org.thoughtcrime.securesms.calls.permissions.CallPolicyState
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/** Not backed up, transferred, or synced. The marker is also outside Android's backup domains. */
class CallPermissionValues(store: KeyValueStore, noBackupDirectory: File) : SignalStoreValues(store) {
  companion object {
    const val KEY_PREFIX = "collie.local_call_permissions."
    internal const val RECORD = KEY_PREFIX + "v1"
  }

  private val installationFile = File(noBackupDirectory, "call-permission-installation")
  private val interruptedWrite = File(noBackupDirectory, "call-permission-write-pending")

  override fun onFirstEverAppLaunch() = Unit
  override val keysToIncludeInBackup: List<String> = emptyList()

  @Synchronized
  internal fun read(): CallPolicyState {
    val record = getString(RECORD, null)
    val state = record?.let(CallPolicyCodec::decode) ?: CallPolicyState()
    val anchor = installationFile.takeIf { it.isFile }?.readText()
    val copiedRecord = state.installation != null && state.installation != anchor
    val unboundRecord = record != null && state.installation == null
    val missingRecord = record == null && anchor != null
    if (!interruptedWrite.exists() && !copiedRecord && !unboundRecord && !missingRecord) return state
    // A kill or storage error during a security write cannot restore earlier, wider permissions.
    val restricted = state.copy(allCallingDisabled = true, directDefault = CallPermission.BLOCK, groupDefault = CallPermission.BLOCK, overrides = emptyMap())
    persist(restricted)
    return restricted
  }

  @Synchronized
  internal fun update(transform: (CallPolicyState) -> CallPolicyState): CallPolicyState {
    val next = transform(read())
    persist(next)
    return next
  }

  private fun persist(state: CallPolicyState) {
    check(interruptedWrite.parentFile!!.isDirectory || interruptedWrite.parentFile!!.mkdirs())
    val existing = installationFile.takeIf { it.isFile }?.readText()
    val anchor = existing?.takeIf { runCatching { UUID.fromString(it).toString() == it }.getOrDefault(false) } ?: UUID.randomUUID().toString().also {
      FileOutputStream(installationFile).use { stream -> stream.write(it.toByteArray(Charsets.UTF_8)); stream.fd.sync() }
    }
    val serialized = CallPolicyCodec.encode(state.copy(installation = anchor))
    FileOutputStream(interruptedWrite).use { it.write(1); it.fd.sync() }
    store.beginWrite().putString(RECORD, serialized).commitAndWaitForPersistence()
    check(interruptedWrite.delete()) { "Could not complete local call permission write" }
  }
}
