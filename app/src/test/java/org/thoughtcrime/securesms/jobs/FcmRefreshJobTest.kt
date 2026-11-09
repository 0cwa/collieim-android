/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.jobs

import io.mockk.every
import io.mockk.verify
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.keyvalue.SettingsValues.NotificationDeliveryMethod
import org.thoughtcrime.securesms.testutil.MockSignalStoreRule
import org.thoughtcrime.securesms.testutil.SystemOutLogger

class FcmRefreshJobTest {

  @get:Rule
  val signalStore = MockSignalStoreRule()

  @Before
  fun setUp() {
    Log.initialize(SystemOutLogger())
  }

  @Test
  fun `exhausted FCM retries do not replace the selected delivery method or enable background mode`() {
    every { signalStore.settings.preferredNotificationMethod } returns NotificationDeliveryMethod.UNIFIEDPUSH

    FcmRefreshJob().onFailure()

    verify(exactly = 0) { signalStore.settings.preferredNotificationMethod = any() }
    verify(exactly = 0) { signalStore.settings.forceWebsocketMode = any() }
  }
}
