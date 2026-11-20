package org.thoughtcrime.securesms.components.settings.app.notifications

import android.app.Application
import android.os.Looper
import android.widget.TextView
import androidx.activity.result.ActivityResultCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import im.molly.unifiedpush.UnifiedPushDefaultDistributorLinkActivity
import im.molly.unifiedpush.components.settings.app.notifications.MollySocketQrScannerActivity
import im.molly.unifiedpush.enrollment.RelayDisclosure
import im.molly.unifiedpush.model.MollySocket
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.signal.appsettings.routes.AppSettingsRoute
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.components.settings.app.routes.AppSettingsRouter
import org.thoughtcrime.securesms.keyvalue.SettingsValues.NotificationDeliveryMethod

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class MollyNotificationCallbacksTest {
  private lateinit var controller: ActivityController<FragmentActivity>
  private lateinit var activity: FragmentActivity
  private lateinit var callbacks: DefaultNotificationsSettingsCallbacks
  private val viewModel = mockk<NotificationsSettingsViewModel>(relaxed = true)
  private val router = mockk<AppSettingsRouter>(relaxed = true)
  private val results = RecordingResults()
  private val disclosure = mockk<RelayDisclosure.Snapshot>()
  private val authorization = mockk<RelayDisclosure.RefreshAuthorization>()
  private val socket = MollySocket.WebServer("v".repeat(87), "https://push.example.org/relay")

  @Before
  fun setUp() {
    controller = Robolectric.buildActivity(FragmentActivity::class.java)
    activity = controller.get()
    activity.setTheme(R.style.Theme_Molly_Material3_Light)
    controller.setup()
    callbacks = DefaultNotificationsSettingsCallbacks(activity, viewModel, router, results)
  }

  @After
  fun tearDown() {
    ShadowDialog.getLatestDialog()?.dismiss()
    if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) controller.pause()
    if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) controller.stop()
    controller.destroy()
  }

  @Test
  fun `cancelling before the scan does not launch it or select a relay`() {
    callbacks.onNotificationMethodChanged(NotificationDeliveryMethod.WEBSOCKET to NotificationDeliveryMethod.UNIFIEDPUSH, disclosure)
    pressButton(AlertDialog.BUTTON_NEGATIVE)

    verify(exactly = 0) { results.qrLauncher.launch(Unit, null) }
    verifyNoSelection()
  }

  @Test
  fun `missing screen authorization does not launch the scanner`() {
    callbacks.onNotificationMethodChanged(NotificationDeliveryMethod.WEBSOCKET to NotificationDeliveryMethod.UNIFIEDPUSH, null)

    verify(exactly = 0) { results.qrLauncher.launch(Unit, null) }
    verifyNoSelection()
  }

  @Test
  fun `cancelling QR setup clears its authorization`() {
    launchScan()
    results.qrResult.onActivityResult(null)
    results.qrResult.onActivityResult(socket)

    verifyNoSelection()
  }

  @Test
  fun `scanned relay requires trust confirmation with the initiating screen authorization`() {
    allowSelection()
    launchScan()
    results.qrResult.onActivityResult(socket)

    verifyNoSelection()
    assertTrue(latestDialog().findViewById<TextView>(android.R.id.message)!!.text.contains(socket.url))
    pressButton(AlertDialog.BUTTON_POSITIVE)

    verify(exactly = 1) { viewModel.initializeMollySocket(socket, refEq(disclosure), any()) }
    verify(exactly = 0) { viewModel.setPreferredNotificationMethod(any()) }
    verify(exactly = 1) { results.distributorLauncher.launch(Unit, null) }
  }

  @Test
  fun `cancelling relay trust does not select a relay or link a distributor`() {
    launchScan()
    results.qrResult.onActivityResult(socket)
    pressButton(AlertDialog.BUTTON_NEGATIVE)

    verifyNoSelection()
  }

  @Test
  fun `rejected selection does not launch the distributor picker`() {
    confirmScannedRelay()

    verify(exactly = 1) { viewModel.initializeMollySocket(socket, refEq(disclosure), any()) }
    verify(exactly = 0) { results.distributorLauncher.launch(Unit, null) }
  }

  @Test
  fun `selection completing while activity is paused does not launch after resume`() {
    val selected = slot<(RelayDisclosure.RefreshAuthorization) -> Unit>()
    every { viewModel.initializeMollySocket(any(), any(), capture(selected)) } returns Unit
    confirmScannedRelay()
    controller.pause()

    selected.captured(authorization)
    controller.resume()
    shadowOf(Looper.getMainLooper()).idle()

    verify(exactly = 0) { results.distributorLauncher.launch(Unit, null) }
  }

  @Test
  fun `distributor success opens settings only for accepted current authorization`() {
    allowSelection()
    allowCompletion()
    confirmScannedRelay()
    returnDistributorResult(true)

    verify(exactly = 1) { viewModel.completeMollySocketSetup(refEq(authorization), any()) }
    verify(exactly = 0) { viewModel.selectFirstDistributor() }
    verify(exactly = 1) { router.navigateTo(AppSettingsRoute.NotificationsRoute.UnifiedPushSettings) }
  }

  @Test
  fun `missing distributor selects fallback before opening settings`() {
    allowSelection()
    allowCompletion()
    confirmScannedRelay()
    returnDistributorResult(false)

    io.mockk.verifyOrder {
      viewModel.completeMollySocketSetup(refEq(authorization), any())
      viewModel.selectFirstDistributor()
      router.navigateTo(AppSettingsRoute.NotificationsRoute.UnifiedPushSettings)
    }
  }

  @Test
  fun `cancelled distributor link retains the guarded fallback behavior`() {
    allowSelection()
    allowCompletion()
    confirmScannedRelay()
    returnDistributorResult(null)

    verify(exactly = 1) { viewModel.selectFirstDistributor() }
    verify(exactly = 1) { router.navigateTo(AppSettingsRoute.NotificationsRoute.UnifiedPushSettings) }
  }

  @Test
  fun `stale distributor result cannot select fallback or navigate`() {
    allowSelection()
    confirmScannedRelay()
    returnDistributorResult(false)

    verify(exactly = 1) { viewModel.completeMollySocketSetup(refEq(authorization), any()) }
    verify(exactly = 0) { viewModel.selectFirstDistributor() }
    verify(exactly = 0) { router.navigateTo(any()) }
  }

  @Test
  fun `distributor result without accepted setup is ignored`() {
    returnDistributorResult(false)

    verify(exactly = 0) { viewModel.completeMollySocketSetup(any(), any()) }
    verify(exactly = 0) { viewModel.selectFirstDistributor() }
    verify(exactly = 0) { router.navigateTo(any()) }
  }

  @Test
  fun `distributor result while started waits before checking authorization and navigating`() {
    allowSelection()
    allowCompletion()
    confirmScannedRelay()
    controller.pause()
    returnDistributorResult(false)

    verify(exactly = 0) { viewModel.completeMollySocketSetup(any(), any()) }
    verify(exactly = 0) { viewModel.selectFirstDistributor() }
    verify(exactly = 0) { router.navigateTo(any()) }

    controller.resume()
    shadowOf(Looper.getMainLooper()).idle()

    verify(exactly = 1) { viewModel.completeMollySocketSetup(refEq(authorization), any()) }
    verify(exactly = 1) { viewModel.selectFirstDistributor() }
    verify(exactly = 1) { router.navigateTo(AppSettingsRoute.NotificationsRoute.UnifiedPushSettings) }
  }

  @Test
  fun `authorization rejected after the resume wait prevents fallback and navigation`() {
    allowSelection()
    allowCompletion()
    confirmScannedRelay()
    controller.pause()
    returnDistributorResult(false)
    verify(exactly = 0) { viewModel.completeMollySocketSetup(any(), any()) }

    // Represent an account or relay change while stopped: the current-context guard rejects on resume.
    every { viewModel.completeMollySocketSetup(any(), any()) } returns Unit
    controller.resume()
    shadowOf(Looper.getMainLooper()).idle()

    verify(exactly = 1) { viewModel.completeMollySocketSetup(refEq(authorization), any()) }
    verify(exactly = 0) { viewModel.selectFirstDistributor() }
    verify(exactly = 0) { router.navigateTo(any()) }
  }

  @Test
  fun `distributor authorization is consumed only once`() {
    allowSelection()
    allowCompletion()
    confirmScannedRelay()
    returnDistributorResult(true)
    returnDistributorResult(false)

    verify(exactly = 1) { viewModel.completeMollySocketSetup(any(), any()) }
    verify(exactly = 0) { viewModel.selectFirstDistributor() }
    verify(exactly = 1) { router.navigateTo(AppSettingsRoute.NotificationsRoute.UnifiedPushSettings) }
  }

  private fun returnDistributorResult(result: Boolean?) {
    results.distributorResult.onActivityResult(result)
    shadowOf(Looper.getMainLooper()).idle()
  }

  private fun launchScan() {
    callbacks.onNotificationMethodChanged(NotificationDeliveryMethod.WEBSOCKET to NotificationDeliveryMethod.UNIFIEDPUSH, disclosure)
    pressButton(AlertDialog.BUTTON_POSITIVE)
  }

  private fun confirmScannedRelay() {
    launchScan()
    results.qrResult.onActivityResult(socket)
    pressButton(AlertDialog.BUTTON_POSITIVE)
  }

  private fun allowSelection() {
    every { viewModel.initializeMollySocket(any(), any(), any()) } answers {
      thirdArg<(RelayDisclosure.RefreshAuthorization) -> Unit>()(authorization)
    }
  }

  private fun allowCompletion() {
    every { viewModel.completeMollySocketSetup(any(), any()) } answers { secondArg<() -> Unit>()() }
  }

  private fun verifyNoSelection() {
    verify(exactly = 0) { viewModel.initializeMollySocket(any(), any(), any()) }
    verify(exactly = 0) { viewModel.setPreferredNotificationMethod(any()) }
    verify(exactly = 0) { results.distributorLauncher.launch(Unit, null) }
  }

  private fun latestDialog(): AlertDialog = ShadowDialog.getLatestDialog() as AlertDialog

  private fun pressButton(which: Int) {
    latestDialog().getButton(which).performClick()
    shadowOf(Looper.getMainLooper()).idle()
  }

  private class RecordingResults : DefaultNotificationsSettingsCallbacks.ActivityResultRegisterer {
    lateinit var qrResult: ActivityResultCallback<MollySocket?>
    lateinit var distributorResult: ActivityResultCallback<Boolean?>
    val qrLauncher: ActivityResultLauncher<Unit> = mockk(relaxed = true)
    val distributorLauncher: ActivityResultLauncher<Unit> = mockk(relaxed = true)

    @Suppress("UNCHECKED_CAST")
    override fun <I, O> registerForActivityResult(
      contract: ActivityResultContract<I, O>,
      callback: ActivityResultCallback<O>
    ): ActivityResultLauncher<I> {
      return when (contract) {
        is MollySocketQrScannerActivity.Contract -> {
          qrResult = callback as ActivityResultCallback<MollySocket?>
          qrLauncher as ActivityResultLauncher<I>
        }
        is UnifiedPushDefaultDistributorLinkActivity.Contract -> {
          distributorResult = callback as ActivityResultCallback<Boolean?>
          distributorLauncher as ActivityResultLauncher<I>
        }
        else -> mockk(relaxed = true)
      }
    }
  }
}
