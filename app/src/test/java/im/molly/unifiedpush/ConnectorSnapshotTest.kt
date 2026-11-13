package im.molly.unifiedpush

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.unifiedpush.android.connector.UnifiedPush

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class ConnectorSnapshotTest {
  @Test fun `snapshot observes the pinned connector public saves without invoking its destructive getter`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    assertNull(UnifiedPushDistributor.savedDistributorSnapshot(context))
    UnifiedPush.saveDistributor(context, "org.example.first")
    assertEquals("org.example.first", UnifiedPushDistributor.savedDistributorSnapshot(context))
    UnifiedPush.saveDistributor(context, "org.example.second")
    assertEquals("org.example.second", UnifiedPushDistributor.savedDistributorSnapshot(context))
  }
}
