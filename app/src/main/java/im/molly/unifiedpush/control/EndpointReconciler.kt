package im.molly.unifiedpush.control

import im.molly.unifiedpush.enrollment.EnrollmentFile
import im.molly.unifiedpush.lifecycle.UnifiedPushLifecycle
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
    val shouldEnqueue = synchronized(UnifiedPushLifecycle.monitor) {
      if (!UnifiedPushLifecycle.isReadyLocked()) return
      val values = SignalStore.unifiedpush
      val store = UnifiedPushControlStore(values)
      val control = values.controlStateRead
      val controlState = control.stateOrNull
      if (controlState == null) {
        Log.w(TAG, "Endpoint update not built: control state unavailable")
        return
      }
      if (controlState.key == null) return
      val context = im.molly.unifiedpush.lifecycle.UnifiedPushAccountTransition.captureContextLocked() ?: return
      if (controlState.activeImportId != null &&
        (controlState.activeAccountId != context.accountId || controlState.activeDeviceId != context.pushDevice?.deviceId || controlState.activeInstance != context.instance)) return
      val device = context.pushDevice ?: return

      val now = System.currentTimeMillis() / 1000
      val subscription = try {
        val endpoint = context.endpoint
        if (endpoint == null) null else ControlFrame.Subscription(endpoint, context.endpointP256dh.orEmpty(), context.endpointAuth.orEmpty())
      } catch (e: EnrollmentFile.Invalid) {
        // The distributor gave an endpoint the contract would refuse, so there is nothing valid to send.
        Log.w(TAG, "Endpoint update not built: ${e.code}")
        null
      }
      val result = try {
        EndpointUpdates.reconcile(store, context.accountId, device.deviceId, subscription, now)
      } catch (e: CorruptControlStateException) {
        Log.w(TAG, "Endpoint update not built: control state unavailable")
        return
      }
      val update = result.update ?: return
      result.built || (resume && now - maxOf(update.lastAttemptAt, update.createdAt) >= RESUME_AFTER_SECONDS)
    }
    if (shouldEnqueue) UnifiedPushControlJob.enqueue()
  }
}
