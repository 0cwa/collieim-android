/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package org.thoughtcrime.securesms.megaphone

import androidx.fragment.app.FragmentActivity
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.thoughtcrime.securesms.util.CommunicationActions
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class DonationMegaphoneTest {
  private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
  private val activity = mockk<FragmentActivity>()
  private val controller = mockk<MegaphoneActionController>(relaxed = true)

  @Before
  fun setUp() {
    mockkStatic(CommunicationActions::class)
    every { controller.megaphoneActivity } returns activity
  }

  @After
  fun tearDown() = unmockkStatic(CommunicationActions::class)

  @Test
  fun localPromptOpensCollieAndCompletesOnlyOnSuccess() {
    val prompt = Megaphones.buildDonateQ2Megaphone(context)
    every { CommunicationActions.tryOpenBrowserLink(activity, any()) } returns false andThen true

    assertThat(prompt.title.resolve(context)).isEqualTo("Support Collie")
    prompt.buttonClickListener!!.onEvent(prompt, controller)
    verify(exactly = 1) { CommunicationActions.tryOpenBrowserLink(activity, "https://liberapay.com/Collie") }
    verify(exactly = 0) { controller.onMegaphoneCompleted(any()) }

    prompt.buttonClickListener!!.onEvent(prompt, controller)
    verify(exactly = 1) { controller.onMegaphoneCompleted(Megaphones.Event.DONATE_MOLLY) }
  }

  @Test
  fun dismissalKeepsTheExistingEventKey() {
    val prompt = Megaphones.buildDonateQ2Megaphone(context)
    assertThat(Megaphones.Event.fromKey("donate_molly")).isEqualTo(prompt.event)
    prompt.secondaryButtonClickListener!!.onEvent(prompt, controller)
    verify(exactly = 1) { controller.onMegaphoneCompleted(Megaphones.Event.DONATE_MOLLY) }
  }

  @Test
  fun displayWindowEndsSevenDaysAfterFirstVisibility() {
    val schedule = ShowForDurationSchedule.showForDays(7)
    val firstVisible = 1000L
    val end = firstVisible + TimeUnit.DAYS.toMillis(7)
    assertThat(schedule.shouldDisplay(0, 0, 0, end)).isTrue()
    assertThat(schedule.shouldDisplay(0, 0, firstVisible, end - 1)).isTrue()
    assertThat(schedule.shouldDisplay(0, 0, firstVisible, end)).isFalse()
  }
}
