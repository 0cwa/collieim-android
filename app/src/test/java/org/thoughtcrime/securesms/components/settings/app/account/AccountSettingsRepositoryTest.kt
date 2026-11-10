/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.components.settings.app.account

import assertk.assertThat
import assertk.assertions.isFalse
import io.mockk.every
import org.junit.Rule
import org.junit.Test
import org.thoughtcrime.securesms.keyvalue.AccountValues
import org.thoughtcrime.securesms.keyvalue.MiscellaneousValues
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.testutil.MockSignalStoreRule

class AccountSettingsRepositoryTest {

  @get:Rule
  val signalStore = MockSignalStoreRule(relaxed = setOf(AccountValues::class, MiscellaneousValues::class))

  @Test
  fun `linked devices are unavailable after local registration is cleared even on a primary device`() {
    every { SignalStore.account.isPrimaryDevice } returns true
    every { SignalStore.account.isRegistered } returns false
    every { SignalStore.account.isUnauthorizedReceived } returns false
    every { SignalStore.misc.isClientDeprecated } returns false

    assertThat(AccountSettingsRepository().canOpenLinkedDevices()).isFalse()
  }
}
