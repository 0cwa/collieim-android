/*
 * Copyright 2026 Collie contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.appsettings.collie

import org.junit.Test
import org.signal.appsettings.labs.LabsSettingsState
import org.signal.core.ui.SignalLayoutStressTest

class CollieSettingsLayoutTest : SignalLayoutStressTest() {

  @Test
  fun `all off`() = assertPassesLayoutChecks {
    CollieSettingsScreen(state = LabsSettingsState(), onEvent = {})
  }

  @Test
  fun `all on`() = assertPassesLayoutChecks {
    CollieSettingsScreen(
      state = LabsSettingsState(storyArchive = true, betterSearch = true, starredMessages = true),
      onEvent = {}
    )
  }
}
