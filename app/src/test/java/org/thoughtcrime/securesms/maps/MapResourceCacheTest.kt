package org.thoughtcrime.securesms.maps

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    client.newCall(request("/1/0/0.png").newBuilder().header("Cache-Control", "only-if-cached").build()).execute().use {
      assertEquals(504, it.code)
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

  private fun reply(code: Int = 200, headers: Map<String, String> = emptyMap(), body: ByteArray = tile, unknownLength: Boolean = false) {
    replies.addLast { request ->
      val responseBody = if (unknownLength) object : ResponseBody() {
        private val buffer = Buffer().write(body)
        override fun contentType() = null
        override fun contentLength() = -1L
        override fun source() = buffer
      } else body.toResponseBody()
      Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("Test tile response")
        .body(responseBody).apply { headers.forEach { (name, value) -> header(name, value) } }.build()
    }
  }

  private fun request(path: String = "/1/1/1.png"): Request = Request.Builder().url("https://tile.openstreetmap.org$path").build()

  private fun fetch(client: OkHttpClient, request: Request = request()): ByteArray = client.newCall(request).execute().use { it.body.bytes() }

  private fun cacheFiles(): List<File> = File(MapResourceCache.getTileCacheDir(context), "maplibre").listFiles()?.filter { it.isFile }.orEmpty()

  private fun date(time: Long): String = DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.ofEpochMilli(time).atZone(ZoneOffset.UTC))
}
