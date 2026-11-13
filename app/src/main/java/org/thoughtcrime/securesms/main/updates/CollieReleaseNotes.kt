/*
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.main.updates

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import im.molly.app.base.ApkInfo
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.util.CommunicationActions

/**
 * Identifies the bundled editorial content, not the installed APK. Change this ID when publishing
 * new highlights (see docs/RELEASE-NOTES-UI.md). A rebuild or version bump alone must not re-advertise
 * old notes, and revisiting an older edition must respect its previous dismissal.
 */
object CollieReleaseNotes {
  const val ID = "collie-release-notes-introduction-1"
  const val RELEASES_URL = "https://github.com/0cwa/collieim-android/releases"
}

@Composable
fun CollieReleaseNotesCard(
  presentation: UpdateNoticeViewModel.Presentation,
  onExpand: () -> Unit,
  onMinimize: () -> Unit,
  onDismiss: () -> Unit,
  maxExpandedHeight: Dp
) {
  val context = LocalContext.current
  val supportUrl = stringResource(R.string.donate_collie_url)

  UpdateNoticeCard(
    title = stringResource(R.string.CollieUpdates__title),
    subtitle = stringResource(R.string.CollieUpdates__installed_version, ApkInfo.versionName),
    highlights = listOf(
      UpdateNoticeHighlight(
        title = stringResource(R.string.CollieUpdates__read_when_ready_title),
        description = stringResource(R.string.CollieUpdates__read_when_ready_body)
      ),
      UpdateNoticeHighlight(
        title = stringResource(R.string.CollieUpdates__keep_chats_clear_title),
        description = stringResource(R.string.CollieUpdates__keep_chats_clear_body)
      )
    ),
    expanded = presentation == UpdateNoticeViewModel.Presentation.EXPANDED,
    onExpand = onExpand,
    onMinimize = onMinimize,
    onDismiss = onDismiss,
    onReadMore = { CommunicationActions.openBrowserLink(context, CollieReleaseNotes.RELEASES_URL) },
    onSupport = { CommunicationActions.openBrowserLink(context, supportUrl) },
    maxExpandedHeight = maxExpandedHeight,
    reopenHint = stringResource(R.string.CollieUpdates__reopen_hint)
  )
}
