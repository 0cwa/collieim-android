/*
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.main.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.signal.core.ui.BottomSheetUtil
import org.signal.core.ui.compose.BottomSheets
import org.signal.core.ui.compose.ComposeBottomSheetDialogFragment
import org.thoughtcrime.securesms.R

/**
 * The Finish setup checklist. It stays open while the person visits a settings screen and refreshes when they come
 * back, so they can work down the list.
 */
class FinishSetupBottomSheet : ComposeBottomSheetDialogFragment() {

  override val hasScrollingContent: Boolean = true

  companion object {
    private const val TAG = "FinishSetupBottomSheet"

    @JvmStatic
    fun show(fragmentManager: FragmentManager) {
      if (fragmentManager.findFragmentByTag(TAG) == null) {
        BottomSheetUtil.show(fragmentManager, TAG, FinishSetupBottomSheet())
      }
    }
  }

  @Composable
  override fun SheetContent() {
    val snapshot by SetupStatusRepository.snapshot.collectAsStateWithLifecycle()

    LifecycleResumeEffect(Unit) {
      SetupStatusRepository.refresh()
      onPauseOrDispose { }
    }

    FinishSetupSheetContent(
      snapshot = snapshot,
      onItemClick = { SetupActions.openItem(requireActivity(), it) },
      onFixProblem = { SetupActions.fixProblem(requireActivity(), it) },
      onWhatsNew = { SetupActions.showWhatsNew(requireActivity()) },
      onSupport = { SetupActions.openSupport(requireActivity()) },
      onHide = {
        SetupStatusRepository.hideRemaining()
        dismissAllowingStateLoss()
      }
    )
  }
}

@Composable
private fun FinishSetupSheetContent(
  snapshot: SetupStatusRepository.Snapshot,
  onItemClick: (SetupItem) -> Unit,
  onFixProblem: (Problem) -> Unit,
  onWhatsNew: () -> Unit,
  onSupport: () -> Unit,
  onHide: () -> Unit
) {
  val state = snapshot.state

  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    modifier = Modifier
      .fillMaxWidth()
      .verticalScroll(rememberScrollState())
      .padding(bottom = 16.dp)
  ) {
    BottomSheets.Handle()

    Column(
      verticalArrangement = Arrangement.spacedBy(8.dp),
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 24.dp, vertical = 8.dp)
    ) {
      Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.fillMaxWidth()) {
        Text(
          text = stringResource(R.string.CollieSetup__title),
          style = MaterialTheme.typography.titleLarge,
          modifier = Modifier.weight(1f)
        )
        Text(
          text = stringResource(R.string.CollieSetup__progress, state.settledCount, state.rows.size),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
      }
      SetupProgress(settled = state.settledCount, total = state.rows.size)
    }

    val problem = state.problem
    if (problem != null && problem != Problem.RELEASE_ACTION) {
      ProblemBanner(
        problem = problem,
        onFix = { onFixProblem(problem) },
        onDismiss = null,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
      )
    }

    SetupChecklist(rows = state.orderedRows(), details = snapshot.details, onItemClick = onItemClick)

    Spacer(modifier = Modifier.height(8.dp))

    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 12.dp)
    ) {
      TextButton(onClick = onWhatsNew) {
        Text(text = stringResource(R.string.CollieSetup__whats_new))
      }
      Spacer(modifier = Modifier.weight(1f))
      TextButton(onClick = onSupport) {
        Text(text = stringResource(R.string.CollieSetup__support), color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
    }

    if (state.visibleRemaining > 0) {
      TextButton(onClick = onHide) {
        Text(text = stringResource(R.string.CollieSetup__hide))
      }
      Text(
        text = stringResource(R.string.CollieSetup__hide_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
      )
    }
  }
}
