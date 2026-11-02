/*
 * Copyright 2026 Molly Project
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package org.thoughtcrime.securesms.messages

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.mockk.*
import io.reactivex.rxjava3.core.Observable
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.util.AppForegroundObserver
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.testutil.SystemOutLogger
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.database.model.databaseprotos.RestoreDecisionState
import org.thoughtcrime.securesms.keyvalue.SettingsValues
import org.thoughtcrime.securesms.net.ConnectivityState
import org.thoughtcrime.securesms.net.InternetConnectivityMonitor
import org.thoughtcrime.securesms.testutil.MockSignalStoreRule
import org.whispersystems.signalservice.api.websocket.SignalWebSocket
import org.whispersystems.signalservice.api.websocket.WebSocketConnectionState
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class IncomingMessageObserverConnectivityTest {
  @get:Rule val store = MockSignalStoreRule()
  private val auth = mockk<SignalWebSocket.AuthenticatedWebSocket>(relaxed = true)
  private val unauth = mockk<SignalWebSocket.UnauthenticatedWebSocket>(relaxed = true)
  private lateinit var observer: IncomingMessageObserver
  private lateinit var update: (ConnectivityState) -> Unit
  private val connected = CountDownLatch(1)

  @Before fun setup() {
    Log.initialize(SystemOutLogger())
    every { store.account.isRegistered } returns true
    every { store.account.isUnauthorizedReceived } returns false
    every { store.account.pushAvailable } returns true
    every { store.settings.forceWebsocketMode } returns SettingsValues.ForceWebsocketMode.DISABLED_BY_USER
    every { store.registration.restoreDecisionState } returns RestoreDecisionState(RestoreDecisionState.State.START)
    every { auth.state } returns Observable.never()
    every { auth.stateSnapshot } returns WebSocketConnectionState.DISCONNECTED
    every { auth.shouldSendKeepAlives() } returns false
    every { auth.connect() } answers { connected.countDown() }
    mockkStatic(AppDependencies::class, AppForegroundObserver::class, MessageContentProcessor::class)
    mockkObject(AppForegroundObserver, MessageContentProcessor.Companion)
    every { AppDependencies.signalServiceNetworkAccess } returns mockk(relaxed = true)
    every { AppDependencies.networkManager } returns mockk(relaxed = true)
    every { AppDependencies.libsignalNetwork } returns mockk(relaxed = true)
    every { MessageContentProcessor.create(any()) } returns mockk(relaxed = true)
    every { AppForegroundObserver.addListener(any()) } just Runs
    every { AppForegroundObserver.removeListener(any()) } just Runs
    mockkConstructor(InternetConnectivityMonitor::class)
    every { anyConstructed<InternetConnectivityMonitor>().register() } just Runs
    every { anyConstructed<InternetConnectivityMonitor>().unregister() } just Runs
    observer = IncomingMessageObserver(ApplicationProvider.getApplicationContext(), auth, unauth)
    val monitorField = IncomingMessageObserver::class.java.getDeclaredField("internetConnectivityMonitor").apply { isAccessible = true }
    val monitor = monitorField.get(observer)
    val callbackField = InternetConnectivityMonitor::class.java.getDeclaredField("onConnectivityUpdated").apply { isAccessible = true }
    @Suppress("UNCHECKED_CAST")
    update = callbackField.get(monitor) as (ConnectivityState) -> Unit
  }

  @After fun cleanup() {
    if (::observer.isInitialized) observer.terminate()
    unmockkConstructor(InternetConnectivityMonitor::class)
    unmockkObject(AppForegroundObserver, MessageContentProcessor.Companion)
    unmockkStatic(AppDependencies::class, AppForegroundObserver::class, MessageContentProcessor::class)
  }

  @Test fun `startup and unavailable or unvalidated states do not connect but online does`() {
    Assert.assertFalse("startup connected before a connectivity report", connected.await(500, TimeUnit.MILLISECONDS))
    update(ConnectivityState.MONITORING_UNAVAILABLE)
    Assert.assertFalse("monitor failure connected", connected.await(500, TimeUnit.MILLISECONDS))
    update(ConnectivityState.CONNECTED_UNVALIDATED)
    Assert.assertFalse("unvalidated network connected", connected.await(500, TimeUnit.MILLISECONDS))
    update(ConnectivityState.ONLINE)
    Assert.assertTrue("validated network did not connect", connected.await(2, TimeUnit.SECONDS))
  }

  @Test fun `losing validation disconnects both websockets`() {
    update(ConnectivityState.ONLINE)
    Assert.assertTrue(connected.await(2, TimeUnit.SECONDS))
    clearMocks(auth, unauth, answers = false, recordedCalls = true)
    update(ConnectivityState.CONNECTED_UNVALIDATED)
    verify(atLeast = 1) { auth.disconnect() }
    verify(exactly = 1) { unauth.disconnect() }
  }
}
