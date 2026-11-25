/*
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.main.setup

import android.app.Application
import androidx.fragment.app.FragmentActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.components.settings.app.AppSettingsActivity
import org.thoughtcrime.securesms.components.settings.app.EXTRA_FOCUS_PUSH_NOTIFICATIONS

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class SetupActionsTest {

  @Test
  fun `how messages reach you opens notifications focused on the push section`() {
    val controller = Robolectric.buildActivity(FragmentActivity::class.java)
    val activity = controller.get()
    activity.setTheme(R.style.Theme_Molly_Material3_Light)
    controller.setup()

    try {
      SetupActions.openItem(activity, SetupItem.DELIVERY)

      val intent = requireNotNull(shadowOf(activity).nextStartedActivity)
      assertEquals(AppSettingsActivity::class.java.name, intent.component?.className)
      assertTrue(intent.getBooleanExtra(EXTRA_FOCUS_PUSH_NOTIFICATIONS, false))
    } finally {
      controller.pause().stop().destroy()
    }
  }
}
