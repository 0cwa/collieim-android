/*
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.main.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.os.bundleOf
import androidx.fragment.app.FragmentManager
import im.molly.app.base.ApkInfo
import kotlinx.coroutines.withContext
import org.signal.core.ui.R as CoreUiR
import org.signal.core.ui.compose.ComposeFullScreenDialogFragment
import org.signal.core.ui.compose.Scaffolds
import org.signal.core.ui.compose.SignalIcons
import org.signal.core.ui.compose.TitledTabRow
import org.signal.core.util.concurrent.SignalDispatchers
import org.thoughtcrime.securesms.R

/** Which tab What's new opens on. */
enum class WhatsNewTab {
  THIS_UPDATE,
  PROJECT_LOG
}

/** Both tabs describe the same bundled current release; reading history does not mark its highlights seen. */
class WhatsNewDialogFragment : ComposeFullScreenDialogFragment() {

  companion object {
    private const val TAG = "WhatsNewDialogFragment"
    private const val ARG_TAB = "tab"

    @JvmStatic
    @JvmOverloads
    fun show(fragmentManager: FragmentManager, tab: WhatsNewTab = WhatsNewTab.THIS_UPDATE) {
      if (fragmentManager.findFragmentByTag(TAG) == null) {
        WhatsNewDialogFragment().apply {
          arguments = bundleOf(ARG_TAB to tab.name)
        }.show(fragmentManager, TAG)
      }
    }
  }

  @Composable
  override fun DialogContent() {
    val context = LocalContext.current
    val notes by produceState<ProjectLog.Notes?>(initialValue = null) {
      value = withContext(SignalDispatchers.IO) { ProjectLogRepository.load(context.applicationContext) }
    }
    val loaded = notes
    if (loaded == null) {
      Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
      }
      return
    }

    val startTab = WhatsNewTab.entries.firstOrNull { it.name == arguments?.getString(ARG_TAB) } ?: WhatsNewTab.THIS_UPDATE
    WhatsNewContent(
      notes = loaded,
      startTab = startTab,
      onBack = { dismissAllowingStateLoss() },
      onReleaseAction = { SetupActions.actOnReleaseNotice(requireActivity(), it) },
      onFullNotes = { SetupActions.openReleaseNotes(requireActivity(), it) },
      onSupport = { SetupActions.openSupport(requireActivity()) },
      onOpenPullRequest = { SetupActions.openPullRequest(requireActivity(), it) },
      onHighlightsSeen = SetupStatusRepository::markNotesSeen
    )
  }
}

@Composable
internal fun WhatsNewContent(
  notes: ProjectLog.Notes,
  startTab: WhatsNewTab,
  onBack: () -> Unit,
  onReleaseAction: (CollieReleaseNotes.Edition) -> Unit,
  onFullNotes: (String) -> Unit,
  onSupport: () -> Unit,
  onOpenPullRequest: (Int) -> Unit,
  onHighlightsSeen: (String) -> Unit
) {
  var tab by rememberSaveable { mutableStateOf(startTab) }
  val edition = if (notes.current.kind == ProjectLog.CurrentKind.RELEASE) CollieReleaseNotes.edition(notes.current.highlightsId) else null

  LaunchedEffect(tab, edition?.id) {
    if (tab == WhatsNewTab.THIS_UPDATE && edition != null) {
      onHighlightsSeen(edition.id)
    }
  }

  Scaffolds.Settings(
    title = stringResource(R.string.CollieWhatsNew__title),
    onNavigationClick = onBack,
    navigationIcon = SignalIcons.ArrowStart.imageVector,
    navigationContentDescription = stringResource(CoreUiR.string.DefaultTopAppBar__navigate_up_content_description)
  ) { padding ->
    Column(modifier = Modifier.padding(padding)) {
      TitledTabRow(
        tabTitles = listOf(stringResource(R.string.CollieWhatsNew__tab_this_update), stringResource(R.string.CollieWhatsNew__tab_project_log)),
        selectedTabIndex = tab.ordinal,
        onTabClick = { tab = WhatsNewTab.entries[it] }
      )

      when (tab) {
        WhatsNewTab.THIS_UPDATE -> ThisUpdate(
          current = notes.current,
          edition = edition,
          onReleaseAction = onReleaseAction,
          onFullNotes = onFullNotes,
          onSupport = onSupport,
          onProjectLog = { tab = WhatsNewTab.PROJECT_LOG }
        )
        WhatsNewTab.PROJECT_LOG -> ProjectLogTab(notes = notes, onOpenPullRequest = onOpenPullRequest)
      }
    }
  }
}

@Composable
private fun ThisUpdate(
  current: ProjectLog.Current,
  edition: CollieReleaseNotes.Edition?,
  onReleaseAction: (CollieReleaseNotes.Edition) -> Unit,
  onFullNotes: (String) -> Unit,
  onSupport: () -> Unit,
  onProjectLog: () -> Unit
) {
  LazyColumn(
    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
    verticalArrangement = Arrangement.spacedBy(20.dp),
    modifier = Modifier.testTag("whats-new-highlights")
  ) {
    item {
      Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = stringResource(R.string.CollieWhatsNew__heading), style = MaterialTheme.typography.headlineSmall)
        Text(
          text = stringResource(R.string.CollieWhatsNew__installed_version, current.version),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
      }
    }

    if (edition?.action != null) {
      item {
        ProblemBanner(
          problem = Problem.RELEASE_ACTION,
          onFix = { onReleaseAction(edition) },
          onDismiss = null,
          contentPadding = PaddingValues(0.dp),
          releaseAction = edition.action
        )
      }
    }

    if (edition == null) {
      item {
        Text(
          text = stringResource(
            when (current.kind) {
              ProjectLog.CurrentKind.RELEASE -> R.string.CollieWhatsNew__no_highlights
              ProjectLog.CurrentKind.DEVELOPMENT -> R.string.CollieWhatsNew__development
              ProjectLog.CurrentKind.UNAVAILABLE -> R.string.CollieLog__unavailable
            }
          ),
          style = MaterialTheme.typography.bodyLarge,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
      }
    }

    items(edition?.highlights.orEmpty()) { highlight ->
      Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
          text = if (highlight.titleIncludesSignalVersion) stringResource(highlight.title, ApkInfo.signalCanonicalVersionName) else stringResource(highlight.title),
          style = MaterialTheme.typography.titleMedium
        )
        Text(
          text = stringResource(highlight.body),
          style = MaterialTheme.typography.bodyLarge,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
      }
    }

    item {
      Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FilledTonalButton(onClick = onProjectLog, modifier = Modifier.fillMaxWidth()) {
          Text(text = stringResource(R.string.CollieWhatsNew__see_project_log))
        }
        current.releaseUrl?.let { url ->
          TextButton(onClick = { onFullNotes(url) }, modifier = Modifier.fillMaxWidth()) {
            Text(text = stringResource(R.string.CollieWhatsNew__full_release_notes))
            Icon(
              imageVector = SignalIcons.Open.imageVector,
              contentDescription = null,
              modifier = Modifier.padding(start = 8.dp)
            )
          }
        }
        TextButton(onClick = onSupport, modifier = Modifier.fillMaxWidth()) {
          Text(text = stringResource(R.string.CollieSetup__support))
          Icon(
            imageVector = SignalIcons.Open.imageVector,
            contentDescription = null,
            modifier = Modifier.padding(start = 8.dp)
          )
        }
      }
    }
  }
}
