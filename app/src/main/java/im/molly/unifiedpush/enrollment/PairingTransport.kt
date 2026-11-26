package im.molly.unifiedpush.enrollment

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import okio.BufferedSource
import okio.buffer
import okio.sink
import okio.source
import java.io.EOFException
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.util.concurrent.TimeUnit

/** Optional QR transport. Only the original pairing envelopes cross this connection. */
internal object PairingTransport {
  private const val MAX_QR_BYTES = 4096
  private const val MAX_RESPONSE_BYTES = 2048L
  private const val TIMEOUT_MS = 15_000
  private val mapper = ObjectMapper(JsonFactory().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION))
    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
  private val https = OkHttpClient.Builder()
    .followRedirects(false)
    .followSslRedirects(false)
    .retryOnConnectionFailure(false)
    .proxy(Proxy.NO_PROXY)
    .callTimeout(TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
    .build()

  data class Scan(val offer: PairingV1.Offer, val submitUrl: HttpUrl?) {
    override fun toString() = "PairingTransport.Scan(offer=$offer)"
  }

  sealed interface Result {
    data object Accepted : Result
    data object AwaitingPush : Result
    data object Offline : Result
    data object Stale : Result
    data object InvalidResponse : Result
    data object UnsafeDestination : Result
    data class Rejected(val code: String) : Result
  }

  fun parse(json: String, nowSeconds: Long, allowExpiredForRouting: Boolean = false): Scan {
    if (json.startsWith('\uFEFF') || json.toByteArray(Charsets.UTF_8).size > MAX_QR_BYTES) invalid("invalid_offer")
    val root = readObject(json) ?: invalid("invalid_offer")
    val offerTime = if (allowExpiredForRouting) 0 else nowSeconds
    if (!root.has("collie_pairing_transport")) return Scan(parseOffer(json, root, offerTime), null)
    val version = root["collie_pairing_transport"]
    val url = root["submit_url"]
    val offer = root["offer"]
    if (root.size() != 3 || !version.isIntegralNumber || !version.canConvertToInt() || version.intValue() != 1 ||
      url?.isTextual != true || offer?.isObject != true) invalid("invalid_offer")
    return Scan(parseOffer(offer.toString(), offer, offerTime), parseSubmitUrl(url.textValue()))
  }

  private fun parseOffer(json: String, root: JsonNode, nowSeconds: Long): PairingV1.Offer {
    try {
      return PairingV1.parseOffer(json, nowSeconds)
    } catch (error: EnrollmentFile.Invalid) {
      val expiry = root["expires_at"]
      if (expiry?.isIntegralNumber == true && expiry.canConvertToLong() && expiry.longValue() in 1..nowSeconds) {
        // Report expiry only when the rest of the offer passes the unchanged pinned parser.
        PairingV1.parseOffer(json, 0)
        invalid("session_expired")
      }
      throw error
    }
  }

  internal fun parseSubmitUrl(value: String): HttpUrl {
    if (value.length > 2048 || value.any { it.isWhitespace() || it.isISOControl() || it == '\\' || it == '#' }) invalid("invalid_submit_url")
    val authority = value.substringAfter("://", "").substringBefore('/').substringBefore('?')
    if (authority.isBlank() || '@' in authority) invalid("invalid_submit_url")
    val url = value.toHttpUrlOrNull() ?: invalid("invalid_submit_url")
    if (url.scheme == "http" && !isLocalName(url.host) && literalAddress(url.host)?.let(::isLocalAddress) != true) {
      invalid("invalid_submit_url")
    }
    return url
  }

  /** The callback authenticates the exact receipt and commits the app's route. */
  fun submit(
    url: HttpUrl?,
    request: String,
    lookup: (String) -> List<InetAddress> = Dns.SYSTEM::lookup,
    acceptReceipt: (String) -> Boolean,
  ): Result {
    if (url == null) return Result.Offline
    val response = try {
      if (url.isHttps) postHttps(url, request) else postLocal(url, request, lookup)
    } catch (_: UnsafeTarget) {
      return Result.UnsafeDestination
    } catch (_: InvalidHttp) {
      return Result.InvalidResponse
    } catch (_: IOException) {
      return Result.Offline
    }
    val json = try {
      PairingV1.decodeUtf8(response.body)
    } catch (_: EnrollmentFile.Invalid) {
      return Result.InvalidResponse
    }
    if (response.code in 200..299) {
      return if (acceptReceipt(json)) Result.Accepted else Result.InvalidResponse
    }
    val error = readObject(json)
    val code = error?.get("error")?.takeIf { it.isTextual }?.textValue()
    return if (error?.size() == 1 && code != null) Result.Rejected(code) else Result.InvalidResponse
  }

  private data class Response(val code: Int, val body: ByteArray)

  private fun postHttps(url: HttpUrl, json: String): Response {
    val request = Request.Builder().url(url).post(json.toByteArray(Charsets.UTF_8).toRequestBody("application/json".toMediaType())).build()
    return https.newCall(request).execute().use { response ->
      if (response.code in 300..399) throw InvalidHttp()
      Response(response.code, readBody(response.body.source()))
    }
  }

  // Android's domain exceptions cannot express these IP ranges. Only this encrypted pairing
  // envelope uses a private-address socket. The app-wide cleartext prohibition stays in effect.
  private fun postLocal(url: HttpUrl, json: String, lookup: (String) -> List<InetAddress>): Response {
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TIMEOUT_MS.toLong())
    val addresses = literalAddress(url.host)?.let(::listOf) ?: if (isLocalName(url.host)) {
      lookup(url.host.removeSuffix("."))
    } else {
      throw UnsafeTarget()
    }
    if (addresses.isEmpty() || addresses.any { !isLocalAddress(it) }) throw UnsafeTarget()
    val target = if (url.encodedQuery == null) url.encodedPath else "${url.encodedPath}?${url.encodedQuery}"
    val host = if (':' in url.host) "[${url.host}]" else url.host
    val bytes = json.toByteArray(Charsets.UTF_8)
    var connectFailure: IOException? = null
    for (address in addresses.distinct()) {
      Socket(Proxy.NO_PROXY).use { socket ->
        val remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
        if (remaining <= 0) throw java.net.SocketTimeoutException()
        try {
          socket.connect(InetSocketAddress(address, url.port), remaining.toInt())
        } catch (e: IOException) {
          connectFailure = e
          return@use
        }
        // Only retry connection failures. The relay may consume a request once any body is sent.
        val output = socket.sink().buffer()
        output.timeout().deadlineNanoTime(deadline)
        output.writeUtf8("POST $target HTTP/1.1\r\nHost: $host:${url.port}\r\nContent-Type: application/json\r\n")
        output.writeUtf8("Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n")
        output.write(bytes).flush()
        val input = socket.source().buffer()
        input.timeout().deadlineNanoTime(deadline)
        return readLocalResponse(input)
      }
    }
    throw connectFailure ?: IOException("No pairing destination could be reached")
  }

  private fun readLocalResponse(input: BufferedSource): Response {
    val status = input.readUtf8LineStrict(512)
    val code = STATUS.matchEntire(status)?.groupValues?.get(1)?.toInt() ?: throw InvalidHttp()
    if (code in 300..399) throw InvalidHttp()
    var budget = 8192L
    var contentLength: Long? = null
    var chunked = false
    while (true) {
      val line = input.readUtf8LineStrict(budget)
      budget -= line.toByteArray(Charsets.UTF_8).size + 2
      if (budget < 0) throw InvalidHttp()
      if (line.isEmpty()) break
      val separator = line.indexOf(':')
      if (separator <= 0) throw InvalidHttp()
      val value = line.substring(separator + 1).trim()
      when (line.substring(0, separator).lowercase()) {
        "content-length" -> {
          if (contentLength != null || value.any { it !in '0'..'9' }) throw InvalidHttp()
          contentLength = value.toLongOrNull() ?: throw InvalidHttp()
        }
        "transfer-encoding" -> {
          if (chunked || !value.equals("chunked", ignoreCase = true)) throw InvalidHttp()
          chunked = true
        }
      }
    }
    val body = when {
      chunked && contentLength != null -> throw InvalidHttp()
      chunked -> readChunks(input)
      contentLength != null && contentLength <= MAX_RESPONSE_BYTES -> input.readByteArray(contentLength)
      contentLength != null -> throw InvalidHttp()
      else -> readBody(input)
    }
    return Response(code, body)
  }

  private fun readChunks(input: BufferedSource): ByteArray {
    val body = Buffer()
    while (true) {
      val line = input.readUtf8LineStrict(128).substringBefore(';')
      if (line.isEmpty() || line.any { it !in "0123456789abcdefABCDEF" }) throw InvalidHttp()
      val size = line.toLongOrNull(16) ?: throw InvalidHttp()
      if (size > MAX_RESPONSE_BYTES - body.size) throw InvalidHttp()
      if (size == 0L) return body.readByteArray()
      input.readFully(body, size)
      if (input.readByte() != 13.toByte() || input.readByte() != 10.toByte()) throw InvalidHttp()
    }
  }

  private fun readBody(input: BufferedSource): ByteArray {
    val body = Buffer()
    while (body.size <= MAX_RESPONSE_BYTES) {
      if (input.read(body, MAX_RESPONSE_BYTES + 1 - body.size) == -1L) return body.readByteArray()
    }
    throw InvalidHttp()
  }

  private fun isLocalName(host: String): Boolean {
    val name = host.removeSuffix(".")
    return name == "localhost" || name.endsWith(".local") && name.length > ".local".length
  }

  private fun literalAddress(host: String): InetAddress? {
    if (':' in host) return runCatching { InetAddress.getByName(host) }.getOrNull()
    val parts = host.split('.')
    if (parts.size != 4) return null
    val bytes = parts.map {
      val part = it.toIntOrNull() ?: return null
      if (part !in 0..255 || part.toString() != it) return null
      part.toByte()
    }
    return InetAddress.getByAddress(bytes.toByteArray())
  }

  private fun isLocalAddress(ip: InetAddress): Boolean {
    val bytes = ip.address
    val first = bytes[0].toInt() and 0xff
    val second = bytes[1].toInt() and 0xff
    return if (bytes.size == 4) {
      first == 10 || first == 127 || first == 172 && second in 16..31 ||
        first == 192 && second == 168 || first == 100 && second in 64..127
    } else {
      ip.isLoopbackAddress || first and 0xfe == 0xfc || first == 0xfe && second and 0xc0 == 0x80
    }
  }

  private fun readObject(json: String): JsonNode? =
    if (json.startsWith('\uFEFF')) null else try {
      mapper.readTree(json)?.takeIf { it.isObject }
    } catch (_: IOException) {
      null
    }

  private fun invalid(code: String): Nothing = throw EnrollmentFile.Invalid(code)
  private class UnsafeTarget : IOException()
  private class InvalidHttp : EOFException()
  private val STATUS = Regex("HTTP/1\\.[01] ([1-5][0-9]{2})(?: [^\\r\\n]*)?")
}
