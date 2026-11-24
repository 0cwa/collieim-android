/*
 * Copyright 2026 Collie contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.components.settings.app.collie

import kotlinx.coroutines.flow.StateFlow
import org.signal.appsettings.collie.CallPermissionChoice
import org.signal.appsettings.collie.CallPermissionOverrideState
import org.signal.appsettings.collie.CallsSettingsState
import org.thoughtcrime.securesms.calls.permissions.CallContext
import org.thoughtcrime.securesms.calls.permissions.CallPermission
import org.thoughtcrime.securesms.calls.permissions.CallPermissions
import org.thoughtcrime.securesms.recipients.Recipient

/** The settings adapter uses the same policy as call admission; it never stores a separate UI preference. */
internal interface CallPermissionsSettingsAccess {
  val changes: StateFlow<Long>
  fun readDevice(): CallsSettingsState
  fun readRecipient(recipient: Recipient): CallPermissionOverrideState
  fun setAllCallingDisabled(disabled: Boolean)
  fun setIncomingDefault(allow: Boolean)
  fun setGroupDefault(allow: Boolean)
  fun setOverride(recipient: Recipient, choice: CallPermissionChoice)
}

internal object LocalCallPermissionsSettingsAccess : CallPermissionsSettingsAccess {
  override val changes: StateFlow<Long>
    get() = CallPermissions.changes

  override fun readDevice(): CallsSettingsState {
    val settings = CallPermissions.settings()
    return CallsSettingsState(
      loading = false,
      available = settings.available,
      allCallingDisabled = settings.allCallingDisabled,
      allowIncoming = settings.directDefault == CallPermission.ALLOW,
      allowGroups = settings.groupDefault == CallPermission.ALLOW
    )
  }

  override fun readRecipient(recipient: Recipient): CallPermissionOverrideState {
    val settings = CallPermissions.settings()
    return CallPermissionOverrideState(
      loading = false,
      available = settings.available,
      allCallingDisabled = settings.allCallingDisabled,
      choice = when (CallPermissions.getOverride(recipient)) {
        CallPermission.INHERIT -> CallPermissionChoice.INHERIT
        CallPermission.ALLOW -> CallPermissionChoice.ALLOW
        CallPermission.BLOCK -> CallPermissionChoice.BLOCK
      },
      defaultAllowsCalls = (if (recipient.isGroup) settings.groupDefault else settings.directDefault) == CallPermission.ALLOW,
      group = recipient.isGroup
    )
  }

  override fun setAllCallingDisabled(disabled: Boolean) = CallPermissions.setAllCallingDisabled(disabled)

  override fun setIncomingDefault(allow: Boolean) = CallPermissions.setDefault(CallContext.DIRECT, if (allow) CallPermission.ALLOW else CallPermission.BLOCK)

  override fun setGroupDefault(allow: Boolean) = CallPermissions.setDefault(CallContext.GROUP, if (allow) CallPermission.ALLOW else CallPermission.BLOCK)

  override fun setOverride(recipient: Recipient, choice: CallPermissionChoice) = CallPermissions.setOverride(
    recipient,
    when (choice) {
      CallPermissionChoice.INHERIT -> CallPermission.INHERIT
      CallPermissionChoice.ALLOW -> CallPermission.ALLOW
      CallPermissionChoice.BLOCK -> CallPermission.BLOCK
    }
  )
}
