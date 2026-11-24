package im.molly.unifiedpush

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContract
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import im.molly.unifiedpush.enrollment.RelayDisclosure
import im.molly.unifiedpush.lifecycle.UnifiedPushLifecycle
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.unifiedpush.android.connector.LinkActivityHelper
import java.lang.ref.WeakReference
import java.util.UUID

class UnifiedPushDefaultDistributorLinkActivity : AppCompatActivity() {
  private val link by lazy { ViewModelProvider(this)[LinkSession::class.java] }
  private val setupId: String? get() = intent.getStringExtra(KEY_SETUP_ID)

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    setResult(RESULT_CANCELED, Intent().putExtra(KEY_SETUP_ID, setupId))
    val current = synchronized(UnifiedPushLifecycle.monitor) { isCurrentSetupLocked(setupId) }
    if (!current) {
      finish()
    } else if (savedInstanceState == null) {
      val helper = LinkActivityHelper(this)
      link.helper = helper
      if (!helper.startLinkActivityForResult()) finishWithResult(false)
    }
  }

  override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    super.onActivityResult(requestCode, resultCode, data)
    val found = synchronized(UnifiedPushLifecycle.monitor) {
      val helper = link.helper ?: return@synchronized null
      if (!isCurrentSetupLocked(setupId)) return@synchronized null
      // The connector saves its choice synchronously. Consume this launch before handing it the result.
      pendingSetup.clear()
      val saved = helper.onLinkActivityResult(requestCode, resultCode, data)
      if (saved) SignalStore.unifiedpush.registrationRetryScheduled = false
      saved
    }
    Log.d(TAG, "Distributor link result accepted: ${found != null}.")
    finishWithResult(found)
  }

  private fun finishWithResult(found: Boolean?) {
    link.helper = null
    val result = Intent().putExtra(KEY_SETUP_ID, setupId)
    if (found != null) result.putExtra(KEY_FOUND, found)
    setResult(RESULT_OK, result)
    finish()
  }

  class LinkSession : ViewModel() {
    // Connector 3.0.10 generates its request code when the helper is constructed.
    // Keep that helper for this outstanding result through configuration changes only.
    internal var helper: LinkActivityHelper? = null

    override fun onCleared() {
      helper = null
    }
  }

  class Setup internal constructor(internal val authorization: RelayDisclosure.RefreshAuthorization) {
    internal val id: String = UUID.randomUUID().toString()
  }

  data class LinkResult(val setupId: String, val found: Boolean?)

  class Contract : ActivityResultContract<Setup, LinkResult?>() {
    override fun createIntent(context: Context, input: Setup): Intent {
      synchronized(UnifiedPushLifecycle.monitor) { pendingSetup = WeakReference(input) }
      return Intent(context, UnifiedPushDefaultDistributorLinkActivity::class.java).putExtra(KEY_SETUP_ID, input.id)
    }

    override fun parseResult(resultCode: Int, intent: Intent?): LinkResult? {
      val setupId = intent?.getStringExtra(KEY_SETUP_ID) ?: return null
      val found = if (resultCode == RESULT_OK && intent.hasExtra(KEY_FOUND)) intent.getBooleanExtra(KEY_FOUND, false) else null
      return LinkResult(setupId, found)
    }
  }

  companion object {
    private const val KEY_SETUP_ID = "setup_id"
    private const val KEY_FOUND = "found"
    private const val TAG = "UnifiedPushDefaultDistributorLinkActivity"
    // The settings ViewModel owns the launch and keeps it alive during activity recreation.
    private var pendingSetup = WeakReference<Setup>(null)

    private fun isCurrentSetupLocked(id: String?): Boolean {
      return pendingSetup.get()?.let {
        it.id == id && UnifiedPushLifecycle.isReadyLocked() && RelayDisclosure.matchesRefreshLocked(it.authorization)
      } == true
    }
  }
}
