/*
 * Copyright 2026 Collie contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.appsettings.collie.groups

import org.junit.Test
import org.signal.core.ui.SignalLayoutStressTest

class GroupPrivacySettingsLayoutTest : SignalLayoutStressTest() {
  @Test
  fun `empty selected list`() = assertPassesLayoutChecks {
    GroupPrivacySettingsScreen(state = GroupPrivacySettingsState(mode = GroupPrivacyMode.SELECTED_PEOPLE, isLoading = false), onEvent = {})
  }

  @Test
  fun `missing contacts permission`() = assertPassesLayoutChecks {
    GroupPrivacySettingsScreen(state = GroupPrivacySettingsState(mode = GroupPrivacyMode.DEVICE_CONTACTS, isLoading = false), onEvent = {})
  }

  @Test
  fun `chosen person with a long name`() = assertPassesLayoutChecks {
    GroupPrivacySettingsScreen(
      state = GroupPrivacySettingsState(
        mode = GroupPrivacyMode.SELECTED_PEOPLE,
        allowedPeople = listOf(GroupAllowedPerson("56370921-d8a0-401b-b70c-019c25a7a3c8", "A contact with a long descriptive display name", null, false)),
        isLoading = false
      ),
      onEvent = {}
    )
  }
}
