/*
 * Copyright 2026 Collie contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.calls.permissions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CallPolicyCodecTest {
  private val account = "a86c6ab3-980d-4ffe-bd5a-a8678513a3c7"
  private val installation = "1ee39c6a-b7c1-4ab2-9d61-e3174e2f50e1"

  @Test
  fun `round trip preserves local identity restrictions and context-specific exceptions`() {
    val state = CallPolicyState(account = account, installation = installation, directDefault = CallPermission.BLOCK)
      .withOverride(CallContext.DIRECT, 12L, CallPermission.ALLOW)
      .withOverride(CallContext.GROUP, 12L, CallPermission.BLOCK)

    assertEquals(state, CallPolicyCodec.decode(CallPolicyCodec.encode(state)))
  }

  @Test
  fun `unknown schema and prohibition values are rejected`() {
    for (record in listOf("2\n$account\n$installation\n0\nALLOW\nALLOW", "1\n$account\n$installation\nyes\nALLOW\nALLOW")) {
      assertThrows(RuntimeException::class.java) { CallPolicyCodec.decode(record) }
    }
  }

  @Test
  fun `device defaults must be explicit and unknown permissions are not ALLOW`() {
    for (permission in listOf("INHERIT", "", "ask", "allow", "UNKNOWN")) {
      assertThrows(RuntimeException::class.java) { CallPolicyCodec.decode(header(direct = permission)) }
      assertThrows(RuntimeException::class.java) { CallPolicyCodec.decode(header(group = permission)) }
    }
  }

  @Test
  fun `stored overrides require one canonical known context and positive recipient ID`() {
    val invalid = listOf(
      "DIRECT:12=INHERIT",
      "DIRECT:12=UNKNOWN",
      "CALL_LINK:12=ALLOW",
      "VOICE:12=ALLOW",
      "DIRECT:0=ALLOW",
      "DIRECT:-1=ALLOW",
      "DIRECT:0012=ALLOW",
      "DIRECT:+12=ALLOW",
      "DIRECT:9223372036854775808=ALLOW",
      "DIRECT:12=ALLOW=BLOCK",
      "DIRECT:12=ALLOW\nDIRECT:12=BLOCK"
    )
    for (override in invalid) {
      assertThrows("Must reject $override", RuntimeException::class.java) { CallPolicyCodec.decode(header() + "\n" + override) }
    }
  }

  @Test
  fun `account and installation bindings require canonical UUIDs`() {
    for (identity in listOf("garbage", "1-1-1-1-1", account.uppercase())) {
      assertThrows(RuntimeException::class.java) { CallPolicyCodec.decode("1\n$identity\n$installation\n0\nALLOW\nALLOW") }
      assertThrows(RuntimeException::class.java) { CallPolicyCodec.decode("1\n$account\n$identity\n0\nALLOW\nALLOW") }
    }
  }

  @Test
  fun `oversized records and override inventories are bounded`() {
    assertThrows(RuntimeException::class.java) { CallPolicyCodec.decode("x".repeat(512 * 1024 + 1)) }
    val tooMany = (1L..10_001L).associate { "DIRECT:$it" to CallPermission.BLOCK }
    assertThrows(RuntimeException::class.java) { CallPolicyCodec.encode(CallPolicyState(overrides = tooMany)) }
    val records = tooMany.map { (key, value) -> "$key=${value.name}" }.joinToString("\n")
    assertThrows(RuntimeException::class.java) { CallPolicyCodec.decode(header() + "\n" + records) }
  }

  private fun header(direct: String = "ALLOW", group: String = "ALLOW"): String = "1\n$account\n$installation\n0\n$direct\n$group"
}
