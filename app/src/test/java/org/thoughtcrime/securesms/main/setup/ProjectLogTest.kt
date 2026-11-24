/*
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.main.setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

class ProjectLogTest {

  @Test
  fun `a pull request file is one entry with its topic and number`() {
    val file = ProjectLog.parse("topic: Push\npr: #96\nCollie notices when push stops working.\nSettings has a Test my push button.\n")
    assertEquals(emptyMap<String, String>(), file.fields)
    assertEquals(listOf(ProjectLog.Entry("Collie notices when push stops working. Settings has a Test my push button.", "push", 96)), file.entries)
  }

  @Test
  fun `release metadata selects highlights and its exact page without becoming changes`() {
    val file = ProjectLog.parse("version: 8.36.3-1-beta1\ndate: 2026-11-21\nhighlights: setup-and-push\ntag: trailblazer-v8.36.3-1-beta1\n\ntopic: signal\nBased on Signal 8.36.3.\n\nNote: words that look like a field stay in the sentence.\n")
    assertEquals("setup-and-push", file.fields["highlights"])
    assertEquals("trailblazer-v8.36.3-1-beta1", file.fields["tag"])
    assertEquals(2, file.entries.size)
    assertEquals("Note: words that look like a field stay in the sentence.", file.entries[1].text)
    assertNull(file.entries[1].topic)
  }

  @Test
  fun `the installed release is selected once even when another record sorts first`() {
    val notes = notes(listOf(release("8.36.3-1-beta1", "Installed.", "current-news"), release("8.36.3-1-beta2", "Later.")))
    assertEquals(ProjectLog.CurrentKind.RELEASE, notes.current.kind)
    assertEquals(listOf("Installed."), notes.current.entries.map { it.text })
    assertEquals("current-news", notes.current.highlightsId)
    assertEquals("https://github.com/0cwa/collieim-android/releases/tag/trailblazer-v8.36.3-1-beta1", notes.current.releaseUrl)
    assertEquals(listOf("8.36.3-1-beta2"), notes.history.map { it.version })
  }

  @Test
  fun `beta1 style leftovers never create another current release or borrow its highlights`() {
    for (pending in listOf("topic: look\nA shipped theme change.", "")) {
      val notes = notes(listOf(release("8.36.3-1-beta1", "Already shipped.", "old-news")), pending = listOf(pending))
      assertEquals(ProjectLog.CurrentKind.UNAVAILABLE, notes.current.kind)
      assertTrue(notes.current.entries.isEmpty())
      assertNull(notes.current.highlightsId)
      assertNull(notes.current.releaseUrl)
      assertTrue(notes.history.isEmpty())
    }
  }

  @Test
  fun `development is explicit even with the same version and no pending entries`() {
    val notes = notes(listOf(release("8.36.3-1-beta1", "Released.", "old-news")), prepared = false)
    assertEquals(ProjectLog.CurrentKind.DEVELOPMENT, notes.current.kind)
    assertTrue(notes.current.entries.isEmpty())
    assertNull(notes.current.highlightsId)
    assertNull(notes.current.releaseUrl)
    assertEquals(listOf("8.36.3-1-beta1"), notes.history.map { it.version })
  }

  @Test
  fun `development preview shows only pending changes in its current section`() {
    val notes = notes(listOf(release("8.36.3-1-beta1", "Already released.")), listOf("pr: 9\nNine.", "No number.", "pr: 12\nTwelve."), prepared = false)
    assertEquals(listOf("No number.", "Twelve.", "Nine."), notes.current.entries.map { it.text })
    assertEquals(listOf("Already released."), notes.history.single().entries.map { it.text })
  }

  @Test
  fun `a missing or duplicate current record never selects the newest announcement`() {
    val missing = notes(listOf(release("8.36.3-1-beta2", "Other.", "other-news")))
    val duplicate = notes(listOf(release("8.36.3-1-beta1", "One.", "one"), release("8.36.3-1-beta1", "Two.", "two")))
    for (result in listOf(missing, duplicate)) {
      assertEquals(ProjectLog.CurrentKind.UNAVAILABLE, result.current.kind)
      assertNull(result.current.highlightsId)
      assertNull(result.current.releaseUrl)
      assertTrue(result.current.entries.isEmpty())
    }
    assertTrue(duplicate.history.isEmpty())
  }

  @Test
  fun `a maintenance release with no entries and no highlights remains current`() {
    val text = release("8.36.3-1-beta1")
    val notes = notes(listOf(text))
    assertEquals(emptyList<String>(), ProjectLog.problems("8.36.3-1-beta1.txt", text, isRelease = true))
    assertEquals(ProjectLog.CurrentKind.RELEASE, notes.current.kind)
    assertTrue(notes.current.entries.isEmpty())
    assertNull(notes.current.highlightsId)
    assertTrue(notes.history.isEmpty())
  }

  @Test
  fun `product inputs determine history even when product versions are identical`() {
    val stable = notes(listOf(release("8.36.3-1-beta1", "New to Stable.", "stable-news", "v8.36.3-1-beta1")))
    val beta = notes(listOf(release("8.36.3-1-beta1", "Tested in Beta.", "beta-news")))
    assertEquals("New to Stable.", stable.current.entries.single().text)
    assertEquals("Tested in Beta.", beta.current.entries.single().text)
    assertEquals("stable-news", stable.current.highlightsId)
    assertEquals("beta-news", beta.current.highlightsId)
    assertTrue(stable.current.releaseUrl!!.endsWith("/v8.36.3-1-beta1"))
    assertTrue(beta.current.releaseUrl!!.endsWith("/trailblazer-v8.36.3-1-beta1"))
  }

  @Test
  fun `same-day releases use numeric candidate order and put the final release first`() {
    val releases = ProjectLog.releases(listOf("8.36.3-1-rc9", "8.36.3-1-rc10", "8.36.3-1").map { release(it) })
    assertEquals(listOf("8.36.3-1", "8.36.3-1-rc10", "8.36.3-1-rc9"), releases.map { it.version })
  }

  @Test
  fun `mixed version forms have the same total order for every input permutation`() {
    val versions = listOf("8.36.3-1-rc9", "8.36.3-1-rc10", "8.36.3-1-rc2x")
    for (first in versions) {
      for (second in versions.filterNot { it == first }) {
        val third = versions.single { it != first && it != second }
        val sorted = ProjectLog.releases(listOf(first, second, third).map { release(it) })
        assertEquals(listOf("8.36.3-1-rc10", "8.36.3-1-rc9", "8.36.3-1-rc2x"), sorted.map { it.version })
      }
    }
  }

  @Test
  fun `dates sort first and malformed release headers are left out`() {
    val releases = ProjectLog.releases(listOf(release("8.36.3-1-beta1").replace("2026-11-21", "2026-11-20"), release("8.36.3-1-beta2"), "version: broken\ndate: someday\n\nLeft out."))
    assertEquals(listOf("8.36.3-1-beta2", "8.36.3-1-beta1"), releases.map { it.version })
    assertEquals(LocalDate.of(2026, 11, 21), releases.first().date)
  }

  @Test
  fun `an external URL or path cannot be used as a release tag`() {
    for (tag in listOf("https://example.com", "../other", "v1?redirect=other")) {
      val text = release("8.36.3-1-beta1", tag = tag)
      assertFalse(ProjectLog.problems("release.txt", text, isRelease = true).isEmpty())
      assertEquals(ProjectLog.CurrentKind.UNAVAILABLE, notes(listOf(text)).current.kind)
    }
  }

  @Test
  fun `display names preserve the underlying version`() {
    assertEquals("8.35.1-3 RC2", ProjectLog.displayVersion("8.35.1-3-rc2"))
    assertEquals("8.36.3-1 Beta1", ProjectLog.displayVersion("8.36.3-1-beta1"))
    assertEquals("8.36.3-1", ProjectLog.displayVersion("8.36.3-1"))
  }

  @Test
  fun `long entries and unknown topics remain authoring problems`() {
    val problems = ProjectLog.problems("x.txt", "topic: weather\n" + "a".repeat(ProjectLog.MAX_LENGTH + 1), isRelease = true)
    assertEquals(4, problems.size)
  }

  @Test
  fun `every bundled product history reads cleanly and has unique version names`() {
    val root = File("src/main/assets/${ProjectLog.ASSET_DIR}")
    for (product in listOf("stable", "beta")) {
      val files = File(root, "${ProjectLog.RELEASES_DIR}/$product").listFiles().orEmpty().toList()
      assertTrue("No bundled $product releases", files.isNotEmpty())
      assertEquals(emptyList<String>(), files.flatMap { ProjectLog.problems(it.name, it.readText(), isRelease = true) })
      val versions = files.map { ProjectLog.parse(it.readText()).fields.getValue("version") }
      assertEquals(versions.size, versions.toSet().size)
      assertEquals(files.map { it.name }.sorted(), versions.map { "$it.txt" }.sorted())
    }
    val pending = File(root, ProjectLog.UNRELEASED_DIR).listFiles().orEmpty()
    assertEquals(emptyList<String>(), pending.flatMap { ProjectLog.problems(it.name, it.readText(), isRelease = false) })
  }

  private fun notes(files: List<String>, pending: List<String> = emptyList(), prepared: Boolean = true): ProjectLog.Notes =
    ProjectLog.notes(files, pending, "8.36.3-1-beta1", prepared)

  private fun release(version: String, entry: String = "", highlights: String? = null, tag: String = "trailblazer-v$version"): String = buildString {
    append("version: $version\ndate: 2026-11-21\ntag: $tag\n")
    highlights?.let { append("highlights: $it\n") }
    if (entry.isNotEmpty()) append("\n$entry\n")
  }
}
