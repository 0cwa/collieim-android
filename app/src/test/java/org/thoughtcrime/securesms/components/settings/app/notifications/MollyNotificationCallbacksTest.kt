package org.thoughtcrime.securesms.components.settings.app.notifications

import android.app.Application
import androidx.activity.result.ActivityResultCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.fragment.app.FragmentActivity
import im.molly.unifiedpush.UnifiedPushDefaultDistributorLinkActivity
import im.molly.unifiedpush.components.settings.app.notifications.MollySocketQrScannerActivity
import im.molly.unifiedpush.model.MollySocket
import io.mockk.mockk
import io.mockk.verify
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.signal.appsettings.routes.AppSettingsRoute
import org.thoughtcrime.securesms.components.settings.app.routes.AppSettingsRouter
import org.thoughtcrime.securesms.keyvalue.SettingsValues.NotificationDeliveryMethod

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class MollyNotificationCallbacksTest {
  private lateinit var activity: FragmentActivity
  private val viewModel = mockk<NotificationsSettingsViewModel>(relaxed = true)
  private val router = mockk<AppSettingsRouter>(relaxed = true)
  private val results = RecordingResults()

  @Before
  fun setUp() {
    activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
    DefaultNotificationsSettingsCallbacks(activity, viewModel, router, results)
  }

  @Test
  fun `cancelling QR setup does not change the delivery method or link a distributor`() {
    results.qrResult.onActivityResult(null)

    verify(exactly = 0) { viewModel.initializeMollySocket(any()) }
    verify(exactly = 0) { viewModel.setPreferredNotificationMethod(any()) }
    verify(exactly = 0) { results.distributorLauncher.launch(Unit, null) }
  }

  @Test
  fun `successful QR setup stores server before selecting UnifiedPush and linking distributor`() {
    val socket = MollySocket.WebServer("v".repeat(87), "https://push.example.org")
    results.qrResult.onActivityResult(socket)

    io.mockk.verifyOrder {
      viewModel.initializeMollySocket(socket)
      viewModel.setPreferredNotificationMethod(NotificationDeliveryMethod.UNIFIEDPUSH)
      results.distributorLauncher.launch(Unit, null)
    }
  }

  @Test
  fun `distributor linking success opens settings without selecting a fallback`() {
    results.distributorResult.onActivityResult(true)
    shadowOf(android.os.Looper.getMainLooper()).idle()

    verify(exactly = 0) { viewModel.selectFirstDistributor() }
    verify(exactly = 1) { router.navigateTo(AppSettingsRoute.NotificationsRoute.UnifiedPushSettings) }
  }

  @Test
  fun `missing distributor selects fallback before opening settings`() {
    results.distributorResult.onActivityResult(false)
    shadowOf(android.os.Looper.getMainLooper()).idle()

    verify(exactly = 1) { viewModel.selectFirstDistributor() }
    verify(exactly = 1) { router.navigateTo(AppSettingsRoute.NotificationsRoute.UnifiedPushSettings) }
  }

  @Test
  fun `cancelled distributor link also selects fallback`() {
    results.distributorResult.onActivityResult(null)
    shadowOf(android.os.Looper.getMainLooper()).idle()

    verify(exactly = 1) { viewModel.selectFirstDistributor() }
    verify(exactly = 1) { router.navigateTo(AppSettingsRoute.NotificationsRoute.UnifiedPushSettings) }
  }

  private class RecordingResults : DefaultNotificationsSettingsCallbacks.ActivityResultRegisterer {
    lateinit var qrResult: ActivityResultCallback<MollySocket?>
    lateinit var distributorResult: ActivityResultCallback<Boolean?>
    val distributorLauncher: ActivityResultLauncher<Unit> = mockk(relaxed = true)

    @Suppress("UNCHECKED_CAST")
    override fun <I, O> registerForActivityResult(
      contract: ActivityResultContract<I, O>,
      callback: ActivityResultCallback<O>
    ): ActivityResultLauncher<I> {
      return when (contract) {
        is MollySocketQrScannerActivity.Contract -> {
          qrResult = callback as ActivityResultCallback<MollySocket?>
          mockk(relaxed = true)
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
