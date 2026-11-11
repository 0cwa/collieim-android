package im.molly.unifiedpush.receiver

import android.content.Context
import im.molly.unifiedpush.UnifiedPushNotificationBuilder
import org.signal.core.util.concurrent.SignalExecutors
import org.signal.core.util.logging.Log
import org.signal.core.util.eventbus.EventBus
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.events.PushServiceEvent
import org.thoughtcrime.securesms.gcm.FcmFetchManager
import org.thoughtcrime.securesms.gcm.PushFetchHandler
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
      val pending = LockedPushState.takePending(context) ?: return
      UnifiedPushReceiver().applyEndpoint(context, pending)
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
    if (!appLocked) {
      SignalStore.unifiedpush.endpointP256dh = null
      SignalStore.unifiedpush.endpointAuth = null
      SignalStore.unifiedpush.endpointInstance = null
      refreshEndpoint(null)
    }
  }

  override fun onMessage(context: Context, message: PushMessage, instance: String) {
    if (appLocked) {
      // Which kind of enrollment this is cannot be read from SignalStore while locked.
      if (LockedPushState.isEncryptedEnrollment(context)) {
        Log.d(TAG, "Encrypted push dropped while locked.")
      } else {
        onMessageLocked(context, message.content.toString(Charsets.UTF_8))
      }
      return
    }

    val epoch = SignalStore.unifiedpush.enrollmentEpoch
    LockedPushState.setEncryptedEnrollment(context, epoch > 0)
    if (epoch > 0) {
      onEncryptedMessage(context, message, epoch)
    } else {
      updateLastReceivedTime(System.currentTimeMillis())
      onMessageUnlocked(context, message.content.toString(Charsets.UTF_8))
    }
  }

  /** An encrypted enrollment accepts nothing but a decrypted, valid, current, unseen v1 event. */
  private fun onEncryptedMessage(context: Context, message: PushMessage, epoch: Long) {
    val values = SignalStore.unifiedpush
    val outcome = synchronized(replayLock) {
      val now = System.currentTimeMillis() / 1000
      val state = EncryptedPush.ReplayState.parse(values.replayState)
      if (state == null) {
        Log.w(TAG, "Replay state unavailable. Encrypted push dropped.")
        return
      }
      EncryptedPush.decide(
        content = message.content,
        decrypted = message.decrypted,
        now = now,
        locked = { appLocked },
        activeInstance = values.endpointInstance ?: "default",
        activeEpoch = epoch,
        state = state,
      ).also { if (it.decision.accepted) values.replayState = it.state.serialize() }
    }

    Log.d(TAG, "Encrypted push: ${outcome.decision}")
    if (!outcome.decision.accepted || appLocked) return
    when (outcome.decision) {
      EncryptedPush.Decision.ACCEPT_WAKE -> {
        updateLastReceivedTime(System.currentTimeMillis())
        startFetch(context)
      }
      EncryptedPush.Decision.ACCEPT_TEST -> {
        updateLastReceivedTime(System.currentTimeMillis())
        UnifiedPushNotificationBuilder(context).setNotificationTest()
      }
      EncryptedPush.Decision.ACCEPT_STATUS -> values.statusCode = outcome.event?.code
      else -> Unit
    }
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
