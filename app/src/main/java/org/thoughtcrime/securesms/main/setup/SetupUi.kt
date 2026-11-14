/*
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.main.setup

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import im.molly.unifiedpush.health.PushHealth
import org.signal.core.ui.compose.SignalIcons
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.banner.ui.compose.Action
import org.thoughtcrime.securesms.banner.ui.compose.DefaultBanner
import org.thoughtcrime.securesms.banner.ui.compose.Importance

/**
 * Shows the first of [candidates] that fits on one line in the available width, so the text is shortened by choosing
 * other words, never by cutting it off. Order the candidates from longest to shortest.
 */
@Composable
fun FittingText(
  candidates: List<String>,
  style: TextStyle,
  color: Color,
  modifier: Modifier = Modifier
) {
  val textMeasurer = rememberTextMeasurer()
  val resolvedStyle = LocalTextStyle.current.merge(style)

  BoxWithConstraints(modifier = modifier) {
    val maxWidth = constraints.maxWidth
    val bounded = constraints.hasBoundedWidth
    val text = remember(candidates, maxWidth, bounded, resolvedStyle) {
      if (!bounded) {
        candidates.first()
      } else {
        candidates.firstOrNull { textMeasurer.measure(it, resolvedStyle, maxLines = 1, softWrap = false).size.width <= maxWidth } ?: candidates.last()
      }
    }

    Text(
      text = text,
      style = resolvedStyle,
      color = color,
      maxLines = 1,
      softWrap = false,
      overflow = TextOverflow.Clip
    )
  }
}

/** The status line's wording from longest to shortest. */
@Composable
fun statusLineCandidates(statusLine: StatusLine): List<String> = when (statusLine) {
  StatusLine.None -> emptyList()
  is StatusLine.Setup -> listOf(
    stringResource(R.string.CollieStatus__setup_long, statusLine.remaining),
    stringResource(R.string.CollieStatus__setup_short),
    stringResource(R.string.CollieStatus__setup_tiny)
  )
  StatusLine.Updated -> listOf(
    stringResource(R.string.CollieStatus__updated_long),
    stringResource(R.string.CollieStatus__updated_short),
    stringResource(R.string.CollieStatus__updated_tiny)
  )
  is StatusLine.Issue -> when (statusLine.problem) {
    Problem.NOTIFICATIONS_OFF -> listOf(stringResource(R.string.CollieStatus__notifications_off_long), stringResource(R.string.CollieStatus__notifications_off_short))
    Problem.PUSH_APP_MISSING -> listOf(stringResource(R.string.CollieStatus__push_app_missing_long), stringResource(R.string.CollieStatus__push_app_missing_short))
    Problem.MESSAGES_DELAYED -> listOf(stringResource(R.string.CollieStatus__messages_delayed_long), stringResource(R.string.CollieStatus__messages_delayed_short))
    Problem.PUSH_FAILING -> listOf(stringResource(R.string.CollieStatus__push_failing_long), stringResource(R.string.CollieStatus__push_failing_short))
    Problem.RELEASE_ACTION -> listOf(stringResource(R.string.CollieStatus__release_action_long), stringResource(R.string.CollieStatus__release_action_short))
  }
}

/** Problems that stop messages arriving are red; news and setup use the theme's accent. */
@Composable
private fun statusLineColor(statusLine: StatusLine): Color {
  return if (statusLine is StatusLine.Issue && statusLine.problem != Problem.RELEASE_ACTION) {
    MaterialTheme.colorScheme.error
  } else {
    MaterialTheme.colorScheme.primary
  }
}

/**
 * The Chats toolbar title with the status line under it. When large text leaves no room for both lines, only the
 * status line shows: it is the one with something to say.
 */
@Composable
fun MainToolbarTitle(
  title: String,
  statusLine: StatusLine,
  onStatusLineClick: () -> Unit
) {
  if (statusLine == StatusLine.None) {
    Text(text = title, maxLines = 1)
    return
  }

  val candidates = statusLineCandidates(statusLine)
  Layout(
    content = {
      Text(text = title, maxLines = 1)
      FittingText(
        candidates = candidates,
        style = MaterialTheme.typography.labelLarge,
        color = statusLineColor(statusLine)
      )
    },
    modifier = Modifier.clickable(
      onClickLabel = candidates.first(),
      role = Role.Button,
      onClick = onStatusLineClick
    )
  ) { measurables, constraints ->
    val loose = constraints.copy(minWidth = 0, minHeight = 0)
    val titlePlaceable = measurables[0].measure(loose)
    val statusPlaceable = measurables[1].measure(loose)
    val bothFit = !constraints.hasBoundedHeight || titlePlaceable.height + statusPlaceable.height <= constraints.maxHeight

    val contentWidth = if (bothFit) maxOf(titlePlaceable.width, statusPlaceable.width) else statusPlaceable.width
    val contentHeight = if (bothFit) titlePlaceable.height + statusPlaceable.height else statusPlaceable.height
    val width = contentWidth.coerceIn(constraints.minWidth, constraints.maxWidth)
    val height = contentHeight.coerceIn(constraints.minHeight, constraints.maxHeight)

    layout(width, height) {
      val top = (height - contentHeight) / 2
      if (bothFit) {
        titlePlaceable.place(0, top)
        statusPlaceable.place(0, top + titlePlaceable.height)
      } else {
        statusPlaceable.place(0, top)
      }
    }
  }
}

/** The banner for [problem], used above the chats and at the top of the setup sheet. */
@Composable
fun ProblemBanner(
  problem: Problem,
  onFix: () -> Unit,
  onDismiss: (() -> Unit)?,
  contentPadding: PaddingValues
) {
  val action = CollieReleaseNotes.action
  val (title, body, button) = when (problem) {
    Problem.NOTIFICATIONS_OFF -> Triple(R.string.CollieProblem__notifications_off_title, R.string.CollieProblem__notifications_off_body, R.string.CollieProblem__notifications_off_action)
    Problem.PUSH_APP_MISSING -> Triple(R.string.CollieProblem__push_app_missing_title, R.string.CollieProblem__push_app_missing_body, R.string.CollieProblem__push_app_missing_action)
    Problem.MESSAGES_DELAYED -> Triple(R.string.CollieProblem__messages_delayed_title, R.string.CollieProblem__messages_delayed_body, R.string.CollieProblem__messages_delayed_action)
    Problem.PUSH_FAILING -> Triple(R.string.CollieProblem__push_failing_title, R.string.CollieProblem__push_failing_body, R.string.CollieProblem__push_failing_action)
    Problem.RELEASE_ACTION -> if (action != null) Triple(action.title, action.body, action.button) else return
  }

  DefaultBanner(
    title = stringResource(title),
    body = stringResource(body),
    importance = if (problem == Problem.RELEASE_ACTION) Importance.NORMAL else Importance.ERROR,
    onDismissListener = onDismiss,
    actions = listOf(Action(button, onClick = onFix)),
    paddingValues = contentPadding
  )
}

/** A thin bar split into one segment per item, filled for each item that needs nothing more. */
@Composable
fun SetupProgress(settled: Int, total: Int, modifier: Modifier = Modifier) {
  Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = modifier.fillMaxWidth()) {
    repeat(total) { index ->
      Box(
        modifier = Modifier
          .weight(1f)
          .height(4.dp)
          .background(
            color = if (index < settled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(2.dp)
          )
      )
    }
  }
}

/** Rows left to do come first, so the list reads as a to-do list rather than a report. */
fun SetupState.orderedRows(): List<SetupRow> = rows.sortedBy {
  when (it.status) {
    ItemStatus.TODO -> 0
    ItemStatus.SKIPPED -> 1
    ItemStatus.DONE -> 2
  }
}

@Composable
fun SetupChecklist(
  rows: List<SetupRow>,
  details: SetupStatusRepository.Details,
  onItemClick: (SetupItem) -> Unit,
  modifier: Modifier = Modifier,
  horizontalPadding: androidx.compose.ui.unit.Dp = 24.dp
) {
  Column(modifier = modifier.fillMaxWidth()) {
    rows.forEach { row ->
      SetupItemRow(row = row, details = details, onClick = { onItemClick(row.item) }, horizontalPadding = horizontalPadding)
    }
  }
}

@Composable
private fun SetupItemRow(
  row: SetupRow,
  details: SetupStatusRepository.Details,
  onClick: () -> Unit,
  horizontalPadding: androidx.compose.ui.unit.Dp
) {
  val stateText = stringResource(
    when (row.status) {
      ItemStatus.DONE -> R.string.CollieSetup__done_description
      ItemStatus.TODO -> R.string.CollieSetup__todo_description
      ItemStatus.SKIPPED -> R.string.CollieSetup__skipped_description
    }
  )

  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier
      .fillMaxWidth()
      .heightIn(min = 64.dp)
      .clickable(role = Role.Button, onClick = onClick)
      .semantics(mergeDescendants = true) { stateDescription = stateText }
      .padding(horizontal = horizontalPadding, vertical = 10.dp)
  ) {
    StatusMark(row.status)
    Spacer(modifier = Modifier.width(16.dp))
    Column(modifier = Modifier.weight(1f)) {
      Text(text = stringResource(titleOf(row.item)), style = MaterialTheme.typography.bodyLarge)
      Text(
        text = descriptionOf(row, details),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
      )
    }
    Spacer(modifier = Modifier.width(8.dp))
    Icon(
      imageVector = SignalIcons.ChevronRight.imageVector,
      contentDescription = null,
      tint = MaterialTheme.colorScheme.onSurfaceVariant
    )
  }
}

@Composable
private fun StatusMark(status: ItemStatus) {
  when (status) {
    ItemStatus.DONE -> Box(
      contentAlignment = Alignment.Center,
      modifier = Modifier
        .size(24.dp)
        .background(color = MaterialTheme.colorScheme.primary, shape = CircleShape)
    ) {
      Icon(
        imageVector = SignalIcons.Check.imageVector,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onPrimary,
        modifier = Modifier.size(16.dp)
      )
    }
    ItemStatus.SKIPPED -> Icon(
      imageVector = SignalIcons.MinusCircle.imageVector,
      contentDescription = null,
      tint = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.size(24.dp)
    )
    ItemStatus.TODO -> Box(
      modifier = Modifier
        .size(24.dp)
        .padding(2.dp)
        .border(width = 2.dp, color = MaterialTheme.colorScheme.primary, shape = CircleShape)
    )
  }
}

private fun titleOf(item: SetupItem): Int = when (item) {
  SetupItem.NOTIFICATIONS -> R.string.CollieSetup__notifications_title
  SetupItem.DELIVERY -> R.string.CollieSetup__delivery_title
  SetupItem.BATTERY -> R.string.CollieSetup__battery_title
  SetupItem.UPDATE_CHECKS -> R.string.CollieSetup__updates_title
  SetupItem.BACKUPS -> R.string.CollieSetup__backups_title
}

@Composable
private fun descriptionOf(row: SetupRow, details: SetupStatusRepository.Details): String {
  val done = row.status == ItemStatus.DONE
  return when (row.item) {
    SetupItem.NOTIFICATIONS -> stringResource(if (done) R.string.CollieSetup__notifications_done else R.string.CollieSetup__notifications_todo)
    SetupItem.DELIVERY -> when (details.delivery) {
      Delivery.GOOGLE -> stringResource(R.string.CollieSetup__delivery_google)
      Delivery.UNIFIEDPUSH -> when {
        !done -> stringResource(R.string.CollieSetup__delivery_unifiedpush_todo)
        details.pushTestResult == PushHealth.Hop.NONE && details.pushTestAtSeconds > 0 -> {
          stringResource(R.string.CollieSetup__delivery_unifiedpush_tested, relativeTime(details.pushTestAtSeconds * 1000))
        }
        else -> stringResource(R.string.CollieSetup__delivery_unifiedpush)
      }
      Delivery.WEBSOCKET -> stringResource(if (done) R.string.CollieSetup__delivery_websocket else R.string.CollieSetup__delivery_websocket_todo)
    }
    SetupItem.BATTERY -> stringResource(
      when (row.status) {
        ItemStatus.DONE -> R.string.CollieSetup__battery_done
        ItemStatus.TODO -> R.string.CollieSetup__battery_todo
        ItemStatus.SKIPPED -> R.string.CollieSetup__battery_skipped
      }
    )
    SetupItem.UPDATE_CHECKS -> when {
      details.availableUpdateName != null -> stringResource(R.string.CollieSetup__updates_available, details.availableUpdateName)
      !done -> stringResource(R.string.CollieSetup__updates_todo)
      details.lastUpdateCheckMs > 0 -> stringResource(R.string.CollieSetup__updates_done_checked, relativeTime(details.lastUpdateCheckMs))
      else -> stringResource(R.string.CollieSetup__updates_done)
    }
    SetupItem.BACKUPS -> stringResource(if (done) R.string.CollieSetup__backups_done else R.string.CollieSetup__backups_todo)
  }
}

private fun relativeTime(timeMs: Long): String {
  return DateUtils.getRelativeTimeSpanString(timeMs, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
}
