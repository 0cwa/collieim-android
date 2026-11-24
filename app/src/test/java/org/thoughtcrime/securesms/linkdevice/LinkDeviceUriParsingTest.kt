/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.linkdevice

import android.app.Application
import android.net.Uri
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.util.Base64
import org.signal.libsignal.protocol.InvalidKeyException

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class LinkDeviceUriParsingTest {

  companion object {
    private const val STANDARD_B64 = "BdDF6+/UPrbtdNGGRtLqDie8khmxlEEMrN8t/mNF1m5V"
    private const val URL_SAFE_B64 = "BdDF6-_UPrbtdNGGRtLqDie8khmxlEEMrN8t_mNF1m5V"
    private const val STANDARD_B64_PERCENT_ENCODED = "BdDF6%2B%2FUPrbtdNGGRtLqDie8khmxlEEMrN8t%2FmNF1m5V"
    private const val EPHEMERAL_ID = "nw_QVJRauc7Y-g39lB_3fA=="
    private const val EPHEMERAL_ID_PERCENT_ENCODED = "nw_QVJRauc7Y-g39lB_3fA%3D%3D"

    private val EXPECTED_KEY: ByteArray = Base64.decode(STANDARD_B64)
  }

  @Test
  fun `standard base64 percent-encoded - parses`() {
    val uri = Uri.parse("sgnl://linkdevice?uuid=$EPHEMERAL_ID_PERCENT_ENCODED&pub_key=$STANDARD_B64_PERCENT_ENCODED&capabilities=")

    assertTrue(LinkDeviceRepository.isValidQr(uri))
    assertEquals(EPHEMERAL_ID, uri.getQueryParameter("uuid"))
    assertArrayEquals(EXPECTED_KEY, LinkDeviceRepository.parsePublicKey(uri)!!.serialize())
  }

  @Test
  fun `url-safe base64 unescaped - parses`() {
    val uri = Uri.parse("sgnl://linkdevice?uuid=$EPHEMERAL_ID_PERCENT_ENCODED&pub_key=$URL_SAFE_B64&capabilities=")

    assertTrue(LinkDeviceRepository.isValidQr(uri))
    assertArrayEquals(EXPECTED_KEY, LinkDeviceRepository.parsePublicKey(uri)!!.serialize())
  }

  @Test
  fun `url-safe base64 with percent-encoded padding - parses`() {
    val bytes = ByteArray(33) { if (it == 0) 0x05 else it.toByte() }
    val encoded = Base64.encodeUrlSafeWithPadding(bytes).replace("=", "%3D")
    val uri = Uri.parse("sgnl://linkdevice?uuid=$EPHEMERAL_ID_PERCENT_ENCODED&pub_key=$encoded")

    assertArrayEquals(bytes, LinkDeviceRepository.parsePublicKey(uri)!!.serialize())
  }

  @Test
  fun `standard base64 with unescaped plus - does not parse`() {
    val uri = Uri.parse("sgnl://linkdevice?uuid=$EPHEMERAL_ID_PERCENT_ENCODED&pub_key=$STANDARD_B64&capabilities=")

    val key = try {
      LinkDeviceRepository.parsePublicKey(uri)?.serialize()
    } catch (e: InvalidKeyException) {
      null
    }

    assertFalse(EXPECTED_KEY.contentEquals(key))
  }

  @Test
  fun `missing pub_key - returns null`() {
    val uri = Uri.parse("sgnl://linkdevice?uuid=$EPHEMERAL_ID_PERCENT_ENCODED")

    assertFalse(LinkDeviceRepository.isValidQr(uri))
    assertNull(LinkDeviceRepository.parsePublicKey(uri))
  }

  @Test
  fun `pasted code trims surrounding whitespace and preserves capabilities`() {
    val code = "sgnl://linkdevice?uuid=$EPHEMERAL_ID_PERCENT_ENCODED&pub_key=$URL_SAFE_B64&capabilities=backup5,numberless,future"

    val parsed = LinkDeviceRepository.parseLinkingCode(" \n$code\r\n ")

    assertEquals(code, parsed.toString())
    assertEquals("backup5,numberless,future", parsed!!.getQueryParameter("capabilities"))
  }

  @Test
  fun `non linking URLs and ambiguous destinations are rejected`() {
    val query = "uuid=$EPHEMERAL_ID_PERCENT_ENCODED&pub_key=$URL_SAFE_B64"
    listOf(
      "https://linkdevice?$query",
      "sgnl://other?$query",
      "sgnl:linkdevice?$query",
      "sgnl://user@linkdevice?$query",
      "sgnl://linkdevice:443?$query",
      "sgnl://linkdevice/other?$query",
      "sgnl://linkdevice?$query#fragment",
      "sgnl://linkdevice?$query&uuid=other",
      "sgnl://linkdevice?$query&pub_key=$URL_SAFE_B64",
      "sgnl://linkdevice?$query&capabilities=backup5&capabilities=numberless"
    ).forEach { assertNull(it, LinkDeviceRepository.parseLinkingCode(it)) }
  }

  @Test
  fun `empty malformed and oversized codes are rejected without throwing`() {
    listOf("", " \n ", "123456", "not a linking code", "sgnl://linkdevice?uuid=%20&pub_key=$URL_SAFE_B64", "sgnl://linkdevice?uuid=one%0Atwo&pub_key=$URL_SAFE_B64", "x".repeat(4097))
      .forEach { assertNull(LinkDeviceRepository.parseLinkingCode(it)) }
  }

  @Test
  fun `malformed public keys are rejected before linking`() {
    listOf("", "invalid", "!".repeat(44), "A".repeat(44), "A".repeat(4096), "$URL_SAFE_B64%0A", "$URL_SAFE_B64=", STANDARD_B64)
      .forEach { key ->
        val uri = Uri.parse("sgnl://linkdevice?uuid=$EPHEMERAL_ID_PERCENT_ENCODED&pub_key=$key")
        assertFalse(LinkDeviceRepository.isValidQr(uri))
        assertNull(LinkDeviceRepository.parsePublicKey(uri))
      }
  }
}
