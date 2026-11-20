package org.thoughtcrime.securesms.maps

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.util.crypto.AttachmentSecret
import org.signal.core.util.crypto.ModernDecryptingPartInputStream
import org.signal.core.util.crypto.ModernEncryptingPartOutputStream
import org.thoughtcrime.securesms.net.Networking
import org.thoughtcrime.securesms.util.EncryptedStreamUtils
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class MapResourceCacheTest {
  @get:Rule val temporary = TemporaryFolder()

  private val previousEnabled = Networking.isEnabled
  private val previousProxy = Networking.socksProxy
  private val requests = mutableListOf<Request>()
  private val replies = ArrayDeque<(Request) -> Response>()
  private val tile = "private-tile-pixels".toByteArray()
  private val secret = AttachmentSecret(null, null, ByteArray(32) { it.toByte() })
  private var now = Instant.parse("2026-11-20T12:00:00Z").toEpochMilli()
  private lateinit var context: Context

  @Before
  fun setUp() {
    context = object : ContextWrapper(ApplicationProvider.getApplicationContext()) {
      override fun getCacheDir(): File = temporary.root
      override fun getApplicationContext(): Context = this
    }
    // Keep the production encrypted stream format, using a test key instead of Android's keystore.
    mockkObject(EncryptedStreamUtils)
    every { EncryptedStreamUtils.getOutputStream(any(), any()) } answers {
      ModernEncryptingPartOutputStream.createFor(secret, secondArg<File>(), true).second
    }
    every { EncryptedStreamUtils.getInputStream(any(), any()) } answers {
      ModernDecryptingPartInputStream.createFor(secret, secondArg<File>(), 0)
    }
  }

  @After
  fun tearDown() {
    Networking.isEnabled = previousEnabled
    Networking.socksProxy = previousProxy
    unmockkAll()
  }

  @Test
  fun `fresh tiles and their metadata are reused from encrypted opaque files`() {
    val client = client()
    reply(headers = mapOf("Cache-Control" to "max-age=60", "ETag" to "private-etag"))
    assertArrayEquals(tile, fetch(client))
    assertArrayEquals(tile, fetch(client))
    assertEquals(1, requests.size)

    val file = cacheFiles().single()
    assertTrue(file.name.matches(Regex("[0-9a-f]{64}")))
    val diskBytes = file.readBytes().toString(Charsets.ISO_8859_1)
    assertFalse(diskBytes.contains("private-tile-pixels"))
    assertFalse(diskBytes.contains("private-etag"))
    assertFalse(diskBytes.contains("openstreetmap"))
    val decrypted = ModernDecryptingPartInputStream.createFor(secret, file, 0).use { it.readBytes() }
    assertTrue(decrypted.toString(Charsets.ISO_8859_1).contains("private-etag"))
    assertTrue(decrypted.toString(Charsets.ISO_8859_1).contains("private-tile-pixels"))
  }

  @Test
  fun `expired tiles revalidate with validators and a 304 renews their encrypted record`() {
    val client = client()
    reply(headers = mapOf("Cache-Control" to "max-age=60", "ETag" to "tile-v1", "Last-Modified" to date(now - 100_000)))
    fetch(client)
    now += 61_000
    reply(code = 304, headers = mapOf("Cache-Control" to "max-age=120"), body = ByteArray(0))

    assertArrayEquals(tile, fetch(client))
    assertEquals("tile-v1", requests.last().header("If-None-Match"))
    assertEquals(date(now - 161_000), requests.last().header("If-Modified-Since"))
    now += 119_000
    assertArrayEquals(tile, fetch(client))
    assertEquals(2, requests.size)
    assertEquals(1, cacheFiles().size)
  }

  @Test
  fun `cache misses discard native validators and store the complete response`() {
    val client = client()
    val nativeRequest = request().newBuilder()
      .header("If-None-Match", "evicted-native-tile")
      .header("If-Modified-Since", date(now - 100_000))
      .build()
    reply(headers = mapOf("ETag" to "current-tile"))
    assertArrayEquals(tile, fetch(client, nativeRequest))
    assertNull(requests.single().header("If-None-Match"))
    assertNull(requests.single().header("If-Modified-Since"))
    assertEquals(1, cacheFiles().size)
    assertArrayEquals(tile, fetch(client, nativeRequest))
    assertEquals(1, requests.size)
  }

  @Test
  fun `non-tile and explicit no-store requests keep caller managed validators`() {
    val client = client()
    val bypasses = listOf(
      request().newBuilder().header("Cache-Control", "no-store").build(),
      request("/metadata.json")
    )
    for (bypass in bypasses) {
      reply()
      fetch(client, bypass.newBuilder().header("If-None-Match", "caller-validator").build())
      assertEquals("caller-validator", requests.last().header("If-None-Match"))
    }
    assertTrue(cacheFiles().isEmpty())
  }

  @Test
  fun `an unsolicited 304 without a cached body is closed and rejected`() {
    val client = client()
    var closed = false
    reply(code = 304, body = ByteArray(0), onClose = { closed = true })
    assertThrows(IOException::class.java) { fetch(client) }
    assertTrue(closed)
    assertTrue(cacheFiles().isEmpty())
  }

  @Test
  fun `Expires Date and Age limit freshness instead of applying the fallback lifetime`() {
    val client = client()
    reply(headers = mapOf("Date" to date(now - 30_000), "Expires" to date(now + 30_000), "Age" to "50"))
    fetch(client)
    now += 9_000
    fetch(client)
    assertEquals(1, requests.size)
    now += 2_000
    reply()
    fetch(client)
    assertEquals(2, requests.size)
  }

  @Test
  fun `MapLibre receives remaining freshness without an Expires override`() {
    val client = client()
    reply(headers = mapOf("Cache-Control" to "max-age=60", "Age" to "45", "Expires" to date(now + TimeUnit.DAYS.toMillis(1))))
    client.newCall(request()).execute().use {
      assertEquals("max-age=15, must-revalidate", it.header("Cache-Control"))
      assertNull(it.header("Expires"))
    }
    now += 10_000
    client.newCall(request()).execute().use {
      assertEquals("max-age=5, must-revalidate", it.header("Cache-Control"))
      assertNull(it.header("Expires"))
    }
    now += 6_000
    reply(code = 304, headers = mapOf("Cache-Control" to "max-age=120", "Age" to "30", "Expires" to date(now + TimeUnit.DAYS.toMillis(1))), body = ByteArray(0))
    client.newCall(request()).execute().use {
      assertEquals("max-age=90, must-revalidate", it.header("Cache-Control"))
      assertNull(it.header("Expires"))
      assertArrayEquals(tile, it.body.bytes())
    }
  }

  @Test
  fun `non-ASCII validators are retained for cache revalidation without reaching the SDK request builder`() {
    val client = client()
    reply(headers = mapOf("Cache-Control" to "max-age=0", "ETag" to "\"café\""))
    client.newCall(request()).execute().use {
      assertNull(it.header("ETag"))
      assertArrayEquals(tile, it.body.bytes())
    }
    reply(code = 304, headers = mapOf("Cache-Control" to "max-age=60", "ETag" to "\"café\""), body = ByteArray(0))
    assertArrayEquals(tile, fetch(client))
    assertEquals("\"café\"", requests.last().header("If-None-Match"))
    assertArrayEquals(tile, fetch(client))
    assertEquals(2, requests.size)
  }

  @Test
  fun `time spent downloading the body is removed from native freshness`() {
    val client = client()
    reply(headers = mapOf("Cache-Control" to "max-age=60"), onClose = { now += 20_000 })
    client.newCall(request()).execute().use {
      assertEquals("max-age=40, must-revalidate", it.header("Cache-Control"))
      assertArrayEquals(tile, it.body.bytes())
    }
  }

  @Test
  fun `malformed validation metadata closes the network response`() {
    val client = client()
    reply(headers = mapOf("Cache-Control" to "max-age=0", "ETag" to "tile-v1"))
    fetch(client)
    var closed = false
    reply(code = 304, headers = mapOf("ETag" to "invalid\u0000etag"), body = ByteArray(0), onClose = { closed = true })
    assertThrows(IOException::class.java) { fetch(client) }
    assertTrue(closed)
  }

  @Test
  fun `responses without expiry information receive a seven day lifetime`() {
    val client = client()
    reply()
    fetch(client)
    now += TimeUnit.DAYS.toMillis(7) - 1
    fetch(client)
    assertEquals(1, requests.size)
    now++
    reply()
    fetch(client)
    assertEquals(2, requests.size)
  }

  @Test
  fun `no-cache forces validation while no-store removes the cached representation`() {
    val client = client()
    reply(headers = mapOf("Cache-Control" to "no-cache", "ETag" to "tile-v1"))
    fetch(client)
    reply(code = 304, headers = mapOf("Cache-Control" to "no-store"), body = ByteArray(0))
    assertArrayEquals(tile, fetch(client))
    assertEquals("tile-v1", requests.last().header("If-None-Match"))
    assertTrue(cacheFiles().isEmpty())
    reply(headers = mapOf("Cache-Control" to "no-store"))
    fetch(client)
    assertEquals(3, requests.size)
    assertTrue(cacheFiles().isEmpty())
  }

  @Test
  fun `request no-cache bypasses a fresh entry and only-if-cached does not access the network`() {
    val client = client()
    reply(headers = mapOf("ETag" to "tile-v1"))
    fetch(client)
    reply(code = 304, body = ByteArray(0))
    fetch(client, request().newBuilder().header("Cache-Control", "no-cache").build())
    assertEquals("tile-v1", requests.last().header("If-None-Match"))
    for (control in listOf("only-if-cached", "only-if-cached, no-store")) {
      client.newCall(request("/1/0/0.png").newBuilder().header("Cache-Control", control).build()).execute().use {
        assertEquals(504, it.code)
      }
    }
    assertEquals(2, requests.size)
  }

  @Test
  fun `Vary request headers must match and Vary star is never stored`() {
    val client = client()
    val english = request().newBuilder().header("Accept-Language", "en").build()
    val french = request().newBuilder().header("Accept-Language", "fr").build()
    reply(headers = mapOf("Vary" to "Accept-Language"))
    fetch(client, english)
    fetch(client, english)
    reply(headers = mapOf("Vary" to "*"))
    fetch(client, french)
    assertEquals(2, requests.size)
    assertTrue(cacheFiles().isEmpty())
  }

  @Test
  fun `both declared and streaming oversized responses fail without leaving cache files`() {
    for (unknownLength in listOf(false, true)) {
      val client = client()
      reply(body = ByteArray(MapResourceCache.MAX_TILE_BYTES + 1), unknownLength = unknownLength)
      val failure = assertThrows(IOException::class.java) { fetch(client) }
      assertEquals("Map tile exceeds the size limit", failure.message)
      assertTrue(cacheFiles().isEmpty())
    }
  }

  @Test
  fun `cache budget evicts older encrypted records and retains the most recent tile`() {
    val client = client(maxBytes = 3000)
    val pixels = ByteArray(1600) { it.toByte() }
    reply(body = pixels)
    fetch(client)
    now++
    reply(body = pixels)
    fetch(client, request("/1/0/1.png"))
    assertTrue(cacheFiles().sumOf { it.length() } <= 3000)
    assertEquals(1, cacheFiles().size)
    fetch(client, request("/1/0/1.png"))
    assertEquals(2, requests.size)
    reply(body = pixels)
    fetch(client)
    assertEquals(3, requests.size)
  }

  @Test
  fun `an interrupted or corrupt record is removed and fetched again`() {
    val client = client()
    reply()
    fetch(client)
    cacheFiles().single().writeBytes(byteArrayOf(1, 2, 3))
    reply()
    assertArrayEquals(tile, fetch(client))
    assertEquals(2, requests.size)
    assertEquals(1, cacheFiles().size)
    verify(exactly = 0) { EncryptedStreamUtils.getInputStream(any(), any()) }
  }

  @Test
  fun `network policy is enforced before reading an already cached tile`() {
    Networking.isEnabled = true
    Networking.socksProxy = null
    val client = withTransport(MapResourceClient.create(context).newBuilder())
    reply()
    fetch(client)
    Networking.isEnabled = false

    assertThrows(IOException::class.java) { fetch(client) }
    assertEquals(1, requests.size)
    verify(exactly = 0) { EncryptedStreamUtils.getInputStream(any(), any()) }
  }

  private fun client(maxBytes: Long = 32L * 1024 * 1024): OkHttpClient = withTransport(
    OkHttpClient.Builder().addInterceptor(MapResourceCache(context, { now }, maxBytes))
  )

  private fun withTransport(builder: OkHttpClient.Builder): OkHttpClient = builder.addInterceptor { chain ->
    requests += chain.request()
    check(replies.isNotEmpty()) { "Unexpected map network request" }
    replies.removeFirst().invoke(chain.request())
  }.build()

  private fun reply(code: Int = 200, headers: Map<String, String> = emptyMap(), body: ByteArray = tile, unknownLength: Boolean = false, onClose: () -> Unit = {}) {
    replies.addLast { request ->
      val responseBody = object : ResponseBody() {
        private val source = object : ForwardingSource(Buffer().write(body)) {
          override fun close() {
            onClose()
            super.close()
          }
        }.buffer()
        override fun contentType() = null
        override fun contentLength() = if (unknownLength) -1L else body.size.toLong()
        override fun source() = source
      }
      val responseHeaders = Headers.Builder().apply { headers.forEach { (name, value) -> addUnsafeNonAscii(name, value) } }.build()
      Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("Test tile response")
        .headers(responseHeaders).body(responseBody).build()
    }
  }

  private fun request(path: String = "/1/1/1.png"): Request = Request.Builder().url("https://tile.openstreetmap.org$path").build()

  private fun fetch(client: OkHttpClient, request: Request = request()): ByteArray = client.newCall(request).execute().use { it.body.bytes() }

  private fun cacheFiles(): List<File> = File(MapResourceCache.getTileCacheDir(context), "maplibre").listFiles()?.filter { it.isFile }.orEmpty()

  private fun date(time: Long): String = DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.ofEpochMilli(time).atZone(ZoneOffset.UTC))
}
