/*
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.whispersystems.signalservice.api

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import okhttp3.Credentials
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.signal.core.util.Base64
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyType
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import org.signal.network.config.SignalServiceConfiguration
import org.signal.network.config.SignalServiceUrl
import org.signal.network.config.TrustStore
import org.whispersystems.signalservice.api.account.AccountAttributes
import org.whispersystems.signalservice.api.account.PreKeyCollection
import org.whispersystems.signalservice.api.groupsv2.GroupsV2Operations
import org.whispersystems.signalservice.api.push.SignalServiceAddress
import org.whispersystems.signalservice.internal.push.DeviceLimitExceededException
import org.whispersystems.signalservice.internal.push.PushServiceSocket
import org.whispersystems.signalservice.internal.push.exceptions.MissingCapabilitiesException
import org.whispersystems.signalservice.internal.util.BlacklistingTrustManager
import org.whispersystems.signalservice.internal.util.StaticCredentialsProvider
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ProxySelector
import javax.net.SocketFactory
import javax.net.ssl.TrustManager

/** Exercises the HTTP contract used by MollySocket without contacting the Signal service. */
class SignalServiceAccountManagerLinkedRegistrationTest {

  private val mapper = ObjectMapper()
  private val aciPreKeys = preKeys(11)
  private val pniPreKeys = preKeys(21)

  @Test
  fun `fetching linked device preserves credentials attributes and both prekey collections`() {
    val (deviceId, request) = register(fcmToken = "must-not-be-sent")
    val body = body(request)

    assertEquals(7, deviceId)
    assertEquals("PUT", request.method)
    assertEquals("https://linked-registration.invalid/v1/devices/link", request.url.toString())
    // createForDeviceLink has no ACI or number yet; this is the new device's password.
    assertEquals(Credentials.basic("null", DEVICE_PASSWORD), request.header("Authorization"))
    assertEquals("MollySocket-test", request.header("X-Signal-Agent"))
    assertEquals("provisioning-code", body["verificationCode"].asText())
    assertEquals(
      mapper.readTree(
        """{
          "fetchesMessages": true,
          "registrationId": 123,
          "pniRegistrationId": 456,
          "name": "ZW5jcnlwdGVkLW5hbWU=",
          "capabilities": {
            "storage": false,
            "versionedExpirationTimer": true,
            "attachmentBackfill": true,
            "spqr": true,
            "usernameChangeSyncMessage": true,
            "optionalPhoneNumber": false
          }
        }"""
      ),
      body["accountAttributes"]
    )
    assertPreKeys(body, "aci", aciPreKeys)
    assertPreKeys(body, "pni", pniPreKeys)
    assertTrue(body.path("gcmToken").isNull)
  }

  @Test
  fun `push linked device retains its FCM token`() {
    val (_, request) = register(fetchesMessages = false, fcmToken = "linked-device-fcm")
    val body = body(request)

    assertEquals(false, body["accountAttributes"]["fetchesMessages"].asBoolean())
    assertEquals("linked-device-fcm", body["gcmToken"]["gcmRegistrationId"].asText())
    assertEquals(true, body["gcmToken"]["webSocketChannel"].asBoolean())
  }

  @Test
  fun `link capability conflict retains its endpoint-specific exception`() {
    assertThrows(MissingCapabilitiesException::class.java) {
      register(responseCode = 409, responseBody = """{"missingCapabilities":["spqr"]}""")
    }
  }

  @Test
  fun `device limit retains the counts used by UnifiedPush notification`() {
    val error = assertThrows(DeviceLimitExceededException::class.java) {
      register(responseCode = 411, responseBody = """{"current":6,"max":6}""")
    }

    assertEquals(6, error.current)
    assertEquals(6, error.max)
  }

  @Test
  fun `invalid response device ID fails as an IO error`() {
    val error = assertThrows(IOException::class.java) {
      register(responseBody = response("not-a-device-id"))
    }

    assertTrue(error.cause is NumberFormatException)
  }

  private fun register(
    responseCode: Int = 200,
    responseBody: String = response("7"),
    fetchesMessages: Boolean = true,
    fcmToken: String? = null
  ): Pair<Int, Request> {
    var captured: Request? = null
    val interceptor = Interceptor { chain ->
      val request = chain.request().also { captured = it }
      Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(responseCode)
        .message("Fixture response")
        .body(responseBody.toResponseBody("application/json".toMediaType()))
        .build()
    }
    val trustStore = mockk<TrustStore>()
    val defaultTrustManager = BlacklistingTrustManager.createForDefaultTrustStore()
    // Android's BKS store is irrelevant to this in-process HTTP exchange. Keep the real
    // request, JSON, response handling, and configured client/interceptor path intact.
    mockkStatic(BlacklistingTrustManager::class)
    try {
      every { BlacklistingTrustManager.createFor(trustStore) } returns arrayOf<TrustManager>(defaultTrustManager)
      val configuration = SignalServiceConfiguration(
        signalServiceUrls = arrayOf(SignalServiceUrl("https://linked-registration.invalid", trustStore)),
        signalCdnUrlMap = mapOf(0 to emptyArray(), 2 to emptyArray()),
        signalStorageUrls = emptyArray(),
        signalCdsiUrls = emptyArray(),
        signalSvr2Urls = emptyArray(),
        networkInterceptors = listOf(interceptor),
        socketFactory = SocketFactory.getDefault(),
        proxySelector = ProxySelector.of(InetSocketAddress.createUnresolved("proxy.invalid", 1080)),
        dns = Dns { throw AssertionError("Registration fixture must not resolve a network host") },
        zkGroupServerPublicParams = byteArrayOf(),
        genericServerPublicParams = byteArrayOf(),
        backupServerPublicParams = byteArrayOf(),
        censored = false
      )
      val credentials = StaticCredentialsProvider(null, null, null, SignalServiceAddress.DEFAULT_DEVICE_ID, DEVICE_PASSWORD)
      val socket = PushServiceSocket(configuration, credentials, "MollySocket-test", false)
      val manager = SignalServiceAccountManager(null, null, socket, mockk<GroupsV2Operations>())
      val attributes = AccountAttributes(
        signalingKey = null,
        registrationId = 123,
        fetchesMessages = fetchesMessages,
        registrationLock = null,
        unidentifiedAccessKey = null,
        unrestrictedUnidentifiedAccess = true,
        capabilities = AccountAttributes.Capabilities(false, true, true, true, true, false),
        discoverableByPhoneNumber = false,
        name = "ZW5jcnlwdGVkLW5hbWU=",
        pniRegistrationId = 456,
        recoveryPassword = null
      )
      val deviceId = manager.finishNewDeviceRegistration("provisioning-code", attributes, aciPreKeys, pniPreKeys, fcmToken)
      return deviceId to checkNotNull(captured)
    } finally {
      unmockkStatic(BlacklistingTrustManager::class)
    }
  }

  private fun body(request: Request): JsonNode {
    val buffer = Buffer()
    checkNotNull(request.body).writeTo(buffer)
    return mapper.readTree(buffer.readUtf8())
  }

  private fun assertPreKeys(body: JsonNode, prefix: String, keys: PreKeyCollection) {
    val signed = body["${prefix}SignedPreKey"]
    assertEquals(keys.signedPreKey.id, signed["keyId"].asInt())
    assertEquals(Base64.encodeWithoutPadding(keys.signedPreKey.keyPair.publicKey.serialize()), signed["publicKey"].asText())
    assertEquals(Base64.encodeWithoutPadding(keys.signedPreKey.signature), signed["signature"].asText())
    val pq = body["${prefix}PqLastResortPreKey"]
    assertEquals(keys.lastResortKyberPreKey.id, pq["keyId"].asInt())
    assertEquals(Base64.encodeWithoutPadding(keys.lastResortKyberPreKey.keyPair.publicKey.serialize()), pq["publicKey"].asText())
    assertEquals(Base64.encodeWithoutPadding(keys.lastResortKyberPreKey.signature), pq["signature"].asText())
  }

  private fun preKeys(id: Int): PreKeyCollection {
    val identity = ECKeyPair.generate()
    val signed = ECKeyPair.generate()
    val pq = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
    return PreKeyCollection(
      IdentityKey(identity.publicKey),
      SignedPreKeyRecord(id, 1L, signed, identity.privateKey.calculateSignature(signed.publicKey.serialize())),
      KyberPreKeyRecord(id + 1, 1L, pq, identity.privateKey.calculateSignature(pq.publicKey.serialize()))
    )
  }

  private fun response(deviceId: String): String =
    """{"uuid":"00000000-0000-4000-8000-000000000001","pni":"00000000-0000-4000-8000-000000000002","deviceId":"$deviceId"}"""

  companion object {
    private const val DEVICE_PASSWORD = "new-linked-device-password"
  }
}
