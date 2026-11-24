/* SPDX-License-Identifier: AGPL-3.0-only */
package org.thoughtcrime.securesms.service.webrtc

import android.app.Application
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.signal.core.util.logging.Log
import org.signal.ringrtc.CallManager
import org.thoughtcrime.securesms.calls.permissions.CallPermission
import org.thoughtcrime.securesms.calls.permissions.CallPermissionSettings
import org.thoughtcrime.securesms.calls.permissions.CallPermissions
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.jobs.CallingAssetsDownloadJob
import org.thoughtcrime.securesms.testutil.EmptyLogger
import org.thoughtcrime.securesms.testutil.MockSignalStoreRule
import java.util.function.BooleanSupplier
import java.util.function.Consumer

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [35], instrumentedPackages = ["org.signal.ringrtc"])
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
    every { CallPermissions.runIfCurrent(any(), any()) } answers {
      if (disabled) false else {
        secondArg<Runnable>().run()
        true
      }
    }
    every { CallPermissions.settings() } answers {
      CallPermissionSettings(true, disabled, CallPermission.ALLOW, CallPermission.ALLOW, 1L)
    }
    every { AppDependencies.signalCallManager } returns manager
    every { AppDependencies.application } throws AssertionError("Cold assets must not read or download files")
    every { manager.isEngineReady } returns false
    ReflectionHelpers.getStaticField<MutableSet<String>>(CallingAssets::class.java, "registeredLog").clear()
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
    mockkStatic(CallingAssets::class)
    every { store.misc.callingAssetsVersion } returns 0
    every { CallingAssets.downloadMissingAssets() } returns true
    every { CallingAssets.registerAssetsIfNeeded() } returns Unit

    assertTrue(CallingAssetsDownloadJob().run().isSuccess)

    verify(exactly = 1) { CallingAssets.downloadMissingAssets() }
    verify(exactly = 1) { store.misc.callingAssetsVersion = CallingAssets.CURRENT_VERSION }
    verify(exactly = 0) { manager.isEngineReady }
  }

  @Test
  fun defaultOffValidCachedAssetQueuesAcquisitionAndRegistersOnceAfterPreparation() {
    val (entry, content) = mockCachedAsset()
    val authorization = slot<BooleanSupplier>()
    val registration = slot<Consumer<CallManager>>()
    every { manager.withCallEngine(false, capture(authorization), capture(registration), any()) } returns mockk()
    every { manager.addAsset(any(), any()) } returns Unit

    CallingAssets.registerAssetsIfNeeded()

    assertTrue(authorization.captured.asBoolean)
    verifyOrder {
      CallingAssets["verify"](content, entry)
      manager.withCallEngine(false, any(), any(), any())
    }
    verify(exactly = 0) { manager.addAsset(any(), any()) }

    // withCallEngine prepares assets before invoking the queued registration callback.
    every { manager.isEngineReady } returns true
    CallingAssets.registerAssetsIfNeeded()
    registration.captured.accept(mockk(relaxed = true))

    verify(exactly = 1) { manager.addAsset(entry.assetGroup, content) }
  }

  @Test
  fun revocationBeforeCachedRegistrationNeitherAddsAssetNorMarksItRegistered() {
    val (entry, content) = mockCachedAsset()
    val authorization = slot<BooleanSupplier>()
    val registration = slot<Consumer<CallManager>>()
    every { manager.withCallEngine(false, capture(authorization), capture(registration), any()) } returns mockk()
    every { manager.addAsset(any(), any()) } returns Unit

    CallingAssets.registerAssetsIfNeeded()
    disabled = true
    every { manager.isEngineReady } returns true

    assertFalse(authorization.captured.asBoolean)
    registration.captured.accept(mockk(relaxed = true))
    verify(exactly = 0) { manager.addAsset(any(), any()) }

    disabled = false
    CallingAssets.registerAssetsIfNeeded()
    verify(exactly = 1) { manager.addAsset(entry.assetGroup, content) }
  }

  @Test
  fun defaultOffDoesNotAcquireManagerForMissingOrInvalidCachedContent() {
    val (entry, _) = mockCachedAsset(valid = false)
    CallingAssets.registerAssetsIfNeeded()
    every { CallingAssets["getFromFile"](entry.name) } returns null
    CallingAssets.registerAssetsIfNeeded()

    verify(exactly = 0) { AppDependencies.signalCallManager }
    verify(exactly = 0) { manager.withCallEngine(any(), any(), any(), any()) }
  }

  /** Keep IO and digest inputs controlled while exercising the real registration/runtime bridge. */
  private fun mockCachedAsset(valid: Boolean = true): Pair<CallingAssets.ManifestEntry, ByteArray> {
    val entry = ReflectionHelpers.getStaticField<List<CallingAssets.ManifestEntry>>(CallingAssets::class.java, "ASSETS").first()
    val content = byteArrayOf(1, 2, 3)
    mockkObject(CallingAssets, recordPrivateCalls = true)
    every { CallingAssets["getFromFile"](any<String>()) } returns null
    every { CallingAssets["getFromFile"](entry.name) } returns content
    every { CallingAssets["verify"](content, entry) } returns valid
    return entry to content
  }
}
