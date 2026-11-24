/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package org.thoughtcrime.securesms.calls.permissions

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fixed vectors from the exact pinned RingRTC root_key.rs tests; no native library is loaded. */
class CallLinkKeyCodecTest {
  private val v0 = "crbn-mxzp-zprt-rxkr-mpqq-rtrr-cddc-bftt"
  private val v1 = "crbnmxzp-zprtrxkr-mpqqrtrr-cddcbftt-bc-pprrrrqz"
  private val pendingV1 = "crbnmxzp-zprtrxkr-mpqqrtrr-cddcbftt-nc-bbbbbbbb"

  @Test
  fun `pinned v0 and v1 representations match the native fixed vectors`() {
    val vectors = mapOf(
      v0 to "1b087ef9f9bdbe6b79aabdbb122103dd",
      v1 to "1b087ef9f9bdbe6b79aabdbb122103dd0199bbbbaf",
      pendingV1 to "1b087ef9f9bdbe6b79aabdbb122103dd8100000000"
    )
    vectors.forEach { (formatted, hex) ->
      val parsed = CallLinkKeyCodec.parse(formatted)
      assertArrayEquals(hexBytes(hex), parsed)
      assertEquals(formatted, CallLinkKeyCodec.format(parsed!!))
    }
  }

  @Test
  fun `room identity remains unchanged across version upgrade epoch and incomplete creation`() {
    // Independently computed HKDF-SHA256 vector for the pinned Rust test CRN.
    val expected = hexBytes("fb9d3a738715b3abad413d81c525885388415d74c611e2c8c32e4234ba09798d")
    listOf(v0, v1, pendingV1).forEach {
      assertArrayEquals(expected, CallLinkKeyCodec.deriveRoomId(CallLinkKeyCodec.parse(it)!!))
    }
  }

  @Test
  fun `unsupported lengths and versions fail before derivation`() {
    val key = CallLinkKeyCodec.parse(v0)!!
    listOf(0, 15, 17, 20, 22, 25, 65536).forEach { size -> assertFalse(CallLinkKeyCodec.valid(key.copyOf(size))) }
    listOf(0, 2, 127, 128, 130, 255).forEach { version ->
      val bytes = key.copyOf(21).also { it[16] = version.toByte() }
      assertFalse(CallLinkKeyCodec.valid(bytes))
    }
  }

  @Test
  fun `only aligned repeated nibble pairs in the CRN are rejected`() {
    val key = CallLinkKeyCodec.parse(v0)!!
    for (nibble in 0..15) {
      val repeated = key.copyOf().also { it[4] = (nibble * 17).toByte(); it[5] = it[4] }
      assertFalse(CallLinkKeyCodec.valid(repeated))
    }
    val oddAlignedPair = key.copyOf().also { it[3] = 0x55; it[4] = 0x55 }
    assertTrue(CallLinkKeyCodec.valid(oddAlignedPair))
    assertNotNull(CallLinkKeyCodec.parse(pendingV1)) // Zero epoch bytes are not CRN.
  }

  @Test
  fun `noncanonical or unbounded remote strings are rejected`() {
    listOf(
      "", v0.uppercase(), v0.replace('-', ' '), v0.replace("crbn", "crbñ"), v0.replace("crbn", "crba"),
      v0.replace("-", ""), "$v0-", "$v0-crbn", "$v0\u0000", " $v0", "$v0 ",
      v1.dropLast(1), v1.replaceFirst('-', 'b'), "b".repeat(65536)
    ).forEach { assertNull(it.take(48), CallLinkKeyCodec.parse(it)) }
  }

  @Test
  fun `all byte values round trip across both supported formats`() {
    for (seed in 0..255) {
      val crn = ByteArray(16) { (seed + it).toByte() }
      if (!CallLinkKeyCodec.valid(crn)) continue
      assertArrayEquals(crn, CallLinkKeyCodec.parse(CallLinkKeyCodec.format(crn)))
      val v1Bytes = crn.copyOf(21).also { it[16] = 1; it[17] = seed.toByte() }
      assertArrayEquals(v1Bytes, CallLinkKeyCodec.parse(CallLinkKeyCodec.format(v1Bytes)))
    }
  }

  private fun hexBytes(value: String): ByteArray = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
