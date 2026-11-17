package org.thoughtcrime.securesms.apkupdate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.signal.core.util.JsonUtils

class CollieUpdateProfileTest {
  @Test
  fun `compiled identities match the reviewed engineering contract snapshot`() {
    val snapshot = requireNotNull(javaClass.getResourceAsStream("/apkupdate/update-profiles.json")).use {
      JsonUtils.getMapper().readTree(it)
    }
    assertEquals("ee8dd193a430402aa6f964ff8a496958ec58fa73", snapshot.path("engineering_revision").asText())
    val profiles = snapshot.path("profiles")
    assertEquals(profiles.fieldNames().asSequence().toSet(), CollieUpdateProfile.entries.map { it.channelName }.toSet())
    for (profile in CollieUpdateProfile.entries) {
      val expected = profiles.path(profile.channelName)
      assertEquals(expected.path("package").asText(), profile.packageName)
      assertEquals(expected.path("repository_url").asText().removeSuffix("/"), profile.repositoryUrl)
      assertEquals(expected.path("repository_certificate").asText(), profile.repositoryCertificate)
      assertEquals(expected.path("apk_certificate").asText(), profile.apkCertificate)
      assertEquals(expected.path("tag_prefix").asText(), profile.tagPrefix)
    }
  }

  @Test
  fun `only exact production package identities select a profile`() {
    assertEquals(CollieUpdateProfile.STABLE, CollieUpdateProfile.forApplicationId("im.collie.app"))
    assertEquals(CollieUpdateProfile.TRAILBLAZER_BETA, CollieUpdateProfile.forApplicationId("im.collie.app.trailblazer"))
    for (packageName in listOf("", "im.molly.app", "IM.COLLIE.APP", "im.collie.app.staging", "im.collie.app.imo", "im.collie.app.trailblazer.staging", "im.collie.app.trailblazer.extra")) {
      assertNull(packageName, CollieUpdateProfile.forApplicationId(packageName))
    }
  }

  @Test
  fun `the same version routes to its installed product release namespace`() {
    assertEquals("https://github.com/0cwa/collieim-android/releases/tag/v8.35.1-3-rc2", CollieUpdateProfile.STABLE.releasePage("8.35.1-3-rc2"))
    assertEquals("https://github.com/0cwa/collieim-android/releases/tag/trailblazer-v8.35.1-3-rc2", CollieUpdateProfile.TRAILBLAZER_BETA.releasePage("8.35.1-3-rc2"))
  }

  @Test
  fun `stable version policy is unchanged and Beta rejects development identities`() {
    for (version in listOf("8.35.2", "8.35.2-1", "8.35.2-1-rc1", "8.35.2-beta1", "8.35.2-alpha1")) {
      assertTrue(version, CollieUpdateProfile.STABLE.isValidVersionName(version))
    }
    for (version in listOf("8.35", "8.35.2+build1", "8.35.2-1-port-unvalidated")) {
      assertFalse(version, CollieUpdateProfile.STABLE.isValidVersionName(version))
    }
    for (version in listOf("8.36.0", "8.36.0-beta1", "8.36.0-1+build2")) {
      assertTrue(version, CollieUpdateProfile.TRAILBLAZER_BETA.isValidVersionName(version))
    }
    for (version in listOf("8.36.0-dev", "8.36.0-development", "8.36.0-1-port-unvalidated", "8.36.0+LOCAL", "8.36.0-snapshot.1")) {
      assertFalse(version, CollieUpdateProfile.TRAILBLAZER_BETA.isValidVersionName(version))
    }
  }

  @Test
  fun `Beta accepts long engineering-valid versions while Stable retains its length limit`() {
    val longVersion = "8.36.0-" + "1".repeat(74)
    assertEquals(81, longVersion.length)
    assertTrue(CollieUpdateProfile.TRAILBLAZER_BETA.isValidVersionName(longVersion))
    assertEquals("https://github.com/0cwa/collieim-android/releases/tag/trailblazer-v$longVersion", CollieUpdateProfile.TRAILBLAZER_BETA.releasePage(longVersion))
    assertTrue(CollieUpdateProfile.STABLE.isValidVersionName("1".repeat(75) + ".36.0"))
    assertFalse(CollieUpdateProfile.STABLE.isValidVersionName("1".repeat(76) + ".36.0"))
  }

  @Test
  fun `neither product accepts a version that changes the release URL`() {
    for (profile in CollieUpdateProfile.entries) {
      for (version in listOf("../8.35.2", "8.35.2?x=y", "8.35.2#other", "https://example.org", "8.35.2/other", "8.35.2%2f..", "8.35.2\n")) {
        assertThrows(IllegalArgumentException::class.java) { profile.releasePage(version) }
      }
    }
  }
}
