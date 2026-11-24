/*
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package im.molly.unifiedpush

import android.app.Application
import im.molly.unifiedpush.model.MollySocketDevice
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.linkdevice.LinkDeviceRepository
import org.thoughtcrime.securesms.testutil.MockSignalStoreRule
import org.thoughtcrime.securesms.testutil.SystemOutLogger
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class MollySocketRepositoryTest {

  @get:Rule
  val signalStore = MockSignalStoreRule()

  private val newDeviceId = 9
  private val prekeyFailure = IOException("synthetic prekey setup failure")
  private val storeFailure = IllegalStateException("synthetic multi-device flag failure")

  @Before
  fun setUp() {
    Log.initialize(SystemOutLogger())
    mockkObject(MollySocketRepository, recordPrivateCalls = true)
    mockkObject(LinkDeviceRepository)
    // Registration succeeds and returns a new device ID; only the steps after it run for real.
    every { MollySocketRepository["registerNewDevice"](any<String>()) } returns newDeviceId
  }

  @After
  fun tearDown() {
    unmockkObject(MollySocketRepository, LinkDeviceRepository)
  }

  @Test
  fun `new device is kept when prekey setup succeeds`() {
    every { MollySocketRepository["loadPreKeys"](newDeviceId) } returns Unit

    val device = createDevice().getOrThrow()

    assertEquals(newDeviceId, device.deviceId)
    verify(exactly = 1) { signalStore.account.isMultiDevice = true }
    coVerify(exactly = 0) { LinkDeviceRepository.removeDevice(any()) }
  }

  @Test
  fun `new device is removed when prekey setup fails`() {
    every { MollySocketRepository["loadPreKeys"](newDeviceId) } throws prekeyFailure
    coEvery { LinkDeviceRepository.removeDevice(newDeviceId) } returns true

    val failure = createDevice().exceptionOrNull()

    assertSame(prekeyFailure, failure)
    assertEquals(0, failure!!.suppressed.size)
    verify(exactly = 0) { signalStore.account.isMultiDevice = any() }
    coVerify(exactly = 1) { LinkDeviceRepository.removeDevice(newDeviceId) }
  }

  @Test
  fun `failed removal after prekey setup failure keeps the original error`() {
    every { MollySocketRepository["loadPreKeys"](newDeviceId) } throws prekeyFailure
    coEvery { LinkDeviceRepository.removeDevice(newDeviceId) } returns false

    val failure = createDevice().exceptionOrNull()

    assertSame(prekeyFailure, failure)
    assertTrue(failure!!.suppressed.single() is IOException)
    verify(exactly = 0) { signalStore.account.isMultiDevice = any() }
    coVerify(exactly = 1) { LinkDeviceRepository.removeDevice(newDeviceId) }
  }

  @Test
  fun `new device is removed when marking account multi-device fails`() {
    every { MollySocketRepository["loadPreKeys"](newDeviceId) } returns Unit
    every { signalStore.account.isMultiDevice = true } throws storeFailure
    coEvery { LinkDeviceRepository.removeDevice(newDeviceId) } returns true

    val failure = createDevice().exceptionOrNull()

    assertSame(storeFailure, failure)
    assertEquals(0, failure!!.suppressed.size)
    coVerify(exactly = 1) { LinkDeviceRepository.removeDevice(newDeviceId) }
  }

  @Test
  fun `failed removal after multi-device flag failure keeps the original error`() {
    every { MollySocketRepository["loadPreKeys"](newDeviceId) } returns Unit
    every { signalStore.account.isMultiDevice = true } throws storeFailure
    coEvery { LinkDeviceRepository.removeDevice(newDeviceId) } returns false

    val failure = createDevice().exceptionOrNull()

    assertSame(storeFailure, failure)
    assertTrue(failure!!.suppressed.single() is IOException)
    coVerify(exactly = 1) { LinkDeviceRepository.removeDevice(newDeviceId) }
  }

  /** Device removal refuses the main thread, which is the Robolectric test thread. */
  private fun createDevice(): Result<MollySocketDevice> {
    val executor = Executors.newSingleThreadExecutor()
    return try {
      executor.submit<Result<MollySocketDevice>> {
        runCatching { MollySocketRepository.createDevice() }
      }.get(5, TimeUnit.SECONDS)
    } finally {
      executor.shutdownNow()
    }
  }
}
