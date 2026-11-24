/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package org.thoughtcrime.securesms.calls.permissions

import org.thoughtcrime.securesms.service.webrtc.links.CallLinkRoomId

/** A validated call-link value for storage, sync and previews, without RingRTC dispatch. */
class ManagedCallLinkRootKey private constructor(private val bytes: ByteArray) {
  val keyBytes: ByteArray
    get() = bytes.copyOf()

  val roomId: CallLinkRoomId by lazy { CallLinkRoomId.fromBytes(CallLinkKeyCodec.deriveRoomId(bytes)) }

  fun toFormattedString(): String = CallLinkKeyCodec.format(bytes)

  override fun toString(): String = toFormattedString().take(4) + if (bytes.size == 16) {
    "-****-****-****-****-****-****-****"
  } else {
    "****-********-********-********-**-********"
  }

  override fun equals(other: Any?): Boolean = other is ManagedCallLinkRootKey && bytes.contentEquals(other.bytes)

  override fun hashCode(): Int = bytes.contentHashCode()

  companion object {
    @JvmStatic
    fun fromBytes(bytes: ByteArray): ManagedCallLinkRootKey? {
      if (bytes.size != 16 && bytes.size != 21) return null
      return bytes.copyOf().takeIf(CallLinkKeyCodec::valid)?.let(::ManagedCallLinkRootKey)
    }

    @JvmStatic
    fun parse(formatted: String): ManagedCallLinkRootKey? = CallLinkKeyCodec.parse(formatted)?.let(::ManagedCallLinkRootKey)
  }
}
