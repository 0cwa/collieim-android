package org.thoughtcrime.securesms.maps

import android.content.Context
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.thoughtcrime.securesms.util.EncryptedStreamUtils
import org.whispersystems.signalservice.internal.crypto.PaddingInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.DateTimeException
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/** Encrypted, bounded tile cache. Its filenames cannot be recovered across process sessions. */
class MapResourceCache internal constructor(
  private val context: Context,
  private val clock: () -> Long = System::currentTimeMillis,
  private val maxCacheBytes: Long = 32L * 1024 * 1024
) : Interceptor {
  companion object {
    private const val FORMAT_VERSION = 1
    internal const val MAX_TILE_BYTES = 1024 * 1024
    private const val MAX_HEADERS = 32
    private const val MAX_HEADER_LENGTH = 4096
    private const val MAX_RECORD_BYTES = MAX_TILE_BYTES + 64 * 1024
    private val DEFAULT_LIFETIME_MILLIS = TimeUnit.DAYS.toMillis(7)
    private val SESSION_SALT = ByteArray(32).also { SecureRandom().nextBytes(it) }
    private val LOCK = Any()
    private val TILE_PATH = Regex("/[0-9]{1,2}/[0-9]+/[0-9]+\\.png")
    private val STORED_HEADERS = setOf("Cache-Control", "Expires", "Date", "Age", "ETag", "Last-Modified", "Content-Type", "Vary")

    @JvmStatic
    fun getTileCacheDir(context: Context): File = File(context.cacheDir, "tiles")
  }

  private val directory = File(getTileCacheDir(context), "maplibre")

  override fun intercept(chain: Interceptor.Chain): Response {
    val request = chain.request()
    if (request.method != "GET" || request.url.host != "tile.openstreetmap.org" || !TILE_PATH.matches(request.url.encodedPath)) {
      return chain.proceed(request)
    }

    if (request.cacheControl.noStore) {
      remove(request)
      if (request.cacheControl.onlyIfCached) return cacheMiss(request)
      val response = bufferResponse(chain.proceed(request))
      return response.newBuilder()
        .headers(response.headers.forMapLibre("no-store, max-age=0, must-revalidate"))
        .build()
    }

    val cached = read(request)
    if (cached != null && !request.cacheControl.noCache && cached.isFresh(request, clock())) {
      return cached.response(request, clock())
    }
    if (request.cacheControl.onlyIfCached) {
      return cacheMiss(request)
    }

    val conditional = if (cached == null) request else request.newBuilder()
      .headers(
        request.headers.newBuilder()
          .removeAll("If-None-Match")
          .removeAll("If-Modified-Since")
          .apply {
            cached.headers["ETag"]?.let { addReceivedHeader("If-None-Match", it) }
            cached.headers["Last-Modified"]?.let { addReceivedHeader("If-Modified-Since", it) }
          }
          .build()
      )
      .build()

    val sentAt = clock()
    val networkResponse = chain.proceed(conditional)
    val receivedAt = clock()

    if (networkResponse.code == 304 && cached != null) {
      val updatedHeaders = networkResponse.use { response ->
        cached.headers.newBuilder().apply {
          for (name in STORED_HEADERS) {
            val values = response.headers.values(name)
            if (values.isNotEmpty()) {
              removeAll(name)
              values.forEach { addReceivedHeader(name, it) }
            }
          }
          // A validation response starts a new age calculation, even if it omits Date or Age.
          removeAll("Date")
          addReceivedHeader("Date", response.header("Date") ?: formatDate(receivedAt))
          removeAll("Age")
          addReceivedHeader("Age", response.header("Age") ?: "0")
        }.build()
      }
      val updated = entry(request, updatedHeaders, cached.body, sentAt, receivedAt)
      storeOrRemove(request, updated)
      return updated.response(request, clock())
    }

    val buffered = bufferResponse(networkResponse)
    if (buffered.code == 200) {
      val bytes = buffered.body.bytes()
      val updated = entry(request, buffered.headers, bytes, sentAt, receivedAt)
      storeOrRemove(request, updated)
      return buffered.newBuilder()
        .headers(buffered.headers.forMapLibre(updated.nativeCacheControl(clock())))
        .body(bytes.toResponseBody(buffered.body.contentType()))
        .build()
    }
    if (buffered.code == 404 || buffered.code == 410) remove(request)
    return buffered
  }

  private fun cacheMiss(request: Request): Response = Response.Builder()
    .request(request).protocol(Protocol.HTTP_1_1).code(504).message("Map tile is not cached")
    .body(ByteArray(0).toResponseBody()).build()

  private fun bufferResponse(response: Response): Response {
    try {
      if (response.body.contentLength() > MAX_TILE_BYTES) throw IOException("Map tile exceeds the size limit")
      val output = ByteArrayOutputStream()
      response.body.byteStream().use { input ->
        val buffer = ByteArray(8192)
        while (true) {
          val count = input.read(buffer)
          if (count == -1) break
          if (output.size() + count > MAX_TILE_BYTES) throw IOException("Map tile exceeds the size limit")
          output.write(buffer, 0, count)
        }
      }
      return response.newBuilder().body(output.toByteArray().toResponseBody(response.body.contentType())).build()
    } catch (e: IOException) {
      response.close()
      throw e
    }
  }

  private fun entry(request: Request, headers: Headers, body: ByteArray, sentAt: Long, receivedAt: Long): Entry {
    val stored = Headers.Builder().apply {
      for (name in STORED_HEADERS) headers.values(name).forEach { addReceivedHeader(name, it) }
    }.build()
    val vary = Headers.Builder().apply {
      for (name in varyNames(stored)) request.headers.values(name).forEach { addReceivedHeader(name, it) }
    }.build()
    val responseDate = parseDate(stored["Date"]) ?: receivedAt
    val age = stored["Age"]?.toLongOrNull()?.coerceIn(0, Long.MAX_VALUE / 1000)?.times(1000) ?: 0
    val initialAge = maxOf(positiveDifference(receivedAt, responseDate), saturatedAdd(age, positiveDifference(receivedAt, sentAt)))
    return Entry(stored, vary, body, receivedAt, initialAge)
  }

  private fun storeOrRemove(request: Request, entry: Entry) {
    if (entry.headers.toCacheControl().noStore || "*" in varyNames(entry.headers) || entry.body.isEmpty()) {
      remove(request)
    } else {
      write(request, entry)
    }
  }

  private fun file(request: Request): File {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update(SESSION_SALT)
    val name = digest.digest(request.url.toString().toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    return File(directory, name)
  }

  private fun read(request: Request): Entry? = synchronized(LOCK) {
    val file = file(request)
    if (!file.isFile) return@synchronized null
    try {
      // The stream helper reads the complete 32-byte key prefix before returning a closeable stream.
      if (file.length() < 32 || file.length() > MAX_RECORD_BYTES * 2L) throw IOException("Invalid map cache record")
      val entry = DataInputStream(EncryptedStreamUtils.getInputStream(context, file)).use { input ->
        if (input.readInt() != FORMAT_VERSION) throw IOException("Invalid map cache format")
        val receivedAt = input.readLong()
        val initialAge = input.readLong()
        if (receivedAt < 0 || initialAge < 0) throw IOException("Invalid map cache age")
        val headers = readHeaders(input)
        val vary = readHeaders(input)
        val length = input.readInt()
        if (length !in 1..MAX_TILE_BYTES) throw IOException("Invalid map cache size")
        val body = ByteArray(length)
        input.readFully(body)
        Entry(headers, vary, body, receivedAt, initialAge)
      }
      if (varyNames(entry.headers).any { entry.varyHeaders.values(it) != request.headers.values(it) }) return@synchronized null
      file.setLastModified(clock())
      entry
    } catch (e: IOException) {
      file.delete()
      null
    } catch (e: IllegalArgumentException) {
      file.delete()
      null
    }
  }

  private fun write(request: Request, entry: Entry) = synchronized(LOCK) {
    var temporary: File? = null
    try {
      val bytes = ByteArrayOutputStream().also { output ->
        DataOutputStream(output).use {
          it.writeInt(FORMAT_VERSION)
          it.writeLong(entry.receivedAt)
          it.writeLong(entry.initialAge)
          writeHeaders(it, entry.headers)
          writeHeaders(it, entry.varyHeaders)
          it.writeInt(entry.body.size)
          it.write(entry.body)
        }
      }.toByteArray()
      if (bytes.size > MAX_RECORD_BYTES || PaddingInputStream.getPaddedSize(bytes.size.toLong()) + 32 > maxCacheBytes) return@synchronized
      if (!directory.isDirectory && !directory.mkdirs()) return@synchronized
      temporary = File.createTempFile("tile-", ".tmp", directory)
      EncryptedStreamUtils.getOutputStream(context, temporary).use { output ->
        PaddingInputStream(bytes.inputStream(), bytes.size.toLong()).use { it.copyTo(output) }
      }
      val destination = file(request)
      if (!temporary.renameTo(destination)) throw IOException("Unable to store map cache record")
      destination.setLastModified(clock())
      val files = directory.listFiles()?.filter { it.isFile }?.sortedBy { it.lastModified() }.orEmpty()
      var total = files.sumOf { it.length() }
      for (candidate in files) {
        if (total <= maxCacheBytes) break
        val size = candidate.length()
        if (candidate.delete()) total -= size
      }
    } catch (e: IOException) {
      // Cache failures must not turn a successful tile request into a failed map.
    } finally {
      temporary?.delete()
    }
  }

  private fun remove(request: Request) = synchronized(LOCK) { file(request).delete() }

  private fun readHeaders(input: DataInputStream): Headers {
    val count = input.readInt()
    if (count !in 0..MAX_HEADERS) throw IOException("Invalid map cache headers")
    return Headers.Builder().apply {
      repeat(count) {
        val name = input.readUTF()
        val value = input.readUTF()
        if (name.length > MAX_HEADER_LENGTH || value.length > MAX_HEADER_LENGTH) throw IOException("Invalid map cache header")
        addReceivedHeader(name, value)
      }
    }.build()
  }

  private fun writeHeaders(output: DataOutputStream, headers: Headers) {
    if (headers.size > MAX_HEADERS) throw IOException("Too many map cache headers")
    output.writeInt(headers.size)
    for (index in 0 until headers.size) {
      if (headers.name(index).length > MAX_HEADER_LENGTH || headers.value(index).length > MAX_HEADER_LENGTH) throw IOException("Map cache header is too large")
      output.writeUTF(headers.name(index))
      output.writeUTF(headers.value(index))
    }
  }

  private data class Entry(val headers: Headers, val varyHeaders: Headers, val body: ByteArray, val receivedAt: Long, val initialAge: Long) {
    fun age(now: Long): Long = saturatedAdd(initialAge, positiveDifference(now, receivedAt))

    private fun lifetime(): Long {
      val control = headers.toCacheControl()
      if (control.noCache || control.noStore) return 0
      return when {
        control.maxAgeSeconds >= 0 -> control.maxAgeSeconds * 1000L
        headers["Expires"] != null -> positiveDifference(parseDate(headers["Expires"]) ?: 0, parseDate(headers["Date"]) ?: receivedAt)
        else -> DEFAULT_LIFETIME_MILLIS
      }
    }

    fun isFresh(request: Request, now: Long): Boolean {
      val requestedLifetime = if (request.cacheControl.maxAgeSeconds >= 0) request.cacheControl.maxAgeSeconds * 1000L else Long.MAX_VALUE
      return age(now) < minOf(lifetime(), requestedLifetime)
    }

    fun nativeCacheControl(now: Long): String {
      val control = headers.toCacheControl()
      val prefix = when {
        control.noStore -> "no-store, "
        control.noCache -> "no-cache, "
        else -> ""
      }
      val remainingSeconds = (positiveDifference(lifetime(), age(now)) / 1000).coerceAtMost(Int.MAX_VALUE.toLong())
      return "${prefix}max-age=$remainingSeconds, must-revalidate"
    }

    fun response(request: Request, now: Long): Response = Response.Builder()
      .request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
      .headers(headers.forMapLibre(nativeCacheControl(now))).header("Age", (age(now) / 1000).toString())
      .body(body.toResponseBody(headers["Content-Type"]?.toMediaTypeOrNull()))
      .sentRequestAtMillis(receivedAt).receivedResponseAtMillis(receivedAt).build()
  }
}

private fun Headers.toCacheControl() = okhttp3.CacheControl.parse(this)

/** MapLibre 13.7.1 drops Age/Date, ignores no-cache, and lets Expires override max-age. */
private fun Headers.forMapLibre(cacheControl: String): Headers = newBuilder()
  .set("Cache-Control", cacheControl)
  .removeAll("Expires")
  .apply {
    // Its request builder also rejects non-ASCII validators. This cache can replay them itself.
    for (name in listOf("ETag", "Last-Modified")) {
      if (values(name).any { value -> value.any { it != '\t' && it !in ' '..'~' } }) removeAll(name)
    }
  }
  .build()

private fun Headers.Builder.addReceivedHeader(name: String, value: String): Headers.Builder {
  if (value.any { it != '\t' && (it < ' ' || it == '\u007f') }) throw IOException("Invalid map cache header")
  return addUnsafeNonAscii(name, value)
}

private fun varyNames(headers: Headers): Set<String> = headers.values("Vary").flatMap { it.split(',') }.map { it.trim() }.filter { it.isNotEmpty() }.toSet()

private fun parseDate(value: String?): Long? = try {
  value?.let { ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }
} catch (e: DateTimeException) {
  null
} catch (e: ArithmeticException) {
  null
}

private fun formatDate(value: Long): String = DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.ofEpochMilli(value).atZone(ZoneOffset.UTC))

private fun positiveDifference(later: Long, earlier: Long): Long = if (later <= earlier) 0 else (later - earlier).takeIf { it >= 0 } ?: Long.MAX_VALUE

private fun saturatedAdd(left: Long, right: Long): Long = if (left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right
