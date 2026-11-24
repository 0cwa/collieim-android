/*
 * Copyright 2026 Collie
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.linkdevice

import android.app.Application
import android.net.Uri
import androidx.lifecycle.viewModelScope
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.models.ServiceId.PNI
import org.signal.core.models.backup.MessageBackupKey
import org.signal.core.util.logging.Log
import org.signal.remoteconfig.RemoteConfig
import org.thoughtcrime.securesms.linkdevice.LinkDeviceRepository.LinkDeviceResult
import org.thoughtcrime.securesms.linkdevice.LinkDeviceSettingsState.DialogState
import org.thoughtcrime.securesms.linkdevice.LinkDeviceSettingsState.QrCodeState
import org.thoughtcrime.securesms.testutil.MockAppDependenciesRule
import org.thoughtcrime.securesms.testutil.MockSignalStoreRule
import org.thoughtcrime.securesms.testutil.SystemOutLogger
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE)
class LinkDeviceViewModelTest {

  companion object {
    private const val PUBLIC_KEY = "BdDF6-_UPrbtdNGGRtLqDie8khmxlEEMrN8t_mNF1m5V"
    private const val CODE = "sgnl://linkdevice?uuid=temporary-device&pub_key=$PUBLIC_KEY"
    private const val SYNC_CODE = "$CODE&capabilities=backup5,nopni2"
  }

  @get:Rule
  val signalStore = MockSignalStoreRule()

  @get:Rule
  val appDependencies = MockAppDependenciesRule()

  private val requestStarted = CountDownLatch(1)
  private val finishRequest = CountDownLatch(1)
  private val requestCount = AtomicInteger()
  private val requestedUri = AtomicReference<Uri>()
  private val requestedBackupKey = AtomicReference<MessageBackupKey?>()
  private var wasUnderTest = false

  private lateinit var viewModel: LinkDeviceViewModel

  @Before
  fun setUp() {
    Dispatchers.setMain(StandardTestDispatcher())
    Log.initialize(SystemOutLogger())
    wasUnderTest = RemoteConfig.underTest
    RemoteConfig.underTest = true

    every { signalStore.account.isLinkedDevice } returns false
    every { signalStore.account.pni } returns PNI.from(UUID.fromString("00000000-0000-4000-8000-000000000001"))
    every { signalStore.uiHints.hasSeenLinkDeviceQrEducationSheet() } returns true

    mockkObject(LinkDeviceRepository)
    every { LinkDeviceRepository.addDevice(any(), any()) } answers {
      requestedUri.set(firstArg())
      requestedBackupKey.set(secondArg())
      requestCount.incrementAndGet()
      requestStarted.countDown()
      // Keep the actual IO worker in flight while confirmation and dismissal callbacks run.
      finishRequest.await(5, TimeUnit.SECONDS)
      LinkDeviceResult.NoDevice
    }

    viewModel = LinkDeviceViewModel()
  }

  @After
  fun tearDown() {
    finishRequest.countDown()
    if (::viewModel.isInitialized) {
      runBlocking { viewModel.viewModelScope.coroutineContext[Job]?.cancelAndJoin() }
    }
    unmockkObject(LinkDeviceRepository)
    RemoteConfig.underTest = wasUnderTest
    Dispatchers.resetMain()
  }

  @Test
  fun `pasting a trimmed code waits for confirmation without provisioning`() {
    viewModel.requestLinkWithoutQrCode(true)

    viewModel.onQrCodeScanned(" \n$CODE\r\n ")

    assertTrue(viewModel.state.value.linkWithoutQrCode)
    assertEquals(CODE, viewModel.state.value.linkUri.toString())
    assertEquals(QrCodeState.VALID_WITHOUT_SYNC, viewModel.state.value.qrCodeState)
    assertEquals(DialogState.None, viewModel.state.value.dialogState)
    assertNoProvisioning()
  }

  @Test
  fun `pasted backup5 code offers transfer for a numberless account`() {
    every { signalStore.account.pni } returns null
    viewModel.requestLinkWithoutQrCode(true)

    viewModel.onQrCodeScanned(SYNC_CODE)

    assertEquals(QrCodeState.VALID_WITH_SYNC, viewModel.state.value.qrCodeState)
    assertEquals("backup5,nopni2", viewModel.state.value.linkUri!!.getQueryParameter("capabilities"))
    assertNoProvisioning()
  }

  @Test
  fun `numberless capable code without backup5 uses ordinary confirmation`() {
    every { signalStore.account.pni } returns null

    viewModel.onQrCodeScanned("$CODE&capabilities=nopni2,future-capability")

    assertEquals(QrCodeState.VALID_WITHOUT_SYNC, viewModel.state.value.qrCodeState)
    assertNoProvisioning()
  }

  @Test
  fun `backup capability cannot bypass an outdated device rejection`() {
    every { signalStore.account.pni } returns null

    viewModel.onQrCodeScanned("$CODE&capabilities=backup5,nopni")
    viewModel.addDevice(shouldSync = false)
    viewModel.addDevice(shouldSync = true)

    assertEquals(QrCodeState.OUTDATED_DEVICE, viewModel.state.value.qrCodeState)
    assertEquals(DialogState.None, viewModel.state.value.dialogState)
    assertNoProvisioning()
  }

  @Test
  fun `invalid code cannot be confirmed or retained`() {
    viewModel.onQrCodeScanned("sgnl://linkdevice?uuid=temporary-device&pub_key=invalid")
    viewModel.addDevice(shouldSync = false)
    viewModel.addDevice(shouldSync = true)

    assertEquals(QrCodeState.INVALID, viewModel.state.value.qrCodeState)
    assertNull(viewModel.state.value.linkUri)
    assertEquals(DialogState.None, viewModel.state.value.dialogState)
    assertNoProvisioning()
  }

  @Test
  fun `canceling confirmation clears the code and a later confirmation does nothing`() {
    viewModel.onQrCodeScanned(SYNC_CODE)

    viewModel.onQrCodeDismissed()
    viewModel.addDevice(shouldSync = false)

    assertEquals(QrCodeState.NONE, viewModel.state.value.qrCodeState)
    assertNull(viewModel.state.value.linkUri)
    assertEquals(DialogState.None, viewModel.state.value.dialogState)
    assertNoProvisioning()
  }

  @Test
  fun `switching input method clears a previous pending confirmation`() {
    viewModel.onQrCodeScanned(CODE)

    viewModel.requestLinkWithoutQrCode(true)
    viewModel.addDevice(shouldSync = false)

    assertTrue(viewModel.state.value.linkWithoutQrCode)
    assertEquals(QrCodeState.NONE, viewModel.state.value.qrCodeState)
    assertNull(viewModel.state.value.linkUri)
    assertNoProvisioning()
  }

  @Test
  fun `another scan cannot replace the code awaiting confirmation`() {
    viewModel.onQrCodeScanned(SYNC_CODE)

    viewModel.onQrCodeScanned(CODE.replace("temporary-device", "another-device"))

    assertEquals(SYNC_CODE, viewModel.state.value.linkUri.toString())
    assertEquals(QrCodeState.VALID_WITH_SYNC, viewModel.state.value.qrCodeState)
    assertNoProvisioning()
  }

  @Test
  fun `a linked account cannot start another device link`() {
    every { signalStore.account.isLinkedDevice } returns true
    viewModel = LinkDeviceViewModel()

    viewModel.onQrCodeScanned(SYNC_CODE)
    viewModel.addDevice(shouldSync = true)

    assertFalse(viewModel.state.value.canLinkDevices)
    assertEquals(QrCodeState.NONE, viewModel.state.value.qrCodeState)
    assertNull(viewModel.state.value.linkUri)
    assertNoProvisioning()
  }

  @Test
  fun `a code without transfer capability cannot request a backup`() {
    viewModel.onQrCodeScanned(CODE)

    viewModel.addDevice(shouldSync = true)

    assertEquals(QrCodeState.VALID_WITHOUT_SYNC, viewModel.state.value.qrCodeState)
    assertEquals(CODE, viewModel.state.value.linkUri.toString())
    assertEquals(DialogState.None, viewModel.state.value.dialogState)
    assertNoProvisioning()
  }

  @Test
  fun `confirmation consumes the code before dismissal and cannot be repeated`() {
    viewModel.onQrCodeScanned(SYNC_CODE)

    viewModel.addDevice(shouldSync = false)

    assertEquals(QrCodeState.NONE, viewModel.state.value.qrCodeState)
    assertNull(viewModel.state.value.linkUri)
    assertEquals(DialogState.Linking, viewModel.state.value.dialogState)

    viewModel.onQrCodeDismissed()
    viewModel.addDevice(shouldSync = false)
    viewModel.onQrCodeScanned(CODE.replace("temporary-device", "another-device"))

    assertTrue("The approved request should start", requestStarted.await(5, TimeUnit.SECONDS))
    assertEquals(1, requestCount.get())
    assertEquals(SYNC_CODE, requestedUri.get().toString())
    assertNull(requestedBackupKey.get())
    assertNull(viewModel.state.value.linkUri)

    completeRequest()
    verify(exactly = 1) { LinkDeviceRepository.addDevice(any(), any()) }
  }

  @Test
  fun `transfer confirmation keeps its captured code when the sheet dismisses`() {
    viewModel.onQrCodeScanned(SYNC_CODE)

    viewModel.addDevice(shouldSync = true)
    viewModel.onQrCodeDismissed()

    assertTrue("The transfer request should start", requestStarted.await(5, TimeUnit.SECONDS))
    assertEquals(SYNC_CODE, requestedUri.get().toString())
    assertNotNull(requestedBackupKey.get())
    assertNull(viewModel.state.value.linkUri)
    assertEquals(DialogState.Linking, viewModel.state.value.dialogState)

    completeRequest()
    verify(exactly = 1) { LinkDeviceRepository.addDevice(any(), any()) }
  }

  private fun assertNoProvisioning() {
    verify(exactly = 0) { LinkDeviceRepository.addDevice(any(), any()) }
  }

  private fun completeRequest() {
    finishRequest.countDown()
    runBlocking {
      withTimeout(5_000) {
        viewModel.state.first { it.linkDeviceResult == LinkDeviceResult.NoDevice && it.dialogState == DialogState.None }
      }
    }
  }
}
