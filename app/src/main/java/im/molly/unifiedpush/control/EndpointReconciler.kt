package im.molly.unifiedpush.control

import im.molly.unifiedpush.enrollment.EnrollmentFile
import im.molly.unifiedpush.enrollment.PairingRegistrationGate
import im.molly.unifiedpush.control.ReceiverControlState
import im.molly.unifiedpush.lifecycle.UnifiedPushLifecycle
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.jobs.UnifiedPushControlJob
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.service.KeyCachingService

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
  fun reconcile(resume: Boolean = false, retryPending: Boolean = false, forceReceiver: Boolean = false) {
    val shouldEnqueue = synchronized(UnifiedPushLifecycle.monitor) {
      if (KeyCachingService.isLocked() || !UnifiedPushLifecycle.isReadyLocked()) return
      val values = SignalStore.unifiedpush
      // The v2 frame encrypts the complete endpoint tuple. Do not build or queue one while a
      // pairing-scoped route is tentative or active; its only safe promotion is the signed v3 path.
      if (PairingRegistrationGate.isRegistrationPending()) return
      val store = UnifiedPushControlStore(values)
      val control = values.controlStateRead
      val controlState = control.stateOrNull
      if (controlState == null) {
        Log.w(TAG, "Endpoint update not built: control state unavailable")
        return
      }
      val receiver = ReceiverControlState.parse(values.v4ReceiverState)
      if (controlState.key == null && receiver?.confirmed != true) return
      val context = im.molly.unifiedpush.lifecycle.UnifiedPushAccountTransition.captureContextLocked() ?: return
      val pairing = values.pairingEndpointBinding
      val v4Current = receiver != null && receiver.confirmed && receiver.accountId == context.accountId && receiver.instance == context.instance &&
        (pairing == null || pairing.importId == receiver.importId && pairing.instance == receiver.instance &&
          pairing.accountId == context.accountId && pairing.deviceId == context.localDeviceId)
      if (controlState.activeImportId != null &&
        (controlState.activeAccountId != context.accountId || controlState.activeDeviceId != context.pushDevice?.deviceId || controlState.activeInstance != context.instance) && !v4Current) return
      val device = context.pushDevice

      val now = System.currentTimeMillis() / 1000
      val subscription = try {
        val endpoint = context.endpoint
        if (endpoint == null) null else ControlFrame.Subscription(endpoint, context.endpointP256dh.orEmpty(), context.endpointAuth.orEmpty())
      } catch (e: EnrollmentFile.Invalid) {
        // The distributor gave an endpoint the contract would refuse, so there is nothing valid to send.
        Log.w(TAG, "Endpoint update not built: ${e.code}")
        null
      }
      if (pairing == null && PairingRegistrationGate.isPairingInstance(context.instance)) return
      if (pairing != null) {
        val v3Identity = controlState.activeImportId != null && controlState.activeInstance == pairing.instance &&
          controlState.activeAccountId == pairing.accountId && controlState.activeDeviceId == context.pushDevice?.deviceId
        val v4Identity = v4Current && receiver != null && receiver.importId == pairing.importId &&
          receiver.accountId == pairing.accountId && receiver.instance == pairing.instance
        if ((!v3Identity && !v4Identity) || context.localDeviceId != pairing.deviceId ||
          context.instance != pairing.instance || context.accountId != pairing.accountId ||
          context.pushDevice == null || subscription == null) return
      }
      var needsSend = false
      if (receiver?.confirmed == true && subscription != null && receiver.accountId == context.accountId && receiver.instance == context.instance) {
        val updatedReceiver = try {
          ReceiverControlState.reconcile(receiver, context.accountId, receiver.bridgeDeviceId, subscription, now, forceReceiver)
        } catch (e: EnrollmentFile.Invalid) {
          Log.w(TAG, "Receiver endpoint update not built: ${e.code}")
          null
        }
        if (updatedReceiver != null) {
          values.v4ReceiverState = updatedReceiver.serialize()
          val pendingAt = if (updatedReceiver.pendingSentAt > 0) updatedReceiver.pendingSentAt else updatedReceiver.pendingCreatedAt
          needsSend = updatedReceiver.pendingEpoch != null && (forceReceiver || retryPending || !updatedReceiver.pendingFrame!!.contentEquals(receiver.pendingFrame ?: byteArrayOf()) || resume && now - pendingAt >= RESUME_AFTER_SECONDS)
        }
      }
      if (controlState.key != null && device != null) {
        val result = try {
          EndpointUpdates.reconcile(store, context.accountId, device.deviceId, subscription, now)
        } catch (e: CorruptControlStateException) {
          Log.w(TAG, "Endpoint update not built: control state unavailable")
          return
        }
        val update = result.update
        if (update != null) needsSend = needsSend || result.built || retryPending || (resume && now - maxOf(update.lastAttemptAt, update.createdAt) >= RESUME_AFTER_SECONDS)
      }
      needsSend
    }
    if (shouldEnqueue) UnifiedPushControlJob.enqueue()
  }
}
