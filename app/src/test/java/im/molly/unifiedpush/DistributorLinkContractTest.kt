package im.molly.unifiedpush

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class DistributorLinkContractTest {
  private val context: Context get() = ApplicationProvider.getApplicationContext()

  @Test fun `default and picker requests select the appropriate mode`() {
    val default = UnifiedPushDefaultDistributorLinkActivity.Contract(useDefault = true).createIntent(context, Unit)
    val picker = UnifiedPushDefaultDistributorLinkActivity.Contract(useDefault = false).createIntent(context, Unit)
    assertTrue(default.getBooleanExtra("use_default", false))
    assertFalse(picker.getBooleanExtra("use_default", true))
  }

  @Test fun `cancellation cannot be reported as a successful link`() {
    val contract = UnifiedPushDefaultDistributorLinkActivity.Contract(useDefault = false)
    assertNull(contract.parseResult(Activity.RESULT_CANCELED, Intent().putExtra("found", true)))
    assertNull(contract.parseResult(Activity.RESULT_CANCELED, null))
    assertEquals(true, contract.parseResult(Activity.RESULT_OK, Intent().putExtra("found", true)))
    assertEquals(false, contract.parseResult(Activity.RESULT_OK, Intent().putExtra("found", false)))
  }
}
