package org.thoughtcrime.securesms.gcm

import android.content.Context
import im.molly.unifiedpush.lifecycle.UnifiedPushAccountTransition.FetchAuthorization
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test

class PushFetchHandlerTest {
  private val context = mockk<Context>()

  @Before fun setup() {
    mockkStatic(FcmFetchManager::class)
    every { FcmFetchManager.startForegroundService(context) } just Runs
    every { FcmFetchManager.enqueueFetch(context, true, null) } just Runs
  }

  @After fun cleanup() = unmockkStatic(FcmFetchManager::class)

  @Test fun `high priority push starts service and schedules fetch`() {
    PushFetchHandler.handleReceivedNotification(context, true)
    verify(exactly = 1) { FcmFetchManager.startForegroundService(context) }
    verify(exactly = 1) { FcmFetchManager.enqueueFetch(context, true, null) }
  }

  @Test fun `UnifiedPush fetch authorization is forwarded to the manager`() {
    val authorization = FetchAuthorization("process", 7, "account", 1, 4, "instance")
    every { FcmFetchManager.enqueueFetch(context, true, authorization) } just Runs

    PushFetchHandler.handleReceivedNotification(context, true, authorization)

    verify(exactly = 1) { FcmFetchManager.enqueueFetch(context, true, authorization) }
  }
}
