/*
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.main.setup

import android.os.Bundle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.os.bundleOf
import androidx.fragment.app.FragmentManager
import im.molly.app.base.ApkInfo
import org.signal.core.ui.R as CoreUiR
import org.signal.core.ui.compose.ComposeFullScreenDialogFragment
import org.signal.core.ui.compose.Scaffolds
import org.signal.core.ui.compose.SignalIcons
import org.signal.core.ui.compose.TitledTabRow
import org.thoughtcrime.securesms.R

/** Which tab What's new opens on. */
enum class WhatsNewTab {
  THIS_UPDATE,
  PROJECT_LOG
}

/**
 * What's new: this update's highlights, and the project log of every change. Opening it counts as having seen the
 * highlights, whichever tab it opens on.
 */
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

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    if (savedInstanceState == null) {
      SetupStatusRepository.markNotesSeen()
    }
  }

  @Composable
  override fun DialogContent() {
    val startTab = WhatsNewTab.entries.firstOrNull { it.name == arguments?.getString(ARG_TAB) } ?: WhatsNewTab.THIS_UPDATE
    WhatsNewContent(
      startTab = startTab,
      onBack = { dismissAllowingStateLoss() },
      onReleaseAction = { SetupActions.fixProblem(requireActivity(), Problem.RELEASE_ACTION) },
      onFullNotes = { SetupActions.openReleaseNotes(requireActivity()) },
      onSupport = { SetupActions.openSupport(requireActivity()) },
      onOpenPullRequest = { SetupActions.openPullRequest(requireActivity(), it) }
    )
  }
}

@Composable
private fun WhatsNewContent(
  startTab: WhatsNewTab,
  onBack: () -> Unit,
  onReleaseAction: () -> Unit,
  onFullNotes: () -> Unit,
  onSupport: () -> Unit,
  onOpenPullRequest: (Int) -> Unit
) {
  var tab by rememberSaveable { mutableStateOf(startTab) }

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
          onReleaseAction = onReleaseAction,
          onFullNotes = onFullNotes,
          onSupport = onSupport,
          onProjectLog = { tab = WhatsNewTab.PROJECT_LOG }
        )
        WhatsNewTab.PROJECT_LOG -> ProjectLogTab(installedVersion = ApkInfo.versionName, onOpenPullRequest = onOpenPullRequest)
      }
    }
  }
}

@Composable
private fun ThisUpdate(
  onReleaseAction: () -> Unit,
  onFullNotes: () -> Unit,
  onSupport: () -> Unit,
  onProjectLog: () -> Unit
) {
  LazyColumn(
    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
    verticalArrangement = Arrangement.spacedBy(20.dp)
  ) {
    item {
      Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = stringResource(R.string.CollieWhatsNew__heading), style = MaterialTheme.typography.headlineSmall)
        Text(
          text = stringResource(R.string.CollieWhatsNew__installed_version, ApkInfo.versionName),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
      }
    }

    if (CollieReleaseNotes.action != null) {
      item {
        ProblemBanner(
          problem = Problem.RELEASE_ACTION,
          onFix = onReleaseAction,
          onDismiss = null,
          contentPadding = PaddingValues(0.dp)
        )
      }
    }

    items(CollieReleaseNotes.highlights) { highlight ->
      Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(text = stringResource(highlight.title), style = MaterialTheme.typography.titleMedium)
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
        TextButton(onClick = onFullNotes, modifier = Modifier.fillMaxWidth()) {
          Text(text = stringResource(R.string.CollieWhatsNew__full_release_notes))
          Icon(
            imageVector = SignalIcons.Open.imageVector,
            contentDescription = null,
            modifier = Modifier.padding(start = 8.dp)
          )
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
