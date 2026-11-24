/* SPDX-License-Identifier: AGPL-3.0-only */
package org.thoughtcrime.securesms.calls.permissions

import android.os.SystemClock
import java.util.UUID

/**
 * Local arrival stamp for calling messages deferred behind ordinary message jobs. It is not a
 * permission grant: admission still checks the authenticated sender, context and current policy.
 * Restored work cannot renew its lifetime or acquire permissions from a subsequent generation.
 */
class CallMessageQueueReceipt internal constructor(
  val process: String,
  val generation: Long,
  val receivedAt: Long
) {
  fun queueDelayMillis(): Long? {
    val now = SystemClock.elapsedRealtime()
    if (process != processId || generation != CallPermissions.generation() || receivedAt < 0 || now < receivedAt) return null
    return (now - receivedAt).takeIf { it < CallSessionAuthorizer.MAX_MESSAGE_AGE_MS }
  }

  companion object {
    private val processId = UUID.randomUUID().toString()

    fun capture() = CallMessageQueueReceipt(processId, CallPermissions.generation(), SystemClock.elapsedRealtime())

    fun fromPersisted(process: String?, generation: Long?, receivedAt: Long?): CallMessageQueueReceipt? {
      if (process != processId || generation == null || generation <= 0 || receivedAt == null || receivedAt < 0) return null
      return CallMessageQueueReceipt(process, generation, receivedAt)
    }
  }
}
