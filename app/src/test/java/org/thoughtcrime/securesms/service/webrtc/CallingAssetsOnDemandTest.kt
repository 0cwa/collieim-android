/* SPDX-License-Identifier: AGPL-3.0-only */
package org.thoughtcrime.securesms.service.webrtc

import android.app.Application
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.calls.permissions.CallPermission
import org.thoughtcrime.securesms.calls.permissions.CallPermissionSettings
import org.thoughtcrime.securesms.calls.permissions.CallPermissions
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.jobs.CallingAssetsDownloadJob
import org.thoughtcrime.securesms.testutil.EmptyLogger
import org.thoughtcrime.securesms.testutil.MockSignalStoreRule

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [35])
class CallingAssetsOnDemandTest {
  @get:Rule val store = MockSignalStoreRule()

  private val manager = mockk<SignalCallManager>()
  private var disabled = false

  @Before
  fun setUp() {
    Log.initialize(EmptyLogger())
    mockkObject(CallPermissions)
    mockkStatic(CallPermissions::class, AppDependencies::class)
    every { CallPermissions.generation() } returns 1L
    every { CallPermissions.isCurrent(any()) } answers { !disabled }
    every { CallPermissions.settings() } answers {
      CallPermissionSettings(true, disabled, CallPermission.ALLOW, CallPermission.ALLOW, 1L)
    }
    every { AppDependencies.signalCallManager } returns manager
    every { AppDependencies.application } throws AssertionError("Cold assets must not read or download files")
    every { manager.isEngineReady } returns false
  }

  @After
  fun tearDown() = unmockkAll()

  @Test
  fun absoluteDisableSkipsDownloadAndRegistrationWithoutManagerLookup() {
    disabled = true
    assertTrue(CallingAssetsDownloadJob().run().isSuccess)
    CallingAssets.registerAssetsIfNeeded()

    verify(exactly = 0) { AppDependencies.signalCallManager }
    verify(exactly = 0) { AppDependencies.application }
  }

  @Test
  fun coldOnDemandDefersAssetsWithoutRetryOrNativeRegistration() {
    store.callPermissions.setOnDemandCallingEnabled(true)
    assertTrue(CallingAssetsDownloadJob().run().isSuccess)
    CallingAssets.registerAssetsIfNeeded()

    verify(exactly = 0) { manager.addAsset(any(), any()) }
    verify(exactly = 0) { AppDependencies.application }
    verify(exactly = 0) { store.misc.callingAssetsVersion = any() }
  }

  @Test
  fun defaultOffStillDownloadsAndRecordsAssetVersion() {
    mockkObject(CallingAssets)
    every { store.misc.callingAssetsVersion } returns 0
    every { CallingAssets.downloadMissingAssets() } returns true
    every { CallingAssets.registerAssetsIfNeeded() } returns Unit

    assertTrue(CallingAssetsDownloadJob().run().isSuccess)

    verify(exactly = 1) { CallingAssets.downloadMissingAssets() }
    verify(exactly = 1) { store.misc.callingAssetsVersion = CallingAssets.CURRENT_VERSION }
    verify(exactly = 0) { manager.isEngineReady }
  }
}
