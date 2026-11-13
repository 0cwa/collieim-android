package im.molly.unifiedpush

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.ActivityNotFoundException
import android.content.DialogInterface
import android.app.Dialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import im.molly.unifiedpush.lifecycle.UnifiedPushLifecycle
import im.molly.unifiedpush.receiver.LockedPushState
import im.molly.unifiedpush.receiver.UnifiedPushReceiver
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.service.KeyCachingService
import org.unifiedpush.android.connector.INSTANCE_DEFAULT
import org.unifiedpush.android.connector.UnifiedPush
import java.util.UUID

class UnifiedPushDefaultDistributorLinkActivity : AppCompatActivity() {
  private val link = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
    val distributor = if (result.resultCode == Activity.RESULT_OK) distributorFromResult(result.data) else null
    if (distributor == null) finishWithoutLink() else selectDistributor(distributor)
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    if (savedInstanceState != null) return
    if (UnifiedPush.getDistributors(this).isEmpty()) {
      finishWithoutLink()
      return
    }

    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("unifiedpush://link"))
    if (intent.resolveActivity(packageManager) != null) {
      try {
        if (this.intent.getBooleanExtra(KEY_USE_DEFAULT, true)) link.launch(intent)
        else link.launch(Intent.createChooser(intent, "UnifiedPush"))
      } catch (e: ActivityNotFoundException) {
        // The resolver disappeared after the check; do not silently choose a different provider.
        finishWithoutLink()
      }
    } else {
      selectLegacyDistributor()
    }
  }

  private fun selectLegacyDistributor() {
    val distributors = UnifiedPush.getDistributors(this)
    if (distributors.isEmpty()) {
      finishWithoutLink()
      return
    }
    LegacyDistributorDialog.create(distributors).show(supportFragmentManager, KEY_LEGACY_DIALOG)
  }

  /** FragmentManager restores the legacy list if the picker activity is recreated. */
  class LegacyDistributorDialog : DialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
      val activity = requireActivity() as UnifiedPushDefaultDistributorLinkActivity
      val distributors = requireArguments().getStringArrayList(KEY_DISTRIBUTORS).orEmpty()
      val labels = distributors.map { packageName ->
        try {
          activity.packageManager.getApplicationLabel(activity.packageManager.getApplicationInfo(packageName, 0)).toString()
        } catch (e: PackageManager.NameNotFoundException) {
          packageName
        }
      }.toTypedArray()
      return MaterialAlertDialogBuilder(activity)
        .setTitle(R.string.UnifiedPushSettingsFragment__distributor_app)
        .setItems(labels) { _, selected -> activity.selectDistributor(distributors[selected]) }
        .setNegativeButton(android.R.string.cancel) { _, _ -> activity.finishWithoutLink() }
        .create()
    }

    override fun onCancel(dialog: DialogInterface) {
      super.onCancel(dialog)
      (activity as? UnifiedPushDefaultDistributorLinkActivity)?.finishWithoutLink()
    }

    companion object {
      fun create(distributors: List<String>): LegacyDistributorDialog = LegacyDistributorDialog().apply {
        arguments = Bundle().apply { putStringArrayList(KEY_DISTRIBUTORS, ArrayList(distributors)) }
      }
    }
  }

  private fun selectDistributor(distributor: String) {
    if (distributor !in UnifiedPush.getDistributors(this)) {
      finishWithoutLink()
      return
    }
    val previous = UnifiedPushDistributor.savedDistributorSnapshot(this)
    val selectionId = UUID.randomUUID().toString()
    val (noChange, schedule) = synchronized(UnifiedPushLifecycle.monitor) {
      if (previous == distributor && !UnifiedPushReceiver.selectionNeedsRecoveryLocked(this)) {
        true to null
      } else {
        false to UnifiedPushReceiver.recordEndpointLocked(this, LockedPushState.Endpoint(null, null, null, INSTANCE_DEFAULT), selectionId = selectionId)
      }
    }
    if (noChange) {
      finishWithoutLink()
      return
    }
    if (schedule == null) {
      Log.w(TAG, "Could not persist distributor change.")
      finishWithoutLink()
      return
    }
    var saved = false
    var ready = false
    try {
      UnifiedPush.saveDistributor(this, distributor)
      saved = true
    } catch (e: RuntimeException) {
      // The SDK may have saved only part of the change. The durable intent will retry the
      // provider that the SDK actually retained, but the UI must not report success.
      Log.w(TAG, "Could not save distributor change.", e)
    } finally {
      try {
        ready = UnifiedPushReceiver.finishSelection(this, selectionId)
      } catch (e: RuntimeException) {
        Log.w(TAG, "Could not finish distributor change.", e)
      } finally {
        if (!KeyCachingService.isLocked()) UnifiedPushReceiver.applyPendingEndpoint(this)
      }
    }
    if (!saved || !ready) {
      finishWithoutLink()
      return
    }
    if (!KeyCachingService.isLocked()) SignalStore.unifiedpush.registrationRetryScheduled = false
    setResult(RESULT_OK, Intent().putExtra(KEY_FOUND, true))
    finish()
  }

  @Suppress("DEPRECATION")
  private fun distributorFromResult(data: Intent?): String? {
    val pending = if (Build.VERSION.SDK_INT >= 33) data?.getParcelableExtra("pi", PendingIntent::class.java)
    else data?.getParcelableExtra<PendingIntent>("pi")
    return pending?.creatorPackage
  }

  private fun finishWithoutLink() {
    setResult(RESULT_OK)
    finish()
  }

  class Contract(private val useDefault: Boolean) : ActivityResultContract<Unit, Boolean?>() {
    override fun createIntent(context: Context, input: Unit): Intent =
      Intent(context, UnifiedPushDefaultDistributorLinkActivity::class.java).putExtra(KEY_USE_DEFAULT, useDefault)

    override fun parseResult(resultCode: Int, intent: Intent?): Boolean? =
      if (resultCode == RESULT_OK) intent?.getBooleanExtra(KEY_FOUND, false) else null
  }

  companion object {
    private const val KEY_LEGACY_DIALOG = "legacy_distributor_dialog"
    private const val KEY_DISTRIBUTORS = "distributors"
    private const val KEY_USE_DEFAULT = "use_default"
    private const val KEY_FOUND = "found"
    private const val TAG = "UnifiedPushDefaultDistributorLinkActivity"
  }
}
