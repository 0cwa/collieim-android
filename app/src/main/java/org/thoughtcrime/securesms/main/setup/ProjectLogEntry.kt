/*
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.main.setup

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.signal.core.ui.compose.Scaffolds
import org.signal.core.ui.compose.SignalIcons
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.util.CommunicationActions

@Composable
fun ProjectLogEntry(onBack: () -> Unit) {
  val context = LocalContext.current
  val notes = ProjectLogRepository.load(context)
  Scaffolds.Settings(
    title = stringResource(R.string.CollieLog__title),
    onNavigationClick = onBack,
    navigationIcon = SignalIcons.ArrowStart.imageVector,
    navigationContentDescription = stringResource(org.signal.core.ui.R.string.CloseButton__content_description)
  ) { padding ->
    Column(modifier = Modifier.padding(padding)) {
      ProjectLogTab(
        notes = notes,
        onOpenPullRequest = { number ->
          CommunicationActions.openBrowserLink(context, ProjectLog.PR_URL + number)
        },
        modifier = Modifier.weight(1f)
      )
    }
  }
}
