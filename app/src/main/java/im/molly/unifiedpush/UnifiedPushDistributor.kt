package im.molly.unifiedpush

import android.content.Context
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.unifiedpush.android.connector.UnifiedPush
import org.thoughtcrime.securesms.service.KeyCachingService

object UnifiedPushDistributor {

  @JvmStatic
  fun registerApp(vapid: String?) {
    UnifiedPush.registerApp(AppDependencies.application, vapid = vapid)
  }

  @JvmStatic
  fun unregisterApp() {
    UnifiedPush.unregisterApp(AppDependencies.application)
    // MessagingReceiver.onUnregistered won't be called after the unregistration request
    SignalStore.unifiedpush.endpoint = null
  }

  fun saveDistributor(context: Context, distributor: String) {
    if (!KeyCachingService.isLocked()) SignalStore.unifiedpush.registrationRetryScheduled = false
    UnifiedPush.saveDistributor(context, distributor)
  }

  /** Read-only comparison against the pinned 3.0.10 connector's saved package.
   * The public getter can enqueue an asynchronous unregistration for a removed provider.
   * All changes still go through the connector's public API.
   */
  internal fun savedDistributorSnapshot(context: Context): String? =
    context.getSharedPreferences("unifiedpush.connector", Context.MODE_PRIVATE).getString("unifiedpush.distributor", null)

  fun checkIfActive(): Boolean {
    return UnifiedPush.getAckDistributor(AppDependencies.application) != null
  }
}
