/*
 * Copyright 2026 Collie contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package org.thoughtcrime.securesms.backup.v2.barkup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException
import java.time.Instant

class BarkupClientTest {
  private val now = Instant.parse("2026-11-27T12:00:00Z")

  @Test
  fun `HTTPS prefix and existing v1 suffix are retained exactly once`() {
    assertEquals("https://backup.example/v1", offer("https://backup.example").api.toString())
    assertEquals("https://backup.example/v1", offer("https://backup.example/v1/").api.toString())
    assertEquals("https://backup.example/prefix/v1", offer("https://backup.example/prefix/").api.toString())
  }

  @Test
  fun `unsafe and expired offers do not yield credentials or a connection target`() {
    for (url in listOf("http://backup.example", "https://user@backup.example", "https://backup.example?token=x", "https://backup.example#fragment")) {
      assertThrows(IOException::class.java) { offer(url) }
    }
    assertThrows(IOException::class.java) { offer("https://backup.example", "2026-11-27T12:00:00Z") }
  }

  @Test
  fun `Barkup disables redirects and automatic retries on the production transport`() {
    val client = BarkupClient.newHttpClient()
    assertFalse(client.followRedirects)
    assertFalse(client.followSslRedirects)
    assertFalse(client.retryOnConnectionFailure)
  }

  private fun offer(url: String, expiry: String = "2026-11-27T12:10:00Z") = BarkupOffer.parse(
    """{"collie_barkup_pairing":1,"url":"$url","token":"test-offer","expires_at":"$expiry"}""",
    now
  )
}
