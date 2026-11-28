/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.appsettings.privacy

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalInspectionMode
import org.junit.Test
import org.signal.core.ui.SignalLayoutStressTest
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/** Stress-tests Molly's privacy screen, including Collie's inline network section. */
class PrivacySettingsLayoutTest : SignalLayoutStressTest() {

  private val populated = PrivacySettingsState(
    blockedCount = 1234,
    blockUnknown = true,
    showHiddenContacts = true,
    hiddenCount = 1234,
    readReceipts = true,
    typingIndicators = true,
    universalExpireTimer = 4.days,
    passphraseLock = true,
    passphraseLockTriggerValues = setOf("timeout"),
    passphraseLockTriggerOptions = listOf(
      PassphraseLockTriggerOption("timeout", "After a period of inactivity"),
      PassphraseLockTriggerOption("screen_off", "When the screen turns off")
    ),
    isPassphraseLockTimeoutEnabled = true,
    passphraseLockTimeout = 6.days + 23.hours,
    biometricScreenLock = true,
    screenSecurity = true,
    incognitoKeyboard = true,
    networkConnectionStatus = PrivacySettingsState.NetworkConnectionStatus.Failed,
    proxySummary = PrivacySettingsState.ProxySummary.Socks5("proxy.example.invalid", 9050)
  )

  @Test
  fun `defaults`() = assertPassesLayoutChecks {
    Screen(PrivacySettingsState())
  }

  @Test
  fun `populated`() = assertPassesLayoutChecks {
    Screen(populated)
  }

  @Test
  fun `without a phone number and with a forced secure window`() = assertPassesLayoutChecks {
    Screen(populated.copy(hasPhoneNumber = false, screenSecurity = false, isScreenSecurityForced = true))
  }

  @Test
  fun `checking the connection through a proxy`() = assertPassesLayoutChecks {
    Screen(
      populated.copy(
        passphraseLockTimeout = 30.minutes,
        networkConnectionStatus = PrivacySettingsState.NetworkConnectionStatus.Checking(throughProxy = true)
      )
    )
  }

  @Test
  fun `picking automatic lockdown triggers`() = assertPassesLayoutChecks {
    Screen(populated.copy(dialog = PrivacySettingsState.Dialog.AutomaticLockdown))
  }

  @Test
  fun `showing a snackbar`() = assertPassesLayoutChecks {
    Screen(populated.copy(snackbar = PrivacySettingsState.Snackbar.BiometricEnrollmentRequired))
  }

  @Composable
  private fun Screen(state: PrivacySettingsState) {
    // The biometrics helper needs a FragmentActivity to show its prompt. Inspection mode makes it pass straight through.
    CompositionLocalProvider(LocalInspectionMode provides true) {
      PrivacySettingsScreen(state = state, onEvent = {})
    }
  }
}
