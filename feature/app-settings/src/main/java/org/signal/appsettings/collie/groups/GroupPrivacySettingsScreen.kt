/*
 * Copyright 2026 Collie contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.appsettings.collie.groups

import androidx.annotation.StringRes
import androidx.annotation.VisibleForTesting
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.tooling.preview.PreviewWrapper
import org.signal.appsettings.AppSettingsLazyColumn
import org.signal.appsettings.R
import org.signal.appsettings.asCollieSettingTitle
import org.signal.core.ui.compose.DayNightPreviews
import org.signal.core.ui.compose.Rows
import org.signal.core.ui.compose.Rows.TextAndLabel
import org.signal.core.ui.compose.Scaffolds
import org.signal.core.ui.compose.SignalIcons
import org.signal.core.ui.compose.SignalPreviewWrapper
import org.signal.core.ui.compose.Texts

@VisibleForTesting
object GroupPrivacySettingsTestTags {
  const val SCROLLER = "group-privacy-scroller"
  const val CHOOSE_PEOPLE = "group-privacy-choose-people"
  const val ERROR = "group-privacy-error"
  fun mode(mode: GroupPrivacyMode) = "group-privacy-mode-${mode.name}"
  fun removePerson(aci: String) = "group-privacy-remove-$aci"
}

/** Local group admission controls. Selecting a mode requests a durable write from the host. */
@Composable
fun GroupPrivacySettingsScreen(
  state: GroupPrivacySettingsState,
  onEvent: (GroupPrivacySettingsEvent) -> Unit,
  modifier: Modifier = Modifier
) {
  Scaffolds.Settings(
    title = stringResource(R.string.GroupPrivacySettings__title),
    navigationIcon = SignalIcons.ArrowStart.imageVector,
    navigationContentDescription = stringResource(R.string.AppSettingsScreen__go_back),
    onNavigationClick = { onEvent(GroupPrivacySettingsEvent.NavigateBackClicked) },
    modifier = modifier,
    expandableTitle = true
  ) { contentPadding ->
    AppSettingsLazyColumn(
      contentPadding = contentPadding,
      modifier = Modifier.testTag(GroupPrivacySettingsTestTags.SCROLLER).selectableGroup()
    ) {
      item {
        Rows.TextRow(label = stringResource(R.string.GroupPrivacySettings__local_scope))
      }

      if (state.isLoading) {
        item { Rows.TextRow(text = stringResource(R.string.GroupPrivacySettings__loading)) }
      }

      state.error?.let { error ->
        item {
          Rows.TextRow(
            text = stringResource(if (error == GroupPrivacySettingsState.Error.LOAD_FAILED) R.string.GroupPrivacySettings__load_failed else R.string.GroupPrivacySettings__save_failed),
            foregroundTint = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag(GroupPrivacySettingsTestTags.ERROR)
          )
        }
        if (error == GroupPrivacySettingsState.Error.LOAD_FAILED) {
          item {
            Rows.TextRow(text = stringResource(R.string.GroupPrivacySettings__retry), onClick = { onEvent(GroupPrivacySettingsEvent.RetryClicked) })
          }
        }
      }

      if (!state.isLoading && state.error != GroupPrivacySettingsState.Error.LOAD_FAILED) {
        item { Texts.SectionHeader(text = stringResource(R.string.GroupPrivacySettings__who_can_add)) }
        GroupPrivacyMode.entries.forEach { mode ->
          item(key = mode.name) {
            Rows.RadioRow(
              selected = state.mode == mode,
              content = {
                TextAndLabel(
                  text = stringResource(mode.title()).asCollieSettingTitle(),
                  label = AnnotatedString(stringResource(mode.description())),
                  enabled = state.canChange
                )
              },
              enabled = state.canChange,
              modifier = Modifier
                .selectable(
                  selected = state.mode == mode,
                  enabled = state.canChange,
                  role = Role.RadioButton,
                  onClick = { onEvent(GroupPrivacySettingsEvent.ModeSelected(mode)) }
                )
                .testTag(GroupPrivacySettingsTestTags.mode(mode))
            )
          }
        }

        if (state.isSaving) {
          item { Rows.TextRow(text = stringResource(R.string.GroupPrivacySettings__saving)) }
        }

        if (state.mode == GroupPrivacyMode.DEVICE_CONTACTS && !state.hasContactsPermission) {
          item { Rows.TextRow(label = stringResource(R.string.GroupPrivacySettings__contacts_permission)) }
        }

        if (state.mode == GroupPrivacyMode.SELECTED_PEOPLE) {
          item { Texts.SectionHeader(text = stringResource(R.string.GroupPrivacySettings__chosen_people)) }
          item {
            Rows.TextRow(
              text = stringResource(R.string.GroupPrivacySettings__choose_people).asCollieSettingTitle(),
              label = AnnotatedString(stringResource(R.string.GroupPrivacySettings__choose_people_description)),
              enabled = state.canChange,
              onClick = { onEvent(GroupPrivacySettingsEvent.ChoosePeopleClicked) },
              modifier = Modifier.testTag(GroupPrivacySettingsTestTags.CHOOSE_PEOPLE)
            )
          }
          if (state.allowedPeople.isEmpty()) {
            item { Rows.TextRow(label = stringResource(R.string.GroupPrivacySettings__empty_list)) }
          }
          state.allowedPeople.forEach { person ->
            item(key = person.aci) {
              val name = person.name ?: stringResource(R.string.GroupPrivacySettings__unavailable_person)
              Rows.TextRow(
                text = {
                  Column {
                    Text(text = name, style = MaterialTheme.typography.bodyLarge)
                    Text(text = person.address ?: stringResource(R.string.GroupPrivacySettings__contact_details_unavailable), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (!person.isEligible) {
                      Text(text = stringResource(R.string.GroupPrivacySettings__person_ineligible), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                  }
                },
                trailingIcon = {
                  IconButton(
                    onClick = { onEvent(GroupPrivacySettingsEvent.RemovePersonClicked(person.aci)) },
                    enabled = state.canChange,
                    modifier = Modifier.testTag(GroupPrivacySettingsTestTags.removePerson(person.aci))
                  ) {
                    Icon(painter = SignalIcons.MinusCircle.painter, contentDescription = stringResource(R.string.GroupPrivacySettings__remove_person, name))
                  }
                }
              )
            }
          }
        }

        if (state.mode != GroupPrivacyMode.SIGNAL_DEFAULT) {
          item { Rows.TextRow(label = stringResource(R.string.GroupPrivacySettings__unknown_actor)) }
        }
        item { Rows.TextRow(label = stringResource(R.string.GroupPrivacySettings__review_scope)) }
      }
    }
  }
}

@StringRes
private fun GroupPrivacyMode.title(): Int = when (this) {
  GroupPrivacyMode.SIGNAL_DEFAULT -> R.string.GroupPrivacySettings__signal_default
  GroupPrivacyMode.DEVICE_CONTACTS -> R.string.GroupPrivacySettings__contacts
  GroupPrivacyMode.SELECTED_PEOPLE -> R.string.GroupPrivacySettings__selected
  GroupPrivacyMode.ASK_EVERY_TIME -> R.string.GroupPrivacySettings__ask
}

@StringRes
private fun GroupPrivacyMode.description(): Int = when (this) {
  GroupPrivacyMode.SIGNAL_DEFAULT -> R.string.GroupPrivacySettings__signal_default_description
  GroupPrivacyMode.DEVICE_CONTACTS -> R.string.GroupPrivacySettings__contacts_description
  GroupPrivacyMode.SELECTED_PEOPLE -> R.string.GroupPrivacySettings__selected_description
  GroupPrivacyMode.ASK_EVERY_TIME -> R.string.GroupPrivacySettings__ask_description
}

@PreviewWrapper(SignalPreviewWrapper::class)
@DayNightPreviews
@Composable
private fun GroupPrivacySettingsScreenPreview() {
  GroupPrivacySettingsScreen(state = GroupPrivacySettingsState(mode = GroupPrivacyMode.SELECTED_PEOPLE, isLoading = false), onEvent = {})
}
