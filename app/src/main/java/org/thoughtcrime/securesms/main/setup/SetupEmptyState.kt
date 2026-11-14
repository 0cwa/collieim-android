/*
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.main.setup

import android.view.ViewGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.signal.core.ui.compose.Buttons
import org.signal.core.ui.compose.theme.SignalTheme
import org.signal.core.util.requireFragmentActivity
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.conversation.NewConversationActivity

/**
 * What an empty chat list shows. A new account sees the setup checklist there, since nothing else competes for the
 * space; once setup is done or hidden, it shows the usual "No chats yet" text.
 */
object SetupEmptyState {

  @JvmStatic
  fun createView(parent: ViewGroup): ComposeView {
    return ComposeView(parent.context).apply {
      layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
      setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
      setContent {
        SignalTheme {
          val activity = context.requireFragmentActivity()
          EmptyChats(
            onItemClick = { SetupActions.openItem(activity, it) },
            onMessageFriend = { activity.startActivity(NewConversationActivity.createIntent(activity)) },
            onHide = { SetupStatusRepository.hideRemaining() }
          )
        }
      }
    }
  }
}

@Composable
private fun EmptyChats(
  onItemClick: (SetupItem) -> Unit,
  onMessageFriend: () -> Unit,
  onHide: () -> Unit
) {
  val snapshot by SetupStatusRepository.snapshot.collectAsStateWithLifecycle()
  val state = snapshot.state

  if (state.visibleRemaining == 0) {
    Text(
      text = stringResource(R.string.conversation_list_fragment__no_chats_yet_get_started_by_messaging_a_friend),
      style = MaterialTheme.typography.bodyLarge,
      textAlign = TextAlign.Center,
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 32.dp, vertical = 52.dp)
    )
    return
  }

  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    modifier = Modifier
      .fillMaxWidth()
      .padding(top = 24.dp, bottom = 96.dp)
  ) {
    Column(
      verticalArrangement = Arrangement.spacedBy(6.dp),
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 24.dp)
    ) {
      Text(text = stringResource(R.string.CollieSetup__empty_title), style = MaterialTheme.typography.titleLarge)
      Text(
        text = stringResource(R.string.CollieSetup__empty_body),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
      )
      SetupProgress(settled = state.settledCount, total = state.rows.size, modifier = Modifier.padding(top = 6.dp))
    }

    SetupChecklist(
      rows = state.orderedRows(),
      details = snapshot.details,
      onItemClick = onItemClick,
      modifier = Modifier.padding(vertical = 8.dp)
    )

    Buttons.LargeTonal(onClick = onMessageFriend) {
      Text(text = stringResource(R.string.CollieSetup__message_a_friend))
    }
    TextButton(onClick = onHide) {
      Text(text = stringResource(R.string.CollieSetup__hide))
    }
  }
}
