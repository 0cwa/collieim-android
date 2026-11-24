package org.thoughtcrime.securesms.maps

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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
      .header("Cache-Control", "no-store")
      .header("User-Agent", "MapLibre Android API device ABI fingerprint")
      .header("If-None-Match", "previous-tile-version")
      .header("If-Modified-Since", "Tue, 17 Nov 2026 12:00:00 GMT")
      .build()

    client.newCall(request).execute().use { response ->
      assertEquals("no-store, max-age=0, must-revalidate", response.header("Cache-Control"))
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
      "https://username:password@tile.openstreetmap.org/14/8192/5461.png",
      "http://vector.openstreetmap.org/shortbread_v1/14/4823/6160.mvt",
      "https://vector.openstreetmap.org.evil.example/shortbread_v1/14/4823/6160.mvt",
      "https://vector.openstreetmap.org:8443/shortbread_v1/14/4823/6160.mvt",
      "https://username:password@vector.openstreetmap.org/shortbread_v1/14/4823/6160.mvt"
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
  fun `only the raster and Shortbread tile templates accept valid XYZ coordinates`() {
    val urls = listOf(
      tileUrl(MapResourceClient.RASTER_TILE_TEMPLATE, 0, 0, 0),
      tileUrl(MapResourceClient.RASTER_TILE_TEMPLATE, 19, 524287, 524287),
      tileUrl(MapResourceClient.TRANSIT_TILE_TEMPLATE, 0, 0, 0),
      tileUrl(MapResourceClient.TRANSIT_TILE_TEMPLATE, 14, 16383, 16383)
    )
    for (url in urls) {
      client.newCall(Request.Builder().url(url).header("Cache-Control", "no-store").build()).execute().close()
    }
    assertEquals(urls, requests.map { it.url.toString() })
  }

  @Test
  fun `remote styles wrong schemas malformed coordinates and tracking parameters are rejected`() {
    val urls = listOf(
      "https://tile.openstreetmap.org/style.json",
      "https://tile.openstreetmap.org/20/1/1.png",
      "https://tile.openstreetmap.org/0/1/0.png",
      "https://tile.openstreetmap.org/19/0/524288.png",
      "https://tile.openstreetmap.org/01/1/1.png",
      "https://tile.openstreetmap.org/1/%31/1.png",
      "https://tile.openstreetmap.org/1/1/1.png?tracking=value",
      "https://tile.openstreetmap.org/1/1/1.png#fragment",
      "https://vector.openstreetmap.org/shortbread_v1/tilejson.json",
      "https://vector.openstreetmap.org/shortbread_v2/14/4823/6160.mvt",
      "https://vector.openstreetmap.org/shortbread_v1/14/4823/6160.pbf",
      "https://vector.openstreetmap.org/shortbread_v1/15/4823/6160.mvt",
      "https://vector.openstreetmap.org/shortbread_v1/14/16384/0.mvt",
      "https://vector.openstreetmap.org/shortbread_v1/14/0/16384.mvt",
      "https://vector.openstreetmap.org/shortbread_v1/-1/0/0.mvt",
      "https://vector.openstreetmap.org/shortbread_v1/14/04823/6160.mvt",
      "https://vector.openstreetmap.org/shortbread_v1/14/4823/6160.mvt?token=secret",
      "https://vector.openstreetmap.org/shortbread_v1/14/4823/6160.mvt#fragment",
      "https://vector.openstreetmap.org/14/4823/6160.png",
      "https://tile.openstreetmap.org/shortbread_v1/14/4823/6160.mvt"
    )
    for (url in urls) {
      val failure = assertThrows(IOException::class.java) {
        client.newCall(Request.Builder().url(url).build()).execute().close()
      }
      assertEquals("Map resource destination is not allowed", failure.message)
    }
    assertEquals(0, requests.size)
  }

  @Test
  fun `map transport accepts GET requests only`() {
    val requestsToReject = listOf(
      tileRequest().newBuilder().head().build(),
      tileRequest().newBuilder().post(ByteArray(0).toRequestBody()).build()
    )
    for (request in requestsToReject) {
      assertThrows(IOException::class.java) { client.newCall(request).execute().close() }
    }
    assertEquals(0, requests.size)
  }

  @Test
  fun `a proxy transition during connection setup prevents sending tile request headers`() {
    client.newCall(tileRequest()).execute().close()
    val chain = mockk<Interceptor.Chain>()
    every { chain.request() } returns requests.single()
    Networking.socksProxy = SocksProxy("127.0.0.1", 9050)

    val failure = assertThrows(IOException::class.java) {
      client.networkInterceptors.single().intercept(chain)
    }

    assertEquals("Map network route changed", failure.message)
    verify(exactly = 0) { chain.proceed(any()) }
  }

  @Test
  fun `an unchanged proxy route allows the request after connection setup`() {
    client.newCall(tileRequest()).execute().close()
    val chain = mockk<Interceptor.Chain>()
    val request = requests.single()
    val response = Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
      .code(200).message("OK").body(ByteArray(0).toResponseBody()).build()
    every { chain.request() } returns request
    every { chain.proceed(request) } returns response

    client.networkInterceptors.single().intercept(chain).use { assertSame(response, it) }

    verify(exactly = 1) { chain.proceed(request) }
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

  private fun tileUrl(template: String, zoom: Int, x: Int, y: Int): String = template
    .replace("{z}", zoom.toString()).replace("{x}", x.toString()).replace("{y}", y.toString())
}
