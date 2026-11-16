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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentManager
import im.molly.app.base.ApkInfo
import org.signal.core.ui.R as CoreUiR
import org.signal.core.ui.compose.ComposeFullScreenDialogFragment
import org.signal.core.ui.compose.Scaffolds
import org.signal.core.ui.compose.SignalIcons
import org.thoughtcrime.securesms.R

/** The bundled release notes as an ordinary screen. Opening it counts as having seen them. */
class WhatsNewDialogFragment : ComposeFullScreenDialogFragment() {

  companion object {
    private const val TAG = "WhatsNewDialogFragment"

    @JvmStatic
    fun show(fragmentManager: FragmentManager) {
      if (fragmentManager.findFragmentByTag(TAG) == null) {
        WhatsNewDialogFragment().show(fragmentManager, TAG)
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
    WhatsNewContent(
      onBack = { dismissAllowingStateLoss() },
      onReleaseAction = { SetupActions.fixProblem(requireActivity(), Problem.RELEASE_ACTION) },
      onFullNotes = { SetupActions.openReleaseNotes(requireActivity()) }
    )
  }
}

@Composable
private fun WhatsNewContent(
  onBack: () -> Unit,
  onReleaseAction: () -> Unit,
  onFullNotes: () -> Unit
) {
  Scaffolds.Settings(
    title = stringResource(R.string.CollieWhatsNew__title),
    onNavigationClick = onBack,
    navigationIcon = SignalIcons.ArrowStart.imageVector,
    navigationContentDescription = stringResource(CoreUiR.string.DefaultTopAppBar__navigate_up_content_description)
  ) { padding ->
    LazyColumn(
      contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(20.dp),
      modifier = Modifier.padding(padding)
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
        OutlinedButton(onClick = onFullNotes, modifier = Modifier.fillMaxWidth()) {
          Text(text = stringResource(R.string.CollieWhatsNew__full_release_notes))
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
