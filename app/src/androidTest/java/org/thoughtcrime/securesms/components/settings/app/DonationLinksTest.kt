/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package org.thoughtcrime.securesms.components.settings.app

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.font.FontWeight
import androidx.test.ext.junit.runners.AndroidJUnit4
import assertk.assertThat
import assertk.assertions.isLessThan
import assertk.assertions.isEqualTo
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.signal.core.ui.compose.theme.SignalTheme
import org.thoughtcrime.securesms.util.CommunicationActions

@RunWith(AndroidJUnit4::class)
class DonationLinksTest {
  @get:Rule val composeRule = createComposeRule()

  @Before fun setUp() {
    mockkStatic(CommunicationActions::class)
    every { CommunicationActions.openBrowserLink(any(), any()) } returns Unit
  }

  @After fun tearDown() = unmockkStatic(CommunicationActions::class)

  @Test fun colliePrecedesMollyAndEachLinkOpensItsOwnRecipient() {
    composeRule.setContent { SignalTheme { DonationLinks() } }
    val collie = composeRule.onNodeWithText("Donate to Collie 🧡🦴")
    val molly = composeRule.onNodeWithText("Donate to Molly")
    assertThat(collie.fetchSemanticsNode().boundsInRoot.top).isLessThan(molly.fetchSemanticsNode().boundsInRoot.top)
    val collieText = collie.fetchSemanticsNode().config[SemanticsProperties.Text].single()
    val mollyText = molly.fetchSemanticsNode().config[SemanticsProperties.Text].single()
    assertThat(collieText.spanStyles.single().item.fontWeight).isEqualTo(FontWeight.Bold)
    assertThat(mollyText.spanStyles.isEmpty()).isEqualTo(true)
    collie.performClick()
    molly.performClick()
    verify(exactly = 1) { CommunicationActions.openBrowserLink(any(), "https://liberapay.com/Collie") }
    verify(exactly = 1) { CommunicationActions.openBrowserLink(any(), "https://opencollective.com/mollyim") }
  }
}
