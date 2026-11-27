/*
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.main.setup

import android.content.Context
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import im.molly.app.base.ApkInfo
import org.signal.core.ui.compose.SignalIcons
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.BuildConfig
import org.thoughtcrime.securesms.R
import java.io.IOException
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Reads one immutable set of bundled notes for both What's new tabs. Nothing is fetched. */
object ProjectLogRepository {
  private val TAG = Log.tag(ProjectLogRepository::class)
  private var cached: ProjectLog.Notes? = null

  @Synchronized
  fun load(context: Context): ProjectLog.Notes {
    cached?.let { return it }
    val notes = try {
      val product = if (BuildConfig.TRAILBLAZER_BETA) "beta" else "stable"
      ProjectLog.notes(
        releaseFiles = read(context, "${ProjectLog.RELEASES_DIR}/$product"),
        unreleasedFiles = read(context, ProjectLog.UNRELEASED_DIR),
        installedVersion = ApkInfo.versionName,
        prepared = BuildConfig.COLLIE_RELEASE_PREPARED
      )
    } catch (e: IOException) {
      Log.w(TAG, "Could not read the project log", e)
      ProjectLog.Notes(
        current = ProjectLog.Current(ApkInfo.versionName, ProjectLog.CurrentKind.UNAVAILABLE, emptyList()),
        history = emptyList()
      )
    }
    cached = notes
    return notes
  }

  private fun read(context: Context, directory: String): List<String> {
    val path = "${ProjectLog.ASSET_DIR}/$directory"
    return context.assets.list(path).orEmpty().sorted().map { name ->
      context.assets.open("$path/$name").bufferedReader().use { it.readText() }
    }
  }
}

/** The installed build always has one section; earlier releases from the same product follow it. */
@Composable
fun ProjectLogTab(
  notes: ProjectLog.Notes,
  onOpenPullRequest: (Int) -> Unit,
  modifier: Modifier = Modifier
) {
  var open by rememberSaveable(notes.current.version, notes.current.kind) { mutableStateOf(setOf("current")) }
  val current = notes.current

  LazyColumn(
    contentPadding = PaddingValues(top = 16.dp, bottom = 24.dp),
    modifier = modifier.testTag("project-log-list")
  ) {
    item {
      Text(
        text = stringResource(R.string.CollieLog__intro),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
      )
    }

    item(key = "header-current") {
      ReleaseHeader(
        version = current.version,
        detail = when (current.kind) {
          ProjectLog.CurrentKind.RELEASE -> current.date?.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)).orEmpty()
          ProjectLog.CurrentKind.DEVELOPMENT -> stringResource(R.string.CollieLog__development_build)
          ProjectLog.CurrentKind.UNAVAILABLE -> stringResource(R.string.CollieLog__unavailable)
        },
        changeCount = current.entries.size,
        isInstalled = true,
        expanded = "current" in open,
        onToggle = { open = if ("current" in open) open - "current" else open + "current" },
        modifier = Modifier.testTag("project-log-current")
      )
    }

    if ("current" in open) {
      itemsIndexed(current.entries, key = { index, _ -> "current/$index" }) { _, entry ->
        LogEntryRow(entry = entry, onOpenPullRequest = onOpenPullRequest)
      }
      if (current.entries.isEmpty() && current.kind != ProjectLog.CurrentKind.UNAVAILABLE) {
        item(key = "current-empty") {
          Text(
            text = stringResource(R.string.CollieLog__no_changes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
          )
        }
      }
    }

    notes.history.forEach { release ->
      val key = "release-${release.version}"
      val expanded = key in open

      item(key = "divider-$key") { HorizontalDivider(modifier = Modifier.padding(horizontal = 24.dp)) }
      item(key = "header-$key") {
        ReleaseHeader(
          version = release.version,
          detail = release.date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)),
          changeCount = release.entries.size,
          isInstalled = false,
          expanded = expanded,
          onToggle = { open = if (expanded) open - key else open + key }
        )
      }

      if (expanded) {
        itemsIndexed(release.entries, key = { index, _ -> "$key/$index" }) { _, entry ->
          LogEntryRow(entry = entry, onOpenPullRequest = onOpenPullRequest)
        }
      }
    }

    if (current.entries.any { it.pr != null } || notes.history.any { release -> release.entries.any { it.pr != null } }) {
      item {
        Text(
          text = stringResource(R.string.CollieLog__links_note),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp)
        )
      }
    }
  }
}

@Composable
private fun ReleaseHeader(
  version: String,
  detail: String,
  changeCount: Int,
  isInstalled: Boolean,
  expanded: Boolean,
  onToggle: () -> Unit,
  modifier: Modifier = Modifier
) {
  val canExpand = changeCount > 0
  val rotation by animateFloatAsState(targetValue = if (expanded) 270f else 90f, label = "chevron")
  val expandedText = stringResource(if (expanded) R.string.CollieLog__collapse else R.string.CollieLog__expand)

  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = modifier
      .fillMaxWidth()
      .then(if (canExpand) Modifier.clickable(role = Role.Button, onClick = onToggle) else Modifier)
      .semantics(mergeDescendants = true) { if (canExpand) stateDescription = expandedText }
      .padding(horizontal = 24.dp, vertical = 14.dp)
  ) {
    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Text(text = ProjectLog.displayVersion(version), style = MaterialTheme.typography.titleMedium)
      if (isInstalled) {
        Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small) {
          Text(
            text = stringResource(R.string.CollieLog__your_version),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
          )
        }
      }
      Text(
        text = listOfNotNull(detail.takeIf { it.isNotEmpty() }, if (canExpand) pluralStringResource(R.plurals.CollieLog__changes, changeCount, changeCount) else null)
          .joinToString(" · "),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
      )
    }
    if (canExpand) {
      Icon(
        imageVector = SignalIcons.ChevronRight.imageVector,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.rotate(rotation)
      )
    }
  }
}

@Composable
private fun LogEntryRow(entry: ProjectLog.Entry, onOpenPullRequest: (Int) -> Unit) {
  val pr = entry.pr
  val linkLabel = stringResource(R.string.CollieLog__details_on_github)

  Row(
    verticalAlignment = Alignment.Top,
    modifier = Modifier
      .fillMaxWidth()
      .then(if (pr != null) Modifier.clickable(role = Role.Button, onClickLabel = linkLabel) { onOpenPullRequest(pr) } else Modifier)
      .padding(start = 24.dp, end = 24.dp, top = 6.dp, bottom = 10.dp)
  ) {
    Column(modifier = Modifier.weight(1f)) {
      topicLabel(entry.topic)?.let { label ->
        Text(text = stringResource(label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
      }
      Text(text = entry.text, style = MaterialTheme.typography.bodyLarge)
    }
    if (pr != null) {
      Spacer(modifier = Modifier.width(12.dp))
      Icon(
        imageVector = SignalIcons.Open.imageVector,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        // Centred on the first line of the sentence, below the topic label when there is one.
        modifier = Modifier
          .padding(top = if (topicLabel(entry.topic) != null) 19.dp else 3.dp)
          .size(18.dp)
      )
    }
  }
}

private fun topicLabel(topic: String?): Int? = when (topic) {
  "setup" -> R.string.CollieLog__topic_setup
  "push" -> R.string.CollieLog__topic_push
  "notifications" -> R.string.CollieLog__topic_notifications
  "backups" -> R.string.CollieLog__topic_backups
  "privacy" -> R.string.CollieLog__topic_privacy
  "security" -> R.string.CollieLog__topic_security
  "updates" -> R.string.CollieLog__topic_updates
  "settings" -> R.string.CollieLog__topic_settings
  "devices" -> R.string.CollieLog__topic_devices
  "look" -> R.string.CollieLog__topic_look
  "signal" -> R.string.CollieLog__topic_signal
  "fixes" -> R.string.CollieLog__topic_fixes
  else -> null
}
