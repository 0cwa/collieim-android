/*
 * Copyright 2026 Collie contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.components.settings.app.collie.groups

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.signal.appsettings.collie.groups.ChooseGroupPeopleEvent
import org.signal.appsettings.collie.groups.ChooseGroupPeopleState
import org.signal.appsettings.routes.AppSettingsNavigationEvent
import org.signal.appsettings.routes.navigateBack
import org.signal.core.models.recipients.RecipientId
import org.signal.core.ui.compose.EventDrivenViewModel
import org.signal.core.util.logging.Log

class ChooseGroupPeopleViewModel(
  private val parentEventEmitter: (AppSettingsNavigationEvent) -> Unit
) : EventDrivenViewModel<ChooseGroupPeopleEvent>(TAG) {
  companion object {
    private val TAG = Log.tag(ChooseGroupPeopleViewModel::class)
  }

  private val _state = MutableStateFlow(ChooseGroupPeopleState())
  val state: StateFlow<ChooseGroupPeopleState> = _state.asStateFlow()
  private val validatedIdentities = mutableMapOf<RecipientId, String>()
  private var selectedIdentities: Map<RecipientId, String> = emptyMap()
  private var completed = false

  /** The picker asks before adding a chip; phone-number-only selections are never resolved implicitly. */
  suspend fun validateSelection(id: RecipientId?): Boolean {
    if (id == null || _state.value.isSaving || completed) {
      _state.update { it.copy(error = ChooseGroupPeopleState.Error.INELIGIBLE_PERSON) }
      return false
    }
    val aci = try {
      GroupPrivacySettingsRepository.resolveSelectableAci(id)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.w(TAG, "Could not verify a chosen group inviter", e)
      null
    }
    if (aci == null) {
      _state.update { it.copy(error = ChooseGroupPeopleState.Error.INELIGIBLE_PERSON) }
      return false
    }
    validatedIdentities[id] = aci
    _state.update { it.copy(error = null) }
    return true
  }

  override suspend fun processEvent(event: ChooseGroupPeopleEvent) {
    if (completed) return
    when (event) {
      ChooseGroupPeopleEvent.NavigateBackClicked -> if (!_state.value.isSaving) parentEventEmitter.navigateBack()
      is ChooseGroupPeopleEvent.SearchQueryChanged -> _state.update { it.copy(searchQuery = event.query) }
      ChooseGroupPeopleEvent.ErrorDismissed -> _state.update { it.copy(error = null) }
      is ChooseGroupPeopleEvent.SelectionChanged -> {
        if (!_state.value.isSaving) {
          if (event.recipients.any { it !in validatedIdentities }) {
            selectedIdentities = emptyMap()
            _state.update { it.copy(selectedCount = 0, error = ChooseGroupPeopleState.Error.INELIGIBLE_PERSON) }
          } else {
            selectedIdentities = event.recipients.associateWith { validatedIdentities.getValue(it) }
            _state.update { it.copy(selectedCount = selectedIdentities.size) }
          }
        }
      }
      ChooseGroupPeopleEvent.AddClicked -> save()
    }
  }

  private suspend fun save() {
    if (!_state.value.canSave) return
    val selected = selectedIdentities.toMap()
    _state.update { it.copy(isSaving = true, error = null) }
    try {
      GroupPrivacySettingsRepository.addPeople(selected)
      completed = true
      _state.update { it.copy(isSaving = false, selectedCount = 0) }
      parentEventEmitter.navigateBack()
    } catch (e: CancellationException) {
      throw e
    } catch (e: GroupPrivacySettingsRepository.SelectionChangedException) {
      _state.update { it.copy(isSaving = false, error = ChooseGroupPeopleState.Error.IDENTITY_CHANGED) }
    } catch (e: Exception) {
      Log.w(TAG, "Could not save chosen group inviters", e)
      _state.update { it.copy(isSaving = false, error = ChooseGroupPeopleState.Error.SAVE_FAILED) }
    }
  }
}
