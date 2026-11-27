/*
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.groups.v2.admission

/** Stable stored names; changing their order must never change a person's choice. */
enum class GroupAdmissionMode {
  SIGNAL_DEFAULT,
  DEVICE_CONTACTS,
  SELECTED_PEOPLE,
  ASK_EVERY_TIME
}

data class GroupAdmissionSettings(
  val mode: GroupAdmissionMode = GroupAdmissionMode.SIGNAL_DEFAULT,
  val allowedPeople: Set<String> = emptySet()
)

internal enum class AdmissionStatus {
  SIGNAL_DEFAULT,
  HELD,
  AUTOMATIC,
  LOCAL_CONSENT,
  EXISTING_GROUP
}

internal enum class MembershipKind { ABSENT, PENDING, REQUESTING, FULL, UNKNOWN }

/** An authenticated membership identity, not the latest title/avatar revision. */
internal data class AdmissionMembership(
  val kind: MembershipKind,
  val episode: String,
  val revision: Int,
  val joinedAtRevision: Int? = null
)

internal data class AdmissionInviter(
  val aci: String?,
  val blocked: Boolean = false,
  val hidden: Boolean = false,
  val deviceContact: Boolean = false
)

/** Pure policy: permitting an inviter never promotes an actual pending invitation on the server. */
internal object GroupAdmissionPolicy {
  fun evaluate(settings: GroupAdmissionSettings, inviter: AdmissionInviter?): AdmissionStatus {
    if (settings.mode == GroupAdmissionMode.SIGNAL_DEFAULT) return AdmissionStatus.SIGNAL_DEFAULT
    if (inviter?.aci == null || inviter.blocked || inviter.hidden) return AdmissionStatus.HELD

    return when (settings.mode) {
      GroupAdmissionMode.SIGNAL_DEFAULT -> AdmissionStatus.SIGNAL_DEFAULT
      GroupAdmissionMode.DEVICE_CONTACTS -> if (inviter.deviceContact) AdmissionStatus.AUTOMATIC else AdmissionStatus.HELD
      GroupAdmissionMode.SELECTED_PEOPLE -> if (inviter.aci in settings.allowedPeople) AdmissionStatus.AUTOMATIC else AdmissionStatus.HELD
      GroupAdmissionMode.ASK_EVERY_TIME -> AdmissionStatus.HELD
    }
  }
}

internal enum class ConsentKind { ACCEPT, JOIN, REQUEST, CREATE }

/** Written before an explicit local operation. The expected revision is fixed before each network attempt. */
internal data class AdmissionIntent(
  val id: String,
  val kind: ConsentKind,
  val originEpisode: String?,
  val expectedRevision: Int? = null
)

internal data class AdmissionRecord(
  val membership: AdmissionMembership,
  val status: AdmissionStatus,
  val generation: String = membership.episode,
  val pendingWrite: String? = null
)

internal data class AdmissionAccountState(
  val account: String,
  val settings: GroupAdmissionSettings = GroupAdmissionSettings(),
  val records: Map<String, AdmissionRecord> = emptyMap(),
  val intents: Map<String, AdmissionIntent> = emptyMap()
) {
  fun isHeld(group: String): Boolean {
    val record = records[group]
    return if (record == null) {
      settings.mode != GroupAdmissionMode.SIGNAL_DEFAULT
    } else {
      record.pendingWrite != null || record.status == AdmissionStatus.HELD
    }
  }
}
