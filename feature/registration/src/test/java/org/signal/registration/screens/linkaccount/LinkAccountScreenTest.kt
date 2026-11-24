/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.screens.linkaccount

import android.app.Application
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performFirstLinkClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.ui.CoreUiDependenciesRule
import org.signal.core.ui.compose.QrCodeData
import org.signal.core.ui.compose.theme.SignalTheme
import org.signal.registration.screens.quickrestore.QrState
import org.signal.registration.test.TestTags

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class LinkAccountScreenTest {

  companion object {
    private const val LINKING_CODE = "sgnl://linkdevice?uuid=temporary%2Bid&pub_key=key%2Bvalue%3D&capabilities=backup5"
  }

  @get:Rule
  val composeTestRule = createComposeRule()

  @get:Rule
  val coreUiDependenciesRule = CoreUiDependenciesRule(ApplicationProvider.getApplicationContext())

  @Test
  @Config(sdk = [33])
  fun `copy linking code writes exactly the QR payload as sensitive clipboard content`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    composeTestRule.setContent {
      SignalTheme {
        LinkAccountScreen(
          state = readyState(),
          onEvent = {}
        )
      }
    }

    composeTestRule.onNodeWithTag(TestTags.LINK_ACCOUNT_COPY_CODE_BUTTON).performScrollTo().performClick()

    assertEquals(LINKING_CODE, clipboard.primaryClip!!.getItemAt(0).text.toString())
    assertTrue(clipboard.primaryClip!!.description.extras!!.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE))
    composeTestRule.onNodeWithTag(TestTags.LINK_ACCOUNT_COPY_CODE_BUTTON).assertTextContains("Code copied")
  }

  @Test
  fun `copy is disabled before a live linking code is ready`() {
    composeTestRule.setContent {
      SignalTheme {
        LinkAccountScreen(state = LinkAccountScreenState(), onEvent = {})
      }
    }

    composeTestRule.onNodeWithTag(TestTags.LINK_ACCOUNT_COPY_CODE_BUTTON).performScrollTo().assertIsNotEnabled()
  }

  @Test
  fun `copy is disabled when the session fails or has been used`() {
    val state = mutableStateOf(readyState())
    composeTestRule.setContent {
      SignalTheme {
        LinkAccountScreen(state = state.value, onEvent = {})
      }
    }

    // Even a stale URL cannot be copied if the session is not ready.
    for (qrState in listOf(QrState.Loading, QrState.Failed, QrState.Scanned)) {
      composeTestRule.runOnIdle { state.value = readyState().copy(qrCodeState = qrState) }
      composeTestRule.onNodeWithTag(TestTags.LINK_ACCOUNT_COPY_CODE_BUTTON).performScrollTo().assertIsNotEnabled()
    }
  }

  @Test
  fun `rotation resets copy feedback and copies only after another tap`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val state = mutableStateOf(readyState())
    composeTestRule.setContent {
      SignalTheme {
        LinkAccountScreen(state = state.value, onEvent = {})
      }
    }

    composeTestRule.onNodeWithTag(TestTags.LINK_ACCOUNT_COPY_CODE_BUTTON).performScrollTo().performClick()
    val rotatedCode = LINKING_CODE.replace("temporary", "rotated")
    composeTestRule.runOnIdle { state.value = readyState(rotatedCode) }

    assertEquals(LINKING_CODE, clipboard.primaryClip!!.getItemAt(0).text.toString())
    composeTestRule.onNodeWithTag(TestTags.LINK_ACCOUNT_COPY_CODE_BUTTON).assertTextContains("Copy linking code").performClick()
    assertEquals(rotatedCode, clipboard.primaryClip!!.getItemAt(0).text.toString())
  }

  private fun readyState(code: String = LINKING_CODE): LinkAccountScreenState {
    return LinkAccountScreenState(
      qrCodeState = QrState.Loaded(QrCodeData.forData(code, supportIconOverlay = false)),
      linkingCode = code
    )
  }

  @Test
  fun `when Get Help is clicked, GetHelpClick event is emitted`() {
    var emittedEvent: LinkAccountScreenEvent? = null

    composeTestRule.setContent {
      SignalTheme {
        LinkAccountScreen(
          state = LinkAccountScreenState(),
          onEvent = { emittedEvent = it }
        )
      }
    }

    composeTestRule.onNodeWithTag(TestTags.LINK_ACCOUNT_GET_HELP_BUTTON).performScrollTo().performClick()

    assert(emittedEvent == LinkAccountScreenEvent.GetHelpClick)
  }

  @Test
  fun `when Create Account is clicked, CreateAccountClick event is emitted`() {
    var emittedEvent: LinkAccountScreenEvent? = null

    composeTestRule.setContent {
      SignalTheme {
        LinkAccountScreen(
          state = LinkAccountScreenState(),
          onEvent = { emittedEvent = it }
        )
      }
    }

    composeTestRule.onNodeWithTag(TestTags.LINK_ACCOUNT_CREATE_ACCOUNT_LINK).performFirstLinkClick()

    assert(emittedEvent == LinkAccountScreenEvent.CreateAccountClick)
  }

  @Test
  fun `when Display Overlay is clicked, DisplayOverlayClick event is emitted`() {
    var emittedEvent: LinkAccountScreenEvent? = null

    composeTestRule.setContent {
      SignalTheme {
        LinkAccountScreen(
          state = LinkAccountScreenState(qrCodeState = QrState.Loaded(qrCodeData = QrCodeData.forData("sgnl://test", true))),
          onEvent = { emittedEvent = it }
        )
      }
    }

    composeTestRule.onNodeWithTag(TestTags.LINK_ACCOUNT_DISPLAY_OVERLAY_BUTTON).performClick()

    assert(emittedEvent == LinkAccountScreenEvent.DisplayOverlayClick)
  }

  @Test
  fun `when Hide Overlay is clicked, HideOverlayClick event is emitted`() {
    var emittedEvent: LinkAccountScreenEvent? = null

    composeTestRule.setContent {
      SignalTheme {
        LinkAccountScreen(
          state = LinkAccountScreenState(
            qrCodeState = QrState.Loaded(qrCodeData = QrCodeData.forData("sgnl://test", true)),
            displayQrOverlay = true
          ),
          onEvent = {
            emittedEvent = it
          }
        )
      }
    }

    composeTestRule.onNodeWithTag(TestTags.LINK_ACCOUNT_HIDE_OVERLAY_BUTTON).performClick()

    assert(emittedEvent == LinkAccountScreenEvent.HideOverlayClick)
  }
}
