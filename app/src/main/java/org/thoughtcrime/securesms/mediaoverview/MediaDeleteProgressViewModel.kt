/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.mediaoverview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.signal.core.util.concurrent.SignalDispatchers
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.database.MediaTable
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.jobs.MultiDeviceDeleteSyncJob
import org.thoughtcrime.securesms.util.AttachmentUtil

/**
 * Drives a bulk media-delete operation and exposes [State] so a Compose dialog can show real
 * X / Y progress instead of an indeterminate spinner.
 */
class MediaDeleteProgressViewModel : ViewModel() {

  enum class Operation { DELETE, REMOVE_ATTACHMENTS }

  data class State(
    val processed: Int = 0,
    val total: Int = 0,
    val hasStarted: Boolean = false,
    val isDone: Boolean = false,
    val operation: Operation = Operation.DELETE,
    val removed: Int = 0,
    val skipped: Int = 0,
    val failed: Int = 0
  )

  private val _state = MutableStateFlow(State())
  val state: StateFlow<State> = _state.asStateFlow()

  private var job: Job? = null

  fun start(records: Collection<MediaTable.MediaRecord>, operation: Operation = Operation.DELETE): Boolean {
    if (job?.isActive == true) return false
    val snapshot = records.distinctBy { it.attachment?.attachmentId ?: it.messageId }
    _state.value = State(total = snapshot.size, hasStarted = true, operation = operation)

    job = viewModelScope.launch(SignalDispatchers.IO) {
      try {
        if (operation == Operation.REMOVE_ATTACHMENTS) {
          snapshot.forEachIndexed { index, record ->
            ensureActive()
            try {
              val id = record.attachment?.attachmentId
              val removed = id != null && SignalDatabase.attachments.removeAttachmentLocally(id)
              _state.update {
                if (removed) it.copy(removed = it.removed + 1) else it.copy(skipped = it.skipped + 1)
              }
            } catch (e: CancellationException) {
              throw e
            } catch (e: Exception) {
              Log.w(TAG, "Unable to remove a selected attachment", e)
              _state.update { it.copy(failed = it.failed + 1) }
            }
            _state.update { it.copy(processed = index + 1) }
          }
        } else {
          val deletedMessageRecords = AttachmentUtil.deleteAttachments(snapshot) { processed ->
            _state.update { it.copy(processed = processed) }
          }

          if (deletedMessageRecords.isNotEmpty()) {
            MultiDeviceDeleteSyncJob.enqueueMessageDeletes(deletedMessageRecords)
          }
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.w(TAG, "Media operation failed", e)
        _state.update { it.copy(failed = it.failed + 1) }
      }

      _state.update { it.copy(isDone = true) }
    }
    return true
  }

  companion object {
    private val TAG = Log.tag(MediaDeleteProgressViewModel::class)
  }
}
