package org.thoughtcrime.securesms.mediaoverview

import android.app.Application
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModelProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.thoughtcrime.securesms.flags.CollieFlags

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class MediaActionsTest {
  @Before
  fun enableRemoval() {
    mockkObject(CollieFlags)
    every { CollieFlags.attachmentRemoval } returns true
  }

  @After
  fun restoreEditionFlag() = unmockkObject(CollieFlags)

  @Test
  fun `cancelling the real removal confirmation keeps the selection and starts no work`() {
    val controller = Robolectric.buildActivity(FragmentActivity::class.java)
    controller.get().setTheme(com.google.android.material.R.style.Theme_Material3_DayNight_NoActionBar)
    controller.setup()
    try {
      val fragment = Fragment()
      controller.get().supportFragmentManager.beginTransaction().add(fragment, "media").commitNow()
      var selectionCleared = false

      MediaActions.handleRemoveAttachments(fragment, listOf(mockk()), Runnable { selectionCleared = true })
      val dialog = ShadowDialog.getLatestDialog() as AlertDialog
      assertNotNull(dialog)
      dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()

      assertFalse(selectionCleared)
      assertFalse(ViewModelProvider(fragment)[MediaDeleteProgressViewModel::class.java].state.value.hasStarted)
    } finally {
      controller.pause().stop().destroy()
    }
  }
}
