/* SPDX-License-Identifier: AGPL-3.0-only */
package org.thoughtcrime.securesms.calls.permissions

import org.junit.Assert.assertEquals
import org.junit.Test

/** Vectors for the exact pinned RingId::from_era_id algorithm, without loading RingRTC. */
class CallEraIdTest {
  @Test
  fun `sixteen ASCII hex digits preserve all unsigned bits`() {
    assertEquals(0x1122334455667788L, CallEraId.fromEra("1122334455667788"))
    assertEquals(-1L, CallEraId.fromEra("ffffffffffffffff"))
    assertEquals(Long.MIN_VALUE, CallEraId.fromEra("8000000000000000"))
    assertEquals(0x7FFFFFFFFFFFFFFFL, CallEraId.fromEra("7FFFFFFFFFFFFFFF"))
  }

  @Test
  fun `zero is reserved and unsigned parser accepts leading plus`() {
    assertEquals(-1L, CallEraId.fromEra("0000000000000000"))
    assertEquals(-1L, CallEraId.fromEra("+000000000000000"))
    assertEquals(1L, CallEraId.fromEra("+000000000000001"))
  }

  @Test
  fun `noncanonical era values use first SHA256 bytes as little endian`() {
    assertEquals(-8675572703097654512L, CallEraId.fromEra("mesozoic"))
    assertEquals(1449310910991872227L, CallEraId.fromEra(""))
    assertEquals(-5800563930798637804L, CallEraId.fromEra("000000000000000"))
    assertEquals(7180280882046548565L, CallEraId.fromEra("-000000000000001"))
  }

  @Test
  fun `unicode is hashed as UTF8 and never parsed as ASCII hex`() {
    assertEquals(204153173550088016L, CallEraId.fromEra("é".repeat(16)))
    assertEquals(-4732129714345513715L, CallEraId.fromEra("＋000000000000000"))
  }
}
