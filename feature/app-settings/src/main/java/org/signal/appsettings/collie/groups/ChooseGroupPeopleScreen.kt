/*
 * Copyright 2026 Collie contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.appsettings.collie.groups

import androidx.annotation.VisibleForTesting
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.signal.appsettings.R
import org.signal.appsettings.appSettingsScreen
import org.signal.core.ui.compose.Buttons
import org.signal.core.ui.compose.CircularProgressWrapper
import org.signal.core.ui.compose.Rows
import org.signal.core.ui.compose.Scaffolds
import org.signal.core.ui.compose.SignalIcons
import org.signal.core.ui.compose.Snackbars

@VisibleForTesting
object ChooseGroupPeopleTestTags {
  const val PICKER = "choose-group-people-picker"
  const val ADD = "choose-group-people-add"
}

@Composable
fun ChooseGroupPeopleScreen(
  state: ChooseGroupPeopleState,
  onEvent: (ChooseGroupPeopleEvent) -> Unit,
  modifier: Modifier = Modifier,
  contactPicker: @Composable (listContentPadding: PaddingValues, modifier: Modifier) -> Unit = { _, _ -> }
) {
  Scaffolds.Settings(
    title = stringResource(R.string.GroupPrivacySettings__choose_people),
    navigationIcon = SignalIcons.ArrowStart.imageVector,
    navigationContentDescription = stringResource(R.string.AppSettingsScreen__go_back),
    onNavigationClick = { onEvent(ChooseGroupPeopleEvent.NavigateBackClicked) },
    snackbarHost = {
      Snackbars.Host(
        message = state.error?.let {
          stringResource(
            when (it) {
              ChooseGroupPeopleState.Error.INELIGIBLE_PERSON -> R.string.ChooseGroupPeople__ineligible
              ChooseGroupPeopleState.Error.IDENTITY_CHANGED -> R.string.ChooseGroupPeople__identity_changed
              ChooseGroupPeopleState.Error.SAVE_FAILED -> R.string.ChooseGroupPeople__save_failed
            }
          )
        },
        onDismissed = { onEvent(ChooseGroupPeopleEvent.ErrorDismissed) }
      )
    },
    modifier = modifier
  ) { contentPadding ->
    Column(
      modifier = Modifier.padding(contentPadding).consumeWindowInsets(contentPadding).imePadding().fillMaxSize().appSettingsScreen()
    ) {
      Rows.TextRow(label = stringResource(R.string.ChooseGroupPeople__description))
      Box(modifier = Modifier.weight(1f)) {
        contactPicker(PaddingValues(bottom = 96.dp), Modifier.fillMaxSize().testTag(ChooseGroupPeopleTestTags.PICKER))
        CircularProgressWrapper(isLoading = state.isSaving, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)) {
          Buttons.LargeTonal(
            enabled = state.canSave,
            onClick = { onEvent(ChooseGroupPeopleEvent.AddClicked) },
            modifier = Modifier.testTag(ChooseGroupPeopleTestTags.ADD)
          ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
              Text(text = stringResource(R.string.ChooseGroupPeople__add))
              Text(text = pluralStringResource(R.plurals.ChooseGroupPeople__selected, state.selectedCount, state.selectedCount))
            }
          }
        }
      }
    }
  }
}
