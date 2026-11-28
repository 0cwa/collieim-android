/*
 * Copyright 2026 Collie contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package org.thoughtcrime.securesms.keyvalue

import okhttp3.HttpUrl
import org.signal.network.util.JsonUtil
import org.signal.storage.keyvalue.KeyValueStore
import org.signal.storage.keyvalue.SignalStoreValues
import org.thoughtcrime.securesms.backup.v2.barkup.BarkupCredentials
import org.thoughtcrime.securesms.backup.v2.barkup.barkupApi
import org.thoughtcrime.securesms.backup.v2.barkup.barkupJson
import java.io.IOException
import java.util.UUID

/** Local opt-in and device credentials live in Signal's encrypted store and never leave it in a backup. */
class CollieBarkupValues(store: KeyValueStore) : SignalStoreValues(store) {
  private companion object {
    const val ENROLLMENT = "collie.barkup.enrollment"
    const val STATUS = "collie.barkup.status"
    const val LAST_SUCCESS = "collie.barkup.last_success"
  }

  override fun onFirstEverAppLaunch() = Unit
  override val keysToIncludeInBackup: List<String> = emptyList()

  private var pairingGeneration = 0L

  internal enum class Status { READY, WORKING, FAILED }

  internal class Enrollment(val id: String, val account: String, val api: HttpUrl, val token: String)

  internal class PairingAttempt(val account: String, val generation: Long)

  internal data class Summary(val host: String, val status: Status, val lastSuccess: Long)

  @Synchronized
  internal fun enrollment(account: String): Enrollment? {
    val record = getString(ENROLLMENT, null) ?: return null
    return try {
      val json = barkupJson(record)
      if (json.path("account").textValue() != account) return null
      val id = json.path("id").textValue() ?: return null
      val api = barkupApi(json.path("api").textValue() ?: return null)
      val token = json.path("token").textValue() ?: return null
      Enrollment(id, account, api, token)
    } catch (_: IOException) {
      null
    }
  }

  @Synchronized
  internal fun beginPairing(account: String): PairingAttempt = PairingAttempt(account, ++pairingGeneration)

  @Synchronized
  internal fun enable(attempt: PairingAttempt, api: HttpUrl, credentials: BarkupCredentials, currentAccount: () -> String?): Boolean {
    if (attempt.generation != pairingGeneration || currentAccount() != attempt.account) return false
    pairingGeneration++
    val record = JsonUtil.toJson(mapOf("account" to attempt.account, "id" to UUID.randomUUID().toString(), "api" to api.toString(), "token" to credentials.token, "device_id" to credentials.deviceId))
    check(record.isNotEmpty())
    store.beginWrite()
      .putString(ENROLLMENT, record)
      .remove(STATUS)
      .remove(LAST_SUCCESS)
      .commitAndWaitForPersistence()
    return true
  }

  @Synchronized
  internal fun summary(account: String): Summary? {
    val enrollment = enrollment(account) ?: return null
    val status = Status.entries.getOrElse(getInteger(STATUS, 0)) { Status.READY }
    return Summary(enrollment.api.host, status, getLong(LAST_SUCCESS, 0))
  }

  @Synchronized
  internal fun setStatus(enrollment: Enrollment, status: Status, completedAt: Long? = null) {
    if (enrollment(enrollment.account)?.id != enrollment.id) return
    val write = store.beginWrite().putInteger(STATUS, status.ordinal)
    if (completedAt != null) write.putLong(LAST_SUCCESS, completedAt)
    write.apply()
  }

  @Synchronized
  internal fun disable(onRevoked: (String) -> Unit = {}) {
    pairingGeneration++
    val record = getString(ENROLLMENT, null) ?: return
    val enrollmentId = try {
      barkupJson(record).path("id").textValue()
    } catch (_: IOException) {
      null
    }
    store.beginWrite().remove(ENROLLMENT).remove(STATUS).remove(LAST_SUCCESS).commitAndWaitForPersistence()
    enrollmentId?.let(onRevoked)
  }
}
