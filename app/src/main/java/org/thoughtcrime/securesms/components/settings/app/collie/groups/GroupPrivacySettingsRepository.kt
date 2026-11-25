/*
 * Copyright 2026 Collie contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.components.settings.app.collie.groups

import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.withContext
import org.signal.appsettings.collie.groups.GroupAllowedPerson
import org.signal.appsettings.collie.groups.GroupPrivacyMode
import org.signal.appsettings.collie.groups.GroupPrivacySettingsState
import org.signal.core.models.ServiceId.ACI
import org.signal.core.models.recipients.RecipientId
import org.signal.core.util.concurrent.SignalDispatchers
import org.signal.core.util.orNull
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.groups.v2.admission.GroupAdmission
import org.thoughtcrime.securesms.groups.v2.admission.GroupAdmissionMode
import org.thoughtcrime.securesms.recipients.Recipient

/** Resolves display names locally; only stable, explicitly selected ACIs reach the admission store. */
object GroupPrivacySettingsRepository {
  suspend fun load(): GroupPrivacySettingsState = withContext(SignalDispatchers.IO) {
    val settings = GroupAdmission.getSettings()
    val context = AppDependencies.application
    val people = settings.allowedPeople.map { aci ->
      val recipient = ACI.parseOrNull(aci)?.let { SignalDatabase.recipients.getByAci(it).orNull() }?.let { Recipient.resolved(it).fresh() }
      GroupAllowedPerson(
        aci = aci,
        name = recipient?.getDisplayName(context),
        address = recipient?.let { person -> person.e164.orNull()?.takeIf { person.shouldShowE164 } ?: person.username.orNull() },
        isEligible = recipient?.let { selectableAci(it) == aci } == true
      )
    }.sortedWith(compareBy({ it.name.orEmpty().lowercase() }, { it.aci }))

    GroupPrivacySettingsState(
      mode = settings.mode.toUi(),
      allowedPeople = people,
      hasContactsPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED,
      isLoading = false
    )
  }

  suspend fun setMode(mode: GroupPrivacyMode) = withContext(SignalDispatchers.IO) {
    GroupAdmission.setMode(mode.toDomain())
  }

  suspend fun removePerson(aci: String) = withContext(SignalDispatchers.IO) {
    GroupAdmission.setAllowedPeople(GroupAdmission.getSettings().allowedPeople - aci)
  }

  suspend fun resolveSelectableAci(recipientId: RecipientId): String? = withContext(SignalDispatchers.IO) {
    selectableAci(Recipient.resolved(recipientId).fresh())
  }

  /** A changed identity rejects the entire selection; it never silently grants permission to a replacement account. */
  suspend fun addPeople(selected: Map<RecipientId, String>) = withContext(SignalDispatchers.IO) {
    require(selected.isNotEmpty())
    val current = selected.mapValues { (id, _) -> selectableAci(Recipient.resolved(id).fresh()) }
    if (selected.any { (id, expectedAci) -> current[id] != expectedAci }) {
      throw SelectionChangedException()
    }
    GroupAdmission.setAllowedPeople(GroupAdmission.getSettings().allowedPeople + selected.values)
  }

  internal fun selectableAci(recipient: Recipient): String? = GroupAdmission.selectableAci(recipient)

  class SelectionChangedException : IllegalStateException("A chosen group inviter is no longer eligible")
}

private fun GroupAdmissionMode.toUi(): GroupPrivacyMode = when (this) {
  GroupAdmissionMode.SIGNAL_DEFAULT -> GroupPrivacyMode.SIGNAL_DEFAULT
  GroupAdmissionMode.DEVICE_CONTACTS -> GroupPrivacyMode.DEVICE_CONTACTS
  GroupAdmissionMode.SELECTED_PEOPLE -> GroupPrivacyMode.SELECTED_PEOPLE
  GroupAdmissionMode.ASK_EVERY_TIME -> GroupPrivacyMode.ASK_EVERY_TIME
}

private fun GroupPrivacyMode.toDomain(): GroupAdmissionMode = when (this) {
  GroupPrivacyMode.SIGNAL_DEFAULT -> GroupAdmissionMode.SIGNAL_DEFAULT
  GroupPrivacyMode.DEVICE_CONTACTS -> GroupAdmissionMode.DEVICE_CONTACTS
  GroupPrivacyMode.SELECTED_PEOPLE -> GroupAdmissionMode.SELECTED_PEOPLE
  GroupPrivacyMode.ASK_EVERY_TIME -> GroupAdmissionMode.ASK_EVERY_TIME
}
