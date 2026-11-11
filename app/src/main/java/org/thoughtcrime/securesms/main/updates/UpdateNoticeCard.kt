/*
 * Copyright 2026 Collie
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.main.updates

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.signal.core.ui.compose.DayNightPreviews
import org.signal.core.ui.compose.IconButtons
import org.signal.core.ui.compose.Previews
import org.thoughtcrime.securesms.R

data class UpdateNoticeHighlight(val title: String, val description: String)

object UpdateNoticeTags {
  const val NOTICE = "update_notice"
  const val COLLAPSED = "update_notice_collapsed"
  const val EXPANDED = "update_notice_expanded"
  const val MINIMIZE = "update_notice_minimize"
  const val DISMISS = "update_notice_dismiss"
  const val READ_MORE = "update_notice_read_more"
  const val SUPPORT = "update_notice_support"
  const val CONTENT = "update_notice_content"
}

/**
 * A notice that occupies its own space in the list pane instead of covering conversations.
 *
 * The caller owns eligibility and persistence. This presentation has no timers, notifications,
 * network access or version policy, so an update reminder can reuse the same interaction later.
 * Minimize always stays visible. On a short or large-text window, the dismissal action scrolls
 * with the body rather than squeezing the notes between two fixed control rows.
 */
@Composable
fun UpdateNoticeCard(
  title: String,
  subtitle: String,
  highlights: List<UpdateNoticeHighlight>,
  expanded: Boolean,
  onExpand: () -> Unit,
  onMinimize: () -> Unit,
  onDismiss: () -> Unit,
  onReadMore: () -> Unit,
  onSupport: (() -> Unit)? = null,
  modifier: Modifier = Modifier,
  maxExpandedHeight: Dp = 440.dp,
  compactTitle: String = stringResource(R.string.CollieUpdates__whats_new),
  readMoreLabel: String = stringResource(R.string.CollieUpdates__full_release_notes)
) {
  Surface(
    shape = MaterialTheme.shapes.large,
    color = MaterialTheme.colorScheme.surfaceContainerLow,
    contentColor = MaterialTheme.colorScheme.onSurface,
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    modifier = modifier
      .padding(horizontal = 16.dp, vertical = 6.dp)
      .fillMaxWidth()
      .testTag(UpdateNoticeTags.NOTICE)
  ) {
    if (expanded) {
      ExpandedNotice(
        title = title,
        subtitle = subtitle,
        highlights = highlights,
        onMinimize = onMinimize,
        onDismiss = onDismiss,
        onReadMore = onReadMore,
        onSupport = onSupport,
        readMoreLabel = readMoreLabel,
        maxHeight = maxExpandedHeight
      )
    } else {
      CollapsedNotice(compactTitle, subtitle, onExpand, onDismiss)
    }
  }
}

@Composable
private fun CollapsedNotice(title: String, subtitle: String, onExpand: () -> Unit, onDismiss: () -> Unit) {
  val collapsedDescription = stringResource(R.string.CollieUpdates__minimized)

  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier.fillMaxWidth()
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier
        .weight(1f)
        .clickable(role = Role.Button, onClick = onExpand)
        .heightIn(min = 48.dp)
        .padding(start = 16.dp, top = 8.dp, end = 4.dp, bottom = 8.dp)
        .testTag(UpdateNoticeTags.COLLAPSED)
        .semantics { stateDescription = collapsedDescription }
    ) {
      Icon(
        painter = painterResource(R.drawable.symbol_file_24),
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(20.dp)
      )
      Spacer(Modifier.width(12.dp))
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = title,
          style = MaterialTheme.typography.labelLarge
        )
        Text(
          text = subtitle,
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis
        )
      }
      Icon(
        painter = painterResource(R.drawable.symbol_chevron_down_24),
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant
      )
    }

    IconButtons.IconButton(
      onClick = onDismiss,
      size = 48.dp,
      modifier = Modifier.testTag(UpdateNoticeTags.DISMISS)
    ) {
      Icon(
        painter = painterResource(R.drawable.ic_x_20),
        contentDescription = stringResource(R.string.CollieUpdates__dismiss_description),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(20.dp)
      )
    }
  }
}

@Composable
private fun ExpandedNotice(
  title: String,
  subtitle: String,
  highlights: List<UpdateNoticeHighlight>,
  onMinimize: () -> Unit,
  onDismiss: () -> Unit,
  onReadMore: () -> Unit,
  onSupport: (() -> Unit)?,
  readMoreLabel: String,
  maxHeight: Dp
) {
  val expandedDescription = stringResource(R.string.CollieUpdates__expanded)
  val minimizeDescription = stringResource(R.string.CollieUpdates__minimize_description)
  val scrollDismissAction = maxHeight < 180.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)

  Column(
    modifier = Modifier
      .heightIn(max = maxHeight)
      .testTag(UpdateNoticeTags.EXPANDED)
      .semantics { stateDescription = expandedDescription }
  ) {
    // Keep this header small so the body still has room in landscape and at large font scales.
    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier
        .fillMaxWidth()
        .padding(start = 16.dp, end = 8.dp)
    ) {
      Icon(
        painter = painterResource(R.drawable.symbol_file_24),
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(20.dp)
      )
      Spacer(Modifier.weight(1f))
      TextButton(
        onClick = onMinimize,
        modifier = Modifier
          .heightIn(min = 48.dp)
          .testTag(UpdateNoticeTags.MINIMIZE)
          .semantics { contentDescription = minimizeDescription }
      ) {
        Text(stringResource(R.string.CollieUpdates__minimize))
        Spacer(Modifier.width(6.dp))
        Icon(
          painter = painterResource(R.drawable.symbol_chevron_up_24),
          contentDescription = null,
          modifier = Modifier.size(20.dp)
        )
      }
    }

    Column(
      verticalArrangement = Arrangement.spacedBy(16.dp),
      modifier = Modifier
        .weight(1f, fill = false)
        .verticalScroll(rememberScrollState())
        .testTag(UpdateNoticeTags.CONTENT)
        .padding(horizontal = 16.dp)
    ) {
      Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
          text = title,
          style = MaterialTheme.typography.titleLarge,
          modifier = Modifier.semantics { heading() }
        )
        Text(
          text = subtitle,
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
      }

      highlights.forEach { highlight ->
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
          Text(text = highlight.title, style = MaterialTheme.typography.titleSmall)
          Text(
            text = highlight.description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
        }
      }

      TextButton(
        onClick = onReadMore,
        modifier = Modifier
          .heightIn(min = 48.dp)
          .testTag(UpdateNoticeTags.READ_MORE)
      ) {
        Text(readMoreLabel)
        Spacer(Modifier.width(6.dp))
        Icon(
          painter = painterResource(R.drawable.symbol_arrow_upright_compact_16),
          contentDescription = null,
          modifier = Modifier.size(16.dp)
        )
      }

      if (onSupport != null) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
          Text(
            text = stringResource(R.string.CollieUpdates__support_message),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
          TextButton(
            onClick = onSupport,
            modifier = Modifier
              .heightIn(min = 48.dp)
              .testTag(UpdateNoticeTags.SUPPORT)
          ) {
            Icon(
              painter = painterResource(R.drawable.symbol_heart_24),
              contentDescription = null,
              modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.CollieUpdates__support_collie))
          }
        }
      }

      if (scrollDismissAction) {
        DismissNoticeButton(
          onDismiss = onDismiss,
          modifier = Modifier
            .align(Alignment.End)
            .padding(bottom = 8.dp)
        )
      }
    }

    if (!scrollDismissAction) {
      Box(
        contentAlignment = Alignment.CenterEnd,
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 16.dp, vertical = 8.dp)
      ) {
        DismissNoticeButton(onDismiss)
      }
    }
  }
}

@Composable
private fun DismissNoticeButton(onDismiss: () -> Unit, modifier: Modifier = Modifier) {
  val dismissDescription = stringResource(R.string.CollieUpdates__dismiss_description)

  FilledTonalButton(
    onClick = onDismiss,
    modifier = modifier
      .heightIn(min = 48.dp)
      .testTag(UpdateNoticeTags.DISMISS)
      .semantics { contentDescription = dismissDescription }
  ) {
    Text(stringResource(R.string.CollieUpdates__got_it))
  }
}

@DayNightPreviews
@Composable
private fun ExpandedUpdateNoticePreview() {
  UpdateNoticePreview(expanded = true)
}

@DayNightPreviews
@Composable
private fun CollapsedUpdateNoticePreview() {
  UpdateNoticePreview(expanded = false)
}

@Preview(name = "large text", widthDp = 320, fontScale = 2f)
@Composable
private fun LargeTextUpdateNoticePreview() {
  UpdateNoticePreview(expanded = true)
}

@Composable
private fun UpdateNoticePreview(expanded: Boolean) {
  Previews.Preview {
    Box(modifier = Modifier.width(360.dp)) {
      UpdateNoticeCard(
        title = stringResource(R.string.CollieUpdates__title),
        subtitle = stringResource(R.string.CollieUpdates__summary),
        highlights = listOf(
          UpdateNoticeHighlight(
            stringResource(R.string.CollieUpdates__read_when_ready_title),
            stringResource(R.string.CollieUpdates__read_when_ready_body)
          ),
          UpdateNoticeHighlight(
            stringResource(R.string.CollieUpdates__keep_chats_clear_title),
            stringResource(R.string.CollieUpdates__keep_chats_clear_body)
          )
        ),
        expanded = expanded,
        onExpand = {},
        onMinimize = {},
        onDismiss = {},
        onReadMore = {},
        onSupport = {}
      )
    }
  }
}
