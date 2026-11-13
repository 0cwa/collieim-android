/*
 * Copyright 2026 Collie Messenger
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.backup

import android.graphics.Rect
import android.view.View
import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.hamcrest.Matcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.signal.core.ui.CoreUiDependencies
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.components.settings.app.AppSettingsActivity
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.testing.SignalActivityRule
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class BackupDialogSecurityTest {
  @get:Rule val harness = SignalActivityRule(othersCount = 0)
  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val context get() = instrumentation.targetContext
  private var previousSecurity: Boolean? = null

  @After fun restoreSetting() {
    previousSecurity?.let { SignalStore.settings.isScreenSecurityEnabled = it }
  }

  @Test fun enabledSecurityProtectsPassphraseDialogWindow() = checkDialog(secure = true)
  @Test fun disabledSecurityAllowsPassphraseDialogCapture() = checkDialog(secure = false)

  private fun checkDialog(secure: Boolean) {
    previousSecurity = SignalStore.settings.isScreenSecurityEnabled
    SignalStore.settings.isScreenSecurityEnabled = secure
    assertEquals("Expected screen-security policy", secure, CoreUiDependencies.isScreenSecurityEnabled)
    ActivityScenario.launch<AppSettingsActivity>(AppSettingsActivity.home(context)).use { scenario ->
      scenario.onActivity { activity ->
        assertEquals("Activity secure flag", secure, activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
        BackupDialog.showEnableBackupDialog(activity, null, null) { }
      }

      val bounds = AtomicReference<Rect>()
      val flags = AtomicInteger()
      onView(withId(R.id.code_first)).inRoot(isDialog()).perform(object : ViewAction {
        override fun getConstraints(): Matcher<View> = androidx.test.espresso.matcher.ViewMatchers.isDisplayed()
        override fun getDescription(): String = "inspect the synthetic backup dialog window"
        override fun perform(uiController: UiController, view: View) {
          val rect = Rect()
          check(view.hasWindowFocus() && view.getGlobalVisibleRect(rect))
          bounds.set(rect)
          flags.set((view.rootView.layoutParams as WindowManager.LayoutParams).flags)
        }
      })
      val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
      try {
        val rect = bounds.get()
        var nonBlack = 0
        for (y in rect.top until rect.bottom) {
          for (x in rect.left until rect.right) {
            if (bitmap.getPixel(x, y) and 0x00ffffff != 0) nonBlack++
          }
        }
        if (secure) {
          assertEquals("Secure dialog pixels must be redacted", 0, nonBlack)
        } else {
          assertTrue("Dialog must be visible when screen security is disabled", nonBlack > 0)
        }
      } finally {
        bitmap.recycle()
      }
      assertEquals("Dialog secure flag", secure, flags.get() and WindowManager.LayoutParams.FLAG_SECURE != 0)
    }
  }
}
