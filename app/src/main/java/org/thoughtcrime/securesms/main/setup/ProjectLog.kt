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
 * A release file starts with `version` and `date`, and may select a `highlights` edition and an exact GitHub `tag`.
 * Stable and Beta records live in separate product directories. Pending fragments are authoring inputs, not releases.
 */
object ProjectLog {
  const val ASSET_DIR = "collie-log"
  const val RELEASES_DIR = "releases"
  const val UNRELEASED_DIR = "unreleased"
  const val MAX_LENGTH = 120
  const val PR_URL = "https://github.com/0cwa/collieim-android/pull/"
  private const val RELEASE_URL = "https://github.com/0cwa/collieim-android/releases/tag/"

  /** Topics a line may carry. Each has a label in collie_log.xml. */
  val TOPICS = setOf("setup", "push", "notifications", "backups", "privacy", "security", "updates", "settings", "devices", "look", "signal", "fixes")

  private val KEYS = setOf("version", "date", "highlights", "tag", "topic", "pr")
  private val FIELD = Regex("""^([a-z]+):\s*(.*)$""")
  private val IDENTIFIER = Regex("""[A-Za-z0-9][A-Za-z0-9._-]*""")
  private val VERSION = Regex("""^(\d+)\.(\d+)\.(\d+)(?:-(\d+))?(?:-(alpha|beta|rc)(\d+))?$""")

  data class Entry(val text: String, val topic: String? = null, val pr: Int? = null)

  /** A finalized record. An empty maintenance release is still a release. */
  data class Release(
    val version: String,
    val date: LocalDate,
    val entries: List<Entry>,
    val highlightsId: String? = null,
    val tag: String? = null
  ) {
    val releaseUrl: String? get() = tag?.takeIf(IDENTIFIER::matches)?.let { RELEASE_URL + it }
  }

  enum class CurrentKind { RELEASE, DEVELOPMENT, UNAVAILABLE }

  data class Current(
    val version: String,
    val kind: CurrentKind,
    val entries: List<Entry>,
    val date: LocalDate? = null,
    val highlightsId: String? = null,
    val releaseUrl: String? = null
  )

  /** Both tabs receive this one selection. History never awards itself an installed-version badge. */
  data class Notes(val current: Current, val history: List<Release>)

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

  /** Product selection happens before loading these files; a suffix never selects a product. */
  fun releases(releaseFiles: List<String>): List<Release> {
    return releaseFiles.map(::parse).mapNotNull { file ->
      val version = file.fields["version"] ?: return@mapNotNull null
      val date = parseDate(file.fields["date"]) ?: return@mapNotNull null
      if (!IDENTIFIER.matches(version)) return@mapNotNull null
      val highlights = file.fields["highlights"]
      val tag = file.fields["tag"]
      if (highlights != null && !IDENTIFIER.matches(highlights)) return@mapNotNull null
      if (tag != null && !IDENTIFIER.matches(tag)) return@mapNotNull null
      Release(version, date, file.entries, highlights, tag)
    }.sortedWith { left, right ->
      right.date.compareTo(left.date).takeIf { it != 0 } ?: compareVersions(right.version, left.version)
    }
  }

  fun notes(
    releaseFiles: List<String>,
    unreleasedFiles: List<String>,
    installedVersion: String,
    prepared: Boolean
  ): Notes {
    val released = releases(releaseFiles)
    // Duplicate records are invalid input, not two sections with the same Compose key.
    val unique = released.groupBy { it.version }.values.mapNotNull { it.singleOrNull() }
    if (!prepared) {
      val pending = unreleasedFiles.flatMap { parse(it).entries }
        .sortedByDescending { it.pr ?: Int.MAX_VALUE }
      return Notes(Current(installedVersion, CurrentKind.DEVELOPMENT, pending), unique)
    }

    val current = unique.singleOrNull { it.version == installedVersion }
    if (current == null || unreleasedFiles.isNotEmpty()) {
      return Notes(Current(installedVersion, CurrentKind.UNAVAILABLE, emptyList()), unique.filterNot { it.version == installedVersion })
    }
    return Notes(
      Current(installedVersion, CurrentKind.RELEASE, current.entries, current.date, current.highlightsId, current.releaseUrl),
      unique.filterNot { it === current }
    )
  }

  /** Problems that would make a file show badly. Bundled files must have none; a unit test checks them. */
  fun problems(name: String, text: String, isRelease: Boolean): List<String> {
    val file = parse(text)
    val problems = mutableListOf<String>()
    if (isRelease) {
      if (file.fields["version"]?.matches(IDENTIFIER) != true) problems += "$name: invalid version"
      if (parseDate(file.fields["date"]) == null) problems += "$name: date must be YYYY-MM-DD"
      for (field in listOf("highlights", "tag")) {
        if (file.fields[field]?.matches(IDENTIFIER) == false) problems += "$name: invalid $field"
      }
    }
    if (!isRelease && file.entries.isEmpty()) problems += "$name: no entries"
    file.entries.forEach { entry ->
      if (entry.text.length > MAX_LENGTH) problems += "$name: longer than $MAX_LENGTH characters: ${entry.text}"
      if (entry.topic != null && entry.topic !in TOPICS) problems += "$name: unknown topic ${entry.topic}"
    }
    return problems
  }

  fun displayVersion(version: String): String = version.replace(Regex("""-(alpha|beta|rc)(\d+)$""")) { match ->
    val stage = when (match.groupValues[1]) {
      "alpha" -> "Alpha"
      "beta" -> "Beta"
      else -> "RC"
    }
    " $stage${match.groupValues[2]}"
  }

  /** A same-day RC10 follows RC9, and a final release follows its candidates. */
  private fun compareVersions(left: String, right: String): Int {
    fun components(value: String): List<Long>? {
      val match = VERSION.matchEntire(value) ?: return null
      val numbers = (1..4).map { index -> match.groupValues[index].ifEmpty { "0" }.toLongOrNull() ?: return null }
      val stage = when (match.groupValues[5]) {
        "alpha" -> 0L
        "beta" -> 1L
        "rc" -> 2L
        else -> 3L
      }
      return numbers + stage + (match.groupValues[6].ifEmpty { "0" }.toLongOrNull() ?: return null)
    }
    val a = components(left)
    val b = components(right)
    if (a == null && b == null) return left.compareTo(right)
    if (a == null) return -1
    if (b == null) return 1
    for (index in a.indices) {
      val result = a[index].compareTo(b[index])
      if (result != 0) return result
    }
    return left.compareTo(right)
  }

  private fun parseDate(value: String?): LocalDate? {
    return try {
      value?.let(LocalDate::parse)
    } catch (e: DateTimeParseException) {
      null
    }
  }
}
