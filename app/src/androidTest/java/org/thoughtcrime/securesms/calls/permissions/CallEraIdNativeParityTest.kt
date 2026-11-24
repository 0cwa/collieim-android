/* SPDX-License-Identifier: AGPL-3.0-only */
package org.thoughtcrime.securesms.calls.permissions

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.signal.ringrtc.CallId

/** Checks the managed metadata conversion against the actual packaged RingRTC implementation. */
@RunWith(AndroidJUnit4::class)
class CallEraIdNativeParityTest {
  @Test
  fun managedEraIdMatchesPinnedNative() {
    val values = listOf(
      "1122334455667788", "0000000000000000", "ffffffffffffffff", "7FFFFFFFFFFFFFFF", "8000000000000000",
      "+000000000000000", "+000000000000001", "mesozoic", "", "000000000000000", "-000000000000001",
      "é".repeat(16), "＋000000000000000"
    )
    for (era in values) {
      assertEquals("Era: $era", CallId.fromEra(era).longValue(), CallEraId.fromEra(era))
    }
  }
}
