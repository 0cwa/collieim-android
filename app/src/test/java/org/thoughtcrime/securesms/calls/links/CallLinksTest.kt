/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.calls.links

import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.BeforeClass
import org.junit.Test
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.testutil.EmptyLogger

/**
 * See [CallLinks]
 */
class CallLinksTest {
  companion object {
    @JvmStatic
    @BeforeClass
    fun setUpClass() {
      Log.initialize(EmptyLogger())
    }
  }

  @Test
  fun `parseUrl returns null for malformed percent escape instead of throwing`() {
    assertNull(CallLinks.parseUrl("https://signal.link/call/#key=abcdef&n=%ZZ"))
  }

  @Test
  fun `parseUrl returns null for malformed percent escape in key instead of throwing`() {
    assertNull(CallLinks.parseUrl("https://signal.link/call/#key=%ZZ"))
  }

  @Test
  fun `remote link can be parsed and formatted without loading RingRTC`() {
    val key = "crbn-mxzp-zprt-rxkr-mpqq-rtrr-cddc-bftt"
    val parsed = CallLinks.parseUrl("https://signal.link/call/#key=$key")

    assertNotNull(parsed)
    assertEquals("https://signal.link/call/#key=$key", CallLinks.url(parsed!!.keyBytes))
    assertEquals(parsed, CallLinks.parseUrl("sgnl://signal.link/call#key=$key"))
  }

  @Test
  fun `remote versioned link metadata preserves epoch bytes without native parsing`() {
    val key = "crbnmxzp-zprtrxkr-mpqqrtrr-cddcbftt-bc-pprrrrqz"
    val parsed = CallLinks.parseUrl("https://signal.link/call/#key=$key")

    assertNotNull(parsed)
    assertEquals(21, parsed!!.keyBytes.size)
    assertEquals("https://signal.link/call/#key=$key", CallLinks.url(parsed.keyBytes))
  }
}
