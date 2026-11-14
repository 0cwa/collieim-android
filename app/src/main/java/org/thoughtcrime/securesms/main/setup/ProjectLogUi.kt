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
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.withContext
import org.signal.core.ui.compose.SignalIcons
import org.signal.core.util.concurrent.SignalDispatchers
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.R
import java.io.IOException
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Reads the project log bundled under assets/collie-log. Nothing is fetched. */
object ProjectLogRepository {
  private val TAG = Log.tag(ProjectLogRepository::class)

  fun load(context: Context): List<ProjectLog.Release> {
    return try {
      ProjectLog.releases(
        releaseFiles = read(context, ProjectLog.RELEASES_DIR),
        unreleasedFiles = read(context, ProjectLog.UNRELEASED_DIR)
      )
    } catch (e: IOException) {
      Log.w(TAG, "Could not read the project log", e)
      emptyList()
    }
  }

  private fun read(context: Context, directory: String): List<String> {
    val path = "${ProjectLog.ASSET_DIR}/$directory"
    return context.assets.list(path).orEmpty().sorted().map { name ->
      context.assets.open("$path/$name").bufferedReader().use { it.readText() }
    }
  }
}

/**
 * The Project log tab: every change, grouped by release, newest first. The newest group and the installed version
 * start open; older releases are one tap away so the page stays short.
 */
@Composable
fun ProjectLogTab(
  installedVersion: String,
  onOpenPullRequest: (Int) -> Unit,
  modifier: Modifier = Modifier
) {
  val context = LocalContext.current
  val releases by produceState<List<ProjectLog.Release>?>(initialValue = null) {
    value = withContext(SignalDispatchers.IO) { ProjectLogRepository.load(context.applicationContext) }
  }

  val loaded = releases ?: return
  ProjectLogList(releases = loaded, installedVersion = installedVersion, onOpenPullRequest = onOpenPullRequest, modifier = modifier)
}

@Composable
private fun ProjectLogList(
  releases: List<ProjectLog.Release>,
  installedVersion: String,
  onOpenPullRequest: (Int) -> Unit,
  modifier: Modifier = Modifier
) {
  val initiallyOpen = releases.mapIndexedNotNull { index, release ->
    (release.version ?: "").takeIf { index == 0 || release.version == installedVersion }
  }
  var open by rememberSaveable { mutableStateOf(initiallyOpen.toSet()) }

  LazyColumn(
    contentPadding = PaddingValues(top = 16.dp, bottom = 24.dp),
    modifier = modifier
  ) {
    item {
      Text(
        text = stringResource(if (releases.isEmpty()) R.string.CollieLog__empty else R.string.CollieLog__intro),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
      )
    }

    releases.forEachIndexed { index, release ->
      val key = release.version ?: ""
      val expanded = key in open

      if (index > 0) {
        item(key = "divider-$key") { HorizontalDivider(modifier = Modifier.padding(horizontal = 24.dp)) }
      }

      item(key = "header-$key") {
        ReleaseHeader(
          release = release,
          isInstalled = release.version != null && release.version == installedVersion,
          expanded = expanded,
          onToggle = { open = if (expanded) open - key else open + key }
        )
      }

      if (expanded) {
        items(release.entries, key = { "$key/${it.pr}/${it.text}" }) { entry ->
          LogEntryRow(entry = entry, onOpenPullRequest = onOpenPullRequest)
        }
      }
    }

    if (releases.isNotEmpty()) {
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
  release: ProjectLog.Release,
  isInstalled: Boolean,
  expanded: Boolean,
  onToggle: () -> Unit
) {
  val rotation by animateFloatAsState(targetValue = if (expanded) 270f else 90f, label = "chevron")
  val expandedText = stringResource(if (expanded) R.string.CollieLog__collapse else R.string.CollieLog__expand)
  val title = release.version?.let(ProjectLog::displayVersion) ?: stringResource(R.string.CollieLog__this_build)
  val detail = when {
    release.version == null -> stringResource(R.string.CollieLog__this_build_detail)
    else -> release.date?.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)).orEmpty()
  }

  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier
      .fillMaxWidth()
      .clickable(role = Role.Button, onClick = onToggle)
      .semantics(mergeDescendants = true) { stateDescription = expandedText }
      .padding(horizontal = 24.dp, vertical = 14.dp)
  ) {
    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text = title, style = MaterialTheme.typography.titleMedium)
        if (isInstalled) {
          Spacer(modifier = Modifier.width(8.dp))
          Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small) {
            Text(
              text = stringResource(R.string.CollieLog__your_version),
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.onSecondaryContainer,
              modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
            )
          }
        }
      }
      Text(
        text = listOf(detail, pluralStringResource(R.plurals.CollieLog__changes, release.entries.size, release.entries.size))
          .filter { it.isNotEmpty() }
          .joinToString(" · "),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
      )
    }
    Icon(
      imageVector = SignalIcons.ChevronRight.imageVector,
      contentDescription = null,
      tint = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.rotate(rotation)
    )
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
