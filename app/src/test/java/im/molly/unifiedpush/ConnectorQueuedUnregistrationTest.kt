package im.molly.unifiedpush

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.MessagingReceiver
import org.unifiedpush.android.connector.UnifiedPush
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage
import org.unifiedpush.android.connector.keys.KeyManager

/** Documents a connector 3.0.10 limitation; this is not a passing recovery scenario. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class ConnectorQueuedUnregistrationTest {
  @Test fun `queued old unregistration removes newly saved distributor in pinned connector`() {
    val application = ApplicationProvider.getApplicationContext<Application>()
    val manager = mockk<PackageManager>()
    var available = listOf("org.example.old")
    every { manager.queryBroadcastReceivers(any<Intent>(), any<Int>()) } answers {
      available.map { name ->
        ResolveInfo().apply { activityInfo = ActivityInfo().apply { packageName = name; exported = true } }
      }
    }
    val context = object : ContextWrapper(application) {
      override fun getPackageManager(): PackageManager = manager
    }
    val keys = mockk<KeyManager>(relaxed = true)
    every { keys.exists(any()) } returns true
    UnifiedPush.saveDistributor(context, "org.example.old")
    UnifiedPush.register(context, "default", null, null, keys)

    available = listOf("org.example.new")
    assertNull(UnifiedPush.getSavedDistributor(context))
    val queued = shadowOf(application).broadcastIntents.last {
      it.action == "org.unifiedpush.android.connector.UNREGISTERED"
    }
    UnifiedPush.saveDistributor(context, "org.example.new")
    assertEquals("org.example.new", UnifiedPushDistributor.savedDistributorSnapshot(context))

    var unregistered = false
    val receiver = object : MessagingReceiver() {
      override fun getKeyManager(context: Context): KeyManager = keys
      override fun onNewEndpoint(context: Context, endpoint: PushEndpoint, instance: String) = Unit
      override fun onRegistrationFailed(context: Context, reason: FailedReason, instance: String) = Unit
      override fun onMessage(context: Context, message: PushMessage, instance: String) = Unit
      override fun onUnregistered(context: Context, instance: String) { unregistered = true }
    }
    receiver.onReceive(context, queued)
    assertTrue(unregistered)
    // This is the known inherited failure: connector cleanup removes the newer save.
    assertNull(UnifiedPushDistributor.savedDistributorSnapshot(context))
  }
}
