/* SPDX-License-Identifier: AGPL-3.0-only */
package org.thoughtcrime.securesms.calls.permissions

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.signal.ringrtc.CallException
import org.signal.ringrtc.CallLinkRootKey

/** Compare metadata handling with the exact RingRTC library packaged in the tested Beta APK. */
@RunWith(AndroidJUnit4::class)
class CallLinkKeyNativeParityTest {
  @Test
  fun canonicalVersionsAndRoomIdentityMatchPinnedNative() {
    val vectors = listOf(
      "crbn-mxzp-zprt-rxkr-mpqq-rtrr-cddc-bftt",
      "crbnmxzp-zprtrxkr-mpqqrtrr-cddcbftt-bc-pprrrrqz",
      "crbnmxzp-zprtrxkr-mpqqrtrr-cddcbftt-nc-bbbbbbbb"
    )
    for (formatted in vectors) {
      val managed = requireNotNull(ManagedCallLinkRootKey.parse(formatted))
      val native = CallLinkRootKey(formatted)
      assertArrayEquals(native.keyBytes, managed.keyBytes)
      assertEquals(native.toUnredactedString(), managed.toFormattedString())
      assertArrayEquals(native.deriveRoomId(), managed.roomId.encodeForProto().toByteArray())
    }

    for (seed in listOf(0, 1, 15, 16, 127, 128, 254, 255)) {
      val crn = ByteArray(16) { (seed + it).toByte() }
      val variants = listOf(crn, crn.copyOf(21).also { it[16] = 1; it[17] = seed.toByte() })
      for (bytes in variants) {
        val managed = requireNotNull(ManagedCallLinkRootKey.fromBytes(bytes))
        val native = CallLinkRootKey(bytes)
        assertEquals(native.toUnredactedString(), managed.toFormattedString())
        assertArrayEquals(native.deriveRoomId(), managed.roomId.encodeForProto().toByteArray())
      }
    }
  }

  @Test
  fun malformedBinaryKeysAreRejectedWithoutBroadeningNativeAcceptance() {
    val validCrn = ByteArray(16) { it.toByte() }
    val invalid = listOf(
      ByteArray(16), ByteArray(15), ByteArray(25),
      validCrn.copyOf(21).also { it[16] = 2 },
      validCrn.copyOf(21).also { it[16] = 0x82.toByte() },
      validCrn.copyOf().also { it[4] = 0x55; it[5] = 0x55 }
    )
    for (bytes in invalid) {
      assertNull(ManagedCallLinkRootKey.fromBytes(bytes))
      try {
        CallLinkRootKey(bytes)
        fail("Native unexpectedly accepted malformed key of length ${bytes.size}")
      } catch (_: CallException) {
        // Expected exact-pin validation failure.
      }
    }
  }
}
