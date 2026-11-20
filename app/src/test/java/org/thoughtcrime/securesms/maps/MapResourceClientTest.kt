package org.thoughtcrime.securesms.maps

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thoughtcrime.securesms.BuildConfig
import org.thoughtcrime.securesms.net.Networking
import org.thoughtcrime.securesms.net.SocksProxy
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class MapResourceClientTest {
  private val previousEnabled = Networking.isEnabled
  private val previousProxy = Networking.socksProxy
  private val requests = mutableListOf<Request>()
  private lateinit var client: OkHttpClient

  @Before
  fun setUp() {
    Networking.isEnabled = true
    Networking.socksProxy = null
    client = MapResourceClient.create(ApplicationProvider.getApplicationContext()).newBuilder()
      // Exercise the real policy interceptor without making network connections.
      .addInterceptor { chain ->
        requests += chain.request()
        Response.Builder()
          .request(chain.request())
          .protocol(Protocol.HTTP_1_1)
          .code(200)
          .message("OK")
          .header("Cache-Control", "no-store, max-age=604800")
          .header("ETag", "tile-version")
          .header("Last-Modified", "Wed, 18 Nov 2026 12:00:00 GMT")
          .body(byteArrayOf(1).toResponseBody())
          .build()
      }
      .build()
  }

  @After
  fun tearDown() {
    Networking.isEnabled = previousEnabled
    Networking.socksProxy = previousProxy
    client.connectionPool.evictAll()
  }

  @Test
  fun `tile requests identify Collie without the SDK device fingerprint and retain cache validators`() {
    val request = Request.Builder()
      .url("https://tile.openstreetmap.org/14/8192/5461.png")
      .header("User-Agent", "MapLibre Android API device ABI fingerprint")
      .header("If-None-Match", "previous-tile-version")
      .header("If-Modified-Since", "Tue, 17 Nov 2026 12:00:00 GMT")
      .build()

    client.newCall(request).execute().use { response ->
      assertEquals("no-store, max-age=604800", response.header("Cache-Control"))
      assertEquals("tile-version", response.header("ETag"))
      assertEquals("Wed, 18 Nov 2026 12:00:00 GMT", response.header("Last-Modified"))
    }

    val sent = requests.single()
    assertEquals("Collie/${BuildConfig.VERSION_NAME} (+https://collie.im)", sent.header("User-Agent"))
    assertEquals("previous-tile-version", sent.header("If-None-Match"))
    assertEquals("Tue, 17 Nov 2026 12:00:00 GMT", sent.header("If-Modified-Since"))
    assertEquals(request.url, sent.url)
  }

  @Test
  fun `disabled networking blocks new calls on an existing client`() {
    client.newCall(tileRequest()).execute().close()
    Networking.isEnabled = false

    val failure = assertThrows(IOException::class.java) {
      client.newCall(tileRequest()).execute().close()
    }

    assertEquals("Map networking is disabled", failure.message)
    assertEquals(1, requests.size)
  }

  @Test
  fun `unavailable SOCKS proxy blocks new calls instead of falling back to direct access`() {
    client.newCall(tileRequest()).execute().close()
    Networking.socksProxy = SocksProxy("127.0.0.1", SocksProxy.INVALID_PORT)

    val failure = assertThrows(IOException::class.java) {
      client.newCall(tileRequest()).execute().close()
    }

    assertEquals("Map proxy is unavailable", failure.message)
    assertEquals(1, requests.size)
  }

  @Test
  fun `foreign hosts insecure URLs credentials and nonstandard ports are rejected without coordinate errors`() {
    val destinations = listOf(
      "http://tile.openstreetmap.org/14/8192/5461.png",
      "https://tile.openstreetmap.org.evil.example/14/8192/5461.png",
      "https://maps.example/14/8192/5461.png?token=secret",
      "https://tile.openstreetmap.org:8443/14/8192/5461.png",
      "https://username:password@tile.openstreetmap.org/14/8192/5461.png"
    )

    for (destination in destinations) {
      val failure = assertThrows(IOException::class.java) {
        client.newCall(Request.Builder().url(destination).build()).execute().close()
      }
      assertEquals("Map resource destination is not allowed", failure.message)
    }
    assertEquals(0, requests.size)
  }

  @Test
  fun `transport uses Collie routing without redirects multiplexing or a plaintext HTTP cache`() {
    assertSame(Networking.socketFactory, client.socketFactory)
    assertSame(Networking.proxySelectorForSocks, client.proxySelector)
    assertSame(Networking.dns, client.dns)
    assertEquals(listOf(Protocol.HTTP_1_1), client.protocols)
    assertFalse(client.followRedirects)
    assertFalse(client.followSslRedirects)
    assertFalse(client.retryOnConnectionFailure)
    assertNull(client.cache)
  }

  private fun tileRequest(): Request = Request.Builder()
    .url("https://tile.openstreetmap.org/14/8192/5461.png")
    .build()
}
