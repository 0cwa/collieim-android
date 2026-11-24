/* SPDX-License-Identifier: AGPL-3.0-only */
package org.thoughtcrime.securesms.keyvalue

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.signal.storage.keyvalue.KeyValueStore
import org.signal.storage.keyvalue.SignalStoreValues
import org.thoughtcrime.securesms.BuildConfig
import org.thoughtcrime.securesms.calls.permissions.CallPermission
import org.thoughtcrime.securesms.calls.permissions.CallPolicyCodec
import org.thoughtcrime.securesms.calls.permissions.CallPolicyState
import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.UUID

/** Not backed up, transferred, or synced. The marker is also outside Android's backup domains. */
class CallPermissionValues(store: KeyValueStore, noBackupDirectory: File) : SignalStoreValues(store) {
  companion object {
    const val KEY_PREFIX = "collie.local_call_permissions."
    internal const val RECORD = KEY_PREFIX + "v1"
    internal const val ON_DEMAND = KEY_PREFIX + "on_demand"

    /** Use the existing Beta product and development builds; Stable has no experiment switch. */
    @JvmStatic
    fun isOnDemandCallingAvailable(): Boolean = BuildConfig.BUILD_TYPE == "beta" || BuildConfig.DEBUG
  }

  private var cachedRecord: String? = null
  private var cachedState: CallPolicyState? = null
  private val startupChanged = MutableStateFlow(0L)

  val onDemandChanges: StateFlow<Long> = startupChanged

  private val installationFile = File(noBackupDirectory, "call-permission-installation")
  private val interruptedWrite = File(noBackupDirectory, "call-permission-write-pending")

  override fun onFirstEverAppLaunch() = Unit
  override val keysToIncludeInBackup: List<String> = emptyList()

  /** A startup preference, not a call grant. Reading it never acquires the calling engine. */
  @Synchronized
  fun isOnDemandCallingEnabled(): Boolean = isOnDemandCallingAvailable() && getBoolean(ON_DEMAND, false)

  /** Publish only a durable choice, without changing the policy generation or active sessions. */
  @Synchronized
  fun setOnDemandCallingEnabled(enabled: Boolean) {
    check(isOnDemandCallingAvailable()) { "On-demand calling is available in TrailBlazer Beta" }
    if (isOnDemandCallingEnabled() == enabled) return
    store.beginWrite().putBoolean(ON_DEMAND, enabled).commitAndWaitForPersistence()
    startupChanged.update { it + 1 }
  }

  @Synchronized
  internal fun read(): CallPolicyState {
    val record = getString(RECORD, null)
    check(record != null || !store.containsKey(RECORD)) { "Null call permission record" }
    val state = if (cachedState != null && record == cachedRecord) cachedState!! else {
      (record?.let(CallPolicyCodec::decode) ?: CallPolicyState()).also { cachedRecord = record; cachedState = it }
    }
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

  private fun syncDirectory() {
    FileChannel.open(interruptedWrite.parentFile!!.toPath(), StandardOpenOption.READ).use { it.force(true) }
  }

  private fun persist(state: CallPolicyState) {
    check(interruptedWrite.parentFile!!.isDirectory || interruptedWrite.parentFile!!.mkdirs())
    val existing = installationFile.takeIf { it.isFile }?.readText()
    val anchor = existing?.takeIf { runCatching { UUID.fromString(it).toString() == it }.getOrDefault(false) } ?: UUID.randomUUID().toString().also {
      FileOutputStream(installationFile).use { stream -> stream.write(it.toByteArray(Charsets.UTF_8)); stream.fd.sync() }
    }
    val serialized = CallPolicyCodec.encode(state.copy(installation = anchor))
    FileOutputStream(interruptedWrite).use { it.write(1); it.fd.sync() }
    syncDirectory()
    store.beginWrite().putString(RECORD, serialized).commitAndWaitForPersistence()
    check(interruptedWrite.delete()) { "Could not complete local call permission write" }
    syncDirectory()
  }
}
