/*
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.groups.v2.admission

import androidx.annotation.WorkerThread
import org.json.JSONArray
import org.json.JSONObject
import org.signal.core.models.ServiceId.ACI
import org.signal.core.util.logging.Log
import org.signal.storage.keyvalue.KeyValueStore
import org.signal.storage.keyvalue.SignalStoreValues

/**
 * One account-bound document in the encrypted store. Settings, episode decisions and operation intents are
 * written together, and are only visible after the disk acknowledges the write. No Signal sync record carries it.
 */
class GroupAdmissionValues(store: KeyValueStore) : SignalStoreValues(store) {
  companion object {
    internal const val KEY = "collie.group_admission.v1"
    private val TAG = Log.tag(GroupAdmissionValues::class.java)
  }

  private var cachedRaw: String? = null
  private var cachedState: AdmissionAccountState? = null

  override fun onFirstEverAppLaunch() = Unit

  override val keysToIncludeInBackup: List<String> = listOf(KEY)

  @Synchronized
  internal fun read(account: String): AdmissionAccountState {
    val raw = getString(KEY, null) ?: return AdmissionAccountState(account)
    if (raw == cachedRaw && cachedState?.account == account) return cachedState!!

    val parsed = try {
      decode(raw, account)
    } catch (e: Exception) {
      // A corrupt/unsupported document must not turn a restrictive choice into broader permission.
      Log.w(TAG, "Unable to read group admission state; requiring local confirmation.", e)
      AdmissionAccountState(account, GroupAdmissionSettings(GroupAdmissionMode.ASK_EVERY_TIME))
    }
    cachedRaw = raw
    cachedState = parsed
    return parsed
  }

  @WorkerThread
  @Synchronized
  internal fun update(account: String, transform: (AdmissionAccountState) -> AdmissionAccountState): AdmissionAccountState {
    val before = read(account)
    val after = transform(before)
    require(after.account == account)
    if (before == after) return before

    val raw = encode(after)
    store.beginWrite().putString(KEY, raw).commitAndWaitForPersistence()
    cachedRaw = raw
    cachedState = after
    return after
  }

  /**
   * The group tables and key-value document are exported at different instants. Restored permissions cannot
   * authorize the restored membership without review, even if a document write had completed before export.
   */
  @WorkerThread
  @Synchronized
  fun onPostBackupRestore() {
    cachedRaw = null
    cachedState = null
    val raw = getString(KEY, null) ?: return
    val account = try {
      JSONObject(raw).getString("account")
    } catch (_: Exception) {
      return
    }
    update(account) { state ->
      state.copy(
        intents = emptyMap(),
        records = state.records.mapValues { (_, record) ->
          if (record.status == AdmissionStatus.SIGNAL_DEFAULT && record.pendingWrite == null) record else record.copy(status = AdmissionStatus.HELD, pendingWrite = null)
        }
      )
    }
  }

  private fun encode(state: AdmissionAccountState): String {
    val records = JSONObject()
    state.records.forEach { (group, record) ->
      validateRecord(record)
      records.put(group, JSONObject().apply {
        put("kind", record.membership.kind.name)
        put("episode", record.membership.episode)
        put("revision", record.membership.revision)
        put("joinedAt", record.membership.joinedAtRevision)
        put("status", record.status.name)
        put("generation", record.generation)
        put("pending", record.pendingWrite)
      })
    }
    val intents = JSONObject()
    state.intents.forEach { (group, intent) ->
      intents.put(group, JSONObject().apply {
        put("id", intent.id)
        put("kind", intent.kind.name)
        put("origin", intent.originEpisode)
        put("revision", intent.expectedRevision)
      })
    }
    return JSONObject().apply {
      put("version", 1)
      put("account", state.account)
      put("mode", state.settings.mode.name)
      put("people", JSONArray(state.settings.allowedPeople.sorted()))
      put("records", records)
      put("intents", intents)
    }.toString()
  }

  private fun decode(raw: String, account: String): AdmissionAccountState {
    val json = JSONObject(raw)
    require(json.getInt("version") == 1)
    val storedAccount = ACI.parseOrThrow(json.getString("account"))
    require(storedAccount.isValid)
    if (storedAccount.toString() != account) return AdmissionAccountState(account)
    val people = json.getJSONArray("people")
    val records = json.getJSONObject("records")
    val intents = json.getJSONObject("intents")
    return AdmissionAccountState(
      account = account,
      settings = GroupAdmissionSettings(
        mode = GroupAdmissionMode.valueOf(json.getString("mode")),
        allowedPeople = (0 until people.length()).map { ACI.parseOrThrow(people.getString(it)).also { require(it.isValid) }.toString() }.toSet()
      ),
      records = records.keys().asSequence().associateWith { group ->
        try {
          decodeRecord(records.getJSONObject(group))
        } catch (e: Exception) {
          Log.w(TAG, "Unable to read a group admission record; requiring local confirmation.", e)
          AdmissionRecord(GroupAdmissionEvidence.unknownMembership(-1), AdmissionStatus.HELD)
        }
      },
      intents = intents.keys().asSequence().mapNotNull { group ->
        try {
          group to decodeIntent(intents.getJSONObject(group))
        } catch (e: Exception) {
          Log.w(TAG, "Unable to read a group admission intent; discarding it.", e)
          null
        }
      }.toMap()
    )
  }

  private fun decodeRecord(entry: JSONObject): AdmissionRecord =
    AdmissionRecord(
      membership = AdmissionMembership(
        kind = MembershipKind.valueOf(entry.getString("kind")),
        episode = entry.getString("episode"),
        revision = entry.getInt("revision"),
        joinedAtRevision = entry.optionalInt("joinedAt")
      ),
      status = AdmissionStatus.valueOf(entry.getString("status")),
      generation = entry.getString("generation"),
      pendingWrite = entry.optionalString("pending")
    ).also(::validateRecord)

  private fun decodeIntent(entry: JSONObject): AdmissionIntent =
    AdmissionIntent(
      id = entry.getString("id"),
      kind = ConsentKind.valueOf(entry.getString("kind")),
      originEpisode = entry.optionalString("origin"),
      expectedRevision = entry.optionalInt("revision")
    ).also {
      require(it.id.isNotBlank())
      require(it.expectedRevision == null || it.expectedRevision >= 0)
      require(it.kind != ConsentKind.ACCEPT || !it.originEpisode.isNullOrBlank())
    }

  private fun validateRecord(record: AdmissionRecord) {
    require(record.generation.isNotBlank())
    require(record.membership.revision >= -1)
    require(record.pendingWrite == null || record.pendingWrite.isNotBlank())
    val membership = record.membership
    when (membership.kind) {
      MembershipKind.FULL -> {
        require(membership.joinedAtRevision != null && membership.joinedAtRevision >= 0)
        require(membership.revision >= membership.joinedAtRevision)
        require(membership.episode == "member:${membership.joinedAtRevision}")
      }
      MembershipKind.PENDING -> {
        require(membership.joinedAtRevision == null)
        val parts = membership.episode.split(':')
        require(parts.size == 5 && parts[0] == "invite")
        require(parts[1].isNotEmpty() && parts[1].all { it in "0123456789abcdef" })
        require(parts[2].all { it in "0123456789abcdef" } && parts[4].all { it in "0123456789abcdef" })
        require(parts[3].toLong() >= 0)
      }
      MembershipKind.REQUESTING -> {
        require(membership.joinedAtRevision == null && membership.episode.startsWith("request:"))
        require(membership.episode.removePrefix("request:").toLong() >= 0)
      }
      MembershipKind.ABSENT -> require(membership.episode == "absent" && membership.joinedAtRevision == null)
      MembershipKind.UNKNOWN -> require(membership.episode == "unknown" && membership.joinedAtRevision == null && record.status == AdmissionStatus.HELD)
    }
    when (record.status) {
      AdmissionStatus.AUTOMATIC, AdmissionStatus.EXISTING_GROUP -> require(membership.kind == MembershipKind.FULL)
      AdmissionStatus.LOCAL_CONSENT -> require(membership.kind == MembershipKind.FULL || membership.kind == MembershipKind.REQUESTING)
      AdmissionStatus.SIGNAL_DEFAULT, AdmissionStatus.HELD -> Unit
    }
  }

  private fun JSONObject.optionalString(key: String): String? = if (has(key) && !isNull(key)) getString(key) else null
  private fun JSONObject.optionalInt(key: String): Int? = if (has(key) && !isNull(key)) getInt(key) else null
}
