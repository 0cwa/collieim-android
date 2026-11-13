package org.thoughtcrime.securesms.apkupdate

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.signal.core.util.JsonUtils
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.jar.JarFile
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class CollieUpdateIndexTest {
  @get:Rule val temporary = TemporaryFolder()
  private val signedFixture: ByteArray get() = fixture("collie-entry.jar")
  private val indexFixture: ByteArray get() = fixture("collie-index-v2.json")

  @Test
  fun `production signed entry authenticates the matching index and Collie release`() {
    val descriptor = CollieUpdateIndex.readEntry(ByteArrayInputStream(signedFixture), temporary.root, 0)
    val result = CollieUpdateIndex.readIndex(ByteArrayInputStream(indexFixture), descriptor, 177704, 35, setOf("arm64-v8a"))
    assertEquals(1794370488000L, result.timestamp)
    assertEquals(CollieUpdateIndex.Update(177705, "8.35.1-3-rc2"), result.update)
    assertEquals("https://github.com/0cwa/collieim-android/releases/tag/v8.35.1-3-rc2", result.update!!.releasePage)
    assertTrue("Temporary archive must be removed", temporary.root.listFiles().isNullOrEmpty())
  }

  @Test
  fun `wrong pinned certificate rejects a correctly signed archive`() {
    assertThrows(IOException::class.java) {
      CollieUpdateIndex.verifyArchive(ByteArrayInputStream(signedFixture), temporary.root, "00".repeat(32))
    }
  }

  @Test
  fun `a trusted certificate in an untrusted signer chain is not a trusted leaf`() {
    val forged = fixture("untrusted-signer-with-repository-certificate.jar")
    val file = temporary.newFile("forged.jar").apply { writeBytes(forged) }
    JarFile(file, true).use { jar ->
      val entry = jar.getJarEntry("entry.json")
      jar.getInputStream(entry).use { it.readBytes() }
      val certificates = requireNotNull(entry.codeSigners).single().signerCertPath.certificates
      assertTrue("Fixture must contain the pinned certificate only in the claimed chain", certificates.drop(1).any { digest(it.encoded) == CollieUpdateIndex.REPOSITORY_CERTIFICATE })
    }
    assertThrows(IOException::class.java) { CollieUpdateIndex.verifyArchive(ByteArrayInputStream(forged), temporary.root) }
  }

  @Test
  fun `tampering with the signed entry fails before parsing`() {
    val tampered = rewriteFixture { name, bytes ->
      if (name == "entry.json") bytes.toString(Charsets.UTF_8).replace("1794370488000", "1794370488001").toByteArray() else bytes
    }
    assertThrows(IOException::class.java) { CollieUpdateIndex.verifyArchive(ByteArrayInputStream(tampered), temporary.root) }
  }

  @Test
  fun `removing signature files cannot make an entry trusted`() {
    val stripped = rewriteFixture { name, bytes -> if (name.endsWith(".RSA") || name.endsWith(".SF")) null else bytes }
    assertThrows(IOException::class.java) { CollieUpdateIndex.verifyArchive(ByteArrayInputStream(stripped), temporary.root) }
  }

  @Test
  fun `unsigned entry is rejected`() {
    assertThrows(IOException::class.java) {
      CollieUpdateIndex.verifyArchive(ByteArrayInputStream(zip("entry.json" to byteArrayOf(1))), temporary.root)
    }
  }

  @Test
  fun `duplicate names are rejected even with different case`() {
    val duplicate = zip("entry.json" to byteArrayOf(1), "ENTRY.JSON" to byteArrayOf(2))
    assertThrows(IOException::class.java) { CollieUpdateIndex.verifyArchive(ByteArrayInputStream(duplicate), temporary.root) }
  }

  @Test
  fun `compressed response size is bounded`() {
    assertThrows(IOException::class.java) {
      CollieUpdateIndex.verifyArchive(ByteArrayInputStream(ByteArray(CollieUpdateIndex.MAX_ARCHIVE_BYTES + 1)), temporary.root)
    }
  }

  @Test
  fun `signature metadata expansion is bounded before verification`() {
    val bomb = zip("META-INF/MANIFEST.MF" to ByteArray(CollieUpdateIndex.MAX_EXPANDED_BYTES + 1), "entry.json" to byteArrayOf(1))
    assertThrows(IOException::class.java) { CollieUpdateIndex.verifyArchive(ByteArrayInputStream(bomb), temporary.root) }
  }

  @Test
  fun `index digest exact size and signed generation all bind the downloaded bytes`() {
    val descriptor = CollieUpdateIndex.readEntry(ByteArrayInputStream(signedFixture), temporary.root, 0)
    val changed = indexFixture.clone().also { it[0] = ' '.code.toByte() }
    assertThrows(IOException::class.java) { CollieUpdateIndex.readIndex(ByteArrayInputStream(changed), descriptor, 1, 35, setOf("arm64-v8a")) }
    assertThrows(IOException::class.java) { CollieUpdateIndex.readIndex(ByteArrayInputStream(indexFixture.copyOf(indexFixture.size - 1)), descriptor, 1, 35, setOf("arm64-v8a")) }
    assertThrows(IOException::class.java) { CollieUpdateIndex.readIndex(ByteArrayInputStream(indexFixture + byteArrayOf(1)), descriptor, 1, 35, setOf("arm64-v8a")) }
    assertThrows(IOException::class.java) { parse(timestamp = 1794370488001) }
  }

  @Test
  fun `same and older releases are never offered`() {
    assertNull(parse(current = 177705).update)
    assertNull(parse(current = 177706).update)
  }

  @Test
  fun `older repository generation is rejected`() {
    assertThrows(IOException::class.java) { CollieUpdateIndex.readEntry(ByteArrayInputStream(signedFixture), temporary.root, 1794370488001) }
  }

  @Test
  fun `signed entry cannot change download origin path format or size limits`() {
    val entry = CollieUpdateIndex.verifyArchive(ByteArrayInputStream(signedFixture), temporary.root).toString(Charsets.UTF_8)
    for (modified in listOf(
      entry.replace("/index-v2.json", "https://example.org/index-v2.json"),
      entry.replace("/index-v2.json", "/../index-v2.json"),
      entry.replace("/index-v2.json", "/index-v2.json?redirect=1"),
      entry.replace("20002", "20001"),
      entry.replace("14849", "0"),
      entry.replace("14849", (CollieUpdateIndex.MAX_EXPANDED_BYTES + 1).toString())
    )) {
      assertThrows(IOException::class.java) { CollieUpdateIndex.parseVerifiedEntry(modified.toByteArray(), 0) }
    }
  }

  @Test
  fun `repository and package identities must match exactly`() {
    for (modified in listOf(
      metadata().replace("https://repo.collie.im/fdroid/repo", "https://example.org/fdroid/repo"),
      metadata().replace("im.collie.app", "im.molly.app")
    )) assertThrows(IOException::class.java) { parse(modified) }
  }

  @Test
  fun `APK signer hash and filename must match policy`() {
    for (modified in listOf(
      metadata().replace(CollieUpdateIndex.APK_CERTIFICATE, "00".repeat(32)),
      metadata().replace("\"sha256\":\"${"ab".repeat(32)}\"", "\"sha256\":\"${"cd".repeat(32)}\""),
      metadata().replace("/im.collie.app_177705.apk", "/../other.apk")
    )) assertThrows(IOException::class.java) { parse(modified) }
  }

  @Test
  fun `release tag cannot add a path query fragment or different URL`() {
    for (version in listOf("../8.35.2", "8.35.2?x=y", "8.35.2#other", "https://example.org", "8.35.2/other", "8.35.2%2f..", "8.35.2\n")) {
      assertThrows(IOException::class.java) { parse(metadata().replace("8.35.1-3-rc2", version.replace("\n", "\\n"))) }
      assertThrows(IllegalArgumentException::class.java) { CollieUpdateIndex.releasePage(version) }
    }
  }

  @Test
  fun `duplicate properties invalid numbers and trailing JSON are rejected`() {
    for (modified in listOf(
      metadata().replace("\"versionCode\":177705", "\"versionCode\":177705,\"versionCode\":177706"),
      metadata().replace("\"versionCode\":177705", "\"versionCode\":177705.0"),
      metadata().replace("\"versionCode\":177705", "\"versionCode\":-1"),
      metadata().replace("\"versionCode\":177705", "\"versionCode\":9223372036854775808"),
      metadata() + " {}"
    )) assertThrows(IOException::class.java) { parse(modified) }
  }

  @Test
  fun `nondefault channels known vulnerabilities and incompatible devices are skipped`() {
    for (modified in listOf(
      metadata().replace("\"manifest\":", "\"releaseChannels\":[\"Beta\"],\"manifest\":"),
      metadata().replace("\"manifest\":", "\"antiFeatures\":{\"KnownVuln\":{}},\"manifest\":"),
      metadata().replace("\"minSdkVersion\":27", "\"minSdkVersion\":36"),
      metadata().replace("arm64-v8a", "unsupported-abi")
    )) assertNull(parse(modified).update)
  }

  @Test
  fun `highest eligible version wins and duplicate version codes are rejected`() {
    val root = JsonUtils.getMapper().readTree(metadata())
    val versions = root.path("packages").path(CollieUpdateIndex.PACKAGE_NAME).path("versions") as ObjectNode
    val next = versions.elements().next().deepCopy<ObjectNode>()
    (next.path("manifest") as ObjectNode).put("versionCode", 177706).put("versionName", "8.35.2")
    (next.path("file") as ObjectNode).put("name", "/im.collie.app_177706.apk").put("sha256", "cd".repeat(32))
    versions.set<JsonNode>("cd".repeat(32), next)
    assertEquals(177706L, parse(root.toString()).update!!.versionCode)
    (next.path("manifest") as ObjectNode).put("versionCode", 177705)
    assertThrows(IOException::class.java) { parse(root.toString()) }
  }

  @Test
  fun `signature fixture bytes equal the exact authenticated entry despite manifest being last`() {
    val entries = mutableMapOf<String, ByteArray>()
    ZipInputStream(ByteArrayInputStream(signedFixture)).use { input ->
      while (true) {
        val entry = input.nextEntry ?: break
        entries[entry.name] = input.readBytes()
      }
    }
    assertArrayEquals(entries.getValue("entry.json"), CollieUpdateIndex.verifyArchive(ByteArrayInputStream(signedFixture), temporary.root))
  }

  private fun parse(json: String = metadata(), current: Long = 177704, timestamp: Long = 1794370488000): CollieUpdateIndex.Result {
    return CollieUpdateIndex.parseVerifiedIndex(json.toByteArray(), timestamp, current, 35, setOf("arm64-v8a"))
  }

  private fun metadata(): String = """
    {"repo":{"address":"https://repo.collie.im/fdroid/repo","timestamp":1794370488000},
    "packages":{"im.collie.app":{"metadata":{"preferredSigner":"${CollieUpdateIndex.APK_CERTIFICATE}"},
    "versions":{"${"ab".repeat(32)}":{"file":{"name":"/im.collie.app_177705.apk","size":100,"sha256":"${"ab".repeat(32)}"},
    "manifest":{"versionCode":177705,"versionName":"8.35.1-3-rc2","signer":{"sha256":["${CollieUpdateIndex.APK_CERTIFICATE}"]},
    "usesSdk":{"minSdkVersion":27},"nativecode":["arm64-v8a"]}}}}}}
  """.trimIndent()

  private fun fixture(name: String) = requireNotNull(javaClass.getResourceAsStream("/apkupdate/$name")).use { it.readBytes() }
  private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

  private fun rewriteFixture(transform: (String, ByteArray) -> ByteArray?): ByteArray {
    val entries = mutableListOf<Pair<String, ByteArray>>()
    ZipInputStream(ByteArrayInputStream(signedFixture)).use { input ->
      while (true) {
        val entry = input.nextEntry ?: break
        transform(entry.name, input.readBytes())?.let { entries += entry.name to it }
      }
    }
    return zip(*entries.toTypedArray())
  }

  private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
    val output = ByteArrayOutputStream()
    ZipOutputStream(output).use { zip ->
      for ((name, bytes) in entries) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(bytes)
        zip.closeEntry()
      }
    }
    return output.toByteArray()
  }
}
