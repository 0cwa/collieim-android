/*
 * Copyright 2026 Collie contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.components.settings.app.collie.groups

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.signal.appsettings.collie.groups.ChooseGroupPeopleEvent
import org.signal.appsettings.collie.groups.ChooseGroupPeopleScreen
import org.signal.appsettings.routes.AppSettingsNavigationEvent
import org.signal.remoteconfig.SelectionLimits
import org.thoughtcrime.securesms.contacts.SelectedContact
import org.thoughtcrime.securesms.recipients.ui.RecipientPicker
import org.thoughtcrime.securesms.recipients.ui.RecipientPickerCallbacks
import org.thoughtcrime.securesms.recipients.ui.RecipientSelection

/** Reuses the normal contact search, restricted to existing registered individual recipients. */
@Composable
fun ChooseGroupPeopleEntry(parentEventEmitter: (AppSettingsNavigationEvent) -> Unit) {
  val viewModel: ChooseGroupPeopleViewModel = viewModel { ChooseGroupPeopleViewModel(parentEventEmitter) }
  val state by viewModel.state.collectAsStateWithLifecycle()
  val callbacks = remember(viewModel) {
    RecipientPickerCallbacks(
      listActions = object : RecipientPickerCallbacks.ListActions {
        override fun onSearchQueryChanged(query: String) = viewModel.onEvent(ChooseGroupPeopleEvent.SearchQueryChanged(query))
        override suspend fun shouldAllowSelection(selection: RecipientSelection): Boolean = viewModel.validateSelection((selection as? RecipientSelection.HasId)?.id)
        override fun onRecipientSelected(selection: RecipientSelection) = Unit
        override fun onSelectionChanged(newSelections: List<SelectedContact>) {
          viewModel.onEvent(ChooseGroupPeopleEvent.SelectionChanged(newSelections.mapNotNull { it.recipientId }.toSet()))
        }
      }
    )
  }

  ChooseGroupPeopleScreen(
    state = state,
    onEvent = viewModel::onEvent,
    contactPicker = { padding, modifier ->
      RecipientPicker(
        searchQuery = state.searchQuery,
        isRefreshing = false,
        displayModes = setOf(RecipientPicker.DisplayMode.PUSH, RecipientPicker.DisplayMode.HIDE_NEW),
        selectionLimits = SelectionLimits.NO_LIMITS,
        includeRecents = false,
        focusAndShowKeyboard = false,
        listBottomPadding = padding.calculateBottomPadding(),
        callbacks = callbacks,
        modifier = modifier
      )
    }
  )
}
