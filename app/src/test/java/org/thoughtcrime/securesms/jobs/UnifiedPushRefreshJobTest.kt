/*
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.jobs

import android.app.Application
import im.molly.unifiedpush.model.MollySocketDevice
import im.molly.unifiedpush.model.RegistrationStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.keyvalue.UnifiedPushValues
import org.thoughtcrime.securesms.linkdevice.Device
import org.thoughtcrime.securesms.linkdevice.LinkDeviceRepository
import org.thoughtcrime.securesms.testutil.MockSignalStoreRule
import org.thoughtcrime.securesms.testutil.SystemOutLogger
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class UnifiedPushRefreshJobTest {

  @get:Rule
  val signalStore = MockSignalStoreRule()

  private val unifiedPush = mockk<UnifiedPushValues>(relaxUnitFun = true)
  private val device = MollySocketDevice(deviceId = 4, password = "retained-relay-credential")
  private var storedDevice: MollySocketDevice? = device

  @Before
  fun setUp() {
    Log.initialize(SystemOutLogger())
    mockkObject(LinkDeviceRepository)

    every { SignalStore.unifiedpush } returns unifiedPush
    every { signalStore.account.isRegistered } returns true
    every { unifiedPush.enabled } returns false
    every { unifiedPush.airGapped } returns false
    every { unifiedPush.registrationStatus } returns RegistrationStatus.UNKNOWN
    every { unifiedPush.device } answers { storedDevice }
    every { unifiedPush.device = any() } answers { storedDevice = firstArg() }

    coEvery { LinkDeviceRepository.loadDevices() } returns listOf(
      Device(id = device.deviceId, name = "MollySocket", createdMillis = null, lastSeenMillis = 0, registrationId = 1)
    )
  }

  @After
  fun tearDown() {
    unmockkObject(LinkDeviceRepository)
  }

  @Test
  fun `failed unlink keeps relay credentials and reports a retryable failure`() {
    coEvery { LinkDeviceRepository.removeDevice(device.deviceId) } returns false

    val failure = runCleanup()

    assertTrue(failure is IOException)
    assertTrue(UnifiedPushRefreshJob().onShouldRetry(failure as IOException))
    assertSame(device, storedDevice)
    verify(exactly = 0) { unifiedPush.device = any() }
    coVerify(exactly = 1) { LinkDeviceRepository.removeDevice(device.deviceId) }
  }

  @Test
  fun `successful unlink clears relay credentials`() {
    coEvery { LinkDeviceRepository.removeDevice(device.deviceId) } returns true

    assertNull(runCleanup())

    assertNull(storedDevice)
    verify(exactly = 1) { unifiedPush.device = null }
    coVerify(exactly = 1) { LinkDeviceRepository.removeDevice(device.deviceId) }
  }

  @Test
  fun `unknown link status keeps relay credentials without attempting unlink`() {
    coEvery { LinkDeviceRepository.loadDevices() } returns null

    assertTrue(runCleanup() is IOException)

    assertSame(device, storedDevice)
    verify(exactly = 0) { unifiedPush.device = any() }
    coVerify(exactly = 0) { LinkDeviceRepository.removeDevice(any()) }
  }

  private fun runCleanup(): Throwable? {
    val executor = Executors.newSingleThreadExecutor()
    return try {
      executor.submit<Throwable?> {
        runCatching { UnifiedPushRefreshJob().onRun() }.exceptionOrNull()
      }.get(5, TimeUnit.SECONDS)
    } finally {
      executor.shutdownNow()
    }
  }
}
