/*
 * Copyright 2026 Collie contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package org.thoughtcrime.securesms.pin

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDialog
import org.robolectric.annotation.Config
import org.signal.core.util.PinHashUtil
import org.signal.storage.keyvalue.PinValues
import org.signal.storage.keyvalue.SvrValues
import org.thoughtcrime.securesms.keyvalue.SignalStore

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class RecoveryPinGuardTest {
  private val pinValues = mockk<PinValues>(relaxed = true)
  private val svr = mockk<SvrValues>(relaxed = true)
  private lateinit var activity: AppCompatActivity
  private var calls = 0

  @Before
  fun setup() {
    mockkObject(SignalStore)
    mockkStatic(PinHashUtil::class)
    every { SignalStore.pin } returns pinValues
    every { SignalStore.svr } returns svr
    every { svr.hasPin() } returns true
    every { svr.hasOptedOut() } returns false
    every { svr.localPinHash } returns "synthetic-hash"
    every { PinHashUtil.verifyLocalPinHash("synthetic-hash", any()) } answers { secondArg<String>() == "1234" }
    val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
    activity = controller.get()
    activity.setTheme(com.google.android.material.R.style.Theme_MaterialComponents_DayNight)
    controller.setup()
  }

  @After
  fun teardown() {
    unmockkStatic(PinHashUtil::class)
    unmockkObject(SignalStore)
  }

  @Test
  fun `disabled guard immediately runs action`() {
    every { pinValues.recoveryPinGuardEnabled } returns false
    RecoveryPinGuard.ifEnabled(activity) { calls++ }
    assertEquals(1, calls)
  }

  @Test
  fun `wrong pin and cancel never run action`() {
    every { pinValues.recoveryPinGuardEnabled } returns true
    RecoveryPinGuard.ifEnabled(activity) { calls++ }
    shadowOf(Looper.getMainLooper()).idle()
    val dialog = ShadowDialog.getLatestDialog() as AlertDialog
    findInput(dialog.window!!.decorView).setText("9999")
    dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
    shadowOf(Looper.getMainLooper()).idle()
    assertEquals(0, calls)
    assertNotNull(findInput(dialog.window!!.decorView).error)
    assertTrue(dialog.isShowing)
    dialog.cancel()
    shadowOf(Looper.getMainLooper()).idle()
    assertEquals(0, calls)
  }

  @Test
  fun `correct pin runs action once`() {
    every { pinValues.recoveryPinGuardEnabled } returns true
    RecoveryPinGuard.ifEnabled(activity) { calls++ }
    shadowOf(Looper.getMainLooper()).idle()
    val dialog = ShadowDialog.getLatestDialog() as AlertDialog
    findInput(dialog.window!!.decorView).setText("1234")
    dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
    shadowOf(Looper.getMainLooper()).idle()
    assertEquals(1, calls)
  }

  private fun findInput(view: View): EditText {
    if (view is EditText) return view
    if (view is ViewGroup) {
      for (i in 0 until view.childCount) {
        try { return findInput(view.getChildAt(i)) } catch (_: NoSuchElementException) { }
      }
    }
    throw NoSuchElementException()
  }
}
