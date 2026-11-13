package org.thoughtcrime.securesms.apkupdate

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import org.signal.core.util.Hex
import org.signal.core.util.JsonUtils
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.jar.JarFile
import java.util.zip.ZipFile

/** Authenticates F-Droid v2 announcements before they reach storage or the UI. */
internal object CollieUpdateIndex {
  const val REPOSITORY_URL = "https://repo.collie.im/fdroid/repo"
  const val PACKAGE_NAME = "im.collie.app"
  const val REPOSITORY_CERTIFICATE = "b0c5dcbac2b65ada4e8f641910097fb0bf86a5f7255319584d453c2932f5233c"
  const val APK_CERTIFICATE = "f8cc0c0c052e5635c26fcd293c2b0af3e87d61ff7f2df3602a9af6e91f73c826"
  const val MAX_ARCHIVE_BYTES = 2 * 1024 * 1024
  const val MAX_EXPANDED_BYTES = 4 * 1024 * 1024
  private const val INDEX_ENTRY = "entry.json"
  private val VERSION_NAME = Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[0-9]+)?(?:-(?:alpha|beta|rc)[0-9]+)?")
  private val HEX_DIGEST = Regex("[0-9a-fA-F]{64}")

  data class Update(val versionCode: Long, val versionName: String) {
    val releasePage: String get() = releasePage(versionName)
  }

  data class Index(val timestamp: Long, val size: Int, val sha256: String)
  data class Result(val timestamp: Long, val update: Update?)

  fun readEntry(input: InputStream, cacheDirectory: File, lastTimestamp: Long): Index {
    return parseVerifiedEntry(verifyArchive(input, cacheDirectory), lastTimestamp)
  }

  fun readIndex(input: InputStream, index: Index, currentVersionCode: Long, deviceSdk: Int, supportedAbis: Set<String>): Result {
    val bytes = readBounded(input, index.size)
    if (bytes.size != index.size || !MessageDigest.isEqual(sha256(bytes), Hex.fromStringCondensed(index.sha256))) {
      throw IOException("Update index does not match its signed digest and size")
    }
    return parseVerifiedIndex(bytes, index.timestamp, currentVersionCode, deviceSdk, supportedAbis)
  }

  fun releasePage(versionName: String): String {
    require(versionName.length <= 80 && VERSION_NAME.matches(versionName)) { "Invalid Collie release version" }
    return "https://github.com/0cwa/collieim-android/releases/tag/v$versionName"
  }

  internal fun verifyArchive(input: InputStream, cacheDirectory: File, certificate: String = REPOSITORY_CERTIFICATE): ByteArray {
    val archive = readBounded(input, MAX_ARCHIVE_BYTES)
    val file = File.createTempFile("collie-update-", ".jar", cacheDirectory)
    try {
      file.outputStream().use { it.write(archive) }
      val names = mutableSetOf<String>()
      var expandedBytes = 0
      // Preflight the same central-directory view JarFile will verify. This bounds signature
      // metadata inflation before the verifier parses it, and rejects ambiguous entry names.
      ZipFile(file).use { zip ->
        for (entry in zip.entries().asSequence()) {
          if (!names.add(entry.name.uppercase(Locale.ROOT)) || names.size > 16) throw IOException("Ambiguous update archive")
          if (entry.name != INDEX_ENTRY && !entry.name.matches(Regex("META-INF/[A-Za-z0-9_.-]*"))) {
            throw IOException("Unexpected update archive entry")
          }
          if (entry.size < 0 || entry.size > MAX_EXPANDED_BYTES - expandedBytes) throw IOException("Oversized update archive entry")
          val bytes = zip.getInputStream(entry).use { readBounded(it, MAX_EXPANDED_BYTES - expandedBytes) }
          if (bytes.size.toLong() != entry.size) throw IOException("Invalid update archive size")
          expandedBytes += bytes.size
        }
      }
      if (INDEX_ENTRY.uppercase(Locale.ROOT) !in names) throw IOException("Missing update entry")

      // F-Droid puts the manifest last, so JarInputStream cannot authenticate this format.
      // JarFile verifies independently of ZIP order. Never relax platform algorithm policies.
      JarFile(file, true).use { jar ->
        val entry = jar.getJarEntry(INDEX_ENTRY) ?: throw IOException("Missing update entry")
        val bytes = jar.getInputStream(entry).use { readBounded(it, MAX_EXPANDED_BYTES) }
        val signers = entry.codeSigners ?: throw SecurityException("Unsigned update entry")
        if (signers.size != 1) throw SecurityException("Unexpected update entry signers")
        val leaf = signers.single().signerCertPath.certificates.firstOrNull() ?: throw SecurityException("Missing update signer")
        // A pinned certificate appearing elsewhere in an untrusted chain does not confer trust.
        if (!MessageDigest.isEqual(sha256(leaf.encoded), Hex.fromStringCondensed(certificate))) {
          throw SecurityException("Untrusted update entry signer")
        }
        if (jar.manifest?.getAttributes(INDEX_ENTRY)?.getValue("SHA-256-Digest") == null) {
          throw SecurityException("Update entry must use a SHA-256 content digest")
        }
        return bytes
      }
    } catch (e: SecurityException) {
      throw IOException("Update entry signature verification failed", e)
    } finally {
      file.delete()
    }
  }

  internal fun parseVerifiedEntry(json: ByteArray, lastTimestamp: Long): Index {
    val root = parseJson(json)
    if (positiveLong(root.path("version")) != 20002L) throw IOException("Unsupported update index version")
    val timestamp = positiveLong(root.path("timestamp"))
    if (timestamp < lastTimestamp) throw IOException("Older update repository generation")
    val index = root.path("index")
    val size = positiveLong(index.path("size"))
    val hash = index.path("sha256").asText()
    if (index.path("name").asText() != "/index-v2.json" || size > MAX_EXPANDED_BYTES || !HEX_DIGEST.matches(hash)) {
      throw IOException("Invalid signed index descriptor")
    }
    return Index(timestamp, size.toInt(), hash)
  }

  /** Only called after the signed entry's digest and size have authenticated the bytes. */
  internal fun parseVerifiedIndex(json: ByteArray, timestamp: Long, currentVersionCode: Long, deviceSdk: Int, supportedAbis: Set<String>): Result {
    val root = parseJson(json)
    val repo = root.path("repo")
    if (repo.path("address").asText() != REPOSITORY_URL || positiveLong(repo.path("timestamp")) != timestamp) {
      throw IOException("Wrong update repository generation")
    }
    val app = root.path("packages").path(PACKAGE_NAME)
    if (!app.path("metadata").path("preferredSigner").asText().equals(APK_CERTIFICATE, ignoreCase = true)) {
      throw IOException("Missing Collie package or wrong preferred signer")
    }
    val entries = app.path("versions")
    if (!entries.isObject || entries.size() > 1000) throw IOException("Invalid Collie versions")
    val versions = mutableSetOf<Long>()
    var selected: Update? = null
    for ((hash, entry) in entries.fields()) {
      val manifest = entry.path("manifest")
      val code = positiveLong(manifest.path("versionCode"))
      if (!versions.add(code)) throw IOException("Duplicate Collie version")
      val file = entry.path("file")
      val signers = manifest.path("signer").path("sha256")
      if (!HEX_DIGEST.matches(hash) || file.path("sha256").asText() != hash ||
        file.path("name").asText() != "/${PACKAGE_NAME}_$code.apk" ||
        !signers.isArray || signers.size() != 1 || !signers[0].asText().equals(APK_CERTIFICATE, ignoreCase = true) ||
        (manifest.has("packageName") && manifest.path("packageName").asText() != PACKAGE_NAME)
      ) {
        throw IOException("Invalid Collie package identity")
      }
      positiveLong(file.path("size"))
      val name = manifest.path("versionName").asText()
      if (name.length > 80 || !VERSION_NAME.matches(name)) throw IOException("Invalid release version")
      val channels = entry.path("releaseChannels")
      if (!channels.isMissingNode && !channels.isArray) throw IOException("Invalid release channels")
      if (channels.size() != 0 || entry.path("antiFeatures").has("KnownVuln")) continue
      val sdk = manifest.path("usesSdk")
      if (positiveLong(sdk.path("minSdkVersion")) > deviceSdk) continue
      if (sdk.has("maxSdkVersion") && positiveLong(sdk.path("maxSdkVersion")) < deviceSdk) continue
      val abis = manifest.path("nativecode")
      if (!abis.isMissingNode && (!abis.isArray || abis.none { it.isTextual && it.asText() in supportedAbis })) continue
      if (code > currentVersionCode && code > (selected?.versionCode ?: 0)) selected = Update(code, name)
    }
    return Result(timestamp, selected)
  }

  private fun parseJson(bytes: ByteArray): JsonNode = JsonUtils.getMapper().copy()
    .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
    .readTree(bytes) ?: throw IOException("Missing update metadata")

  private fun positiveLong(value: JsonNode): Long {
    val text = if (value.isIntegralNumber || value.isTextual) value.asText() else ""
    return text.takeIf { it.matches(Regex("[1-9][0-9]{0,18}")) }?.toLongOrNull() ?: throw IOException("Invalid update number")
  }

  private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

  private fun readBounded(input: InputStream, limit: Int): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
      val count = input.read(buffer)
      if (count == -1) break
      if (count > limit - output.size()) throw IOException("Update metadata exceeds size limit")
      output.write(buffer, 0, count)
    }
    return output.toByteArray()
  }
}
