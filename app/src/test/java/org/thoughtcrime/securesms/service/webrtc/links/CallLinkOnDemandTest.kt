/* SPDX-License-Identifier: AGPL-3.0-only */
package org.thoughtcrime.securesms.service.webrtc.links

import android.app.Application
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.calls.permissions.CallLinkPolicy
import org.thoughtcrime.securesms.calls.permissions.CallPermissions
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.service.webrtc.SignalCallManager
import org.thoughtcrime.securesms.testutil.EmptyLogger
import org.thoughtcrime.securesms.testutil.MockSignalStoreRule

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [35])
class CallLinkOnDemandTest {
  @get:Rule val store = MockSignalStoreRule()

  private val manager = mockk<SignalCallManager>()
  private val credentials = CallLinkCredentials(ByteArray(16) { (it + 1).toByte() }, ByteArray(16) { 42 })
  private var generation = 1L
  private var allowed = true

  @Before
  fun setUp() {
    Log.initialize(EmptyLogger())
    mockkObject(CallPermissions)
    mockkStatic(CallPermissions::class, AppDependencies::class)
    every { CallPermissions.generation() } answers { generation }
    every { CallPermissions.isCurrent(any()) } answers { allowed && firstArg<Long>() == generation }
    every { CallPermissions.canUseCallLinks() } answers { allowed }
    every { manager.isEngineReady } returns false
    every { AppDependencies.signalServiceNetworkAccess } throws AssertionError("Passive or denied links must not request native credentials")
    store.callPermissions.setOnDemandCallingEnabled(true)
  }

  @After
  fun tearDown() = unmockkAll()

  @Test
  fun constructionAndUnsubscribedOperationsArePassive() {
    val links = SignalCallLinkManager(manager)
    links.createCallLink(credentials)
    links.readCallLink(credentials)
    links.updateCallLinkName(credentials, "Local draft")
    links.deleteCallLink(credentials)

    verify(exactly = 0) { manager.withCallEngine(any(), any(), any(), any()) }
    verify(exactly = 0) { AppDependencies.signalServiceNetworkAccess }
  }

  @Test
  fun coldOnDemandReadCompletesWithoutCredentialsOrEngineAcquisition() {
    SignalCallLinkManager(manager).readCallLink(credentials).test()
      .assertResult(ReadCallLinkResult.Failure(CallLinkPolicy.DENIED_STATUS))

    verify(exactly = 0) { manager.withCallEngine(any(), any(), any(), any()) }
    verify(exactly = 0) { AppDependencies.signalServiceNetworkAccess }
  }

  @Test
  fun disabledOperationsNeverAcquireEngine() {
    allowed = false
    val links = SignalCallLinkManager(manager)
    links.createCallLink(credentials).test().assertResult(CreateCallLinkResult.Failure(CallLinkPolicy.DENIED_STATUS))
    links.readCallLink(credentials).test().assertResult(ReadCallLinkResult.Failure(CallLinkPolicy.DENIED_STATUS))
    links.updateCallLinkName(credentials, "Draft").test().assertResult(UpdateCallLinkResult.NotAuthorized)
    links.deleteCallLink(credentials).test().assertResult(UpdateCallLinkResult.NotAuthorized)

    verify(exactly = 0) { manager.withCallEngine(any(), any(), any(), any()) }
    verify(exactly = 0) { AppDependencies.signalServiceNetworkAccess }
  }

  @Test
  fun revokeAndReenableBeforeSubscriptionCannotReviveLinkRequest() {
    val operation = SignalCallLinkManager(manager).createCallLink(credentials)
    generation += 2
    operation.test().assertResult(CreateCallLinkResult.Failure(CallLinkPolicy.DENIED_STATUS))

    verify(exactly = 0) { manager.withCallEngine(any(), any(), any(), any()) }
    verify(exactly = 0) { AppDependencies.signalServiceNetworkAccess }
  }
}
