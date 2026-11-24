/* SPDX-License-Identifier: AGPL-3.0-only */
package org.thoughtcrime.securesms.testutil

import android.app.Application
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.util.logging.Log
import org.signal.remoteconfig.RemoteConfig
import org.thoughtcrime.securesms.calls.permissions.CallPermission
import org.thoughtcrime.securesms.keyvalue.SignalStore

/** A ready call-policy fixture must also support the store's ordinary lazy config reads. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE)
class MockSignalStoreRuleTest {
  @get:Rule
  val store = MockSignalStoreRule()

  private var wasUnderTest = false
  private var wasInternalUserDisabled = false

  @Before
  fun setUp() {
    Log.initialize(LogRecorder())
    wasUnderTest = RemoteConfig.underTest
    wasInternalUserDisabled = RemoteConfig.internalUserDisabled
    resetRemoteConfig()
    RemoteConfig.underTest = true
    RemoteConfig.internalUserDisabled = false
  }

  @After
  fun tearDown() {
    resetRemoteConfig()
    RemoteConfig.underTest = wasUnderTest
    RemoteConfig.internalUserDisabled = wasInternalUserDisabled
  }

  @Test
  fun `an initialized mock store supports unstubbed remote-config defaults`() {
    assertTrue(SignalStore.isInitialized)
    assertFalse(RemoteConfig.initialized)

    assertEquals(4, RemoteConfig.pinnedChatLimit)

    assertTrue(RemoteConfig.initialized)
    assertTrue(RemoteConfig.debugDiskValues.isEmpty())
    assertTrue(RemoteConfig.debugPendingDiskValues.isEmpty())
  }

  @Test
  fun `remote-config initialization uses this fixtures storage without changing call permissions`() {
    store.remoteConfig.pendingConfig = "{\"global.pinnedChatLimit\":9}"

    assertEquals(9, RemoteConfig.pinnedChatLimit)

    assertTrue(RemoteConfig.initialized)
    val policy = store.callPermissions.read()
    assertFalse(policy.allCallingDisabled)
    assertEquals(CallPermission.ALLOW, policy.directDefault)
    assertEquals(CallPermission.ALLOW, policy.groupDefault)
    assertTrue(policy.overrides.isEmpty())
  }

  private fun resetRemoteConfig() {
    RemoteConfig.overrides = emptyMap()
    RemoteConfig.REMOTE_VALUES.clear()
    RemoteConfig.initialized = false
  }
}
