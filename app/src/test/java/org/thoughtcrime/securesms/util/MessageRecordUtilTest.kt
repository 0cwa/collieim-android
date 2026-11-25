/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageRecordUtilTest {

  @Test
  fun `body over line limit needs overflow and truncates at line limit`() {
    val body = "line\n".repeat(MAX_BODY_DISPLAY_LINES) + "tail"

    assertTrue(hasOverflowingBody(body))
    assertEquals("line\n".repeat(MAX_BODY_DISPLAY_LINES - 1) + "line", body.take(getMaxBodyDisplayLength(body)))
  }

  @Test
  fun `body below line and character limits does not need overflow`() {
    val body = "line\n".repeat(MAX_BODY_DISPLAY_LINES - 1)

    assertFalse(hasOverflowingBody(body))
    assertEquals(MAX_BODY_DISPLAY_LENGTH, getMaxBodyDisplayLength(body))
  }

  @Test
  fun `character limit remains in force for bodies without many newlines`() {
    val body = "x".repeat(MAX_BODY_DISPLAY_LENGTH + 1)

    assertTrue(hasOverflowingBody(body))
    assertEquals(MAX_BODY_DISPLAY_LENGTH, getMaxBodyDisplayLength(body))
  }

  @Test
  fun `character limit wins when it occurs before line limit`() {
    val body = "x".repeat(MAX_BODY_DISPLAY_LENGTH - 1) + "\n".repeat(MAX_BODY_DISPLAY_LINES)

    assertEquals(MAX_BODY_DISPLAY_LENGTH, getMaxBodyDisplayLength(body))
  }
}
