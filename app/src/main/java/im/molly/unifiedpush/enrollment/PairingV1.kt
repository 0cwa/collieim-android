package im.molly.unifiedpush.enrollment

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.internal.idn.Punycode
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.ecc.ECPublicKey
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Byte-exact implementation of CollieSocket contracts/pairing/v1. */
object PairingV1 {
  const val CONTRACT_COMMIT = "9e69698761aaa3a17d668790c9493fe7ce49a212"
  private val mapper = ObjectMapper(JsonFactory().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION))
  private val uuidV4 = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
  private val label = Regex("[A-Za-z0-9](?:[A-Za-z0-9 ._:-]{0,62}[A-Za-z0-9])?")
  private val x25519Prime = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))

  // Canonical small-order u-coordinates from libsodium's X25519 ref10 validation.
  // The two noncanonical aliases, p and p + 1, fail the range check.
  private val smallOrderX25519Coordinates = setOf(
    BigInteger.ZERO,
    BigInteger.ONE,
    x25519Prime.subtract(BigInteger.ONE),
    BigInteger("325606250916557431795983626356110631294008115727848805560023387167927233504"),
    BigInteger("39382357235489614581723060781553021112529911719440698176882885853963445705823"),
  )

  data class Offer(
    val sessionId: String, val intent: String, val expiresAt: Long, val hostLabel: String,
    val providerOrigin: String?, val vapidPublicKey: String, val serverPublicKey: String,
  ) {
    override fun toString() = "PairingV1.Offer(sessionId=$sessionId, intent=$intent, expiresAt=$expiresAt, hostLabel=$hostLabel)"
  }

  data class SealedRequest(
    val json: String,
    val receiptKey: ByteArray,
    val transcript: ByteArray,
    val requestNonce: ByteArray,
    val requestCiphertext: ByteArray,
    val expectedRequestId: String = "",
    val expectedInstance: String = "",
    val expectedEpoch: Long = 0,
  ) {
    override fun toString() = "PairingV1.SealedRequest"
  }

  fun parseOffer(json: String, nowSeconds: Long): Offer {
    if (json.toByteArray(StandardCharsets.UTF_8).size > 1024 || json.startsWith('\uFEFF')) invalid()
    val root = strictObject(
      json,
      setOf("pairing_version", "session_id", "intent", "expires_at", "host_label", "provider_origin", "vapid_public_key", "server_public_key"),
      setOf("pairing_version", "session_id", "intent", "expires_at", "host_label", "vapid_public_key", "server_public_key"),
    )
    if (root["pairing_version"]?.takeIf { it.isIntegralNumber && it.canConvertToInt() && it.intValue() == 1 } == null) invalid()
    val session = root.text("session_id")
    val intent = root.text("intent")
    val expires = root["expires_at"]?.takeIf { it.isIntegralNumber && it.canConvertToLong() }?.longValue() ?: invalid()
    val host = root.text("host_label")
    val providerNode = root["provider_origin"]
    if (providerNode != null && !providerNode.isTextual) invalid()
    val provider = providerNode?.asText()
    // 600 seconds is a server-side issuance policy. The phone's pinned schema only bounds the
    // integer representation and requires that an offer is not expired.
    if (!uuidV4.matches(session) || intent !in setOf("pair_bridge", "add_receiver") || expires !in 1..9_007_199_254_740_991L ||
      expires <= nowSeconds ||
      !label.matches(host) || provider?.let { !isCanonicalOrigin(it) } == true) invalid()
    val vapid = decode(root.text("vapid_public_key"), 65)
    EnrollmentFile.requireOnCurve(vapid)
    val server = decode(root.text("server_public_key"), 32)
    requireX25519Coordinate(server)
    return Offer(session, intent, expires, host, provider, encode(vapid), encode(server))
  }

  /** Sealing is called only after host and provider consent, and after endpoint-origin checks. */
  fun seal(offer: Offer, enrollmentJson: String, actualEndpoint: String, random: SecureRandom = SecureRandom()): SealedRequest {
    if (offer.intent != "pair_bridge") invalid("unsupported_intent")
    if (enrollmentJson.toByteArray(StandardCharsets.UTF_8).size !in 1..8192) invalid("invalid_enrollment")
    strictObject(enrollmentJson, null)
    val origin = canonicalOrigin(actualEndpoint) ?: invalid("invalid_endpoint")
    if (offer.providerOrigin != null && offer.providerOrigin != origin) invalid("invalid_endpoint")
    val serverRaw = decode(offer.serverPublicKey, 32)
    // JCA XDH and NamedParameterSpec are unavailable on minSdk 27. Reuse the native
    // Curve25519 implementation already shipped through libsignal on supported Android.
    val pair = ECKeyPair.generate()
    val serializedPublic = pair.publicKey.serialize()
    if (serializedPublic.size != 33 || serializedPublic[0] != 0x05.toByte()) invalid("invalid_key")
    val clientPublic = serializedPublic.copyOfRange(1, serializedPublic.size)
    val serializedServerPublic = byteArrayOf(0x05) + serverRaw
    val serverPublic = ECPublicKey(serializedServerPublic, 0, serializedServerPublic.size)
    val shared = pair.privateKey.calculateAgreement(serverPublic)
    val nonce = ByteArray(12).also(random::nextBytes)
    return sealWithMaterial(offer, enrollmentJson, clientPublic, shared, nonce)
  }

  fun openReceipt(offer: Offer, sealed: SealedRequest, json: String): String {
    if (json.startsWith('\uFEFF') || json.toByteArray(StandardCharsets.UTF_8).size > 2048) invalid("invalid_response")
    val root = strictObject(json, setOf("pairing_version", "session_id", "intent", "nonce", "ciphertext"))
    if (root.size() != 5 || root["pairing_version"]?.let { !it.isIntegralNumber || !it.canConvertToInt() || it.intValue() != 1 } != false || root.text("session_id") != offer.sessionId || root.text("intent") != "pair_bridge") invalid("invalid_response")
    val nonce = decode(root.text("nonce"), 12)
    val ciphertext = decode(root.text("ciphertext"), null).also { if (it.size !in 17..1040) invalid("invalid_response") }
    val aad = sealed.transcript + MessageDigest.getInstance("SHA-256").digest(sealed.requestNonce + sealed.requestCiphertext)
    val plaintext = try { aesGcm(sealed.receiptKey, nonce, aad, ciphertext, decrypt = true) } catch (_: Exception) { invalid("invalid_response") }
    if (plaintext.isEmpty() || plaintext.size > 1024) invalid("invalid_response")
    val receipt = decodeUtf8(plaintext)
    if (receipt.startsWith('\uFEFF')) invalid("invalid_response")
    validateReceipt(strictObject(receipt, setOf("enrollment_version", "request_id", "instance", "subscription_epoch", "selected_event_version", "capabilities", "status", "replayed")), sealed)
    return receipt
  }

  internal fun transcriptForTest(offer: Offer, clientPublicKey: String): ByteArray =
    transcript(offer, decode(offer.vapidPublicKey, 65), decode(offer.serverPublicKey, 32), decode(clientPublicKey, 32))

  internal fun responseForTest(offer: Offer, sealed: SealedRequest, receiptJson: String): String {
    val nonce = ByteArray(12) { (it + 1).toByte() }
    val aad = sealed.transcript + MessageDigest.getInstance("SHA-256").digest(sealed.requestNonce + sealed.requestCiphertext)
    val ciphertext = aesGcm(sealed.receiptKey, nonce, aad, receiptJson.toByteArray(StandardCharsets.UTF_8))
    return """{"pairing_version":1,"session_id":"${offer.sessionId}","intent":"pair_bridge","nonce":"${encode(nonce)}","ciphertext":"${encode(ciphertext)}"}"""
  }

  /** Test seam supplies only the pinned X25519 outputs; production transcript, KDF, and AES stay shared. */
  internal fun sealedFromVectorForTest(
    offer: Offer,
    enrollmentJson: String,
    actualEndpoint: String,
    clientPublicKey: String,
    sharedSecretHex: String,
    nonce: String,
  ): SealedRequest {
    if (offer.intent != "pair_bridge") invalid("unsupported_intent")
    if (enrollmentJson.toByteArray(StandardCharsets.UTF_8).size !in 1..8192) invalid("invalid_enrollment")
    strictObject(enrollmentJson, null)
    val origin = canonicalOrigin(actualEndpoint) ?: invalid("invalid_endpoint")
    if (offer.providerOrigin != null && offer.providerOrigin != origin) invalid("invalid_endpoint")
    val clientRaw = decode(clientPublicKey, 32)
    val shared = decodeHex(sharedSecretHex, 32)
    val requestNonce = decode(nonce, 12)
    return sealWithMaterial(offer, enrollmentJson, clientRaw, shared, requestNonce)
  }

  fun rejectUnsupportedIntent(offer: Offer): Nothing = invalid("unsupported_intent")

  fun isCanonicalOrigin(value: String): Boolean {
    if (value.length > 256 || value.any { it.code !in 33..126 || it == '\\' }) return false
    return value == canonicalOrigin(value)
  }

  /** Canonical ASCII origin shared by scanned offers, endpoints, and consent display. */
  fun canonicalOrigin(value: String): String? {
    val url = value.toHttpUrlOrNull() ?: return null
    if (url.username.isNotEmpty() || url.password.isNotEmpty()) return null
    if (url.scheme !in setOf("http", "https") || url.port !in 1..65535) return null
    val authority = value.trim().substringAfter("://", "")
    val rawHost = if (authority.startsWith('[')) authority.substringAfter('[').substringBefore(']') else url.host
    val host = canonicalHost(rawHost) ?: return null
    val defaultPort = if (url.scheme == "https") 443 else 80
    return "${url.scheme}://${if (host.contains(':')) "[$host]" else host}${if (url.port != defaultPort) ":${url.port}" else ""}"
  }

  private fun canonicalHost(rawHost: String): String? {
    val host = rawHost.lowercase(Locale.ROOT)
    if (host.isEmpty() || host.any { it.code !in 0x21..0x7e || it in "%<>^|" }) return null
    if (host.contains(':')) return canonicalIpv6(host)

    // WHATWG host serialization omits a final dot and emits IPv4 in dotted decimal form.
    val canonical = host.removeSuffix(".")
    if (canonical.isEmpty()) return null
    val labels = canonical.split('.')
    val decodedLabels = labels.map { label ->
      if (!label.startsWith("xn--")) {
        label
      } else {
        // Match OkHttp's Punycode rather than the transitional mapping in java.net.IDN.
        val decoded = Punycode.decode(label) ?: return null
        if (decoded.all { it.code < 128 } || decoded.contains('.')) return null
        if ("https://$decoded/".toHttpUrlOrNull()?.host != label) return null
        decoded
      }
    }
    val containsRtl = decodedLabels.any { label ->
      label.codePoints().anyMatch { directionality(it) in RTL_DIRECTIONS }
    }
    if (containsRtl && decodedLabels.any { !validBidiLabel(it) }) return null
    return canonical
  }

  private fun directionality(codePoint: Int): Int = Character.getDirectionality(codePoint).toInt()

  private val RTL_DIRECTIONS = setOf(
    Character.DIRECTIONALITY_RIGHT_TO_LEFT.toInt(),
    Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC.toInt(),
    Character.DIRECTIONALITY_ARABIC_NUMBER.toInt(),
  )
  private val BIDI_ALLOWED_RTL = setOf(
    Character.DIRECTIONALITY_RIGHT_TO_LEFT.toInt(), Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC.toInt(),
    Character.DIRECTIONALITY_ARABIC_NUMBER.toInt(), Character.DIRECTIONALITY_EUROPEAN_NUMBER.toInt(),
    Character.DIRECTIONALITY_EUROPEAN_NUMBER_SEPARATOR.toInt(), Character.DIRECTIONALITY_COMMON_NUMBER_SEPARATOR.toInt(),
    Character.DIRECTIONALITY_EUROPEAN_NUMBER_TERMINATOR.toInt(), Character.DIRECTIONALITY_OTHER_NEUTRALS.toInt(),
    Character.DIRECTIONALITY_BOUNDARY_NEUTRAL.toInt(), Character.DIRECTIONALITY_NONSPACING_MARK.toInt(),
  )
  private val BIDI_ALLOWED_LTR = setOf(
    Character.DIRECTIONALITY_LEFT_TO_RIGHT.toInt(), Character.DIRECTIONALITY_EUROPEAN_NUMBER.toInt(),
    Character.DIRECTIONALITY_EUROPEAN_NUMBER_SEPARATOR.toInt(), Character.DIRECTIONALITY_COMMON_NUMBER_SEPARATOR.toInt(),
    Character.DIRECTIONALITY_EUROPEAN_NUMBER_TERMINATOR.toInt(), Character.DIRECTIONALITY_OTHER_NEUTRALS.toInt(),
    Character.DIRECTIONALITY_BOUNDARY_NEUTRAL.toInt(), Character.DIRECTIONALITY_NONSPACING_MARK.toInt(),
  )

  private fun validBidiLabel(label: String): Boolean {
    if (label.isEmpty()) return false
    val directions = label.codePoints().toArray().map(::directionality)
    val rtl = directions.first() in setOf(
      Character.DIRECTIONALITY_RIGHT_TO_LEFT.toInt(), Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC.toInt(),
    )
    val ltr = directions.first() == Character.DIRECTIONALITY_LEFT_TO_RIGHT.toInt()
    if (!rtl && !ltr) return false
    val allowedDirections = if (rtl) BIDI_ALLOWED_RTL else BIDI_ALLOWED_LTR
    if (directions.any { it !in allowedDirections }) return false
    val last = directions.lastOrNull { it != Character.DIRECTIONALITY_NONSPACING_MARK.toInt() } ?: return false
    if (rtl) {
      val europeanDigits = Character.DIRECTIONALITY_EUROPEAN_NUMBER.toInt() in directions
      val arabicDigits = Character.DIRECTIONALITY_ARABIC_NUMBER.toInt() in directions
      return last in setOf(
        Character.DIRECTIONALITY_RIGHT_TO_LEFT.toInt(), Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC.toInt(),
        Character.DIRECTIONALITY_EUROPEAN_NUMBER.toInt(), Character.DIRECTIONALITY_ARABIC_NUMBER.toInt(),
      ) && !(europeanDigits && arabicDigits)
    }
    return last in setOf(Character.DIRECTIONALITY_LEFT_TO_RIGHT.toInt(), Character.DIRECTIONALITY_EUROPEAN_NUMBER.toInt())
  }

  private fun canonicalIpv6(host: String): String? {
    if (host.any { it !in '0'..'9' && it !in 'a'..'f' && it != ':' && it != '.' }) return null
    val compression = host.indexOf("::")
    if (compression >= 0 && host.indexOf("::", compression + 2) >= 0) return null
    val leftText = if (compression >= 0) host.substring(0, compression) else host
    val rightText = if (compression >= 0) host.substring(compression + 2) else ""
    fun parseGroups(text: String): MutableList<Int>? {
      if (text.isEmpty()) return mutableListOf()
      val parts = text.split(':')
      val groups = mutableListOf<Int>()
      for (part in parts) {
        if (part.contains('.')) {
          val octets = part.split('.')
          if (octets.size != 4 || octets.any { it.isEmpty() || it.any { c -> c !in '0'..'9' } }) return null
          val values = octets.map { it.toIntOrNull() ?: return null }
          if (values.any { it !in 0..255 }) return null
          groups += (values[0] shl 8) or values[1]
          groups += (values[2] shl 8) or values[3]
        } else {
          if (part.isEmpty() || part.length > 4 || part.any { it !in '0'..'9' && it !in 'a'..'f' }) return null
          groups += part.toIntOrNull(16) ?: return null
        }
      }
      return groups
    }
    val left = parseGroups(leftText) ?: return null
    val right = parseGroups(rightText) ?: return null
    val total = left.size + right.size
    if (compression < 0 && total != 8 || compression >= 0 && total >= 8) return null
    val groups = left + MutableList(if (compression >= 0) 8 - total else 0) { 0 } + right
    if (groups.size != 8) return null
    if (groups.take(5).all { it == 0 } && groups[5] == 0xffff) {
      return "::ffff:${groups[6].toString(16)}:${groups[7].toString(16)}"
    }

    var bestStart = -1
    var bestLength = 1
    var index = 0
    while (index < groups.size) {
      if (groups[index] != 0) { index++; continue }
      var end = index
      while (end < groups.size && groups[end] == 0) end++
      if (end - index > bestLength) { bestStart = index; bestLength = end - index }
      index = end
    }
    if (bestStart < 0) return groups.joinToString(":") { it.toString(16) }
    val before = groups.take(bestStart).joinToString(":") { it.toString(16) }
    val after = groups.drop(bestStart + bestLength).joinToString(":") { it.toString(16) }
    return when {
      before.isEmpty() && after.isEmpty() -> "::"
      before.isEmpty() -> "::$after"
      after.isEmpty() -> "$before::"
      else -> "$before::$after"
    }
  }

  private fun validateReceipt(receipt: JsonNode, sealed: SealedRequest) {
    val status = receipt["status"]?.takeIf { it.isTextual }?.textValue()
    val replayed = receipt["replayed"]?.takeIf { it.isBoolean }?.booleanValue()
    if (receipt.size() != 8 ||
      receipt["enrollment_version"]?.let { !it.isIntegralNumber || !it.canConvertToInt() || it.intValue() != 3 } != false ||
      receipt.textOrNull("request_id") != sealed.expectedRequestId ||
      receipt.textOrNull("instance") != sealed.expectedInstance ||
      receipt["subscription_epoch"]?.let { !it.isIntegralNumber || !it.canConvertToLong() || it.longValue() != sealed.expectedEpoch } != false ||
      receipt["selected_event_version"]?.let { !it.isIntegralNumber || !it.canConvertToInt() || it.intValue() != 3 } != false ||
      receipt["capabilities"]?.toString() != "[\"wake\",\"test\",\"status\"]" ||
      status !in setOf("accepted", "unchanged") || replayed == null ||
      (status == "unchanged" && !replayed) || (!replayed && status != "accepted")
    ) invalid("invalid_response")
  }

  private fun JsonNode.textOrNull(name: String): String? = this[name]?.takeIf { it.isTextual }?.textValue()

  private fun transcript(o: Offer, vapid: ByteArray, server: ByteArray, client: ByteArray): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    out.write("COLLIESOCKET-PAIRING\u0000".toByteArray(StandardCharsets.US_ASCII)); out.write(1)
    out.write(o.sessionId.toByteArray(StandardCharsets.US_ASCII)); out.write(if (o.intent == "pair_bridge") 1 else 2)
    out.write(ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(o.expiresAt).array())
    fun field(value: String) { val bytes = value.toByteArray(StandardCharsets.US_ASCII); out.write(byteArrayOf((bytes.size shr 8).toByte(), bytes.size.toByte())); out.write(bytes) }
    field(o.hostLabel); field(o.providerOrigin ?: ""); out.write(vapid); out.write(server); out.write(client)
    return out.toByteArray()
  }

  private fun deriveKeys(shared: ByteArray, salt: ByteArray): Pair<ByteArray, ByteArray> {
    fun hkdf(info: String): ByteArray {
      val extract = Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(salt, "HmacSHA256")); doFinal(shared) }
      return Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(extract, "HmacSHA256")); update(info.toByteArray(StandardCharsets.US_ASCII)); update(1); doFinal() }
    }
    return hkdf("COLLIESOCKET-PAIRING-V1 request") to hkdf("COLLIESOCKET-PAIRING-V1 receipt")
  }

  private fun aesGcm(key: ByteArray, nonce: ByteArray, aad: ByteArray, input: ByteArray, decrypt: Boolean = false): ByteArray =
    Cipher.getInstance("AES/GCM/NoPadding").run { init(if (decrypt) Cipher.DECRYPT_MODE else Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce)); updateAAD(aad); doFinal(input) }

  private fun sealWithMaterial(offer: Offer, enrollmentJson: String, clientPublic: ByteArray, shared: ByteArray, nonce: ByteArray): SealedRequest {
    if (shared.size != 32 || shared.all { it == 0.toByte() }) {
      shared.fill(0)
      invalid("invalid_key")
    }
    try {
      val enrollment = try { mapper.readTree(enrollmentJson) } catch (_: Exception) { invalid("invalid_enrollment") }
      val subscription = enrollment["subscription"]
      val requestId = enrollment["request_id"]?.takeIf { it.isTextual }?.textValue() ?: invalid("invalid_enrollment")
      val instance = subscription?.get("instance")?.takeIf { it.isTextual }?.textValue() ?: invalid("invalid_enrollment")
      val epoch = subscription?.get("subscription_epoch")?.takeIf { it.isIntegralNumber && it.canConvertToLong() }?.longValue()
        ?: invalid("invalid_enrollment")
      val transcript = transcript(offer, decode(offer.vapidPublicKey, 65), decode(offer.serverPublicKey, 32), clientPublic)
      val keys = deriveKeys(shared, MessageDigest.getInstance("SHA-256").digest(transcript))
      try {
        val ciphertext = aesGcm(keys.first, nonce, transcript, enrollmentJson.toByteArray(StandardCharsets.UTF_8))
        val request = """{"pairing_version":1,"session_id":"${offer.sessionId}","intent":"pair_bridge","client_public_key":"${encode(clientPublic)}","nonce":"${encode(nonce)}","ciphertext":"${encode(ciphertext)}"}"""
        if (request.toByteArray(StandardCharsets.UTF_8).size > 11264) invalid("invalid_request")
        return SealedRequest(request, keys.second, transcript, nonce, ciphertext, requestId, instance, epoch)
      } catch (e: Exception) {
        keys.second.fill(0)
        throw e
      } finally {
        keys.first.fill(0)
      }
    } finally {
      shared.fill(0)
    }
  }

  private fun decodeHex(value: String, size: Int): ByteArray {
    if (value.length != size * 2 || !value.matches(Regex("[0-9a-f]+"))) invalid("invalid_key")
    return ByteArray(size) { index -> value.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
  }
  private fun requireX25519Coordinate(raw: ByteArray) {
    if (raw.size != 32) invalid("invalid_key")
    val coordinate = BigInteger(1, raw.reversedArray())
    if (coordinate >= x25519Prime || coordinate in smallOrderX25519Coordinates) invalid("invalid_key")
  }
  private fun strictObject(text: String, allowed: Set<String>?, required: Set<String>? = allowed): JsonNode {
    try {
      encodeUtf8(text)
      mapper.factory.createParser(text).use { parser ->
        val node = mapper.readTree<JsonNode>(parser) ?: invalid()
        if (parser.nextToken() != null) invalid()
        if (!node.isObject || (allowed != null && node.fieldNames().asSequence().any { it !in allowed })) invalid()
        if (required != null && required.any { !node.has(it) }) invalid()
        return node
      }
    } catch (_: Exception) { invalid() }
  }
  internal fun decodeUtf8(bytes: ByteArray): String = try {
    StandardCharsets.UTF_8.newDecoder()
      .onMalformedInput(CodingErrorAction.REPORT)
      .onUnmappableCharacter(CodingErrorAction.REPORT)
      .decode(ByteBuffer.wrap(bytes))
      .toString()
  } catch (_: CharacterCodingException) {
    invalid("invalid_response")
  }
  private fun encodeUtf8(text: String): ByteArray = try {
    val encoded = StandardCharsets.UTF_8.newEncoder()
      .onMalformedInput(CodingErrorAction.REPORT)
      .onUnmappableCharacter(CodingErrorAction.REPORT)
      .encode(CharBuffer.wrap(text))
    ByteArray(encoded.remaining()).also { encoded.get(it) }
  } catch (_: CharacterCodingException) {
    invalid()
  }
  private fun JsonNode.text(name: String) = this[name]?.takeIf { it.isTextual }?.textValue() ?: invalid()
  private fun decode(value: String, size: Int?): ByteArray {
    if (!value.matches(Regex("[A-Za-z0-9_-]+"))) invalid("invalid_key")
    val bytes = try { Base64.getUrlDecoder().decode(value) } catch (_: Exception) { invalid("invalid_key") }
    if (encode(bytes) != value || size != null && bytes.size != size) invalid("invalid_key")
    return bytes
  }
  private fun encode(value: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(value)
  private fun invalid(code: String = "invalid_offer"): Nothing = throw EnrollmentFile.Invalid(code)
}
