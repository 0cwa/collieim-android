/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.megaphone

import androidx.test.ext.junit.runners.AndroidJUnit4
import assertk.assertThat
import assertk.assertions.isFalse
import assertk.assertions.isNull
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.signal.core.database.deleteAll
import org.thoughtcrime.securesms.database.InAppPaymentTable
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.testing.InAppPaymentsRule
import org.thoughtcrime.securesms.testing.SignalActivityRule
import org.thoughtcrime.securesms.util.VersionTracker

/** Remote donation prompts are suppressed for donors and non-donors alike. */
@RunWith(AndroidJUnit4::class)
class DonationMegaphoneGatingTest {

  @get:Rule
  val harness = SignalActivityRule()

  @get:Rule
  val iapRule = InAppPaymentsRule()

  @Before
  fun setUp() {
    SignalDatabase.inAppPayments.writableDatabase.deleteAll(InAppPaymentTable.TABLE_NAME)
    SignalDatabase.remoteMegaphones.debugRemoveAll()
    setSelfBadges(emptyList())

    mockkStatic(VersionTracker::class)
    every { VersionTracker.getDaysSinceFirstInstalled(any()) } returns 7L
  }

  @After
  fun tearDown() {
    unmockkStatic(VersionTracker::class)
  }

  @Test
  fun nonDonor_suppressesStandardDonateMegaphone() {
    val record = donateMegaphoneRecord(conditionalId = "standard_donate")
    SignalDatabase.remoteMegaphones.insert(record)

    assertThat(RemoteMegaphoneRepository.getRemoteMegaphoneToShow()).isNull()
    assertThat(RemoteMegaphoneRepository.hasRemoteMegaphoneToShow(canShowLocalDonate = true)).isFalse()
  }

  @Test
  fun signalDonorBadge_suppressesStandardDonateMegaphone() {
    val record = donateMegaphoneRecord(conditionalId = "standard_donate")
    SignalDatabase.remoteMegaphones.insert(record)
    setSelfBadges(listOf(donorBadge()))

    assertThat(RemoteMegaphoneRepository.getRemoteMegaphoneToShow()).isNull()
    assertThat(RemoteMegaphoneRepository.hasRemoteMegaphoneToShow(canShowLocalDonate = true)).isFalse()
  }

  @Test
  fun youngInstallation_suppressesStandardDonateMegaphone() {
    every { VersionTracker.getDaysSinceFirstInstalled(any()) } returns 6L
    SignalDatabase.remoteMegaphones.insert(donateMegaphoneRecord(conditionalId = "standard_donate"))

    assertThat(RemoteMegaphoneRepository.getRemoteMegaphoneToShow()).isNull()
    assertThat(RemoteMegaphoneRepository.hasRemoteMegaphoneToShow(canShowLocalDonate = true)).isFalse()
  }
}
