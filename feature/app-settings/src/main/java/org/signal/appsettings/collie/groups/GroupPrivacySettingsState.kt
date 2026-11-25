/*
 * Copyright 2026 Collie contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.appsettings.collie.groups

enum class GroupPrivacyMode {
  SIGNAL_DEFAULT,
  DEVICE_CONTACTS,
  SELECTED_PEOPLE,
  ASK_EVERY_TIME
}

data class GroupAllowedPerson(
  val aci: String,
  val name: String?,
  val address: String?,
  val isEligible: Boolean
)

data class GroupPrivacySettingsState(
  val mode: GroupPrivacyMode = GroupPrivacyMode.SIGNAL_DEFAULT,
  val allowedPeople: List<GroupAllowedPerson> = emptyList(),
  val hasContactsPermission: Boolean = false,
  val isLoading: Boolean = true,
  val isSaving: Boolean = false,
  val error: Error? = null
) {
  val canChange: Boolean get() = !isLoading && !isSaving && error != Error.LOAD_FAILED

  enum class Error {
    LOAD_FAILED,
    SAVE_FAILED
  }
}

data class ChooseGroupPeopleState(
  val searchQuery: String = "",
  val selectedCount: Int = 0,
  val isSaving: Boolean = false,
  val error: Error? = null
) {
  val canSave: Boolean get() = selectedCount > 0 && !isSaving

  enum class Error {
    INELIGIBLE_PERSON,
    IDENTITY_CHANGED,
    SAVE_FAILED
  }
}
