package im.molly.unifiedpush

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import im.molly.unifiedpush.enrollment.RelayDisclosure
import im.molly.unifiedpush.lifecycle.UnifiedPushLifecycle
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.unmockkConstructor
import io.mockk.unmockkObject
import io.mockk.verify
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thoughtcrime.securesms.R
import org.unifiedpush.android.connector.LinkActivityHelper

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [35])
class UnifiedPushDefaultDistributorLinkActivityTest {
  @Test
  fun `activity recreation retains the connector request until the picker returns`() {
    UnifiedPushLifecycle.resetForTests()
    mockkObject(RelayDisclosure)
    mockkConstructor(LinkActivityHelper::class)
    try {
      every { RelayDisclosure.matchesRefreshLocked(any()) } returns true
      every { anyConstructed<LinkActivityHelper>().startLinkActivityForResult() } returns true
      val setup = UnifiedPushDefaultDistributorLinkActivity.Setup(mockk())
      val context = ApplicationProvider.getApplicationContext<Application>()
      val intent = UnifiedPushDefaultDistributorLinkActivity.Contract().createIntent(context, setup)
      val controller = Robolectric.buildActivity(UnifiedPushDefaultDistributorLinkActivity::class.java, intent)
      controller.get().setTheme(R.style.Theme_Molly_Material3_Light)
      controller.setup()
      try {
        val original = ViewModelProvider(controller.get())[UnifiedPushDefaultDistributorLinkActivity.LinkSession::class.java]
        val helper = checkNotNull(original.helper)
        controller.recreate()

        val restored = ViewModelProvider(controller.get())[UnifiedPushDefaultDistributorLinkActivity.LinkSession::class.java]
        assertSame(helper, restored.helper)
        verify(exactly = 1) { anyConstructed<LinkActivityHelper>().startLinkActivityForResult() }
      } finally {
        controller.pause().stop().destroy()
      }
    } finally {
      unmockkConstructor(LinkActivityHelper::class)
      unmockkObject(RelayDisclosure)
    }
  }
}
