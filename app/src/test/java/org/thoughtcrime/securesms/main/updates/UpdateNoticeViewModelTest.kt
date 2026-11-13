/*
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.main.updates

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.storage.keyvalue.KeyValueDataSet
import org.signal.storage.keyvalue.KeyValueStore
import org.signal.storage.keyvalue.MockKeyValuePersistentStorage
import org.thoughtcrime.securesms.keyvalue.UpdateNoticeValues
import org.thoughtcrime.securesms.main.updates.UpdateNoticeViewModel.Presentation
import org.thoughtcrime.securesms.testutil.DirectExecutor

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class UpdateNoticeViewModelTest {

  private val storage = MockKeyValuePersistentStorage.withDataSet(KeyValueDataSet())

  @Test
  fun `new notes wait minimized until the user opens them`() {
    val viewModel = newProcess()

    assertEquals(Presentation.MINIMIZED, viewModel.presentation.value)
    viewModel.expand()
    assertEquals(Presentation.EXPANDED, viewModel.presentation.value)
    assertFalse(newValues().isDismissed(FIRST_EDITION))
  }

  @Test
  fun `process restart never automatically reopens an expanded notice`() {
    val viewModel = newProcess()
    viewModel.expand()

    // Reconstruct both the KeyValueStore and view model, not just the presentation object.
    assertEquals(Presentation.MINIMIZED, newProcess().presentation.value)
    assertFalse(newValues().isDismissed(FIRST_EDITION))
  }

  @Test
  fun `minimizing keeps notes available without recording a dismissal`() {
    val viewModel = newProcess()
    viewModel.expand()
    viewModel.minimize()

    assertEquals(Presentation.MINIMIZED, viewModel.presentation.value)
    assertFalse(newValues().isDismissed(FIRST_EDITION))
    assertEquals(Presentation.MINIMIZED, newProcess().presentation.value)
  }

  @Test
  fun `Got it survives reconstruction of the store and view model`() {
    val viewModel = newProcess()
    viewModel.expand()
    viewModel.dismiss()

    assertEquals(Presentation.DISMISSED, viewModel.presentation.value)
    assertTrue(newValues().isDismissed(FIRST_EDITION))
    assertEquals(Presentation.DISMISSED, newProcess().presentation.value)
  }

  @Test
  fun `dismissing one edition does not hide another or erase an older choice`() {
    newProcess(FIRST_EDITION).dismiss()

    val nextEdition = newProcess(SECOND_EDITION)
    assertEquals(Presentation.MINIMIZED, nextEdition.presentation.value)
    nextEdition.dismiss()

    // Returning to an older build must not bring its already-dismissed notes back.
    assertEquals(Presentation.DISMISSED, newProcess(FIRST_EDITION).presentation.value)
    assertEquals(Presentation.DISMISSED, newProcess(SECOND_EDITION).presentation.value)
    assertEquals(Presentation.MINIMIZED, newProcess("future-update-reminder").presentation.value)
  }

  @Test
  fun `manual reopening is temporary and does not undo a durable dismissal`() {
    newProcess().dismiss()
    val reopened = newProcess()
    reopened.expand()

    assertEquals(Presentation.EXPANDED, reopened.presentation.value)
    assertTrue(newValues().isDismissed(FIRST_EDITION))
    assertEquals(Presentation.DISMISSED, newProcess().presentation.value)

    reopened.minimize()
    assertEquals(Presentation.MINIMIZED, reopened.presentation.value)
    assertTrue(newValues().isDismissed(FIRST_EDITION))
    assertEquals(Presentation.DISMISSED, newProcess().presentation.value)
  }

  @Test
  fun `a visibility change cannot revive a dismissed notice`() {
    val viewModel = newProcess()
    viewModel.dismiss()
    viewModel.minimize()

    assertEquals(Presentation.DISMISSED, viewModel.presentation.value)
    assertEquals(Presentation.DISMISSED, newProcess().presentation.value)
  }

  @Test
  fun `release note choices stay local and first launch does not erase them`() {
    newProcess().dismiss()
    val values = newValues()

    values.onFirstEverAppLaunch()

    assertTrue(values.keysToIncludeInBackup.isEmpty())
    assertTrue(values.isDismissed(FIRST_EDITION))
  }

  private fun newValues(): UpdateNoticeValues = UpdateNoticeValues(KeyValueStore(storage, DirectExecutor()))

  private fun newProcess(noticeId: String = FIRST_EDITION): UpdateNoticeViewModel {
    return UpdateNoticeViewModel(newValues(), noticeId)
  }

  companion object {
    private const val FIRST_EDITION = "release-notes-first-edition"
    private const val SECOND_EDITION = "release-notes-second-edition"
  }
}
