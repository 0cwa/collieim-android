package im.molly.unifiedpush.control

import im.molly.unifiedpush.enrollment.EnrollmentFile
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.jobs.UnifiedPushControlJob
import org.thoughtcrime.securesms.keyvalue.SignalStore

/** Runs [EndpointUpdates.reconcile] for the app's own state and starts the send. */
object EndpointReconciler {

  private val TAG = Log.tag(EndpointReconciler::class.java)

  /** A resume does not resend an update that was built or tried more recently than this: its send is probably queued. */
  private const val RESUME_AFTER_SECONDS = 30L

  /**
   * Call whenever the stored endpoint may have changed, and whenever the app can read its state again: after the
   * connector delivers an endpoint, after an event changed the control state, on unlock, on app start and when the
   * app comes to the foreground. [resume] also sends an update that was built earlier and is still unconfirmed.
   */
  @JvmStatic
  @JvmOverloads
  fun reconcile(resume: Boolean = false) {
    val values = SignalStore.unifiedpush
    val store = UnifiedPushControlStore(values)
    if (store.read().key == null) return
    val aci = SignalStore.account.aci ?: return
    val device = values.device ?: return
    val now = System.currentTimeMillis() / 1000
    val subscription = try {
      val endpoint = values.endpoint
      if (endpoint == null) null else ControlFrame.Subscription(endpoint, values.endpointP256dh.orEmpty(), values.endpointAuth.orEmpty())
    } catch (e: EnrollmentFile.Invalid) {
      // The distributor gave an endpoint the contract would refuse, so there is nothing valid to send.
      Log.w(TAG, "Endpoint update not built: ${e.code}")
      null
    }
    val result = EndpointUpdates.reconcile(store, aci.toString(), device.deviceId, subscription, now)
    val update = result.update ?: return
    if (result.built || (resume && now - maxOf(update.lastAttemptAt, update.createdAt) >= RESUME_AFTER_SECONDS)) UnifiedPushControlJob.enqueue()
  }
}
