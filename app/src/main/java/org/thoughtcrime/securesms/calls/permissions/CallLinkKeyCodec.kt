/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package org.thoughtcrime.securesms.calls.permissions

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Managed metadata projection of RingRTC's root_key.rs and base16.rs, pinned to
 * bdbb0dd1b9f418850dfe1fa3cd4b1c2b1123436f (build-inputs/native-lock.json).
 * Source: https://github.com/signalapp/ringrtc/tree/bdbb0dd1b9f418850dfe1fa3cd4b1c2b1123436f/src/rust/src/lite/call_links
 *
 * This only validates/formats keys and derives their public room identifier. Native creation,
 * credential presentation, encryption and call operations remain behind call admission.
 */
internal object CallLinkKeyCodec {
  private const val ALPHABET = "bcdfghkmnpqrstxz"
  private const val CRN_LENGTH = 16
  private val ROOM_INFO = "20230501-Signal-CallLinkRootKey-RoomId".toByteArray(Charsets.US_ASCII)
  private val V0_GROUPS = intArrayOf(2, 2, 2, 2, 2, 2, 2, 2)
  private val V1_GROUPS = intArrayOf(4, 4, 4, 4, 1, 4)

  fun valid(bytes: ByteArray): Boolean {
    if (bytes.size != CRN_LENGTH && (bytes.size != 21 || (bytes[CRN_LENGTH].toInt() and 0x7f) != 1)) return false
    for (index in 0 until CRN_LENGTH step 2) {
      val first = bytes[index].toInt() and 0xff
      if (bytes[index] == bytes[index + 1] && (first ushr 4) == (first and 0x0f)) return false
    }
    return true
  }

  fun parse(formatted: String): ByteArray? {
    // Accept complete canonical representations only. In particular, do not allow a suffix
    // after the final group, even if a native parser stops after exhausting its group sizes.
    val groups = when (formatted.length) {
      39 -> V0_GROUPS
      47 -> V1_GROUPS
      else -> return null
    }
    val bytes = ByteArray(groups.sum())
    var offset = 0
    var byteIndex = 0
    groups.forEachIndexed { groupIndex, size ->
      if (groupIndex != 0 && formatted[offset++] != '-') return null
      repeat(size) {
        val high = ALPHABET.indexOf(formatted[offset++])
        val low = ALPHABET.indexOf(formatted[offset++])
        if (high < 0 || low < 0) return null
        bytes[byteIndex++] = ((high shl 4) or low).toByte()
      }
    }
    return bytes.takeIf(::valid)
  }

  fun format(bytes: ByteArray): String {
    require(valid(bytes))
    val groups = if (bytes.size == CRN_LENGTH) V0_GROUPS else V1_GROUPS
    var offset = 0
    return groups.joinToString("-") { size ->
      buildString(size * 2) {
        repeat(size) {
          val value = bytes[offset++].toInt() and 0xff
          append(ALPHABET[value ushr 4])
          append(ALPHABET[value and 0x0f])
        }
      }
    }
  }

  fun deriveRoomId(bytes: ByteArray): ByteArray {
    require(valid(bytes))
    // HKDF-SHA256(None, CRN[0..16]).expand(ROOM_INFO, 32). The output is exactly
    // one HKDF block, so expansion consists of HMAC(PRK, info || 0x01).
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(ByteArray(32), "HmacSHA256"))
    val prk = mac.doFinal(bytes.copyOfRange(0, CRN_LENGTH))
    mac.init(SecretKeySpec(prk, "HmacSHA256"))
    mac.update(ROOM_INFO)
    return mac.doFinal(byteArrayOf(1))
  }
}
