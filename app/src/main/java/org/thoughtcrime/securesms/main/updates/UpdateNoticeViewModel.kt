/*
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.main.updates

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.thoughtcrime.securesms.keyvalue.UpdateNoticeValues

/**
 * One independently dismissible notice. Future update reminders can use their own content IDs and
 * the same presentation, without changing release-note choices or scheduling notifications here.
 *
 * Expansion survives rotation with the activity's view model. A new process always starts quietly;
 * only an explicit dismissal is persisted. Opening dismissed notes from the menu is temporary and
 * does not undo that choice for the next launch.
 */
class UpdateNoticeViewModel(
  private val values: UpdateNoticeValues,
  private val noticeId: String
) : ViewModel() {

  enum class Presentation {
    MINIMIZED,
    EXPANDED,
    DISMISSED
  }

  private val internalPresentation = MutableStateFlow(
    if (values.isDismissed(noticeId)) Presentation.DISMISSED else Presentation.MINIMIZED
  )
  val presentation: StateFlow<Presentation> = internalPresentation.asStateFlow()

  fun expand() {
    internalPresentation.value = Presentation.EXPANDED
  }

  fun minimize() {
    if (internalPresentation.value == Presentation.EXPANDED) {
      internalPresentation.value = Presentation.MINIMIZED
    }
  }

  fun dismiss() {
    values.setDismissed(noticeId, true)
    internalPresentation.value = Presentation.DISMISSED
  }
}
