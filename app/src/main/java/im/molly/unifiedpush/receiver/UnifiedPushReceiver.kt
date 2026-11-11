package im.molly.unifiedpush.receiver

import android.content.Context
import im.molly.unifiedpush.UnifiedPushNotificationBuilder
import im.molly.unifiedpush.control.EndpointReconciler
import im.molly.unifiedpush.control.EndpointUpdates
import im.molly.unifiedpush.control.UnifiedPushControlStore
import im.molly.unifiedpush.health.PushHealth
import im.molly.unifiedpush.health.PushHealthActions
import im.molly.unifiedpush.model.RegistrationStatus
import org.signal.core.util.concurrent.SignalExecutors
import org.signal.core.util.logging.Log
import org.signal.core.util.eventbus.EventBus
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.events.PushServiceEvent
import org.thoughtcrime.securesms.gcm.FcmFetchManager
import org.thoughtcrime.securesms.gcm.PushFetchHandler
import org.thoughtcrime.securesms.jobs.UnifiedPushControlJob
import org.thoughtcrime.securesms.jobs.UnifiedPushRefreshJob
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.service.KeyCachingService
import org.thoughtcrime.securesms.util.TextSecurePreferences
import org.thoughtcrime.securesms.util.concurrent.SerialMonoLifoExecutor
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.MessagingReceiver
import org.unifiedpush.android.connector.UnifiedPush
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage

class UnifiedPushReceiver : MessagingReceiver() {

  companion object {
    private val TAG = Log.tag(UnifiedPushReceiver::class.java)
    private val replayLock = Any()

    /** Applies an endpoint that arrived while the app was locked. Called after unlock. */
    @JvmStatic
    fun applyPendingEndpoint(context: Context) {
      val pending = LockedPushState.takePending(context)
      if (pending != null) UnifiedPushReceiver().applyEndpoint(context, pending)
      // Now that the app can read its state: make sure the server holds the current endpoint, and send an update
      // that was built but never confirmed again.
      EndpointReconciler.reconcile(resume = true)
    }

    /** Called when the app comes to the foreground, where the job's attempts may have run out. */
    @JvmStatic
    fun resumeControl() {
      if (!KeyCachingService.isLocked()) EndpointReconciler.reconcile(resume = true)
    }
  }

  private val executor = SerialMonoLifoExecutor(SignalExecutors.UNBOUNDED)

  private val appLocked
    get() = KeyCachingService.isLocked()

  override fun onNewEndpoint(context: Context, endpoint: PushEndpoint, instance: String) {
    Log.i(TAG, "onNewEndpoint($instance)")
    val received = LockedPushState.Endpoint(endpoint.url, endpoint.pubKeySet?.pubKey, endpoint.pubKeySet?.auth, instance)
    if (appLocked) {
      // SignalStore cannot be read while locked: keep the endpoint sealed and apply it at the next unlock.
      LockedPushState.savePending(context, received)
    } else {
      LockedPushState.takePending(context)
      applyEndpoint(context, received)
    }
  }

  private fun applyEndpoint(context: Context, endpoint: LockedPushState.Endpoint) {
    val failed = SignalStore.unifiedpush.registrationFailed
    SignalStore.unifiedpush.registrationFailed = false
    SignalStore.unifiedpush.registrationRetryScheduled = false
    if (failed) {
      UnifiedPushNotificationBuilder(context).clearRegistrationFailed()
      EventBus.default.post(PushServiceEvent)
    }
    SignalStore.unifiedpush.endpointP256dh = endpoint.p256dh
    SignalStore.unifiedpush.endpointAuth = endpoint.auth
    SignalStore.unifiedpush.endpointInstance = endpoint.instance
    val changed = refreshEndpoint(endpoint.url)
    // After the endpoint is stored: the update is derived from the stored state, so it is rebuilt if this is interrupted.
    EndpointReconciler.reconcile()
    if (failed && !changed) {
      AppDependencies.jobManager.add(UnifiedPushRefreshJob(testPing = false, fromNewEndpoint = true))
    }
    if (changed && SignalStore.unifiedpush.airGapped) {
      updateLastReceivedTime(0)
      UnifiedPushNotificationBuilder(context).setNotificationEndpointChangedAirGapped()
    }
  }

  override fun onRegistrationFailed(context: Context, reason: FailedReason, instance: String) {
    Log.w(TAG, "onRegistrationFailed($instance): $reason")
    if (!appLocked) {
      val retryScheduled = SignalStore.unifiedpush.registrationRetryScheduled
      SignalStore.unifiedpush.registrationFailed = true
      UnifiedPushNotificationBuilder(context).setNotificationRegistrationFailed()
      EventBus.default.post(PushServiceEvent)
      if (!retryScheduled) {
        SignalStore.unifiedpush.registrationRetryScheduled = true
        AppDependencies.jobManager.add(UnifiedPushRefreshJob())
      }
    }
  }

  override fun onUnregistered(context: Context, instance: String) {
    // called when this application is unregistered from receiving push messages
    // isPushAvailable becomes false => The websocket starts
    Log.i(TAG, "onUnregistered($instance)")
    UnifiedPush.forceRemoveDistributor(context)
    LockedPushState.takePending(context)
    if (!appLocked) {
      SignalStore.unifiedpush.endpointP256dh = null
      SignalStore.unifiedpush.endpointAuth = null
      SignalStore.unifiedpush.endpointInstance = null
      refreshEndpoint(null)
    }
  }

  override fun onMessage(context: Context, message: PushMessage, instance: String) {
    if (appLocked) {
      LockedPushState.recordPushArrived(context, System.currentTimeMillis())
      // Which kind of enrollment this is cannot be read from SignalStore while locked.
      if (LockedPushState.isEncryptedEnrollment(context)) {
        Log.d(TAG, "Encrypted push dropped while locked.")
      } else {
        onMessageLocked(context, message.content.toString(Charsets.UTF_8))
      }
      return
    }

    val active = SignalStore.unifiedpush.enrollmentEpoch
    val pending = SignalStore.unifiedpush.pendingEnrollmentEpoch
    LockedPushState.setEncryptedEnrollment(context, active > 0)
    // An active encrypted enrollment accepts nothing but encrypted events. Until the first event confirms an
    // exported enrollment, anything that is not an event keeps the stock plaintext behavior.
    if (active > 0 || pending > 0) {
      if (onEncryptedMessage(context, message)) return
      if (active > 0 || EncryptedPush.isEventShaped(message.content)) return
    }
    updateLastReceivedTime(System.currentTimeMillis())
    onMessageUnlocked(context, message.content.toString(Charsets.UTF_8))
  }

  /**
   * Handles [message] as an encrypted event. Returns true if it was accepted (and its effect started). With an
   * active enrollment the epochs accepted are the active one and, until it is confirmed, the pending one.
   */
  private fun onEncryptedMessage(context: Context, message: PushMessage): Boolean {
    val values = SignalStore.unifiedpush
    val controlStore = UnifiedPushControlStore(values)
    val snapshot = controlStore.read()
    var effect = EndpointUpdates.Effect.NONE
    val outcome = synchronized(replayLock) {
      val now = System.currentTimeMillis() / 1000
      val state = EncryptedPush.ReplayState.parse(values.replayState)
      if (state == null) {
        Log.w(TAG, "Replay state unavailable. Encrypted push dropped.")
        return false
      }
      EncryptedPush.decide(
        content = message.content,
        decrypted = message.decrypted,
        now = now,
        locked = { appLocked },
        activeInstance = values.endpointInstance ?: "default",
        acceptedEpochs = EndpointUpdates.acceptedEpochs(snapshot),
        state = state,
        versionForEpoch = { epoch -> EndpointUpdates.versionForEpoch(snapshot, epoch) },
      ).also {
        if (it.decision.accepted) {
          // Committed to disk, with the control state it changes, before any effect. An event at the pending epoch
          // proves the server imported the file; `subscription_updated` at the update's epoch proves it applied the
          // new endpoint. Both are decided inside the write, against the state as it is then.
          effect = EndpointUpdates.accepted(controlStore, it.state.serialize(), it.event!!)
          if (effect == EndpointUpdates.Effect.PROMOTED) LockedPushState.setEncryptedEnrollment(context, true)
        }
      }
    }
    // The endpoint may have changed since the update or the export was built: converge on the current one.
    if (effect != EndpointUpdates.Effect.NONE) EndpointReconciler.reconcile()

    Log.d(TAG, "Encrypted push: ${outcome.decision}")
    if (!outcome.decision.accepted || appLocked) return outcome.decision.accepted
    if (values.registrationStatus != RegistrationStatus.REGISTERED) {
      // The server holds our credentials and just reached us, so the stock server check (an HTTP call) is not needed.
      values.registrationStatus = RegistrationStatus.REGISTERED
      EventBus.default.post(PushServiceEvent)
    }
    when (outcome.decision) {
      EncryptedPush.Decision.ACCEPT_WAKE -> {
        updateLastReceivedTime(System.currentTimeMillis())
        PushHealthActions.onPushAccepted()
        startFetch(context)
      }
      EncryptedPush.Decision.ACCEPT_TEST -> {
        updateLastReceivedTime(System.currentTimeMillis())
        PushHealthActions.onPushAccepted()
        UnifiedPushNotificationBuilder(context).setNotificationTest()
      }
      EncryptedPush.Decision.ACCEPT_STATUS -> {
        // A confirmation is not a server problem; it is only recorded as the end of the pending update.
        if (effect == EndpointUpdates.Effect.CONFIRMED) {
          Log.i(TAG, "Endpoint update confirmed at epoch ${outcome.event?.epoch}")
          // The confirmation came through Signal, CollieSocket and the push provider: the whole path works.
          PushHealthActions.onPing(PushHealth.Hop.NONE)
        }
        else if (outcome.event?.code != EncryptedPush.CODE_SUBSCRIPTION_UPDATED) values.statusCode = outcome.event?.code
      }
      else -> Unit
    }
    return true
  }

  private fun onMessageLocked(context: Context, message: String) {
    when {
      // We look directly in the message to avoid its deserialization
      message.contains("\"urgent\":true") -> {
        if (TextSecurePreferences.isPassphraseLockNotificationsEnabled(context)) {
          Log.d(TAG, "New urgent message received while app is locked.")
          FcmFetchManager.postMayHaveMessagesNotification(context)
        }
      }
    }
  }

  private fun onMessageUnlocked(context: Context, message: String) {
    when {
      message.contains("\"test\":true") -> {
        Log.d(TAG, "Test message received.")
        UnifiedPushNotificationBuilder(context).setNotificationTest()
        AppDependencies.jobManager.add(UnifiedPushRefreshJob(testPing = false, fromNewEndpoint = true))
      }

      else -> startFetch(context)
    }
  }

  private fun startFetch(context: Context) {
    if (SignalStore.account.isRegistered && SignalStore.unifiedpush.enabled) {
      Log.d(TAG, "New message")
      executor.enqueue {
        PushFetchHandler.handleReceivedNotification(context, true)
      }
    }
  }

  private fun updateLastReceivedTime(timestamp: Long) {
    SignalStore.unifiedpush.lastReceivedTime = timestamp
  }

  /**
   * Update UnifiedPush endpoint
   *
   * @return `true` if the endpoint has been updated, `false` if the endpoint is the same
   */
  private fun refreshEndpoint(endpoint: String?): Boolean {
    val stored = SignalStore.unifiedpush.endpoint
    return if (endpoint != stored) {
      SignalStore.unifiedpush.endpoint = endpoint
      AppDependencies.jobManager.add(UnifiedPushRefreshJob(testPing = false, fromNewEndpoint = true))
      true
    } else false
  }
}
