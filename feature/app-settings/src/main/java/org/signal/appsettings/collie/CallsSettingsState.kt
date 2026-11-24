/*
 * Copyright 2026 Collie contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.appsettings.collie

/** Presentation values only; the app's call policy owns persistence and effective permissions. */
data class CallsSettingsState(
  val loading: Boolean = true,
  val available: Boolean = false,
  val allCallingDisabled: Boolean = false,
  val onDemandAvailable: Boolean = false,
  val onDemandCalling: Boolean = false,
  val allowIncoming: Boolean = true,
  val allowGroups: Boolean = true,
  val saving: Boolean = false,
  val saveFailed: Boolean = false
)

/** INHERIT remains a choice of its own, even when the device default currently resolves to ALLOW. */
enum class CallPermissionChoice {
  INHERIT, ALLOW, BLOCK
}

data class CallPermissionOverrideState(
  val loading: Boolean = true,
  val available: Boolean = false,
  val allCallingDisabled: Boolean = false,
  val choice: CallPermissionChoice = CallPermissionChoice.INHERIT,
  val defaultAllowsCalls: Boolean = true,
  val group: Boolean = false,
  val saving: Boolean = false,
  val saveFailed: Boolean = false
)

sealed interface CallsSettingsEvent {
  data object NavigateBack : CallsSettingsEvent
  data class DisableAllChanged(val disabled: Boolean) : CallsSettingsEvent
  data class OnDemandChanged(val enabled: Boolean) : CallsSettingsEvent
  data class IncomingChanged(val allowed: Boolean) : CallsSettingsEvent
  data class GroupsChanged(val allowed: Boolean) : CallsSettingsEvent
  data object Refresh : CallsSettingsEvent
  data object ErrorDismissed : CallsSettingsEvent
}
