/*
 * Copyright 2026 Collie contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package org.thoughtcrime.securesms.backup.v2.barkup

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import okio.HashingSink
import okio.blackholeSink
import okio.buffer
import okio.source
import org.signal.network.util.JsonUtil
import org.thoughtcrime.securesms.net.Networking
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.concurrent.TimeUnit

internal class BarkupOffer private constructor(val api: HttpUrl, val token: String) {
  companion object {
    internal fun parse(text: String, now: Instant = Instant.now()): BarkupOffer {
      if (text.length > 4096) throw IOException("Barkup offer exceeds size limit")
      val node = barkupJson(text)
      if (node.fieldNames().asSequence().toSet() != setOf("collie_barkup_pairing", "url", "token", "expires_at") ||
        !node.path("collie_barkup_pairing").isInt || node.path("collie_barkup_pairing").asInt() != 1
      ) {
        throw IOException("Invalid Barkup pairing offer")
      }
      val expires = try {
        val expiration = node.text("expires_at")
        if (!BARKUP_EXPIRY.matches(expiration)) throw IOException("Invalid Barkup offer expiry")
        Instant.parse(expiration)
      } catch (_: DateTimeParseException) {
        throw IOException("Invalid Barkup offer expiry")
      }
      if (!expires.isAfter(now)) throw IOException("Barkup pairing offer expired")
      return BarkupOffer(barkupApi(node.text("url")), node.bearer("token"))
    }
  }
}

internal class BarkupCredentials(val deviceId: String, val token: String)

internal val BARKUP_MEDIA_NAME = Regex("[a-f0-9]{64}")
internal val BARKUP_TIMESTAMP = Regex("[0-9]{4}(-[0-9]{2}){5}")
private val BARKUP_UPLOAD_ID = Regex("[A-Za-z0-9]{16}")
private val BARKUP_DEVICE_ID = Regex("[A-Za-z0-9]{12}")
private val BARKUP_EXPIRY = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z")
private val BARKUP_LABEL = Regex("[A-Za-z0-9 _.\\-]{1,64}")
private val BEARER_TOKEN = Regex("[A-Za-z0-9._~+/-]{1,512}={0,2}")
private val BARKUP_JSON = ObjectMapper()
  .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
  .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)

internal fun barkupApi(value: String): HttpUrl {
  val url = value.toHttpUrlOrNull() ?: throw IOException("Invalid Barkup URL")
  if (!url.isHttps || url.query != null || url.fragment != null || url.encodedUsername.isNotEmpty() ||
    url.encodedPassword.isNotEmpty() || value.contains('@') || value.contains('\\') || value.any { it.isWhitespace() || it.isISOControl() }
  ) {
    throw IOException("Barkup requires an HTTPS URL without credentials, a query, or a fragment")
  }

  val path = url.encodedPath.trimEnd('/')
  return url.newBuilder().encodedPath(if (path.endsWith("/v1")) path else "$path/v1").build()
}

internal fun barkupJson(value: String): JsonNode {
  return try {
    BARKUP_JSON.readTree(value) ?: throw IOException("Missing Barkup JSON")
  } catch (_: IOException) {
    throw IOException("Invalid Barkup JSON")
  }
}

private fun JsonNode.text(name: String): String = path(name).textValue() ?: throw IOException("Missing Barkup field")

private fun JsonNode.bearer(name: String): String {
  return text(name).takeIf(BEARER_TOKEN::matches) ?: throw IOException("Invalid Barkup token")
}

/** Barkup v1 uses one service origin. Each request rechecks the caller's enrollment. */
internal class BarkupClient(
  private val api: HttpUrl,
  private val token: String? = null,
  private val http: OkHttpClient = newHttpClient(),
  private val checkActive: () -> Unit = {}
) {
  companion object {
    private val JSON = "application/json".toMediaType()
    private val BINARY = "application/octet-stream".toMediaType()
    private val EMPTY_BODY = ByteArray(0).toRequestBody()
    private const val MAX_CHUNK = 32 * 1024 * 1024
    private const val SEND_CHUNK = 4 * 1024 * 1024
    private const val MAX_CONTROL_RESPONSE = 16 * 1024 * 1024L

    internal fun newHttpClient(): OkHttpClient = OkHttpClient.Builder()
      .socketFactory(Networking.socketFactory)
      .proxySelector(Networking.proxySelectorForSocks)
      .dns(Networking.dns)
      .followRedirects(false)
      .followSslRedirects(false)
      .retryOnConnectionFailure(false)
      .callTimeout(90, TimeUnit.SECONDS)
      .build()
  }

  init {
    require(api.isHttps && api.query == null && api.fragment == null && api.username.isEmpty() && api.password.isEmpty())
    require(token == null || BEARER_TOKEN.matches(token))
  }

  fun redeem(offer: BarkupOffer): BarkupCredentials {
    require(api == offer.api && token == null)
    return execute("POST", "pair/redeem", json(mapOf("token" to offer.token))) { response ->
      response.expect(200)
      val node = response.json()
      val deviceId = node.text("device_id")
      if (!node.path("version").isInt || node.path("version").asInt() != 1 || !BARKUP_DEVICE_ID.matches(deviceId) || !BARKUP_LABEL.matches(node.text("label"))) {
        throw IOException("Invalid Barkup pairing response")
      }
      BarkupCredentials(deviceId, node.bearer("token"))
    }
  }

  fun mediaInventory(): Map<String, Long> = execute("GET", "files") { response ->
    response.expect(200)
    val files = response.json().path("files")
    if (!files.isObject) throw IOException("Invalid Barkup inventory")
    files.fields().asSequence().associate { (name, length) ->
      if (!BARKUP_MEDIA_NAME.matches(name) || !length.isIntegralNumber || !length.canConvertToLong() || length.asLong() <= 0) {
        throw IOException("Invalid Barkup inventory item")
      }
      name to length.asLong()
    }
  }

  fun snapshots(): List<String> = execute("GET", "snapshots") { response ->
    response.expect(200)
    val list = response.json()
    if (!list.isArray) throw IOException("Invalid Barkup snapshot list")
    list.map { item ->
      val name = item.textValue() ?: throw IOException("Invalid Barkup snapshot name")
      val timestamp = name.removePrefix("signal-backup-")
      if (timestamp == name || !BARKUP_TIMESTAMP.matches(timestamp)) throw IOException("Invalid Barkup snapshot name")
      timestamp
    }
  }

  fun beginSnapshot(timestamp: String) {
    require(BARKUP_TIMESTAMP.matches(timestamp))
    execute("POST", "snapshots/$timestamp", EMPTY_BODY) { it.expect(201) }
  }

  fun uploadMedia(name: String, ciphertext: File, session: BarkupUploadSession) {
    require(BARKUP_MEDIA_NAME.matches(name))
    val path = "files/${name.take(2)}/$name"
    upload("uploads", mapOf("name" to name, "length" to ciphertext.length()), ciphertext, session) {
      sameBytes(path, ciphertext)
    }
  }

  fun uploadMember(timestamp: String, member: String, file: File, session: BarkupUploadSession) {
    require(BARKUP_TIMESTAMP.matches(timestamp) && member in setOf("metadata", "main", "files"))
    if (file.length() <= SEND_CHUNK) {
      execute("PUT", "snapshots/$timestamp/$member", file.asRequestBody(BINARY)) { it.expect(204) }
    } else {
      upload("snapshots/$timestamp/uploads/$member", mapOf("length" to file.length()), file, session) { false }
    }
  }

  fun finalizeSnapshot(timestamp: String, members: Map<String, File>) {
    require(BARKUP_TIMESTAMP.matches(timestamp))
    val created = execute("POST", "snapshots/$timestamp/finalize", EMPTY_BODY) { response ->
      response.expect(200, 201)
      response.code == 201
    }
    if (created) {
      if (timestamp !in snapshots()) throw IOException("Barkup did not retain the new snapshot")
    } else if (!matchesSnapshot(timestamp, members)) {
      throw IOException("Barkup snapshot does not match the staged archive")
    }
  }

  fun cancel() = http.dispatcher.cancelAll()

  fun matchesSnapshot(timestamp: String, members: Map<String, File>): Boolean {
    require(BARKUP_TIMESTAMP.matches(timestamp) && members.keys == setOf("metadata", "main", "files"))
    return timestamp in snapshots() && members.all { (member, file) -> sameBytes("snapshots/$timestamp/$member", file) }
  }

  private fun upload(startPath: String, startFields: Map<String, Any>, file: File, session: BarkupUploadSession, recoverCommit: () -> Boolean) {
    val length = file.length()
    require(length > 0)
    val previous = session.read()
    val previousOffset = previous?.let(::uploadOffset)
    if (previous != null && previousOffset == null && recoverCommit()) {
      session.clear()
      return
    }
    val id = if (previousOffset != null) {
      checkNotNull(previous)
    } else {
      execute("POST", startPath, json(startFields)) { response ->
        response.expect(201)
        val info = response.json()
        val newId = info.text("id")
        if (!BARKUP_UPLOAD_ID.matches(newId) || info.long("offset") != 0L || info.long("chunk_max") != MAX_CHUNK.toLong()) {
          throw IOException("Invalid Barkup upload session")
        }
        session.save(newId)
        newId
      }
    }
    var offset = previousOffset ?: 0L
    if (offset !in 0..length) throw IOException("Invalid Barkup upload offset")

    while (offset < length) {
      val count = minOf(length - offset, SEND_CHUNK.toLong())
      val nextOffset = offset + count
      val recorded = execute("PATCH", "uploads/$id", FileChunk(file, offset, count), offset) { response ->
        response.expect(204)
        response.uploadOffset()
      }
      if (recorded != nextOffset) throw IOException("Unexpected Barkup upload offset")
      offset = recorded
    }

    execute("POST", "uploads/$id/commit", EMPTY_BODY) { it.expect(200, 201) }
    session.clear()
  }

  private fun uploadOffset(id: String): Long? {
    if (!BARKUP_UPLOAD_ID.matches(id)) throw IOException("Invalid saved Barkup upload session")
    return execute("HEAD", "uploads/$id") { response ->
      if (response.code == 404) {
        null
      } else {
        response.expect(204)
        response.uploadOffset()
      }
    }
  }

  private fun sameBytes(path: String, file: File): Boolean = execute("GET", path) { response ->
    if (response.code == 404) return@execute false
    response.expect(200)
    val length = file.length()
    val body = response.body ?: throw IOException("Missing Barkup data")
    if (body.contentLength() != length) return@execute false

    val expected = HashingSink.sha256(blackholeSink())
    file.source().use { source -> expected.buffer().use { it.writeAll(source) } }
    val actual = HashingSink.sha256(blackholeSink())
    val size = actual.buffer().use { sink ->
      val input = body.source()
      var remaining = length
      while (remaining > 0L) {
        checkActive()
        val read = input.read(sink.buffer, minOf(remaining, 64 * 1024L))
        if (read == -1L) break
        remaining -= read
        sink.emitCompleteSegments()
      }
      length - remaining
    }
    size == length && body.source().exhausted() && actual.hash == expected.hash
  }

  private fun <T> execute(method: String, path: String, body: RequestBody? = null, offset: Long? = null, read: (Response) -> T): T {
    checkActive()
    val url = api.newBuilder().addPathSegments(path).build()
    val builder = Request.Builder().url(url).method(method, body).header("Accept-Encoding", "identity")
    token?.let { builder.header("Authorization", "Bearer $it") }
    offset?.let { builder.header("Upload-Offset", it.toString()) }
    return http.newCall(builder.build()).execute().use(read)
  }

  private fun json(fields: Map<String, Any>): RequestBody = JsonUtil.toJson(fields).toRequestBody(JSON)

  private fun Response.json(): JsonNode {
    val source = body?.source() ?: throw IOException("Missing Barkup response")
    if (source.request(MAX_CONTROL_RESPONSE + 1)) throw IOException("Barkup response exceeds size limit")
    return barkupJson(source.readUtf8())
  }

  private fun JsonNode.long(name: String): Long {
    val field = path(name)
    if (!field.isIntegralNumber || !field.canConvertToLong()) throw IOException("Invalid Barkup number")
    return field.asLong()
  }

  private fun Response.uploadOffset(): Long = header("Upload-Offset")?.toLongOrNull() ?: throw IOException("Missing Barkup upload offset")

  private fun Response.expect(vararg statuses: Int) {
    if (code !in statuses) throw BarkupHttpException(code)
  }

  private class FileChunk(private val file: File, private val offset: Long, private val length: Long) : RequestBody() {
    override fun contentType() = BINARY
    override fun contentLength() = length

    override fun writeTo(sink: BufferedSink) {
      RandomAccessFile(file, "r").use { input ->
        input.seek(offset)
        val bytes = ByteArray(64 * 1024)
        var remaining = length
        while (remaining > 0) {
          val count = minOf(remaining, bytes.size.toLong()).toInt()
          input.readFully(bytes, 0, count)
          sink.write(bytes, 0, count)
          remaining -= count
        }
      }
    }
  }
}

internal class BarkupHttpException(val status: Int) : IOException("Barkup HTTP $status")

internal interface BarkupUploadSession {
  fun read(): String?
  fun save(id: String)
  fun clear()
}
