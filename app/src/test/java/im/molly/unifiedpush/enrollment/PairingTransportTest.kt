package im.molly.unifiedpush.enrollment

import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class PairingTransportTest {
  private val vector = ObjectMapper().readTree(
    checkNotNull(javaClass.getResourceAsStream("/colliesocket-pairing-v1/fixtures/sealed-vectors.json")).use { it.readBytes() },
  )[0]
  private val offerJson = vector["offer"].toString()
  private val now = 1_799_999_999L

  @Test
  fun `transport wrappers and standalone offers keep the exact offer validation`() {
    val bare = PairingTransport.parse(offerJson, now)
    assertNull(bare.submitUrl)
    val url = "http://192.168.1.42:18099/pairing/v1/submit"
    val wrapper = """{"collie_pairing_transport":1,"submit_url":"$url","offer":$offerJson}"""
    assertEquals(bare.offer, PairingTransport.parse(wrapper, now).offer)
    assertEquals(url, PairingTransport.parse(wrapper, now).submitUrl.toString())
    assertEquals("session_expired", assertThrows(EnrollmentFile.Invalid::class.java) {
      PairingTransport.parse(wrapper, now + 1)
    }.code)
    for (invalid in listOf(
      wrapper.replace(":1,", ":1.0,"),
      wrapper.dropLast(1) + ",\"extra\":true}",
      wrapper.replace("\"offer\":", "\"submit_url\":\"$url\",\"offer\":"),
      wrapper.replace("\"pairing_version\":1", "\"pairing_version\":1,\"pairing_version\":1"),
      wrapper + "{}",
      "\uFEFF$wrapper",
    )) {
      assertThrows(EnrollmentFile.Invalid::class.java) { PairingTransport.parse(invalid, now) }
    }
  }

  @Test
  fun `HTTP requires local addresses and all URLs reject credentials and fragments`() {
    val allowed = listOf("10.1.2.3", "172.16.0.1", "172.31.255.254", "192.168.1.42", "100.64.0.1", "100.127.0.1",
      "127.0.0.1", "[::1]", "[fc00::1]", "[fd00::1]", "[fe80::1]", "[febf::1]", "relay.local", "localhost")
    val denied = listOf("8.8.8.8", "192.169.0.1", "172.32.0.1", "100.63.0.1", "100.128.0.1",
      "[fec0::1]", "[2001:db8::1]", "[::ffff:8.8.8.8]", "relay.example", "local.example", "0.0.0.0")
    allowed.forEach { PairingTransport.parseSubmitUrl("http://$it/pairing/v1/submit") }
    denied.forEach { assertThrows(EnrollmentFile.Invalid::class.java) { PairingTransport.parseSubmitUrl("http://$it/") } }
    assertEquals("https://relay.example/", PairingTransport.parseSubmitUrl("https://relay.example").toString())
    listOf("https://user:pass@relay.example/", "http://@127.0.0.1/", "https://relay.example/#", "ftp://127.0.0.1/").forEach {
      assertThrows(EnrollmentFile.Invalid::class.java) { PairingTransport.parseSubmitUrl(it) }
    }
  }

  @Test
  fun `local HTTP posts the exact sealed request and authenticates its chunked receipt`() {
    val offer = PairingV1.parseOffer(offerJson, now)
    val sealed = PairingV1.sealedFromVectorForTest(
      offer,
      vector["enrollment_json"].asText(),
      checkNotNull(offer.providerOrigin) + "/test",
      vector["request"]["client_public_key"].asText(),
      vector["shared_secret_hex"].asText(),
      vector["request"]["nonce"].asText(),
    )
    val method = AtomicReference<String>()
    val contentType = AtomicReference<String>()
    val received = AtomicReference<String>()
    withServer { server ->
      server.createContext("/pairing/v1/submit") { exchange ->
        exchange.use {
          method.set(it.requestMethod)
          contentType.set(it.requestHeaders.getFirst("Content-Type"))
          received.set(it.requestBody.readBytes().toString(Charsets.UTF_8))
          it.responseHeaders.set("Content-Type", "application/json")
          it.sendResponseHeaders(200, 0)
          it.responseBody.write(vector["response"].toString().toByteArray(Charsets.UTF_8))
        }
      }
      val url = PairingTransport.parseSubmitUrl("http://127.0.0.1:${server.address.port}/pairing/v1/submit")
      assertEquals(PairingTransport.Result.Accepted, PairingTransport.submit(url, sealed.json) { receipt ->
        PairingV1.openReceipt(offer, sealed, receipt) == vector["receipt_json"].asText()
      })
      assertEquals("POST", method.get())
      assertEquals("application/json", contentType.get())
      assertEquals(sealed.json, received.get())
      assertEquals(PairingTransport.Result.InvalidResponse, PairingTransport.submit(url, sealed.json) { false })
    }
  }

  @Test
  fun `redirects and oversized responses fail without invoking receipt verification`() {
    val redirected = AtomicBoolean(false)
    withServer { server ->
      server.createContext("/redirect") { exchange ->
        exchange.use {
          it.responseHeaders.set("Location", "/unexpected")
          it.sendResponseHeaders(307, -1)
        }
      }
      server.createContext("/unexpected") { exchange ->
        exchange.use {
          redirected.set(true)
          it.sendResponseHeaders(400, -1)
        }
      }
      server.createContext("/oversize") { exchange ->
        exchange.use {
          it.sendResponseHeaders(200, 2049)
          it.responseBody.write(ByteArray(2049))
        }
      }
      server.createContext("/expired") { exchange ->
        exchange.use {
          val body = """{"error":"session_expired"}""".toByteArray()
          it.sendResponseHeaders(410, body.size.toLong())
          it.responseBody.write(body)
        }
      }
      fun submit(path: String) = PairingTransport.submit(
        PairingTransport.parseSubmitUrl("http://127.0.0.1:${server.address.port}/$path"), "{}",
      ) { error("Receipt should not be verified") }
      assertEquals(PairingTransport.Result.InvalidResponse, submit("redirect"))
      assertFalse(redirected.get())
      assertEquals(PairingTransport.Result.InvalidResponse, submit("oversize"))
      assertEquals(PairingTransport.Result.Rejected("session_expired"), submit("expired"))
    }
  }

  @Test
  fun `bare offers and connection failures enter offline fallback`() {
    val port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
    val unreachable = PairingTransport.parseSubmitUrl("http://127.0.0.1:$port/")
    assertEquals(PairingTransport.Result.Offline, PairingTransport.submit(null, "{}") { error("Unexpected receipt") })
    assertEquals(PairingTransport.Result.Offline, PairingTransport.submit(unreachable, "{}") { error("Unexpected receipt") })
  }

  private fun withServer(block: (HttpServer) -> Unit) {
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.start()
    try {
      block(server)
    } finally {
      server.stop(0)
    }
  }
}
