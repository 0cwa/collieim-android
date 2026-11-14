/*
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.main.setup

import org.junit.Assert.assertEquals
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
  fun `a release file has a header block and then entries`() {
    val file = ProjectLog.parse("version: 8.35.2-1\ndate: 2026-11-20\n\ntopic: signal\nBased on Signal 8.35.2.\n\r\n\nNote: words that look like a field stay in the sentence.\n")

    assertEquals(mapOf("version" to "8.35.2-1", "date" to "2026-11-20"), file.fields)
    assertEquals(2, file.entries.size)
    assertEquals("Note: words that look like a field stay in the sentence.", file.entries[1].text)
    assertNull(file.entries[1].topic)
  }

  @Test
  fun `releases are newest first with this build's changes on top`() {
    val releases = ProjectLog.releases(
      releaseFiles = listOf(
        "version: 1.0\ndate: 2026-01-01\n\nOld.",
        "version: 1.1\ndate: 2026-02-01\n\nNewer.",
        "version: broken\ndate: someday\n\nLeft out.",
        "version: empty\ndate: 2026-03-01\n"
      ),
      unreleasedFiles = listOf("pr: 9\nNine.", "No number.", "pr: 12\nTwelve.")
    )

    assertEquals(listOf(null, "1.1", "1.0"), releases.map { it.version })
    assertEquals(listOf("No number.", "Twelve.", "Nine."), releases[0].entries.map { it.text })
    assertEquals(LocalDate.of(2026, 2, 1), releases[1].date)
  }

  @Test
  fun `without unreleased changes the newest release comes first`() {
    val releases = ProjectLog.releases(listOf("version: 1.0\ndate: 2026-01-01\n\nOld."), emptyList())

    assertEquals(listOf("1.0"), releases.map { it.version })
  }

  @Test
  fun `release candidates read like their release titles`() {
    assertEquals("8.35.1-3 RC2", ProjectLog.displayVersion("8.35.1-3-rc2"))
    assertEquals("8.35.2-1", ProjectLog.displayVersion("8.35.2-1"))
  }

  @Test
  fun `long lines and unknown topics are problems`() {
    val problems = ProjectLog.problems("x.txt", "topic: weather\n" + "a".repeat(ProjectLog.MAX_LENGTH + 1), isRelease = true)

    assertEquals(4, problems.size)
  }

  @Test
  fun `every bundled log file reads cleanly`() {
    val root = File("src/main/assets/${ProjectLog.ASSET_DIR}")
    val releases = File(root, ProjectLog.RELEASES_DIR).listFiles().orEmpty().toList()
    val unreleased = File(root, ProjectLog.UNRELEASED_DIR).listFiles().orEmpty().toList()
    assertTrue("No bundled releases found under ${root.absolutePath}", releases.isNotEmpty())

    val problems = releases.flatMap { ProjectLog.problems(it.name, it.readText(), isRelease = true) } +
      unreleased.flatMap { ProjectLog.problems(it.name, it.readText(), isRelease = false) }
    assertEquals(emptyList<String>(), problems)

    val versions = releases.map { ProjectLog.parse(it.readText()).fields["version"] + ".txt" }
    assertEquals("Release files are named after their version", releases.map { it.name }.sorted(), versions.sorted())
  }
}
