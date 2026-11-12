package im.molly.unifiedpush.receiver

import android.content.Context
import android.os.Looper
import im.molly.unifiedpush.UnifiedPushNotificationBuilder
import im.molly.unifiedpush.control.EndpointReconciler
import im.molly.unifiedpush.control.EndpointUpdates
import im.molly.unifiedpush.control.CorruptControlStateException
import im.molly.unifiedpush.control.UnifiedPushControlStore
import im.molly.unifiedpush.health.PushHealth
import im.molly.unifiedpush.health.PushHealthActions
import im.molly.unifiedpush.lifecycle.UnifiedPushAccountTransition
import im.molly.unifiedpush.lifecycle.UnifiedPushLifecycle
import im.molly.unifiedpush.model.RegistrationStatus
import org.signal.core.util.concurrent.SignalExecutors
import org.signal.core.util.logging.Log
import org.signal.core.util.eventbus.EventBus
import org.signal.storage.keyvalue.KeyValuePersistenceException
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
import kotlinx.coroutines.runBlocking

class UnifiedPushReceiver : MessagingReceiver() {

  companion object {
    private val TAG = Log.tag(UnifiedPushReceiver::class.java)

    /** Applies an endpoint that arrived while the app was locked. Called after unlock. */
    @JvmStatic
    fun applyPendingEndpoint(context: Context) {
      if (Looper.myLooper() == Looper.getMainLooper()) {
        SignalExecutors.BOUNDED.execute { applyPendingEndpoint(context) }
        return
      }
      val receiver = UnifiedPushReceiver()
      val shouldApply = synchronized(UnifiedPushLifecycle.monitor) {
        // Check the lock before inspecting or consuming the sealed endpoint. A callback queued before
        // the app locked must leave the saved endpoint untouched for a later unlock path.
        if (KeyCachingService.isLocked()) return
        if (!UnifiedPushLifecycle.isReadyLocked() && !UnifiedPushLifecycle.hasPendingInvalidationLocked()) return
        LockedPushState.peekPending(context) != null
      }
      if (shouldApply) {
        val effects = runBlocking {
          UnifiedPushAccountTransition.withTransition(
            completesInvalidation = true,
            readyAfter = { UnifiedPushAccountTransition.hasCoherentRegisteredContext() && !KeyCachingService.isLocked() },
          ) {
            synchronized(UnifiedPushLifecycle.monitor) {
              if (KeyCachingService.isLocked()) {
                null
              } else {
                LockedPushState.peekPending(context)?.let { pending ->
                  receiver.applyEndpointLocked(pending).also { LockedPushState.clearPending(context) }
                }
              }
            }
          }
        }
        effects?.let { receiver.publishEndpointEffects(context, it) }
      }
      // Now that the app can read its state: make sure the server holds the current endpoint, and send an update
      // that was built but never confirmed again.
      if (UnifiedPushLifecycle.isReady()) EndpointReconciler.reconcile(resume = true)
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
    val schedule = synchronized(UnifiedPushLifecycle.monitor) {
      // Store first; the worker consumes it only after closing admission and draining old sends.
      LockedPushState.savePending(context, received)
      if (UnifiedPushLifecycle.isReadyLocked()) UnifiedPushLifecycle.invalidateLocked()
      !appLocked && (UnifiedPushLifecycle.isReadyLocked() || UnifiedPushLifecycle.hasPendingInvalidationLocked())
    }
    if (schedule) SignalExecutors.BOUNDED.execute { applyPendingEndpoint(context) }
  }

  private data class EndpointEffects(val failed: Boolean, val changed: Boolean, val airGapped: Boolean)

  /** Updates route tuple and control frame under the lifecycle monitor; UI and job callbacks happen after release. */
  private fun applyEndpointLocked(endpoint: LockedPushState.Endpoint): EndpointEffects {
    check(Thread.holdsLock(UnifiedPushLifecycle.monitor))
    val failed = SignalStore.unifiedpush.registrationFailed
    SignalStore.unifiedpush.registrationFailed = false
    SignalStore.unifiedpush.registrationRetryScheduled = false
    val values = SignalStore.unifiedpush
    val changed = endpoint.url != values.endpoint
    values.setEndpointRegistration(
      endpoint = endpoint.url,
      p256dh = endpoint.p256dh,
      auth = endpoint.auth,
      instance = endpoint.instance.takeIf { endpoint.url != null },
    )
    return EndpointEffects(failed, changed, values.airGapped)
  }

  private fun publishEndpointEffects(context: Context, effects: EndpointEffects) {
    if (effects.failed) {
      UnifiedPushNotificationBuilder(context).clearRegistrationFailed()
      EventBus.default.post(PushServiceEvent)
    }
    // The route is durable and coherent before reconciliation builds a frame from it.
    EndpointReconciler.reconcile()
    if (effects.failed && !effects.changed) {
      AppDependencies.jobManager.add(UnifiedPushRefreshJob(testPing = false, fromNewEndpoint = true))
    }
    if (effects.changed && effects.airGapped) {
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
    val schedule = synchronized(UnifiedPushLifecycle.monitor) {
      LockedPushState.savePending(context, LockedPushState.Endpoint(null, null, null, instance))
      if (UnifiedPushLifecycle.isReadyLocked()) UnifiedPushLifecycle.invalidateLocked()
      !appLocked && (UnifiedPushLifecycle.isReadyLocked() || UnifiedPushLifecycle.hasPendingInvalidationLocked())
    }
    UnifiedPush.forceRemoveDistributor(context)
    if (schedule) SignalExecutors.BOUNDED.execute { applyPendingEndpoint(context) }
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

    val lease = UnifiedPushAccountTransition.acquireLease()
    if (lease == null) {
      Log.w(TAG, "Account transition or registration context unavailable. Push dropped.")
      return
    }

    var leaseTransferred = false
    try {
      val handling = synchronized(UnifiedPushLifecycle.monitor) {
        if (!UnifiedPushLifecycle.isReadyLocked() || !UnifiedPushAccountTransition.contextStillMatchesLocked(lease.snapshot)) {
          return@synchronized null
        }
        val values = SignalStore.unifiedpush
        val controlState = values.controlStateRead.stateOrNull
        if (controlState == null) {
          Log.w(TAG, "Control state unavailable. Push dropped.")
          return@synchronized MessageHandling(drop = true)
        }
        val active = controlState.activeEpoch
        val pending = controlState.pendingEpoch
        LockedPushState.setEncryptedEnrollment(context, active > 0)

        // A saved import intent also requires encrypted delivery, before or after an active import is confirmed.
        if (active > 0 || pending > 0) {
          val encrypted = acceptEncryptedMessageLocked(context, message)
          if (encrypted != null && encrypted.effect != EndpointUpdates.Effect.REJECTED && encrypted.outcome.decision.accepted) {
            return@synchronized MessageHandling(encrypted = encrypted)
          }
          if (active > 0 || EncryptedPush.isEventShaped(message.content)) {
            return@synchronized MessageHandling(drop = true)
          }
        }
        MessageHandling(plaintext = message.content.toString(Charsets.UTF_8))
      }

      if (handling == null || handling.drop) return
      val encrypted = handling.encrypted
      if (encrypted != null) {
        leaseTransferred = finishEncryptedMessage(context, lease, encrypted)
      } else {
        updateLastReceivedTime(System.currentTimeMillis())
        leaseTransferred = onMessageUnlocked(context, checkNotNull(handling.plaintext), lease)
      }
    } finally {
      if (!leaseTransferred) lease.close()
    }
  }

  private data class MessageHandling(
    val encrypted: EncryptedHandling? = null,
    val plaintext: String? = null,
    val drop: Boolean = false,
  )

  private data class EncryptedHandling(
    val outcome: EncryptedPush.Outcome,
    val effect: EndpointUpdates.Effect,
  )

  /** Reads, validates, and durably commits an event while the lifecycle/replay monitor is held. */
  private fun acceptEncryptedMessageLocked(context: Context, message: PushMessage): EncryptedHandling? {
    check(Thread.holdsLock(UnifiedPushLifecycle.monitor))
    val values = SignalStore.unifiedpush
    val controlStore = UnifiedPushControlStore(values)
    val snapshot = try {
      controlStore.read()
    } catch (e: CorruptControlStateException) {
      Log.w(TAG, "Control state unavailable. Encrypted push dropped.")
      return null
    }
    val currentContext = UnifiedPushAccountTransition.captureContextLocked()
    val currentRegistration = currentContext?.let {
      EndpointUpdates.RegistrationContext(it.accountId, it.pushDevice?.deviceId ?: -1, it.instance)
    }
    var effect = EndpointUpdates.Effect.NONE
    val now = System.currentTimeMillis() / 1000
    val state = EncryptedPush.ReplayState.parse(values.replayState)
    if (state == null) {
      Log.w(TAG, "Replay state unavailable. Encrypted push dropped.")
      return null
    }
    val outcome = EncryptedPush.decide(
      content = message.content,
      decrypted = message.decrypted,
      now = now,
      locked = { appLocked },
      activeInstance = currentContext?.instance ?: values.endpointInstance ?: "default",
      acceptedEpochs = EndpointUpdates.acceptedEpochs(snapshot),
      state = state,
      versionsForEpoch = { epoch -> EndpointUpdates.versionsForEpoch(snapshot, epoch) },
      identityMatches = { event ->
        EndpointUpdates.identityMatches(snapshot, event, if (event.version == 3) currentRegistration else null)
      },
    )
    if (outcome.decision.accepted) {
      // Replay and intent promotion share one acknowledged write before any effect starts.
      val event = outcome.event!!
      effect = try {
        EndpointUpdates.accepted(controlStore, outcome.state.serialize(), event, if (event.version == 3) currentRegistration else null)
      } catch (e: KeyValuePersistenceException) {
        Log.w(TAG, "Encrypted push state persistence was not acknowledged. Push dropped.", e)
        return null
      }
      if (effect == EndpointUpdates.Effect.PROMOTED) LockedPushState.setEncryptedEnrollment(context, true)
    }
    return EncryptedHandling(outcome, effect)
  }

  /** Runs callbacks after the durable decision and monitor release. A queued wake keeps its lease through fetch. */
  private fun finishEncryptedMessage(
    context: Context,
    lease: UnifiedPushLifecycle.Lease<UnifiedPushAccountTransition.AccountContext>,
    handling: EncryptedHandling,
  ): Boolean {
    val outcome = handling.outcome
    val effect = handling.effect
    if (effect == EndpointUpdates.Effect.REJECTED || !outcome.decision.accepted) return false
    if (!UnifiedPushLifecycle.isCurrentGeneration(lease.generation)) return false
    if (effect != EndpointUpdates.Effect.NONE) EndpointReconciler.reconcile()

    Log.d(TAG, "Encrypted push: ${outcome.decision}")
    if (appLocked) return false

    val values = SignalStore.unifiedpush
    val becameRegistered = UnifiedPushLifecycle.withCurrentLease(lease) {
      if (values.registrationStatus != RegistrationStatus.REGISTERED) {
        values.registrationStatus = RegistrationStatus.REGISTERED
        true
      } else false
    } ?: return false
    if (becameRegistered) EventBus.default.post(PushServiceEvent)

    return when (outcome.decision) {
      EncryptedPush.Decision.ACCEPT_WAKE -> {
        updateLastReceivedTime(System.currentTimeMillis())
        PushHealthActions.onPushAccepted()
        startFetch(context, lease)
      }
      EncryptedPush.Decision.ACCEPT_TEST -> {
        updateLastReceivedTime(System.currentTimeMillis())
        PushHealthActions.onPushAccepted()
        if (UnifiedPushLifecycle.isCurrentGeneration(lease.generation)) UnifiedPushNotificationBuilder(context).setNotificationTest()
        false
      }
      EncryptedPush.Decision.ACCEPT_STATUS -> {
        if (effect == EndpointUpdates.Effect.CONFIRMED) {
          Log.i(TAG, "Endpoint update confirmed at epoch ${outcome.event?.epoch}")
          PushHealthActions.onPing(PushHealth.Hop.NONE)
        } else if (outcome.event?.code != EncryptedPush.CODE_SUBSCRIPTION_UPDATED && outcome.event?.code != EncryptedPush.CODE_ENROLLMENT_IMPORTED) {
          UnifiedPushLifecycle.withCurrentLease(lease) { values.statusCode = outcome.event?.code }
        }
        false
      }
      else -> false
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

  private fun onMessageUnlocked(context: Context, message: String, lease: UnifiedPushLifecycle.Lease<UnifiedPushAccountTransition.AccountContext>): Boolean {
    return when {
      message.contains("\"test\":true") -> {
        Log.d(TAG, "Test message received.")
        if (!UnifiedPushLifecycle.isCurrentGeneration(lease.generation)) return false
        UnifiedPushNotificationBuilder(context).setNotificationTest()
        AppDependencies.jobManager.add(UnifiedPushRefreshJob(testPing = false, fromNewEndpoint = true))
        false
      }

      else -> startFetch(context, lease)
    }
  }

  private fun startFetch(context: Context, lease: UnifiedPushLifecycle.Lease<UnifiedPushAccountTransition.AccountContext>): Boolean {
    UnifiedPushLifecycle.withCurrentLease(lease) {
      if (SignalStore.account.isRegistered && SignalStore.unifiedpush.enabled) {
        Log.d(TAG, "New message")
        val authorization = UnifiedPushAccountTransition.fetchAuthorization(lease)
        executor.enqueue {
          if (UnifiedPushLifecycle.isCurrentGeneration(authorization.generation)) {
            PushFetchHandler.handleReceivedNotification(context, true, authorization)
          }
        }
      }
    }
    // The fetch manager reacquires its own lease when the queued work starts. This receiver's
    // short acceptance lease always closes when onMessage returns.
    return false
  }

  private fun updateLastReceivedTime(timestamp: Long) {
    SignalStore.unifiedpush.lastReceivedTime = timestamp
  }
}
