/*
 * Copyright 2026 Collie contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package org.thoughtcrime.securesms.calls.permissions

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * Managed equivalent of RingRTC's era-to-ring-ID conversion. Call history and peeks can retain
 * remote metadata without passing it through JNI. The regular CallId(long) wrapper is managed.
 *
 * Pinned source: signalapp/ringrtc bdbb0dd1b9f418850dfe1fa3cd4b1c2b1123436f,
 * src/rust/src/core/group_call.rs, RingId::from_era_id. Update parity tests when the pin changes.
 */
object CallEraId {
  @JvmStatic
  fun fromEra(eraId: String): Long {
    // Rust checks UTF-8 byte length and accepts an optional leading '+'. Requiring ASCII hex here
    // also avoids Java's broader Unicode digit parsing. For accepted input char/byte lengths match.
    if (eraId.length == 16 && eraId.withIndex().all { (index, c) ->
        c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F' || (index == 0 && c == '+')
      }
    ) {
      val value = java.lang.Long.parseUnsignedLong(eraId, 16)
      return if (value == 0L) -1L else value
    }

    val hash = MessageDigest.getInstance("SHA-256").digest(eraId.toByteArray(Charsets.UTF_8))
    return ByteBuffer.wrap(hash).order(ByteOrder.LITTLE_ENDIAN).long
  }
}
