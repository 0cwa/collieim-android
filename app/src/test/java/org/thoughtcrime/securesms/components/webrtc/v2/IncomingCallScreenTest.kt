/*
 * Copyright 2026 Collie Messenger
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package org.thoughtcrime.securesms.components.webrtc.v2

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.ui.CoreUiDependenciesRule
import org.signal.core.ui.compose.theme.SignalTheme
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.recipients.Recipient

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class IncomingCallScreenTest {
  @get:Rule val compose = createComposeRule()
  @get:Rule val dependencies = CoreUiDependenciesRule(ApplicationProvider.getApplicationContext())
  private val context: Application = ApplicationProvider.getApplicationContext()

  @Test
  fun `incoming audio call renders answer and decline controls`() {
    compose.setContent {
      // Keep the account-backed avatar out of this resource-loading regression.
      CompositionLocalProvider(LocalInspectionMode provides true) {
        SignalTheme {
          IncomingCallScreen(
            callRecipient = Recipient(systemContactName = "Caller", isResolving = false),
            callStatus = null,
            isVideoCall = false,
            callScreenControlsListener = CallScreenControlsListener.Empty
          )
        }
      }
    }
    compose.onNodeWithText(context.getString(R.string.WebRtcCallScreen__answer)).assertIsDisplayed()
    compose.onNodeWithText(context.getString(R.string.WebRtcCallScreen__decline)).assertIsDisplayed()
  }
}
