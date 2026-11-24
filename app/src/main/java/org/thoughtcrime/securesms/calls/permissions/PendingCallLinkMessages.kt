/* SPDX-License-Identifier: AGPL-3.0-only */
package org.thoughtcrime.securesms.calls.permissions

import java.util.UUID

/** Bounded managed holding area for media keys that precede the authenticated SFU roster. */
internal class PendingCallLinkMessages(private val clock: () -> Long) {
  companion object {
    const val MAX_MESSAGES = 32
    const val MAX_BYTES = 256 * 1024
    const val MAX_PER_SENDER = 4
    const val TTL_MS = 30_000L
  }

  data class Pending(
    val authorization: CallAuthorization,
    val sender: UUID,
    val opaque: ByteArray,
    val originalAgeMillis: Long,
    val receivedAt: Long,
    val expiresAt: Long
  ) {
    val key: CallSessionKey
      get() = CallSessionKey(authorization.generation, authorization.session, authorization.recipient)

    fun ageAt(now: Long): Long = originalAgeMillis + (now - receivedAt).coerceAtLeast(0)
  }

  private var key: CallSessionKey? = null
  private val pending = ArrayList<Pending>()

  @Synchronized
  fun offer(authorization: CallAuthorization, sender: UUID, opaque: ByteArray, ageMillis: Long): Boolean {
    if (authorization.context != CallContext.CALL_LINK || authorization.linkDemuxId == null || authorization.linkMemberVersion != null ||
      opaque.isEmpty() || opaque.size > RingRtcOpaqueClassifier.MAX_MESSAGE_BYTES || ageMillis !in 0..CallSessionAuthorizer.MAX_MESSAGE_AGE_MS
    ) return false
    val now = clock()
    val expiry = minOf(authorization.expiresAt, now + TTL_MS, now + CallSessionAuthorizer.MAX_MESSAGE_AGE_MS - ageMillis)
    return put(Pending(authorization, sender, opaque.copyOf(), ageMillis, now, expiry))
  }

  /** A failed promotion may put the same frame back without extending its original expiry. */
  @Synchronized
  fun put(message: Pending): Boolean {
    prune()
    if (clock() >= message.expiresAt) return false
    val currentKey = key
    if (currentKey != message.key) {
      if (currentKey != null && (message.key.generation < currentKey.generation ||
          (message.key.generation == currentKey.generation && message.key.serial <= currentKey.serial))
      ) return false
      pending.clear()
      key = message.key
    }
    if (pending.any { it.authorization.sender == message.authorization.sender && it.authorization.sourceDevice == message.authorization.sourceDevice && it.opaque.contentEquals(message.opaque) }) return false
    if (pending.size >= MAX_MESSAGES || pending.sumOf { it.opaque.size } + message.opaque.size > MAX_BYTES ||
      pending.count { it.authorization.sender == message.authorization.sender } >= MAX_PER_SENDER
    ) return false
    pending += message
    return true
  }

  /** Remove before consulting policy/recipient storage, so the queue lock never encloses DB work. */
  @Synchronized
  fun take(currentKey: CallSessionKey): List<Pending> {
    prune()
    if (key != currentKey) return emptyList()
    return pending.toList().also { pending.clear() }
  }

  /** Claim a frame only if a concurrent roster callback has not already taken ownership. */
  @Synchronized
  fun remove(authorization: CallAuthorization): Boolean = pending.removeAll { it.authorization === authorization }

  @Synchronized
  fun clear() {
    pending.clear()
    key = null
  }

  private fun prune() {
    val now = clock()
    pending.removeAll { now >= it.expiresAt }
  }
}
