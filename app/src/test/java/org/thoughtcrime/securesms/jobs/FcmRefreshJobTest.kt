/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.jobs

import android.content.Context
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.signal.core.util.logging.Log
import org.signal.libsignal.net.RequestResult
import org.thoughtcrime.securesms.gcm.FcmUtil
import org.thoughtcrime.securesms.keyvalue.SettingsValues.NotificationDeliveryMethod
import org.thoughtcrime.securesms.net.SignalNetwork
import org.thoughtcrime.securesms.testutil.MockSignalStoreRule
import org.thoughtcrime.securesms.testutil.SystemOutLogger
import org.whispersystems.signalservice.api.account.AccountApi
import java.io.IOException

class FcmRefreshJobTest {

  @get:Rule
  val signalStore = MockSignalStoreRule()

  private val context = mockk<Context>()
  private val accountApi = mockk<AccountApi>()
  private var tokenLastSetTime = 1234L

  @Before
  fun setUp() {
    Log.initialize(SystemOutLogger())
    val network = mockk<SignalNetwork>()
    every { network.accountApi } returns accountApi
    SignalNetwork.init(network)

    mockkStatic(FcmUtil::class)
    every { FcmUtil.deleteFirebaseInstallationId(context) } just Runs
    every { signalStore.account.fcmEnabled } returns false
    every { signalStore.account.fcmTokenLastSetTime } answers { tokenLastSetTime }
    every { signalStore.account.fcmToken = null } answers { tokenLastSetTime = 0 }
    every { accountApi.clearFcmTokenSync() } answers { callOriginal() }
  }

  @After
  fun tearDown() {
    unmockkStatic(FcmUtil::class)
    SignalNetwork.init(SignalNetwork())
  }

  @Test
  fun `exhausted FCM retries do not replace the selected delivery method or enable background mode`() {
    every { signalStore.settings.preferredNotificationMethod } returns NotificationDeliveryMethod.UNIFIEDPUSH

    FcmRefreshJob().onFailure()

    verify(exactly = 0) { signalStore.settings.preferredNotificationMethod = any() }
    verify(exactly = 0) { signalStore.settings.forceWebsocketMode = any() }
  }

  @Test
  fun `failed FCM removal keeps the pending timestamp and reports the original retryable error`() {
    val networkError = IOException("offline")
    coEvery { accountApi.clearFcmToken() } returns RequestResult.RetryableNetworkError(networkError)
    val job = FcmRefreshJob().apply { setContext(context) }

    val thrown = assertThrows(IOException::class.java) { job.onRun() }

    assertSame(networkError, thrown)
    assertTrue(job.onShouldRetry(thrown))
    assertEquals(1234L, tokenLastSetTime)
    coVerify(exactly = 1) { accountApi.clearFcmToken() }
    verify(exactly = 1) { signalStore.account.invalidateFcmToken() }
    verify(exactly = 1) { FcmUtil.deleteFirebaseInstallationId(context) }
    verify(exactly = 0) { signalStore.account.fcmToken = any() }
    verify(exactly = 0) { signalStore.account.fcmEnabled = any() }
  }

  @Test
  fun `successful FCM removal clears the pending timestamp without enabling FCM`() {
    coEvery { accountApi.clearFcmToken() } returns RequestResult.Success(Unit)

    FcmRefreshJob().apply { setContext(context) }.onRun()

    assertEquals(0L, tokenLastSetTime)
    coVerify(exactly = 1) { accountApi.clearFcmToken() }
    verify(exactly = 1) { signalStore.account.invalidateFcmToken() }
    verify(exactly = 1) { FcmUtil.deleteFirebaseInstallationId(context) }
    verify(exactly = 1) { signalStore.account.fcmToken = null }
    verify(exactly = 0) { signalStore.account.fcmEnabled = any() }
  }

  @Test
  fun `disabled FCM without a previously set token skips network and Firebase cleanup`() {
    tokenLastSetTime = 0

    FcmRefreshJob().apply { setContext(context) }.onRun()

    coVerify(exactly = 0) { accountApi.clearFcmToken() }
    verify(exactly = 0) { FcmUtil.deleteFirebaseInstallationId(context) }
    verify(exactly = 0) { signalStore.account.fcmToken = any() }
  }
}
