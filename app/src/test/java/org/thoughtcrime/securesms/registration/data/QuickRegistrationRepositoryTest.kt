/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.registration.data

import android.app.Application
import android.net.Uri
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.util.Base64
import org.signal.libsignal.protocol.InvalidKeyException
import org.signal.core.util.logging.Log
import org.signal.network.NetworkResult
import org.signal.network.api.ProvisioningApi
import org.signal.network.exceptions.NonSuccessfulResponseCodeException
import org.thoughtcrime.securesms.net.SignalNetwork
import org.thoughtcrime.securesms.testutil.EmptyLogger
import org.thoughtcrime.securesms.testutil.LogRecorder
import org.whispersystems.signalservice.api.provisioning.RestoreMethod
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class QuickRegistrationRepositoryTest {

  companion object {
    private const val STANDARD_B64 = "BdDF6+/UPrbtdNGGRtLqDie8khmxlEEMrN8t/mNF1m5V"
    private const val URL_SAFE_B64 = "BdDF6-_UPrbtdNGGRtLqDie8khmxlEEMrN8t_mNF1m5V"
    private const val STANDARD_B64_PERCENT_ENCODED = "BdDF6%2B%2FUPrbtdNGGRtLqDie8khmxlEEMrN8t%2FmNF1m5V"
    private const val EPHEMERAL_ID_PERCENT_ENCODED = "nw_QVJRauc7Y-g39lB_3fA%3D%3D"

    private val EXPECTED_KEY: ByteArray = Base64.decode(STANDARD_B64)
  }

  @Test
  fun `standard base64 percent-encoded - parses`() {
    val data = "sgnl://rereg?uuid=$EPHEMERAL_ID_PERCENT_ENCODED&pub_key=$STANDARD_B64_PERCENT_ENCODED&capabilities="

    assertTrue(QuickRegistrationRepository.isValidReRegistrationQr(data))
    assertArrayEquals(EXPECTED_KEY, QuickRegistrationRepository.parsePublicKey(Uri.parse(data))!!.serialize())
  }

  @Test
  fun `url-safe base64 unescaped - parses`() {
    val data = "sgnl://rereg?uuid=$EPHEMERAL_ID_PERCENT_ENCODED&pub_key=$URL_SAFE_B64&capabilities="

    assertTrue(QuickRegistrationRepository.isValidReRegistrationQr(data))
    assertArrayEquals(EXPECTED_KEY, QuickRegistrationRepository.parsePublicKey(Uri.parse(data))!!.serialize())
  }

  @Test
  fun `url-safe base64 with percent-encoded padding - parses`() {
    val bytes = ByteArray(33) { if (it == 0) 0x05 else it.toByte() }
    val encoded = Base64.encodeUrlSafeWithPadding(bytes).replace("=", "%3D")
    val data = "sgnl://rereg?uuid=$EPHEMERAL_ID_PERCENT_ENCODED&pub_key=$encoded"

    assertArrayEquals(bytes, QuickRegistrationRepository.parsePublicKey(Uri.parse(data))!!.serialize())
  }

  @Test
  fun `standard base64 with unescaped plus - does not parse`() {
    val data = "sgnl://rereg?uuid=$EPHEMERAL_ID_PERCENT_ENCODED&pub_key=$STANDARD_B64&capabilities="

    val key = QuickRegistrationRepository.parsePublicKey(Uri.parse(data))?.serialize()

    assertFalse(EXPECTED_KEY.contentEquals(key))
  }

  @Test
  fun `restore method polling logs only result categories`() = runBlocking {
    val token = "synthetic-token-7c6d4e"
    val body = "synthetic-body-2a9f"
    val header = "synthetic-header-3b8e"
    val uri = "https://example.invalid/restore/$token"
    val exception = NonSuccessfulResponseCodeException(400, uri, body, mapOf("x-test" to header))
    val binaryException = NonSuccessfulResponseCodeException(400, uri, body.toByteArray(), mapOf("x-test" to header))
    val api = mockk<ProvisioningApi>()
    every { api.waitForRestoreMethod(token, 30) } returnsMany listOf(
      NetworkResult.StatusCodeError(exception),
      NetworkResult.StatusCodeError(binaryException),
      NetworkResult.NetworkError(IOException(uri)),
      NetworkResult.ApplicationError(IllegalStateException(uri)),
      NetworkResult.Success(RestoreMethod.REMOTE_BACKUP)
    )
    val network = mockk<SignalNetwork>()
    every { network.provisioningApi } returns api
    val logger = LogRecorder()
    SignalNetwork.init(network)
    Log.initialize(logger)
    try {
      assertEquals(RestoreMethod.REMOTE_BACKUP, QuickRegistrationRepository.waitForRestoreMethodSelectionOnNewDevice(token))
      verify(exactly = 5) { api.waitForRestoreMethod(token, 30) }
      val entries = logger.verbose + logger.debug + logger.information + logger.warnings + logger.errors + logger.wtf
      assertEquals(
        listOf("Result: status error", "Result: status error", "Result: network error", "Result: application error", "Result: success"),
        logger.debug.map { it.message }.filter { it.startsWith("Result:") }
      )
      for (entry in entries) {
        for (secret in listOf(token, body, header, uri)) {
          assertFalse(entry.message.contains(secret))
          assertFalse(entry.throwable?.toString()?.contains(secret) == true)
        }
      }
    } finally {
      SignalNetwork.init(SignalNetwork())
      Log.initialize(EmptyLogger())
    }
  }

  @Test
  fun `missing pub_key - returns null`() {
    val data = "sgnl://rereg?uuid=$EPHEMERAL_ID_PERCENT_ENCODED"

    assertFalse(QuickRegistrationRepository.isValidReRegistrationQr(data))
    assertNull(QuickRegistrationRepository.parsePublicKey(Uri.parse(data)))
  }
}
