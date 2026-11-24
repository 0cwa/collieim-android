/*
 * Copyright 2026 Collie contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.appsettings.collie.groups

import org.signal.core.models.recipients.RecipientId

sealed interface GroupPrivacySettingsEvent {
  data object ScreenResumed : GroupPrivacySettingsEvent
  data object NavigateBackClicked : GroupPrivacySettingsEvent
  data object ChoosePeopleClicked : GroupPrivacySettingsEvent
  data object RetryClicked : GroupPrivacySettingsEvent
  data class ModeSelected(val mode: GroupPrivacyMode) : GroupPrivacySettingsEvent
  data class RemovePersonClicked(val aci: String) : GroupPrivacySettingsEvent
}

sealed interface ChooseGroupPeopleEvent {
  data object NavigateBackClicked : ChooseGroupPeopleEvent
  data object AddClicked : ChooseGroupPeopleEvent
  data object ErrorDismissed : ChooseGroupPeopleEvent
  data class SearchQueryChanged(val query: String) : ChooseGroupPeopleEvent
  data class SelectionChanged(val recipients: Set<RecipientId>) : ChooseGroupPeopleEvent
}
