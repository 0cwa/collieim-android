/*
 * Copyright 2026 Molly Project
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package org.thoughtcrime.securesms.messages

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import im.molly.unifiedpush.lifecycle.UnifiedPushAccountTransition
import im.molly.unifiedpush.lifecycle.UnifiedPushLifecycle
import io.mockk.*
import io.reactivex.rxjava3.core.Observable
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.util.AppForegroundObserver
import org.signal.core.util.logging.Log
import org.signal.core.models.ServiceId
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.keyvalue.UnifiedPushValues
import org.thoughtcrime.securesms.testutil.SystemOutLogger
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.jobmanager.JobManager
import org.signal.storage.protos.RestoreDecisionState
import org.thoughtcrime.securesms.keyvalue.SettingsValues
import org.thoughtcrime.securesms.net.ConnectivityState
import org.thoughtcrime.securesms.net.InternetConnectivityMonitor
import org.thoughtcrime.securesms.testutil.MockSignalStoreRule
import im.molly.unifiedpush.model.MollySocketDevice
import org.whispersystems.signalservice.api.websocket.SignalWebSocket
import org.whispersystems.signalservice.api.websocket.WebSocketConnectionState
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class IncomingMessageObserverConnectivityTest {
  @get:Rule val store = MockSignalStoreRule()
  private val auth = mockk<SignalWebSocket.AuthenticatedWebSocket>(relaxed = true)
  private val unauth = mockk<SignalWebSocket.UnauthenticatedWebSocket>(relaxed = true)
  private val unifiedPushValues = mockk<UnifiedPushValues>(relaxed = true)
  private lateinit var observer: IncomingMessageObserver
  private lateinit var update: (ConnectivityState) -> Unit
  private val connected = CountDownLatch(1)

  @Before fun setup() {
    Log.initialize(SystemOutLogger())
    every { store.account.isRegistered } returns true
    every { store.account.isUnauthorizedReceived } returns false
    val aci = mockk<ServiceId.ACI>()
    every { aci.toString() } returns "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    every { store.account.aci } returns aci
    every { store.account.hasAciIdentityKey() } returns true
    every { store.account.servicePassword } returns "SYNTHETIC-SERVICE-PASSWORD"
    every { store.account.deviceId } returns 1
    every { store.account.pushAvailable } returns true
    every { SignalStore.unifiedpush } returns unifiedPushValues
    every { unifiedPushValues.enabled } returns true
    every { unifiedPushValues.device } returns MollySocketDevice(4, "SYNTHETIC-RELAY-PASSWORD")
    every { unifiedPushValues.endpoint } returns "https://push.example.invalid/endpoint"
    every { unifiedPushValues.endpointP256dh } returns "SYNTHETIC-P256DH"
    every { unifiedPushValues.endpointAuth } returns "SYNTHETIC-AUTH"
    every { unifiedPushValues.endpointInstance } returns "fixture-instance"
    every { store.settings.forceWebsocketMode } returns SettingsValues.ForceWebsocketMode.DISABLED_BY_USER
    every { store.registration.restoreDecisionState } returns RestoreDecisionState(RestoreDecisionState.State.COMPLETED)
    every { auth.state } returns Observable.never()
    every { auth.stateSnapshot } returns WebSocketConnectionState.DISCONNECTED
    every { auth.shouldSendKeepAlives() } returns false
    every { auth.connect() } answers { connected.countDown() }
    every { auth.registerKeepAliveToken(any()) } answers { auth.connect() }
    UnifiedPushLifecycle.resetForTests(initiallyReady = false)
    UnifiedPushLifecycle.initialize(incompleteTransition = false, legacyContextIsCoherent = true)
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
    UnifiedPushLifecycle.resetForTests(initiallyReady = false)
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

  @Test fun `pending admission revoked before observer resumes causes no token or connect`() {
    putObserverInBackgroundIdle()
    val lease = checkNotNull(UnifiedPushAccountTransition.acquireLease())
    val authorization = UnifiedPushAccountTransition.fetchAuthorization(lease)
    val admission = checkNotNull(UnifiedPushAccountTransition.registerFetchAdmission(lease, authorization))

    val executor = Executors.newSingleThreadExecutor()
    try {
      val markerPersisted = CountDownLatch(1)
      val transitionBody = CountDownLatch(1)
      val transition = executor.submit {
        kotlinx.coroutines.runBlocking {
          UnifiedPushLifecycle.withTransition(
            persistIncompleteMarker = { if (it) markerPersisted.countDown() },
            readyAfter = { true },
          ) { transitionBody.countDown() }
        }
      }

      Assert.assertTrue("transition did not start", markerPersisted.await(5, TimeUnit.SECONDS))
      Assert.assertEquals(UnifiedPushLifecycle.FetchAdmissionState.REVOKED, admission.state)
      Assert.assertTrue("pending waiter was not signalled", admission.awaitHandoff(1_000))
      lease.close()
      transition.get(5, TimeUnit.SECONDS)

      update(ConnectivityState.ONLINE)
      observer.updateReceiveLoopToken()

      verify(exactly = 0) { auth.registerKeepAliveToken(any()) }
      verify(exactly = 0) { auth.connect() }
      UnifiedPushLifecycle.finishFetchAdmission(admission)
    } finally {
      executor.shutdownNow()
    }
  }

  @Test fun `claimed admission does not park when transition revokes it before the wait predicate`() {
    putObserverInBackgroundIdle()
    val observerAboutToWait = CountDownLatch(1)
    observer.beforeConnectionWaitForTesting = { observerAboutToWait.countDown() }
    observer.notifyUnifiedPushFetchStateChanged()
    Assert.assertTrue("observer did not reach its idle condition wait", observerAboutToWait.await(5, TimeUnit.SECONDS))
    observer.beforeConnectionWaitForTesting = null

    val lease = checkNotNull(UnifiedPushAccountTransition.acquireLease())
    val authorization = UnifiedPushAccountTransition.fetchAuthorization(lease)
    val admission = checkNotNull(UnifiedPushAccountTransition.registerFetchAdmission(lease, authorization))

    val claimReached = CountDownLatch(1)
    val resumeWaitPredicate = CountDownLatch(1)
    val registrationEntered = CountDownLatch(1)
    val finishRegistration = CountDownLatch(1)
    val events = java.util.Collections.synchronizedList(mutableListOf<String>())
    every { auth.registerKeepAliveToken("MessageRetrieval") } answers {
      registrationEntered.countDown()
      check(finishRegistration.await(5, TimeUnit.SECONDS))
      events += "token-registration-settled"
      auth.connect()
    }

    val executor = Executors.newFixedThreadPool(3)
    try {
      val fetchOwner = executor.submit {
        admission.awaitHandoffCompletion()
        events += "lease-closed"
        lease.close()
      }
      observer.afterAdmissionClaimForTesting = {
        claimReached.countDown()
        check(resumeWaitPredicate.await(5, TimeUnit.SECONDS))
      }
      update(ConnectivityState.ONLINE)

      Assert.assertTrue("observer did not claim the pending request", claimReached.await(5, TimeUnit.SECONDS))
      Assert.assertEquals(UnifiedPushLifecycle.FetchAdmissionState.CLAIMED, admission.state)

      val markerPersisted = CountDownLatch(1)
      val transitionBody = CountDownLatch(1)
      val transition = executor.submit {
        kotlinx.coroutines.runBlocking {
          UnifiedPushLifecycle.withTransition(
            persistIncompleteMarker = { if (it) markerPersisted.countDown() },
            readyAfter = { true },
            afterTransitionStarted = { observer.notifyUnifiedPushFetchStateChanged() },
          ) {
            events += "identity-publication"
            transitionBody.countDown()
          }
        }
      }

      Assert.assertTrue("transition did not revoke the claimed demand", markerPersisted.await(5, TimeUnit.SECONDS))
      Assert.assertTrue(admission.transitionRevoked)
      Assert.assertFalse(UnifiedPushLifecycle.hasActiveFetchAdmissions())
      Assert.assertEquals("transition published while the claimed lease was open", 1L, transitionBody.count)

      resumeWaitPredicate.countDown()
      Assert.assertTrue("observer did not resume token registration after its claim", registrationEntered.await(5, TimeUnit.SECONDS))
      Assert.assertEquals("transition published before token registration settled", 1L, transitionBody.count)
      finishRegistration.countDown()

      Assert.assertTrue("observer did not complete the claimed handoff", admission.awaitHandoff(5_000))
      fetchOwner.get(5, TimeUnit.SECONDS)
      transition.get(5, TimeUnit.SECONDS)
      Assert.assertTrue(events.indexOf("token-registration-settled") < events.indexOf("lease-closed"))
      Assert.assertTrue(events.indexOf("lease-closed") < events.indexOf("identity-publication"))
      verify(exactly = 1) { auth.registerKeepAliveToken("MessageRetrieval") }
    } finally {
      resumeWaitPredicate.countDown()
      finishRegistration.countDown()
      observer.afterAdmissionClaimForTesting = null
      observer.beforeConnectionWaitForTesting = null
      if (admission.state == UnifiedPushLifecycle.FetchAdmissionState.CLAIMED) {
        UnifiedPushLifecycle.completeFetchAdmission(admission)
      }
      lease.close()
      observer.notifyUnifiedPushFetchStateChanged()
      executor.shutdownNow()
    }
  }

  @Test fun `claimed registration settles before publication and current callback may finish afterward`() {
    putObserverInBackgroundIdle()
    val observerParked = CountDownLatch(1)
    val resumeObserver = CountDownLatch(1)
    observer.beforeConnectionWaitForTesting = {
      observerParked.countDown()
      resumeObserver.await()
    }
    observer.notifyUnifiedPushFetchStateChanged()
    try {
      Assert.assertTrue("observer did not park before test setup", observerParked.await(5, TimeUnit.SECONDS))
      update(ConnectivityState.ONLINE)
      val lease = checkNotNull(UnifiedPushAccountTransition.acquireLease())
      val authorization = UnifiedPushAccountTransition.fetchAuthorization(lease)
      val admission = checkNotNull(UnifiedPushAccountTransition.registerFetchAdmission(lease, authorization))

      val registrationEntered = CountDownLatch(1)
      val finishRegistration = CountDownLatch(1)
      val finishAdmittedCallback = CountDownLatch(1)
      val events = java.util.Collections.synchronizedList(mutableListOf<String>())
      every { auth.registerKeepAliveToken("MessageRetrieval") } answers {
        registrationEntered.countDown()
        check(finishRegistration.await(5, TimeUnit.SECONDS))
        events += "token-registration-settled"
        auth.connect()
      }

      val executor = Executors.newFixedThreadPool(3)
      try {
        val fetchOwner = executor.submit {
          check(admission.awaitHandoff(5_000))
          events += "lease-closed"
          lease.close()
          check(finishAdmittedCallback.await(5, TimeUnit.SECONDS))
          events += "admitted-callback-finished"
          UnifiedPushAccountTransition.finishFetchAdmission(admission)
          observer.updateReceiveLoopToken()
        }
        observer.beforeConnectionWaitForTesting = null
        resumeObserver.countDown()
        observer.notifyUnifiedPushFetchStateChanged()
        Assert.assertTrue("observer did not reach the actual token-registration seam", registrationEntered.await(5, TimeUnit.SECONDS))

        val markerPersisted = CountDownLatch(1)
        val transitionBody = CountDownLatch(1)
        val transition = executor.submit {
          kotlinx.coroutines.runBlocking {
            UnifiedPushLifecycle.withTransition(
              persistIncompleteMarker = { if (it) markerPersisted.countDown() },
              readyAfter = { true },
            ) {
              events += "identity-publication"
              transitionBody.countDown()
            }
          }
        }

        Assert.assertTrue("transition did not begin while registration was blocked", markerPersisted.await(5, TimeUnit.SECONDS))
        Assert.assertEquals("publication overtook the claimed token registration", 1L, transitionBody.count)
        finishRegistration.countDown()
        Assert.assertTrue("observer did not complete the claimed handoff", admission.awaitHandoff(5_000))
        Assert.assertEquals(UnifiedPushLifecycle.FetchAdmissionState.ADMITTED, admission.state)
        Assert.assertTrue("publication did not follow lease release", transitionBody.await(5, TimeUnit.SECONDS))
        Assert.assertEquals("callback must still be in flight after publication", 1L, finishAdmittedCallback.count)

        finishAdmittedCallback.countDown()
        fetchOwner.get(5, TimeUnit.SECONDS)
        transition.get(5, TimeUnit.SECONDS)
        Assert.assertTrue(events.indexOf("token-registration-settled") < events.indexOf("lease-closed"))
        Assert.assertTrue(events.indexOf("lease-closed") < events.indexOf("identity-publication"))
        Assert.assertTrue(events.indexOf("identity-publication") < events.indexOf("admitted-callback-finished"))
        verify(atLeast = 1) { auth.removeKeepAliveToken("MessageRetrieval") }
      } finally {
        finishRegistration.countDown()
        finishAdmittedCallback.countDown()
        executor.shutdownNow()
      }
    } finally {
      observer.beforeConnectionWaitForTesting = null
      resumeObserver.countDown()
      observer.notifyUnifiedPushFetchStateChanged()
    }
  }

  @Test fun `handed off UP demand retains its token but does not re-register after transition revocation`() {
    putObserverInBackgroundIdle()
    val observerParked = CountDownLatch(1)
    val resumeObserver = CountDownLatch(1)
    observer.beforeConnectionWaitForTesting = {
      observerParked.countDown()
      resumeObserver.await()
    }
    observer.notifyUnifiedPushFetchStateChanged()
    try {
      Assert.assertTrue("real observer did not park before direct token decisions", observerParked.await(5, TimeUnit.SECONDS))
      update(ConnectivityState.ONLINE)
      val lease = checkNotNull(UnifiedPushAccountTransition.acquireLease())
      val authorization = UnifiedPushAccountTransition.fetchAuthorization(lease)
      val admission = checkNotNull(UnifiedPushAccountTransition.registerFetchAdmission(lease, authorization))

      Assert.assertTrue(observer.updateReceiveLoopToken())
      Assert.assertEquals(UnifiedPushLifecycle.FetchAdmissionState.ADMITTED, admission.state)
      lease.close()
      verify(exactly = 1) { auth.registerKeepAliveToken("MessageRetrieval") }

      val activeDemandRead = CountDownLatch(1)
      val resumeTokenDecision = CountDownLatch(1)
      val executor = Executors.newFixedThreadPool(2)
      try {
        val laterLoop = executor.submit<Boolean> {
          observer.updateReceiveLoopToken {
            activeDemandRead.countDown()
            check(resumeTokenDecision.await(5, TimeUnit.SECONDS))
          }
        }
        Assert.assertTrue("later loop did not read active UP demand", activeDemandRead.await(5, TimeUnit.SECONDS))
        Assert.assertTrue(UnifiedPushLifecycle.hasActiveFetchAdmissions())

        val markerPersisted = CountDownLatch(1)
        val transitionBody = CountDownLatch(1)
        val transition = executor.submit {
          kotlinx.coroutines.runBlocking {
            UnifiedPushLifecycle.withTransition(
              persistIncompleteMarker = { if (it) markerPersisted.countDown() },
              readyAfter = { true },
              afterTransitionStarted = { observer.notifyUnifiedPushFetchStateChanged() },
            ) { transitionBody.countDown() }
          }
        }

        Assert.assertTrue("transition did not start", markerPersisted.await(5, TimeUnit.SECONDS))
        transition.get(5, TimeUnit.SECONDS)
        Assert.assertEquals(0L, transitionBody.count)
        Assert.assertFalse(UnifiedPushLifecycle.hasActiveFetchAdmissions())
        Assert.assertTrue(admission.transitionRevoked)

        resumeTokenDecision.countDown()
        Assert.assertTrue("a retained demand must keep the observer loop active", laterLoop.get(5, TimeUnit.SECONDS))
        verify(exactly = 1) { auth.registerKeepAliveToken("MessageRetrieval") }
        verify(exactly = 0) { auth.removeKeepAliveToken("MessageRetrieval") }

        Assert.assertFalse(observer.updateReceiveLoopToken())
        verify(exactly = 1) { auth.registerKeepAliveToken("MessageRetrieval") }
        verify(exactly = 1) { auth.connect() }
        verify(exactly = 1) { auth.removeKeepAliveToken("MessageRetrieval") }
      } finally {
        resumeTokenDecision.countDown()
        lease.close()
        executor.shutdownNow()
      }
    } finally {
      observer.beforeConnectionWaitForTesting = null
      resumeObserver.countDown()
      observer.notifyUnifiedPushFetchStateChanged()
    }
  }

  @Test fun `authorized helper hands demand to the actual observer and drains without a caller token`() {
    putObserverInBackgroundIdle()
    update(ConnectivityState.ONLINE)
    val jobManager = mockk<JobManager>(relaxed = true)
    every { AppDependencies.isInitialized } returns true
    every { AppDependencies.application } returns ApplicationProvider.getApplicationContext()
    every { AppDependencies.incomingMessageObserver } returns observer
    every { AppDependencies.jobManager } returns jobManager
    every { auth.readMessageBatch(any(), any(), any()) } returns false

    val lease = checkNotNull(UnifiedPushAccountTransition.acquireLease())
    val authorization = UnifiedPushAccountTransition.fetchAuthorization(lease)
    lease.close()
    val executor = Executors.newSingleThreadExecutor()
    val receiveTokenRemoved = CountDownLatch(1)
    every { auth.removeKeepAliveToken("MessageRetrieval") } answers { receiveTokenRemoved.countDown() }
    try {
      val result = executor.submit<UnifiedPushFetchResult> {
        WebSocketDrainer.blockUntilUnifiedPushFetchDrainedAndProcessed(authorization, 5_000)
      }

      Assert.assertEquals(UnifiedPushFetchResult.DRAINED, result.get(8, TimeUnit.SECONDS))
      Assert.assertTrue("observer did not remove its private token after demand cleanup", receiveTokenRemoved.await(5, TimeUnit.SECONDS))
      verify(atLeast = 1) { auth.registerKeepAliveToken("MessageRetrieval") }
      verify(exactly = 0) { auth.registerKeepAliveToken("FcmFetch") }
      verify(exactly = 0) { auth.registerKeepAliveToken("WebsocketStrategy") }
      verify(atLeast = 1) { auth.connect() }
    } finally {
      executor.shutdownNow()
    }
  }

  @Test fun `transition promptly revokes an unclaimed helper demand without starting the socket`() {
    val jobManager = mockk<JobManager>(relaxed = true)
    val observerResolved = CountDownLatch(1)
    every { AppDependencies.isInitialized } returns true
    every { AppDependencies.application } returns ApplicationProvider.getApplicationContext()
    every { AppDependencies.incomingMessageObserver } answers {
      observerResolved.countDown()
      observer
    }
    every { AppDependencies.jobManager } returns jobManager

    val lease = checkNotNull(UnifiedPushAccountTransition.acquireLease())
    val authorization = UnifiedPushAccountTransition.fetchAuthorization(lease)
    lease.close()
    val executor = Executors.newFixedThreadPool(2)
    try {
      val fetch = executor.submit<UnifiedPushFetchResult> {
        WebSocketDrainer.blockUntilUnifiedPushFetchDrainedAndProcessed(authorization, 30_000)
      }
      Assert.assertTrue("fetch did not register its observer demand", observerResolved.await(5, TimeUnit.SECONDS))
      Assert.assertTrue("demand was not pending before the transition", UnifiedPushLifecycle.hasPendingFetchAdmissions())

      val markerPersisted = CountDownLatch(1)
      val transitionBody = CountDownLatch(1)
      val transition = executor.submit {
        kotlinx.coroutines.runBlocking {
          UnifiedPushLifecycle.withTransition(
            persistIncompleteMarker = { if (it) markerPersisted.countDown() },
            readyAfter = { true },
          ) { transitionBody.countDown() }
        }
      }

      Assert.assertTrue("transition did not revoke the pending demand", markerPersisted.await(5, TimeUnit.SECONDS))
      Assert.assertEquals(UnifiedPushFetchResult.REVOKED, fetch.get(5, TimeUnit.SECONDS))
      transition.get(5, TimeUnit.SECONDS)
      Assert.assertEquals(0L, transitionBody.count)
      verify(exactly = 0) { auth.registerKeepAliveToken(any()) }
      verify(exactly = 0) { auth.connect() }
    } finally {
      executor.shutdownNow()
    }
  }

  private fun putObserverInBackgroundIdle() {
    val field = IncomingMessageObserver::class.java.getDeclaredField("appState").apply { isAccessible = true }
    @Suppress("UNCHECKED_CAST")
    val state = field.get(observer) as AtomicReference<Any>
    val stateClass = Class.forName("${IncomingMessageObserver::class.java.name}\$AppState")
    val constructor = stateClass.getDeclaredConstructor(Boolean::class.javaPrimitiveType, Long::class.javaPrimitiveType).apply { isAccessible = true }
    state.set(constructor.newInstance(false, 0L))
  }
}
