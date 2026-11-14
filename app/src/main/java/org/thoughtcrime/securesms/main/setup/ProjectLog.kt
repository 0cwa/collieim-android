/*
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.main.setup

import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * The project log: one plain sentence per change people would notice, grouped by release. It ships inside the APK
 * under assets/collie-log and is never fetched. See docs/PROJECT-LOG.md for how entries are written.
 *
 * A file is blocks separated by blank lines. A block starts with `key: value` lines and ends with the sentence:
 *
 * ```
 * topic: push
 * pr: 96
 * Collie notices when push stops working. Settings has a Test my push button.
 * ```
 *
 * A release file starts with a block that has only `version` and `date`.
 */
object ProjectLog {
  const val ASSET_DIR = "collie-log"
  const val RELEASES_DIR = "releases"
  const val UNRELEASED_DIR = "unreleased"
  const val MAX_LENGTH = 120
  const val PR_URL = "https://github.com/0cwa/collieim-android/pull/"

  /** Topics a line may carry. Each has a label in collie_log.xml. */
  val TOPICS = setOf("setup", "push", "notifications", "backups", "privacy", "security", "updates", "settings", "devices", "look", "signal", "fixes")

  private val KEYS = setOf("version", "date", "topic", "pr")
  private val FIELD = Regex("""^([a-z]+):\s*(.*)$""")

  data class Entry(val text: String, val topic: String? = null, val pr: Int? = null)

  /** The changes in one release, or in this build when [version] is null: merged since the last release. */
  data class Release(val version: String?, val date: LocalDate?, val entries: List<Entry>)

  data class File(val fields: Map<String, String>, val entries: List<Entry>)

  fun parse(text: String): File {
    val header = mutableMapOf<String, String>()
    val entries = mutableListOf<Entry>()

    text.replace("\r\n", "\n").split(Regex("""\n\s*\n""")).forEach { block ->
      val fields = mutableMapOf<String, String>()
      val sentence = mutableListOf<String>()
      block.lines().map { it.trim() }.filter { it.isNotEmpty() }.forEach { line ->
        val match = FIELD.matchEntire(line)
        if (sentence.isEmpty() && match != null && match.groupValues[1] in KEYS) {
          fields[match.groupValues[1]] = match.groupValues[2].trim()
        } else {
          sentence += line
        }
      }

      if (sentence.isEmpty()) {
        header += fields
      } else {
        entries += Entry(
          text = sentence.joinToString(" "),
          topic = fields["topic"]?.lowercase(),
          pr = fields["pr"]?.removePrefix("#")?.toIntOrNull()
        )
      }
    }

    return File(header, entries)
  }

  /**
   * Newest first. Merged-but-unreleased entries come first as one group with no version, newest pull request first.
   * Release files without a version or a valid date are left out rather than shown wrong.
   */
  fun releases(releaseFiles: List<String>, unreleasedFiles: List<String>): List<Release> {
    val released = releaseFiles.map(::parse).mapNotNull { file ->
      val version = file.fields["version"] ?: return@mapNotNull null
      val date = parseDate(file.fields["date"]) ?: return@mapNotNull null
      Release(version, date, file.entries)
    }.filter { it.entries.isNotEmpty() }
      .sortedWith(compareByDescending<Release> { it.date }.thenByDescending { it.version })

    val pending = unreleasedFiles.flatMap { parse(it).entries }
      .sortedWith(compareByDescending<Entry> { it.pr ?: Int.MAX_VALUE })

    return if (pending.isEmpty()) released else listOf(Release(null, null, pending)) + released
  }

  /** Problems that would make a file show badly. Bundled files must have none; a unit test checks them. */
  fun problems(name: String, text: String, isRelease: Boolean): List<String> {
    val file = parse(text)
    val problems = mutableListOf<String>()
    if (isRelease) {
      if (file.fields["version"].isNullOrBlank()) problems += "$name: no version"
      if (parseDate(file.fields["date"]) == null) problems += "$name: date must be YYYY-MM-DD"
    }
    if (file.entries.isEmpty()) problems += "$name: no entries"
    file.entries.forEach { entry ->
      if (entry.text.length > MAX_LENGTH) problems += "$name: longer than $MAX_LENGTH characters: ${entry.text}"
      if (entry.topic != null && entry.topic !in TOPICS) problems += "$name: unknown topic ${entry.topic}"
    }
    return problems
  }

  /** "8.35.1-3-rc2" reads as "8.35.1-3 RC2", the way release titles write it. */
  fun displayVersion(version: String): String = version.replace(Regex("""-rc(\d+)$"""), " RC$1")

  private fun parseDate(value: String?): LocalDate? {
    return try {
      value?.let(LocalDate::parse)
    } catch (e: DateTimeParseException) {
      null
    }
  }
}
