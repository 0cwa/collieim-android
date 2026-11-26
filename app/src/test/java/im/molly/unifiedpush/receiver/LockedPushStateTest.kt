package im.molly.unifiedpush.receiver

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import im.molly.unifiedpush.enrollment.PairingRegistrationGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class LockedPushStateTest {

  @Test
  fun `committed pairing route rejects stale default callbacks while locked`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    try {
      LockedPushState.setProtectedPairingRoute(context, "pairing-v1-current", "org.example.distributor")

      val protectedInstance = LockedPushState.protectedPairingInstance(context)
      assertEquals("pairing-v1-current", protectedInstance)
      assertEquals("org.example.distributor", LockedPushState.protectedPairingDistributor(context))
      assertFalse(PairingRegistrationGate.allowsLockedCallback("default", protectedInstance))
      assertTrue(PairingRegistrationGate.allowsLockedCallback("pairing-v1-current", protectedInstance))
    } finally {
      LockedPushState.setProtectedPairingRoute(context, null, null)
    }
  }
}
