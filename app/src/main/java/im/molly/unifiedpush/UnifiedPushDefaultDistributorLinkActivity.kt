package im.molly.unifiedpush

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContract
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import im.molly.unifiedpush.enrollment.RelayDisclosure
import im.molly.unifiedpush.lifecycle.UnifiedPushLifecycle
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.unifiedpush.android.connector.LinkActivityHelper
import java.lang.ref.WeakReference
import java.util.UUID

class UnifiedPushDefaultDistributorLinkActivity : AppCompatActivity() {
  private val link by lazy { ViewModelProvider(this)[LinkSession::class.java] }
  private var continuation: Job? = null
  private val setupId: String? get() = intent.getStringExtra(KEY_SETUP_ID)

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    setResult(RESULT_CANCELED, Intent().putExtra(KEY_SETUP_ID, setupId))
  }

  override fun onStart() {
    super.onStart()
    continueSetup()
  }

  override fun onStop() {
    continuation?.cancel()
    super.onStop()
  }

  override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    super.onActivityResult(requestCode, resultCode, data)
    if (link.helper == null || link.result != null) return
    link.result = PickerResult(requestCode, resultCode, data)
    if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) continueSetup()
  }

  private fun continueSetup() {
    if (isFinishing || (link.helper != null && link.result == null)) return
    continuation?.cancel()
    continuation = lifecycleScope.launch {
      val setup = synchronized(UnifiedPushLifecycle.monitor) { currentSetupLocked(setupId) }
      if (setup == null) {
        finishWithResult(null)
        return@launch
      }

      // Endpoint transitions can start after the parent launches this activity or while the picker is open.
      while (!isFinishing) {
        val ready = RelayDisclosure.completeSelection(setup.authorization) {
          if (currentSetupLocked(setupId) === setup) resumePickerLocked()
          else finishWithResult(null)
        }
        if (ready) return@launch
        if (synchronized(UnifiedPushLifecycle.monitor) { currentSetupLocked(setupId) !== setup }) {
          finishWithResult(null)
          return@launch
        }
      }
    }
  }

  private fun resumePickerLocked() {
    val result = link.result
    if (result == null) {
      val helper = LinkActivityHelper(this)
      link.helper = helper
      if (!helper.startLinkActivityForResult()) {
        pendingSetup.clear()
        finishWithResult(false)
      }
      return
    }

    val helper = checkNotNull(link.helper)
    // The connector saves its choice synchronously. Consume this launch before handing it the result.
    pendingSetup.clear()
    val found = helper.onLinkActivityResult(result.requestCode, result.resultCode, result.data)
    if (found) SignalStore.unifiedpush.registrationRetryScheduled = false
    Log.d(TAG, "Distributor link result accepted.")
    finishWithResult(found)
  }

  private fun finishWithResult(found: Boolean?) {
    link.helper = null
    link.result = null
    val result = Intent().putExtra(KEY_SETUP_ID, setupId)
    if (found != null) result.putExtra(KEY_FOUND, found)
    setResult(RESULT_OK, result)
    finish()
  }

  class LinkSession : ViewModel() {
    // Connector 3.0.10 generates its request code when the helper is constructed.
    // Keep that helper for this outstanding result through configuration changes only.
    internal var helper: LinkActivityHelper? = null

    internal var result: PickerResult? = null

    override fun onCleared() {
      helper = null
      result = null
    }
  }

  internal data class PickerResult(val requestCode: Int, val resultCode: Int, val data: Intent?)

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

    private fun currentSetupLocked(id: String?): Setup? =
      pendingSetup.get()?.takeIf { it.id == id && RelayDisclosure.matchesRefreshLocked(it.authorization) }
  }
}
