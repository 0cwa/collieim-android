/*
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.main.setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CollieReleaseNotesTest {

  private val releases = File("src/main/assets/${ProjectLog.ASSET_DIR}/${ProjectLog.RELEASES_DIR}")

  @Test
  fun `absent and unknown highlight IDs never borrow another edition`() {
    assertNull(CollieReleaseNotes.edition(null))
    assertNull(CollieReleaseNotes.edition(""))
    assertNull(CollieReleaseNotes.edition("unknown-edition"))
  }

  @Test
  fun `Stable 8_36_3_3 has its own highlights and update action`() {
    val edition = CollieReleaseNotes.edition(CollieReleaseNotes.STABLE_8_36_3_3)
    assertNotNull(edition)
    assertEquals(CollieReleaseNotes.STABLE_8_36_3_3, edition!!.id)
    assertEquals(5, edition.highlights.size)
    assertEquals(CollieReleaseNotes.Destination.APP_UPDATES, edition.action!!.destination)
  }

  @Test
  fun `every selected bundled highlight edition exists and contains highlights`() {
    val selected = listOf("stable", "beta").flatMap { product ->
      val files = File(releases, product).listFiles().orEmpty().filter { it.extension == "txt" }
      assertTrue("No bundled $product releases under ${releases.absolutePath}", files.isNotEmpty())
      files.mapNotNull { file -> ProjectLog.parse(file.readText()).fields["highlights"]?.let { file to it } }
    }
    assertTrue("No bundled release selects highlights; the registry would not be exercised", selected.isNotEmpty())

    for ((file, id) in selected) {
      val edition = CollieReleaseNotes.edition(id)
      assertNotNull("${file.path}: unknown highlight edition $id", edition)
      assertEquals("${file.path}: selected a different edition", id, edition!!.id)
      assertTrue("${file.path}: selected an empty highlight edition", edition.highlights.isNotEmpty())
    }
  }

  @Test
  fun `historical Stable releases do not acquire TrailBlazer highlights`() {
    // A future Stable release may select its own edition; these existing records did not ship these highlights.
    val versions = listOf(
      "8.33.2-1-rc1", "8.33.2-1-rc2", "8.33.2-1-rc3",
      "8.35.1-1-rc1", "8.35.1-2-rc1", "8.35.1-3-rc1", "8.35.1-3-rc2",
      "8.36.3-1-rc1", "8.36.3-1-rc2"
    )
    for (version in versions) {
      val file = File(releases, "stable/$version.txt")
      assertTrue("Missing historical Stable record ${file.path}", file.isFile)
      assertNull("$version must not borrow the Beta edition", ProjectLog.parse(file.readText()).fields["highlights"])
    }
  }
}
