/**
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.service.webrtc.links

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import org.thoughtcrime.securesms.calls.permissions.CallLinkKeyCodec
import org.thoughtcrime.securesms.calls.permissions.ManagedCallLinkRootKey
import java.security.SecureRandom

/**
 * Holds onto the credentials for a given call link.
 */
@Parcelize
data class CallLinkCredentials(
  val linkKeyBytes: ByteArray,
  val adminPassBytes: ByteArray?
) : Parcelable {

  val roomId: CallLinkRoomId by lazy {
    requireNotNull(ManagedCallLinkRootKey.fromBytes(linkKeyBytes)).roomId
  }

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (javaClass != other?.javaClass) return false

    other as CallLinkCredentials

    if (!linkKeyBytes.contentEquals(other.linkKeyBytes)) return false
    if (adminPassBytes != null) {
      if (other.adminPassBytes == null) return false
      if (!adminPassBytes.contentEquals(other.adminPassBytes)) return false
    } else if (other.adminPassBytes != null) {
      return false
    }

    return true
  }

  override fun hashCode(): Int {
    var result = linkKeyBytes.contentHashCode()
    result = 31 * result + (adminPassBytes?.contentHashCode() ?: 0)
    return result
  }

  companion object {
    /**
     * Generate a new call link credential for creating a new call.
     */
    fun generate(): CallLinkCredentials {
      return generate(SecureRandom())
    }

    /** Local editor state must not load RingRTC before a create/join operation is authorized. */
    internal fun generate(random: SecureRandom): CallLinkCredentials {
      return CallLinkCredentials(
        CallLinkKeyCodec.generate(random),
        ByteArray(16).also(random::nextBytes)
      )
    }
  }
}
