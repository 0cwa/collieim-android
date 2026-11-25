/*
 * Copyright 2026 Collie contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.components.settings.app.collie

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import org.signal.appsettings.routes.AppSettingsNavigationEvent
import org.signal.appsettings.routes.AppSettingsRoute
import org.signal.core.ui.compose.split.detailEntry
import org.thoughtcrime.securesms.components.settings.app.collie.groups.ChooseGroupPeopleEntry
import org.thoughtcrime.securesms.components.settings.app.collie.groups.GroupPrivacySettingsEntry
import org.thoughtcrime.securesms.flags.CollieFlags

fun EntryProviderScope<NavKey>.registerCollieRoutes(parentEventEmitter: (AppSettingsNavigationEvent) -> Unit) {
  detailEntry<AppSettingsRoute.Collie> {
    CollieSettingsEntry(parentEventEmitter = parentEventEmitter)
  }
  if (CollieFlags.groupAdmission) {
    detailEntry<AppSettingsRoute.CollieRoute.GroupPrivacy> {
      GroupPrivacySettingsEntry(parentEventEmitter = parentEventEmitter)
    }
    detailEntry<AppSettingsRoute.CollieRoute.ChooseGroupPeople> {
      ChooseGroupPeopleEntry(parentEventEmitter = parentEventEmitter)
    }
  }
}
