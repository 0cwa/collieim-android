package im.molly.unifiedpush.receiver

import android.content.Context
import android.content.Intent
import android.os.Looper
import im.molly.unifiedpush.UnifiedPushNotificationBuilder
import im.molly.unifiedpush.control.ControlFrame
import im.molly.unifiedpush.control.EndpointReconciler
import im.molly.unifiedpush.control.EndpointUpdates
import im.molly.unifiedpush.control.CorruptControlStateException
import im.molly.unifiedpush.control.UnifiedPushControlStore
import im.molly.unifiedpush.control.ReceiverControlState
import im.molly.unifiedpush.control.Target
import im.molly.unifiedpush.health.PushHealth
import im.molly.unifiedpush.health.PushHealthActions
import im.molly.unifiedpush.enrollment.PairingRegistrationGate
import im.molly.unifiedpush.enrollment.PairingDistributorRegistration
import im.molly.unifiedpush.enrollment.EnrollmentFile
import im.molly.unifiedpush.enrollment.PairingV1
import im.molly.unifiedpush.enrollment.RelayDisclosure
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
import org.signal.storage.keyvalue.PlainTextKeyValueStore
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.keyvalue.UnifiedPushValues
import org.thoughtcrime.securesms.service.KeyCachingService
import org.thoughtcrime.securesms.util.concurrent.SerialMonoLifoExecutor
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.MessagingReceiver
import org.unifiedpush.android.connector.UnifiedPush
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage
import kotlinx.coroutines.runBlocking
import java.util.Base64

class UnifiedPushReceiver : MessagingReceiver() {

  companion object {
    private val TAG = Log.tag(UnifiedPushReceiver::class.java)
    private var pendingAirGappedWarning: UnifiedPushLifecycle.TransitionResult<AirGappedWarning>? = null

    /** Applies an endpoint that arrived while the app was locked. Called after unlock. */
    @JvmStatic
    fun applyPendingEndpoint(context: Context) {
      if (Looper.myLooper() == Looper.getMainLooper()) {
        SignalExecutors.BOUNDED.execute { applyPendingEndpoint(context) }
        return
      }
      commitPendingEndpoint(context)?.let { publishPendingEndpointEffects(context, it) }
      if (!KeyCachingService.isLocked()) {
        val confirmation = LockedPushState.peekPairingConfirmation(context)
        val bytes = confirmation?.message?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
        if (confirmation != null && bytes != null) {
          UnifiedPushReceiver().onMessage(context, PushMessage(bytes, true), confirmation.instance)
        }
      }
      publishAirGappedWarning(context)
      // Now that the app can read its state: make sure the server holds the current endpoint, and send an update
      // that was built but never confirmed again.
      if (UnifiedPushLifecycle.isReady()) EndpointReconciler.reconcile(resume = true)
    }

    /** A passphrase lock can restart the app before the queued retry begins. */
    @JvmStatic
    fun resumeScopedRegistration(context: Context) {
      val instance = synchronized(UnifiedPushLifecycle.monitor) {
        if (KeyCachingService.isLocked() || !SignalStore.unifiedpush.registrationRetryScheduled) return
        SignalStore.unifiedpush.pairingEndpointBinding?.instance
      } ?: return
      UnifiedPushReceiver().retryScopedRegistration(context, instance)
    }

    /** Commits the pending connector tuple and its generation under the lifecycle transition. */
    internal fun commitPendingEndpoint(context: Context): UnifiedPushLifecycle.TransitionResult<EndpointEffects?>? {
      val receiver = UnifiedPushReceiver()
      if (KeyCachingService.isLocked()) return null
      synchronized(UnifiedPushLifecycle.monitor) {
        val pairing = SignalStore.unifiedpush.pairingEndpointBinding
        LockedPushState.setProtectedPairingRoute(context, pairing?.instance, pairing?.distributorId)
      }
      val bufferedUnregistration = PairingRegistrationGate.takeBufferedUnregistration()
      if (bufferedUnregistration != null) {
        val binding = PairingRegistrationGate.current()?.binding?.takeIf { it.instance == bufferedUnregistration }
        PairingRegistrationGate.fail(bufferedUnregistration)
        if (binding != null) receiver.discardPairingCandidate(context, binding)
      } else {
        PairingRegistrationGate.takeBufferedEndpoint()?.let { buffered ->
          receiver.admitPairingEndpoint(
            context,
            LockedPushState.Endpoint(buffered.url, buffered.p256dh, buffered.auth, buffered.instance),
          )
        }
      }
      val queuedBeforeLock = LockedPushState.peekPending(context)
      if (queuedBeforeLock != null && PairingRegistrationGate.current()?.binding?.instance == queuedBeforeLock.instance) {
        receiver.admitPairingEndpoint(context, queuedBeforeLock)
        return null
      }
      var discardedGeneration: Long? = null
      val expectedGeneration = synchronized(UnifiedPushLifecycle.monitor) {
        // Check the lock before inspecting or consuming the sealed endpoint. A callback queued before
        // the app locked must leave the saved endpoint untouched for a later unlock path.
        if (KeyCachingService.isLocked()) return null
        val queued = LockedPushState.peekPending(context)
        if (queued != null && !receiver.pendingEndpointAllowedLocked(context, queued)) {
          if (LockedPushState.discardPending(context)) discardedGeneration = UnifiedPushLifecycle.currentGeneration()
          return@synchronized null
        }
        if (!UnifiedPushLifecycle.isReadyLocked() && !UnifiedPushLifecycle.hasPendingEndpointInvalidationLocked()) return null
        if (LockedPushState.peekPending(context) == null) return null
        UnifiedPushLifecycle.currentGeneration()
      }

      if (discardedGeneration != null) {
        restoreAdmissionAfterDiscard(discardedGeneration!!)
        return null
      }
      if (expectedGeneration == null) return null

      return try {
        runBlocking {
          UnifiedPushAccountTransition.withTransitionAndGeneration(
            expectedGeneration = expectedGeneration,
            completesInvalidation = true,
            readyAfter = { UnifiedPushAccountTransition.hasCoherentRegisteredContext() && !KeyCachingService.isLocked() },
          ) {
            synchronized(UnifiedPushLifecycle.monitor) {
              if (KeyCachingService.isLocked()) {
                null
              } else {
                LockedPushState.peekPending(context)?.let { pending ->
                  val effects = if (receiver.pendingEndpointAllowedLocked(context, pending)) receiver.applyEndpointLocked(pending) else null
                  LockedPushState.clearPending(context)
                  effects
                }
              }
            }
          }
        }
      } catch (_: UnifiedPushLifecycle.StaleLifecycleGenerationException) {
        // The winning transition schedules pending connector work after committing.
        null
      }
    }

    /** Publishes callbacks only while the completed endpoint transition still owns a live generation lease. */
    internal fun publishPendingEndpointEffects(
      context: Context,
      transition: UnifiedPushLifecycle.TransitionResult<EndpointEffects?>,
    ) {
      val effects = transition.value ?: return
      UnifiedPushAccountTransition.acquireLease(transition.generation)?.use {
        UnifiedPushReceiver().publishEndpointEffects(context, effects)
      }
      effects.warning?.let {
        publishAirGappedWarning(context, UnifiedPushLifecycle.TransitionResult(it, transition.generation))
      }
    }

    private fun publishAirGappedWarning(
      context: Context,
      warning: UnifiedPushLifecycle.TransitionResult<AirGappedWarning>? = null,
    ) = synchronized(UnifiedPushLifecycle.monitor) {
      if (warning != null && UnifiedPushLifecycle.hasOnlyCredentialChangesSinceLocked(warning.generation)) {
        pendingAirGappedWarning = warning
      }
      val pending = pendingAirGappedWarning ?: return@synchronized
      if (!UnifiedPushLifecycle.hasOnlyCredentialChangesSinceLocked(pending.generation)) {
        pendingAirGappedWarning = null
        return@synchronized
      }
      if (KeyCachingService.isLocked() || !UnifiedPushLifecycle.isReadyLocked()) return@synchronized

      pendingAirGappedWarning = null
      val live = UnifiedPushAccountTransition.captureContextLocked()?.copy(pushDevice = null)
      if (SignalStore.unifiedpush.airGapped && live == pending.value.route &&
        SignalStore.unifiedpush.endpointInstance == pending.value.storedInstance) {
        UnifiedPushNotificationBuilder(context).setNotificationEndpointChangedAirGapped()
      }
    }

    /** A stale locked callback must not leave admission closed after its sealed tuple is discarded. */
    private fun restoreAdmissionAfterDiscard(generation: Long) {
      if (KeyCachingService.isLocked()) return
      try {
        runBlocking {
          UnifiedPushAccountTransition.withTransition(
            expectedGeneration = generation,
            completesInvalidation = true,
            readyAfter = { UnifiedPushAccountTransition.hasCoherentRegisteredContext() && !KeyCachingService.isLocked() },
          ) { }
        }
      } catch (_: UnifiedPushLifecycle.StaleLifecycleGenerationException) {
        return
      }
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

  override fun onReceive(context: Context, intent: Intent) {
    super.onReceive(PairingDistributorRegistration.BroadcastContext(context), intent)
  }

  private fun routeCandidateAcknowledgment(context: Context, instance: String) {
    if (context is PairingDistributorRegistration.BroadcastContext) {
      context.distributor = PairingRegistrationGate.current()?.binding?.takeIf { it.instance == instance }?.distributorId
      if (context.distributor == null && PairingRegistrationGate.isPairingInstance(instance)) {
        if (LockedPushState.protectedPairingInstance(context) != instance) {
          context.distributor = LockedPushState.peekPairingConfirmation(context)
            ?.takeIf { it.instance == instance && System.currentTimeMillis() / 1000 < it.expiresAt }?.distributor
          context.acknowledge = context.distributor != null
        }
      }
    }
  }

  override fun onNewEndpoint(context: Context, endpoint: PushEndpoint, instance: String) {
    routeCandidateAcknowledgment(context, instance)
    Log.i(TAG, "onNewEndpoint($instance)")
    val received = LockedPushState.Endpoint(endpoint.url, endpoint.pubKeySet?.pubKey, endpoint.pubKeySet?.auth, instance)
    // Locked callbacks are buffered before any SignalStore reads. A tentative pairing endpoint is
    // admitted from that buffer after unlock without replacing the currently active route.
    if (appLocked) {
      synchronized(UnifiedPushLifecycle.monitor) {
        val tentative = PairingRegistrationGate.current()?.binding?.instance == instance
        if (tentative) {
          PairingRegistrationGate.bufferEndpoint(
            PairingRegistrationGate.BufferedEndpoint(received.url, received.p256dh, received.auth, received.instance),
          )
        } else {
          val protected = LockedPushState.protectedPairingInstance(context)
          if (!PairingRegistrationGate.allowsLockedCallback(instance, protected)) return
          LockedPushState.savePending(context, received)
          if (UnifiedPushLifecycle.isReadyLocked()) UnifiedPushLifecycle.invalidateLocked()
        }
      }
      return
    }
    if (UnifiedPush.getSavedDistributor(context) != UnifiedPush.getAckDistributor(context)) return
    if (PairingRegistrationGate.current()?.binding?.instance == instance) {
      admitPairingEndpoint(context, received)
      return
    }
    val currentPairing = SignalStore.unifiedpush.pairingEndpointBinding
    val protectedPairingInstance = currentPairing?.instance
      ?: SignalStore.unifiedpush.endpointInstance?.takeIf { PairingRegistrationGate.isPairingInstance(it) }
    val activeRouteInstance = SignalStore.unifiedpush.endpointInstance ?: "default"
    if (!PairingRegistrationGate.allowsEndpointCallback(instance, protectedPairingInstance, activeRouteInstance)) {
      return
    }
    if (currentPairing != null && currentPairing.instance == instance) {
      if (appLocked) return
      val callbackOrigin = im.molly.unifiedpush.enrollment.PairingV1.canonicalOrigin(endpoint.url)
      val tupleChanged = endpoint.url != SignalStore.unifiedpush.endpoint ||
        endpoint.pubKeySet?.pubKey != SignalStore.unifiedpush.endpointP256dh ||
        endpoint.pubKeySet?.auth != SignalStore.unifiedpush.endpointAuth
      if (!pairingBindingMatchesCurrent(context, currentPairing) || callbackOrigin == null ||
        endpoint.pubKeySet?.pubKey == null || endpoint.pubKeySet?.auth == null ||
        (tupleChanged && !pairingIdentityConfirmed(currentPairing))) {
        return
      }
    }
    if (currentPairing != null && instance != currentPairing.instance) {
      // A retained callback from the legacy default instance must not replace the pairing endpoint.
      return
    }
    var unchanged: UnifiedPushLifecycle.TransitionResult<EndpointEffects?>? = null
    var rejectedByPairingGate = false
    val schedule = synchronized(UnifiedPushLifecycle.monitor) {
      val values = SignalStore.unifiedpush
      val currentPairing = values.pairingEndpointBinding
      val livePairingInstance = PairingRegistrationGate.current()?.binding?.instance
        ?: currentPairing?.instance
        ?: values.endpointInstance?.takeIf { PairingRegistrationGate.isPairingInstance(it) }
      val currentRouteInstance = values.endpointInstance ?: "default"
      if (currentPairing != null && currentPairing.instance == instance &&
        (!pairingBindingMatchesCurrent(context, currentPairing) || !pairingIdentityConfirmed(currentPairing))) {
        rejectedByPairingGate = true
        return@synchronized false
      }
      if (!PairingRegistrationGate.allowsEndpointCallback(instance, livePairingInstance, currentRouteInstance)) {
        rejectedByPairingGate = true
        return@synchronized false
      }
      // registerApp also returns the existing endpoint. It must not revoke the registration
      // or test notification that requested it. Real route changes still drain old work.
      if (!appLocked && UnifiedPushLifecycle.isReadyLocked() && matchesCurrentEndpointLocked(received) && LockedPushState.peekPending(context) == null) {
        unchanged = UnifiedPushLifecycle.TransitionResult(applyEndpointLocked(received), UnifiedPushLifecycle.currentGeneration())
        return@synchronized false
      }
      // Store first; the worker consumes it only after closing admission and draining old sends.
      LockedPushState.savePending(context, received)
      if (UnifiedPushLifecycle.isReadyLocked()) UnifiedPushLifecycle.invalidateLocked()
      !appLocked && (UnifiedPushLifecycle.isReadyLocked() || UnifiedPushLifecycle.hasPendingEndpointInvalidationLocked())
    }
    if (rejectedByPairingGate) {
      return
    }
    val completed = unchanged
    if (completed != null) {
      publishPendingEndpointEffects(context, completed)
    } else if (schedule) {
      SignalExecutors.BOUNDED.execute {
        applyPendingEndpoint(context)
      }
    }
  }

  /** Captures an approved registration endpoint as tentative state; the active route stays usable until confirmation. */
  private fun admitPairingEndpoint(context: Context, endpoint: LockedPushState.Endpoint) {
    var admitted: PairingRegistrationGate.Pending? = null
    var rejected: String? = null
    var candidateBinding: PairingRegistrationGate.Binding? = null
    synchronized(UnifiedPushLifecycle.monitor) {
      if (KeyCachingService.isLocked()) return
      val pending = PairingRegistrationGate.current()?.takeIf { it.binding.instance == endpoint.instance } ?: return
      val binding = pending.binding
      candidateBinding = binding
      val values = SignalStore.unifiedpush
      val account = UnifiedPushAccountTransition.captureContextLocked()
      val routeInstance = values.endpointInstance ?: "default"
      val routeUnchanged = values.endpoint == binding.previousEndpoint &&
        values.endpointP256dh == binding.previousP256dh && values.endpointAuth == binding.previousAuth &&
        routeInstance == binding.previousInstance
      val registrationScopeCurrent = UnifiedPushLifecycle.currentGeneration() == binding.lifecycleGeneration &&
        routeUnchanged && account != null && account.accountId == binding.accountId && account.localDeviceId == binding.deviceId &&
        SignalStore.account.aci?.toString() == binding.accountId && SignalStore.account.deviceId == binding.deviceId &&
        binding.matchesDistributorSelection(UnifiedPush.getSavedDistributor(context), UnifiedPush.getAckDistributor(context))
      val candidate = endpoint.url?.let { url ->
        try {
          PairingRegistrationGate.Endpoint(
            url, EnrollmentFile.canonicalKey(endpoint.p256dh.orEmpty(), 65),
            EnrollmentFile.canonicalKey(endpoint.auth.orEmpty(), 16), endpoint.instance,
          )
        } catch (_: EnrollmentFile.Invalid) {
          null
        }
      }
      if (!registrationScopeCurrent || candidate == null) {
        rejected = "Pairing registration scope changed"
      } else {
        admitted = PairingRegistrationGate.claim(
          instance = endpoint.instance,
          savedDistributor = UnifiedPush.getSavedDistributor(context),
          ackDistributor = UnifiedPush.getAckDistributor(context),
          accountId = SignalStore.account.aci?.toString(),
          deviceId = SignalStore.account.deviceId,
          endpoint = candidate,
        )
        if (admitted == null) rejected = "Pairing endpoint did not match the approved offer"
      }
      if (LockedPushState.peekPending(context)?.instance == endpoint.instance) {
        LockedPushState.clearPending(context)
      }
    }
    val accepted = admitted
    if (accepted != null) {
      PairingRegistrationGate.complete(endpoint.instance, checkNotNull(accepted.claimedEndpoint), success = true)
      Log.i(TAG, "Pairing-scoped endpoint captured for the approved session.")
    } else {
      PairingRegistrationGate.fail(endpoint.instance)
      candidateBinding?.let { discardPairingCandidate(context, it) }
      if (rejected != null) Log.w(TAG, rejected!!)
    }
  }

  private fun matchesCurrentEndpointLocked(endpoint: LockedPushState.Endpoint): Boolean {
    val current = UnifiedPushAccountTransition.captureContextLocked() ?: return false
    return current.endpoint == endpoint.url && current.endpointP256dh == endpoint.p256dh &&
      current.endpointAuth == endpoint.auth && current.instance == endpoint.instance
  }

  private fun pendingEndpointAllowedLocked(context: Context, pending: LockedPushState.Endpoint): Boolean {
    check(Thread.holdsLock(UnifiedPushLifecycle.monitor))
    val owner = UnifiedPush.getSavedDistributor(context)
    if (owner != UnifiedPush.getAckDistributor(context)) return false
    val values = SignalStore.unifiedpush
    val pairing = values.pairingEndpointBinding ?: return !PairingRegistrationGate.isPairingInstance(pending.instance)
    if (pending.instance != pairing.instance || owner != pairing.distributorId ||
      pairing.accountId != SignalStore.account.aci?.toString() || pairing.deviceId != SignalStore.account.deviceId) return false
    val confirmed = pairingIdentityConfirmed(pairing)
    if (pending.url != null) {
      val origin = PairingV1.canonicalOrigin(pending.url) ?: return false
      if (pending.p256dh == null || pending.auth == null || origin != pairing.endpointOrigin && !confirmed) return false
    }
    val changed = pending.url != values.endpoint || pending.p256dh != values.endpointP256dh ||
      pending.auth != values.endpointAuth || pending.instance != (values.endpointInstance ?: "default")
    return !changed || confirmed
  }

  private fun pairingBindingMatchesCurrent(
    context: Context,
    binding: org.thoughtcrime.securesms.keyvalue.UnifiedPushValues.PairingEndpointBinding,
  ): Boolean {
    val endpointOrigin = SignalStore.unifiedpush.endpoint?.let { im.molly.unifiedpush.enrollment.PairingV1.canonicalOrigin(it) }
    val ackDistributor = UnifiedPush.getAckDistributor(context)
    return binding.accountId == SignalStore.account.aci?.toString() &&
      binding.deviceId == SignalStore.account.deviceId &&
      UnifiedPush.getSavedDistributor(context) == binding.distributorId &&
      ackDistributor == binding.distributorId && endpointOrigin == binding.endpointOrigin
  }

  /** A changed tuple may replace a scoped route only after its live v3 import or v4 receiver is confirmed. */
  internal fun pairingIdentityConfirmed(
    binding: org.thoughtcrime.securesms.keyvalue.UnifiedPushValues.PairingEndpointBinding,
  ): Boolean = synchronized(UnifiedPushLifecycle.monitor) {
    if (SignalStore.unifiedpush.pairingEndpointBinding != binding) return@synchronized false
    val values = SignalStore.unifiedpush
    val context = UnifiedPushAccountTransition.captureContextLocked() ?: return@synchronized false
    val state = values.controlStateRead.stateOrNull
    val v3Confirmed = state != null && state.activeImportId != null && state.activeKeyId != null &&
      state.activeImportSeq > 0 && state.activeEpoch > 0 &&
      state.activeInstance == binding.instance && state.activeAccountId == binding.accountId &&
      state.activeDeviceId == context.pushDevice?.deviceId &&
      context.accountId == binding.accountId && context.instance == binding.instance &&
      SignalStore.account.aci?.toString() == binding.accountId && SignalStore.account.deviceId == binding.deviceId
    val receiver = ReceiverControlState.parse(values.v4ReceiverState)
    val v4Confirmed = receiver?.confirmed == true && receiver.importId == binding.importId &&
      receiver.accountId == binding.accountId && receiver.instance == binding.instance &&
      context.accountId == binding.accountId && context.instance == binding.instance &&
      context.localDeviceId == binding.deviceId && SignalStore.account.aci?.toString() == binding.accountId &&
      SignalStore.account.deviceId == binding.deviceId
    v3Confirmed || v4Confirmed
  }

  private fun discardPairingCandidate(context: Context, binding: PairingRegistrationGate.Binding) {
    synchronized(UnifiedPushLifecycle.monitor) {
      PairingDistributorRegistration.discard(context, binding)
      LockedPushState.clearPairingConfirmation(context, binding.sessionId)
    }
    SignalExecutors.BOUNDED.execute { EndpointReconciler.reconcile(retryPending = true) }
  }

  internal data class AirGappedWarning(
    val route: UnifiedPushAccountTransition.AccountContext,
    val storedInstance: String?,
  )

  internal data class EndpointEffects(
    val failed: Boolean,
    val changed: Boolean,
    val warning: AirGappedWarning?,
    val allowLegacyRelayRefresh: Boolean,
    val unregisteredInstance: String?,
  )

  /** Updates route tuple and control frame under the lifecycle monitor; UI and job callbacks happen after release. */
  private fun applyEndpointLocked(endpoint: LockedPushState.Endpoint): EndpointEffects {
    check(Thread.holdsLock(UnifiedPushLifecycle.monitor))
    val failed = SignalStore.unifiedpush.registrationFailed
    SignalStore.unifiedpush.registrationFailed = false
    SignalStore.unifiedpush.registrationRetryScheduled = false
    val values = SignalStore.unifiedpush
    val instance = endpoint.instance.takeIf { endpoint.url != null }
    val previousInstance = (values.endpointInstance ?: "default").takeIf { values.endpoint != null }
    val changed = endpoint.url != values.endpoint || endpoint.p256dh != values.endpointP256dh ||
      endpoint.auth != values.endpointAuth || instance != previousInstance
    val airGapped = values.airGapped
    val wasPairingScoped = values.pairingEndpointBinding?.instance == endpoint.instance ||
      PairingRegistrationGate.isPairingInstance(endpoint.instance)
    val existingPairing = values.pairingEndpointBinding?.takeIf { it.instance == endpoint.instance && endpoint.url != null }
    val pairingBinding = existingPairing?.let { binding ->
      endpoint.url?.let(im.molly.unifiedpush.enrollment.PairingV1::canonicalOrigin)?.let { binding.copy(endpointOrigin = it) }
    }
    if (pairingBinding == null) {
      values.setEndpointRegistration(endpoint.url, endpoint.p256dh, endpoint.auth, instance)
      LockedPushState.setProtectedPairingRoute(AppDependencies.application, null, null)
    } else {
      values.setPairingEndpointRegistration(endpoint.url, endpoint.p256dh, endpoint.auth, pairingBinding)
      LockedPushState.setProtectedPairingRoute(AppDependencies.application, pairingBinding.instance, pairingBinding.distributorId)
    }
    PairingRegistrationGate.current()?.takeIf { it.binding.instance != endpoint.instance }?.let { tentative ->
      val binding = tentative.binding
      val scopeStillMatches = values.endpoint == binding.previousEndpoint &&
        values.endpointP256dh == binding.previousP256dh && values.endpointAuth == binding.previousAuth &&
        (values.endpointInstance ?: "default") == binding.previousInstance &&
        SignalStore.account.aci?.toString() == binding.accountId && SignalStore.account.deviceId == binding.deviceId &&
        binding.matchesDistributorSelection(
          UnifiedPush.getSavedDistributor(AppDependencies.application), UnifiedPush.getAckDistributor(AppDependencies.application),
        )
      if (!scopeStillMatches) {
        PairingRegistrationGate.fail(binding.instance)
        SignalExecutors.BOUNDED.execute {
          discardPairingCandidate(AppDependencies.application, binding)
        }
      }
    }
    val warning = if (changed && airGapped) {
      updateLastReceivedTime(0)
      val activeKeyedEnrollment = values.controlStateRead.stateOrNull?.let { it.activeEpoch > 0 && it.key != null } == true
      if (activeKeyedEnrollment) null else UnifiedPushAccountTransition.captureContextLocked()?.copy(pushDevice = null)?.let {
        AirGappedWarning(it, values.endpointInstance)
      }
    } else null
    return EndpointEffects(
      failed = failed,
      changed = changed,
      warning = warning,
      allowLegacyRelayRefresh = endpoint.url != null && pairingBinding == null && !wasPairingScoped,
      unregisteredInstance = endpoint.instance.takeIf { endpoint.url == null },
    )
  }

  private fun publishEndpointEffects(context: Context, effects: EndpointEffects) {
    if (effects.failed) {
      UnifiedPushNotificationBuilder(context).clearRegistrationFailed()
      EventBus.default.post(PushServiceEvent)
    }
    // The route is durable and coherent before reconciliation builds a frame from it.
    EndpointReconciler.reconcile()
    // A new distributor endpoint must also resume legacy MollySocket registration. Control
    // reconciliation cannot do that before an encrypted enrollment has a control key.
    if (effects.allowLegacyRelayRefresh && (effects.changed || effects.failed) && !PairingRegistrationGate.isRegistrationPending() &&
      SignalStore.unifiedpush.pairingEndpointBinding == null) {
      AppDependencies.jobManager.add(UnifiedPushRefreshJob(testPing = false, fromNewEndpoint = true))
    }
  }

  override fun onRegistrationFailed(context: Context, reason: FailedReason, instance: String) {
    Log.w(TAG, "onRegistrationFailed($instance): $reason")
    if (PairingRegistrationGate.isPairingInstance(instance)) {
      val candidate = PairingRegistrationGate.current()?.binding?.takeIf { it.instance == instance }
      if (candidate != null) {
        PairingRegistrationGate.fail(instance)
        discardPairingCandidate(context, candidate)
        return
      }
      if (appLocked) return
      val active = synchronized(UnifiedPushLifecycle.monitor) {
        val binding = SignalStore.unifiedpush.pairingEndpointBinding?.takeIf { it.instance == instance }
        if (binding != null && pairingBindingMatchesCurrent(context, binding) && !SignalStore.unifiedpush.registrationRetryScheduled) {
          SignalStore.unifiedpush.registrationFailed = true
          SignalStore.unifiedpush.registrationRetryScheduled = true
          binding
        } else null
      }
      if (active != null) {
        UnifiedPushNotificationBuilder(context).setNotificationRegistrationFailed()
        EventBus.default.post(PushServiceEvent)
        SignalExecutors.BOUNDED.execute { retryScopedRegistration(context, active.instance) }
      }
      return
    }
    if (appLocked) return
    if (SignalStore.unifiedpush.pairingEndpointBinding != null ||
      PairingRegistrationGate.isPairingInstance(SignalStore.unifiedpush.endpointInstance ?: "")) return
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

  private fun retryScopedRegistration(context: Context, instance: String) {
    synchronized(UnifiedPushLifecycle.monitor) {
      if (appLocked) return
      val binding = SignalStore.unifiedpush.pairingEndpointBinding?.takeIf { it.instance == instance } ?: return
      if (!SignalStore.unifiedpush.registrationRetryScheduled) return
      if (!pairingBindingMatchesCurrent(context, binding) ||
        runCatching { UnifiedPush.registerApp(context, binding.instance, "CollieSocket pairing", binding.vapidPublicKey) }.isFailure) {
        SignalStore.unifiedpush.registrationRetryScheduled = false
      }
    }
  }

  override fun onUnregistered(context: Context, instance: String) {
    // called when this application is unregistered from receiving push messages
    // isPushAvailable becomes false => The websocket starts
    Log.i(TAG, "onUnregistered($instance)")
    if (appLocked) {
      synchronized(UnifiedPushLifecycle.monitor) {
        val protected = LockedPushState.protectedPairingInstance(context)
        if (PairingRegistrationGate.current()?.binding?.instance == instance) {
          PairingRegistrationGate.bufferUnregistration(instance)
        } else {
          if (!PairingRegistrationGate.allowsLockedCallback(instance, protected)) return
          LockedPushState.savePending(context, LockedPushState.Endpoint(null, null, null, instance))
          if (UnifiedPushLifecycle.isReadyLocked()) UnifiedPushLifecycle.invalidateLocked()
        }
      }
      return
    }
    val admission = synchronized(UnifiedPushLifecycle.monitor) {
      val owner = UnifiedPush.getSavedDistributor(context)
      if (owner == null || owner != UnifiedPush.getAckDistributor(context)) return
      val values = SignalStore.unifiedpush
      val pairing = values.pairingEndpointBinding
      val protectedInstance = pairing?.instance
        ?: values.endpointInstance?.takeIf { PairingRegistrationGate.isPairingInstance(it) }
      val routeInstance = values.endpointInstance ?: "default"
      if (!PairingRegistrationGate.allowsEndpointCallback(instance, protectedInstance, routeInstance)) return
      if (pairing?.instance == instance &&
        (!pairingBindingMatchesCurrent(context, pairing) || !pairingIdentityConfirmed(pairing))) return
      owner
    }
    val ownerDistributor = admission
    val pendingPairing = PairingRegistrationGate.current()
    if (pendingPairing?.binding?.instance == instance) {
      PairingRegistrationGate.fail(instance)
      return
    }
    var rejectedByPairingGate = false
    val schedule = synchronized(UnifiedPushLifecycle.monitor) {
      val values = SignalStore.unifiedpush
      if (UnifiedPush.getSavedDistributor(context) != ownerDistributor ||
        UnifiedPush.getAckDistributor(context) != ownerDistributor) {
        rejectedByPairingGate = true
        return@synchronized false
      }
      val livePairing = values.pairingEndpointBinding
      if (livePairing != null && livePairing.instance == instance &&
        (!pairingBindingMatchesCurrent(context, livePairing) || !pairingIdentityConfirmed(livePairing))) {
        rejectedByPairingGate = true
        return@synchronized false
      }
      val livePairingInstance = PairingRegistrationGate.current()?.binding?.instance
        ?: livePairing?.instance
        ?: values.endpointInstance?.takeIf { PairingRegistrationGate.isPairingInstance(it) }
      if (!PairingRegistrationGate.allowsEndpointCallback(instance, livePairingInstance, values.endpointInstance ?: "default")) {
        rejectedByPairingGate = true
        return@synchronized false
      }
      LockedPushState.savePending(context, LockedPushState.Endpoint(null, null, null, instance))
      if (UnifiedPushLifecycle.isReadyLocked()) UnifiedPushLifecycle.invalidateLocked()
      !appLocked && (UnifiedPushLifecycle.isReadyLocked() || UnifiedPushLifecycle.hasPendingEndpointInvalidationLocked())
    }
    if (rejectedByPairingGate) return
    if (schedule) {
      // Commit the tombstone while the old owner still passes callback admission. Removing that
      // owner first changes the saved/ack pair and can make commitPendingEndpoint discard it.
      SignalExecutors.BOUNDED.execute {
        val committed = commitPendingEndpoint(context) ?: return@execute
        if (committed.value == null) return@execute
        publishPendingEndpointEffects(context, committed)
        synchronized(UnifiedPushLifecycle.monitor) {
          if (committed.value.unregisteredInstance == instance &&
            UnifiedPushLifecycle.isCurrentGeneration(committed.generation) && LockedPushState.peekPending(context) == null &&
            UnifiedPush.getSavedDistributor(context) == ownerDistributor &&
            UnifiedPush.getAckDistributor(context) == ownerDistributor) {
            UnifiedPush.forceRemoveDistributor(context)
          }
        }
      }
    }
  }

  private fun matchingPairingConfirmation(
    context: Context,
    message: PushMessage,
    instance: String,
  ): LockedPushState.PairingConfirmation? {
    if (!message.decrypted) return null
    val now = System.currentTimeMillis() / 1000
    val pending = LockedPushState.peekPairingConfirmation(context)
      ?.takeIf { it.instance == instance && now < it.expiresAt } ?: return null
    val version = if (pending.receiverId == null) 3 else 4
    val event = EncryptedPush.parse(message.content, now, setOf(version)) ?: return null
    return pending.takeIf { it.matches(event) }
  }

  private fun bufferPairingConfirmation(context: Context, message: PushMessage, instance: String): Boolean =
    synchronized(UnifiedPushLifecycle.monitor) {
      val pending = matchingPairingConfirmation(context, message, instance) ?: return@synchronized false
      val now = System.currentTimeMillis() / 1000
      val version = if (pending.receiverId == null) 3 else 4
      val incoming = EncryptedPush.parse(message.content, now, setOf(version)) ?: return@synchronized false
      val prior = pending.message?.let { stored ->
        runCatching { Base64.getDecoder().decode(stored) }.getOrNull()?.let { EncryptedPush.parse(it, now, setOf(version)) }
      }
      if (prior == null || incoming.expiresAt > prior.expiresAt) {
        LockedPushState.savePairingConfirmation(context, pending.copy(message = Base64.getEncoder().encodeToString(message.content)))
      }
      true
    }

  private fun restorePairingCandidate(context: Context, message: PushMessage, instance: String) {
    synchronized(UnifiedPushLifecycle.monitor) {
      val pending = matchingPairingConfirmation(context, message, instance) ?: return
      val scope = RelayDisclosure.capture(context) ?: return
      val previousDistributor = scope.configuration.distributor ?: return
      if (RelayDisclosure.pairingScopeDigest(scope) != pending.scopeDigest ||
        UnifiedPush.getAckDistributor(context) != previousDistributor ||
        pending.distributor !in UnifiedPush.getDistributors(context)) return
      val account = scope.account
    if (pending.receiverId != null) {
      val receiver = ReceiverControlState.parse(SignalStore.unifiedpush.v4PendingReceiverState) ?: return
      if (receiver.receiverId != pending.receiverId || receiver.importId != pending.importId ||
        receiver.epoch != pending.epoch || receiver.accountId != account.accountId || receiver.instance != instance) return
      val target = receiver.target
      val origin = PairingV1.canonicalOrigin(target.endpoint) ?: return
      if (pending.providerOrigin != null && origin != pending.providerOrigin) return
      if (!PairingRegistrationGate.isPairingInstance(instance)) {
        val current = try {
          ControlFrame.Subscription(account.endpoint ?: return, account.endpointP256dh.orEmpty(), account.endpointAuth.orEmpty())
        } catch (_: EnrollmentFile.Invalid) { return }
        if (instance != account.instance || pending.distributor != previousDistributor || !target.matches(current) ||
          SignalStore.unifiedpush.vapidPublicKey != pending.vapid) return
      }
      val binding = PairingRegistrationGate.Binding(
        instance, pending.distributor, pending.vapid, pending.providerOrigin, pending.sessionId, pending.expiresAt,
        account.accountId, account.localDeviceId, scope.generation, account.endpoint, account.endpointP256dh,
        account.endpointAuth, account.instance, pending.hostLabel, previousDistributor, disclosure = scope,
      )
      PairingRegistrationGate.restore(
        binding,
        PairingRegistrationGate.Endpoint(target.endpoint, target.p256dh, target.auth, instance),
        PairingRegistrationGate.ImportBinding(
          receiver.importId, "", receiver.epoch, target.endpoint, target.p256dh, target.auth,
          account.accountId, receiver.bridgeDeviceId, instance, receiver.receiverId,
        ),
      )
      return
    }
      val bridge = account.pushDevice ?: return
      val intent = scope.intents.firstOrNull {
        it.eligible && it.requestId == pending.importId && it.keyId == pending.keyId && it.epoch == pending.epoch &&
          it.instance == instance && it.accountId == account.accountId && it.deviceId == bridge.deviceId &&
          it.password == bridge.password
      } ?: return
      val target = intent.target
      val origin = PairingV1.canonicalOrigin(target.endpoint) ?: return
      if (pending.providerOrigin != null && origin != pending.providerOrigin) return
      if (!PairingRegistrationGate.isPairingInstance(instance)) {
        val current = try {
          ControlFrame.Subscription(account.endpoint ?: return, account.endpointP256dh.orEmpty(), account.endpointAuth.orEmpty())
        } catch (_: EnrollmentFile.Invalid) {
          return
        }
        if (instance != account.instance || pending.distributor != previousDistributor || !target.matches(current) ||
          SignalStore.unifiedpush.vapidPublicKey != pending.vapid) return
      }
      PairingRegistrationGate.restore(
        PairingRegistrationGate.Binding(
          instance, pending.distributor, pending.vapid, pending.providerOrigin, pending.sessionId, pending.expiresAt,
          account.accountId, account.localDeviceId, scope.generation, account.endpoint, account.endpointP256dh,
          account.endpointAuth, account.instance, pending.hostLabel, previousDistributor, disclosure = scope,
        ),
        PairingRegistrationGate.Endpoint(target.endpoint, target.p256dh, target.auth, instance),
        PairingRegistrationGate.ImportBinding(
          intent.requestId, intent.keyId, intent.epoch, target.endpoint, target.p256dh, target.auth,
          intent.accountId, intent.deviceId, instance,
        ),
      )
    }
  }

  override fun onMessage(context: Context, message: PushMessage, instance: String) {
    routeCandidateAcknowledgment(context, instance)
    if (appLocked) {
      // SignalStore is unavailable while locked, so a custom instance can only be trusted after unlock.
      LockedPushState.recordPushArrived(context, System.currentTimeMillis())
      if (bufferPairingConfirmation(context, message, instance) || PairingRegistrationGate.isPairingInstance(instance)) return
      // Which kind of enrollment this is cannot be read from SignalStore while locked.
      if (LockedPushState.isEncryptedEnrollment(context)) {
        Log.d(TAG, "Encrypted push dropped while locked.")
      } else {
        onMessageLocked(context, message.content.toString(Charsets.UTF_8))
      }
      return
    }

    // A retained pairing import must not be promoted by another status code that reuses its identity.
    // The exact enrollment confirmation is handled by the pairing restoration path below.
    val pendingConfirmation = LockedPushState.peekPairingConfirmation(context)?.takeIf { it.instance == instance }
    if (pendingConfirmation != null && message.decrypted) {
      val version = if (pendingConfirmation.receiverId == null) 3 else 4
      val event = EncryptedPush.parse(message.content, System.currentTimeMillis() / 1000, setOf(version))
      if (event != null && pendingConfirmation.identityMatches(event) && !pendingConfirmation.matches(event)) return
    }

    val pairingBinding = SignalStore.unifiedpush.pairingEndpointBinding
    val protectedPairingInstance = pairingBinding?.instance
      ?: SignalStore.unifiedpush.endpointInstance?.takeIf { PairingRegistrationGate.isPairingInstance(it) }
    if (instance != protectedPairingInstance && !PairingRegistrationGate.isRegistrationPending()) {
      restorePairingCandidate(context, message, instance)
    }
    val pendingCandidate = PairingRegistrationGate.current()?.takeIf { it.binding.instance == instance }
    if (pendingCandidate != null) {
      val import = pendingCandidate.importBinding ?: return
      val allowed = synchronized(UnifiedPushLifecycle.monitor) {
        PairingRegistrationGate.allowsCandidateConfirmation(
          instance, UnifiedPush.getSavedDistributor(context), UnifiedPush.getAckDistributor(context),
          SignalStore.account.aci?.toString(), SignalStore.account.deviceId,
        ) && RelayDisclosure.matchesDuringTransition(pendingCandidate.binding.disclosure)
      }
      if (!allowed || !EncryptedPush.isEventShaped(message.content)) return
      val registration = pendingCandidate.binding
      val exactCandidate = synchronized(UnifiedPushLifecycle.monitor) {
        candidateConfirmationPreflightLocked(message, import)
      }
      if (!exactCandidate) return
      val handling = try {
        runBlocking {
          UnifiedPushAccountTransition.withTransition(
            expectedGeneration = registration.lifecycleGeneration,
            readyAfter = { UnifiedPushAccountTransition.hasCoherentRegisteredContext() && !KeyCachingService.isLocked() },
          ) {
            synchronized(UnifiedPushLifecycle.monitor) {
              val active = PairingRegistrationGate.current()?.takeIf { it.binding == registration } ?: return@synchronized null
              val endpoint = active.claimedEndpoint ?: return@synchronized null
              val values = SignalStore.unifiedpush
              val now = System.currentTimeMillis() / 1000
              val routeStillMatches = now < registration.expiresAt && endpoint.instance == import.instance &&
                endpoint.url == import.endpoint && endpoint.p256dh == import.p256dh && endpoint.auth == import.auth &&
                values.endpoint == registration.previousEndpoint && values.endpointP256dh == registration.previousP256dh &&
                values.endpointAuth == registration.previousAuth && (values.endpointInstance ?: "default") == registration.previousInstance &&
                SignalStore.account.aci?.toString() == registration.accountId && SignalStore.account.deviceId == registration.deviceId &&
                registration.matchesDistributorSelection(UnifiedPush.getSavedDistributor(context), UnifiedPush.getAckDistributor(context)) &&
                RelayDisclosure.matchesDuringTransition(registration.disclosure)
              val state = values.controlStateRead.stateOrNull ?: return@synchronized null
              val exactIntent = if (import.receiverId == null) {
                state.intents.any { intent -> intent.eligible && intent.requestId == import.requestId &&
                  intent.keyId == import.keyId && intent.epoch == import.epoch && intent.instance == import.instance &&
                  intent.accountId == import.accountId && intent.deviceId == import.deviceId &&
                  intent.target.endpoint == import.endpoint && intent.target.p256dh == import.p256dh && intent.target.auth == import.auth
                }
              } else {
                ReceiverControlState.parse(values.v4PendingReceiverState)?.let { receiver ->
                  receiver.receiverId == import.receiverId && receiver.importId == import.requestId &&
                    receiver.accountId == import.accountId && receiver.instance == import.instance && receiver.epoch == import.epoch &&
                    receiver.target.endpoint == import.endpoint && receiver.target.p256dh == import.p256dh && receiver.target.auth == import.auth
                } == true
              }
              if (!routeStillMatches || !exactIntent || !PairingRegistrationGate.allowsCandidateConfirmation(
                  instance, UnifiedPush.getSavedDistributor(context), UnifiedPush.getAckDistributor(context),
                  SignalStore.account.aci?.toString(), SignalStore.account.deviceId,
                )) return@synchronized null
              val endpointOrigin = im.molly.unifiedpush.enrollment.PairingV1.canonicalOrigin(endpoint.url) ?: return@synchronized null
              val pairingRegistration = org.thoughtcrime.securesms.keyvalue.UnifiedPushValues.PairingEndpointRegistration(
                endpoint.url, endpoint.p256dh, endpoint.auth,
                org.thoughtcrime.securesms.keyvalue.UnifiedPushValues.PairingEndpointBinding(
                  registration.instance, registration.distributorId, registration.vapidPublicKey,
                  registration.providerOrigin, endpointOrigin, registration.sessionId, registration.expiresAt,
                  registration.accountId, registration.deviceId, registration.hostLabel, import.requestId,
                ),
              )
              val previousPairingBinding = values.pairingEndpointBinding
              if (import.receiverId == null) {
                values.setPairingEndpointRegistration(endpoint.url, endpoint.p256dh, endpoint.auth, pairingRegistration.binding)
              }
              fun restoreLegacyRoute() {
                if (previousPairingBinding == null) {
                  values.setEndpointRegistration(registration.previousEndpoint, registration.previousP256dh,
                    registration.previousAuth, registration.previousInstance.takeIf { registration.previousEndpoint != null })
                } else {
                  values.setPairingEndpointRegistration(registration.previousEndpoint, registration.previousP256dh,
                    registration.previousAuth, previousPairingBinding)
                }
              }
              val accepted = acceptEncryptedMessageLocked(
                context, message, import, pairingRegistration.takeIf { import.receiverId != null },
              ) ?: run {
                if (import.receiverId == null) restoreLegacyRoute()
                return@synchronized null
              }
              val event = accepted.outcome.event
              val exactEvent = accepted.outcome.decision == EncryptedPush.Decision.ACCEPT_STATUS &&
                accepted.effect == EndpointUpdates.Effect.PROMOTED && event != null &&
                event.kind == EncryptedPush.Kind.STATUS && event.code == EncryptedPush.CODE_ENROLLMENT_IMPORTED &&
                event.importId == import.requestId && event.epoch == import.epoch && event.instance == import.instance &&
                (if (import.receiverId == null) event.version == 3 && event.keyId == import.keyId
                else event.version == 4 && event.receiverId == import.receiverId)
              if (!exactEvent) {
                if (import.receiverId == null) restoreLegacyRoute()
                return@synchronized null
              }
              values.enabled = true
              SignalStore.settings.preferredNotificationMethod =
                org.thoughtcrime.securesms.keyvalue.SettingsValues.NotificationDeliveryMethod.UNIFIEDPUSH
              LockedPushState.setEncryptedEnrollment(context, true)
              LockedPushState.setProtectedPairingRoute(context, registration.instance, registration.distributorId)
              PairingDistributorRegistration.promote(context, registration)
              PairingRegistrationGate.promote(registration.instance)
              LockedPushState.clearPairingConfirmation(context, registration.sessionId)
              accepted
            }
          }
        }
      } catch (_: Exception) { null }
      if (handling != null) {
        SignalExecutors.BOUNDED.execute {
          org.thoughtcrime.securesms.ApplicationContext.getInstance(context).activatePushNotificationServicesAfterPairing()
        }
        val lease = UnifiedPushAccountTransition.acquireLease()
        if (lease != null) {
          if (!finishEncryptedMessage(context, lease, handling)) lease.close()
        } else {
          EndpointReconciler.reconcile()
          updateLastReceivedTime(System.currentTimeMillis())
        }
      }
      return
    }
    val pendingReceiver = ReceiverControlState.parse(SignalStore.unifiedpush.v4PendingReceiverState)
      ?.takeIf { it.instance == instance && !it.confirmed }
    val confirmation = matchingPairingConfirmation(context, message, instance)
    if (pendingReceiver != null && confirmation != null) {
      val import = pendingReceiver.toImportBinding()
      val handling = synchronized(UnifiedPushLifecycle.monitor) {
        if (!candidateConfirmationPreflightLocked(message, import)) return@synchronized null
        val values = SignalStore.unifiedpush
        val endpoint = values.endpoint ?: return@synchronized null
        val route = UnifiedPushAccountTransition.captureContextLocked() ?: return@synchronized null
        if (route.accountId != pendingReceiver.accountId || route.instance != instance || values.endpointInstance != instance) return@synchronized null
        if (endpoint != pendingReceiver.target.endpoint || values.endpointP256dh != pendingReceiver.target.p256dh ||
          values.endpointAuth != pendingReceiver.target.auth) return@synchronized null
        val origin = PairingV1.canonicalOrigin(endpoint) ?: return@synchronized null
        val previousPairingBinding = values.pairingEndpointBinding
        val pairingRegistration = UnifiedPushValues.PairingEndpointRegistration(
          endpoint, values.endpointP256dh, values.endpointAuth,
          UnifiedPushValues.PairingEndpointBinding(
            pendingReceiver.instance, confirmation.distributor, confirmation.vapid, confirmation.providerOrigin,
            origin, confirmation.sessionId, confirmation.expiresAt, pendingReceiver.accountId, SignalStore.account.deviceId,
            confirmation.hostLabel, pendingReceiver.importId,
          ),
        )
        if (import.receiverId == null) {
          values.setPairingEndpointRegistration(endpoint, values.endpointP256dh, values.endpointAuth, pairingRegistration.binding)
        }
        fun restorePreviousRoute() {
          if (previousPairingBinding == null) {
            values.setEndpointRegistration(endpoint, values.endpointP256dh, values.endpointAuth, values.endpointInstance)
          } else {
            values.setPairingEndpointRegistration(endpoint, values.endpointP256dh, values.endpointAuth, previousPairingBinding)
          }
        }
        val accepted = try {
          acceptEncryptedMessageLocked(
            context, message, import, pairingRegistration.takeIf { import.receiverId != null },
          )
        } catch (_: Exception) { null }
        val event = accepted?.outcome?.event
        val exactConfirmation = accepted?.effect == EndpointUpdates.Effect.PROMOTED &&
          accepted.outcome.decision == EncryptedPush.Decision.ACCEPT_STATUS && event != null &&
          event.kind == EncryptedPush.Kind.STATUS && event.code == EncryptedPush.CODE_ENROLLMENT_IMPORTED &&
          event.version == 4 && event.receiverId == import.receiverId && event.importId == import.requestId &&
          event.epoch == import.epoch && event.instance == import.instance
        if (!exactConfirmation) {
          if (import.receiverId == null) restorePreviousRoute()
          return@synchronized null
        }
        values.enabled = true
        SignalStore.settings.preferredNotificationMethod =
          org.thoughtcrime.securesms.keyvalue.SettingsValues.NotificationDeliveryMethod.UNIFIEDPUSH
        LockedPushState.setEncryptedEnrollment(context, true)
        LockedPushState.setProtectedPairingRoute(context, pendingReceiver.instance, confirmation.distributor)
        LockedPushState.clearPairingConfirmation(context, confirmation.sessionId)
        accepted
      }
      if (handling?.effect == EndpointUpdates.Effect.PROMOTED) {
        val lease = UnifiedPushAccountTransition.acquireLease()
        if (lease != null && !finishEncryptedMessage(context, lease, handling)) lease.close()
      }
      return
    }
    val promoted = ReceiverControlState.parse(SignalStore.unifiedpush.v4ReceiverState)
    val promotedConfirmation = LockedPushState.peekPairingConfirmation(context)?.takeIf {
      it.receiverId != null && promoted?.confirmed == true && promoted.receiverId == it.receiverId && promoted.importId == it.importId
    }
    if (promoted != null && promotedConfirmation != null) {
      synchronized(UnifiedPushLifecycle.monitor) {
        val values = SignalStore.unifiedpush
        val current = ReceiverControlState.parse(values.v4ReceiverState)
        val account = UnifiedPushAccountTransition.captureContextLocked() ?: return@synchronized
        val endpoint = values.endpoint
        val consentScope = RelayDisclosure.capture(context)
        val confirmationStillMatches = current?.confirmed == true && current.receiverId == promoted.receiverId &&
          current.importId == promoted.importId && current.accountId == promoted.accountId &&
          current.instance == promoted.instance && current.instance == instance &&
          promotedConfirmation.instance == current.instance && promotedConfirmation.receiverId == current.receiverId &&
          promotedConfirmation.importId == current.importId && account.accountId == current.accountId &&
          account.localDeviceId == SignalStore.account.deviceId && endpoint == current.target.endpoint &&
          values.endpointP256dh == current.target.p256dh && values.endpointAuth == current.target.auth &&
          consentScope != null && RelayDisclosure.pairingScopeDigest(consentScope) == promotedConfirmation.scopeDigest &&
          RelayDisclosure.matchesDuringTransition(consentScope)
        if (confirmationStillMatches) {
          val origin = PairingV1.canonicalOrigin(checkNotNull(endpoint))
          if (origin != null) {
            values.setPairingEndpointRegistration(endpoint, values.endpointP256dh, values.endpointAuth,
              org.thoughtcrime.securesms.keyvalue.UnifiedPushValues.PairingEndpointBinding(
                current.instance, promotedConfirmation.distributor, promotedConfirmation.vapid,
                promotedConfirmation.providerOrigin, origin, promotedConfirmation.sessionId,
                promotedConfirmation.expiresAt, current.accountId, account.localDeviceId,
                promotedConfirmation.hostLabel, current.importId,
              ),
            )
            values.enabled = true
            LockedPushState.clearPairingConfirmation(context, promotedConfirmation.sessionId)
          }
        }
      }
    }
    if (pairingBinding?.instance == instance && !pairingBindingMatchesCurrent(context, pairingBinding)) return
    val activeRouteInstance = (SignalStore.unifiedpush.endpointInstance ?: "default").takeIf { SignalStore.unifiedpush.endpoint != null }
    if (!PairingRegistrationGate.allowsMessageCallback(
        instance, protectedPairingInstance, registrationPending = PairingRegistrationGate.isRegistrationPending(),
        activeRouteInstance = activeRouteInstance,
      )) return

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
        val receiver = ReceiverControlState.parse(values.v4ReceiverState)?.takeIf { it.instance == instance }
        val pendingReceiver = ReceiverControlState.parse(values.v4PendingReceiverState)?.takeIf { it.instance == instance }
        val currentRouteInstance = values.endpointInstance ?: "default"
        val legacyOwnsRoute = controlState.activeEpoch > 0 &&
          (controlState.activeInstance == instance || controlState.activeInstance == null && instance == currentRouteInstance) ||
          controlState.intents.any { it.eligible && it.instance == instance } ||
          controlState.legacyPending != null && instance == currentRouteInstance
        val scopedPairingRoute = values.pairingEndpointBinding?.instance == instance
        val pendingOnlyRoute = pendingReceiver != null && !legacyOwnsRoute && receiver == null && !scopedPairingRoute
        val encryptedRoute = active > 0 && legacyOwnsRoute || scopedPairingRoute || receiver != null || pendingOnlyRoute
        LockedPushState.setEncryptedEnrollment(context, active > 0 || scopedPairingRoute || receiver != null || pendingReceiver != null)
        if ((encryptedRoute && (receiver != null || pendingReceiver != null || scopedPairingRoute) || scopedPairingRoute && pending <= 0 && active <= 0) && !EncryptedPush.isEventShaped(message.content)) {
          return@synchronized MessageHandling(drop = true)
        }

        // A saved import intent also requires encrypted delivery, before or after an active import is confirmed.
        // Pending v3 imports need to inspect event-shaped messages, while a server that has not
        // imported them yet can still wake a retained legacy route with a plaintext push.
        val inspectLegacyCandidate = pending > 0 && legacyOwnsRoute
        if (encryptedRoute || inspectLegacyCandidate) {
          val encrypted = acceptEncryptedMessageLocked(context, message)
          if (encrypted != null && encrypted.effect != EndpointUpdates.Effect.REJECTED && encrypted.outcome.decision.accepted) {
            if (encrypted.effect == EndpointUpdates.Effect.PROMOTED) {
              val pending = matchingPairingConfirmation(context, message, instance)
              if (pending != null) LockedPushState.clearPairingConfirmation(context, pending.sessionId)
            }
            return@synchronized MessageHandling(encrypted = encrypted)
          }
          if (encryptedRoute || EncryptedPush.isEventShaped(message.content)) {
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
  private fun acceptEncryptedMessageLocked(
    context: Context,
    message: PushMessage,
    candidateImport: PairingRegistrationGate.ImportBinding? = null,
    pairingEndpointRegistration: UnifiedPushValues.PairingEndpointRegistration? = null,
  ): EncryptedHandling? {
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
    val receiverState = if (candidateImport?.receiverId != null) {
      ReceiverControlState.parse(values.v4PendingReceiverState)
    } else ReceiverControlState.parse(values.v4ReceiverState)
    val currentRegistration = candidateImport?.let {
      EndpointUpdates.RegistrationContext(it.accountId, it.deviceId, it.instance)
    } ?: currentContext?.let {
      EndpointUpdates.RegistrationContext(it.accountId, it.pushDevice?.deviceId ?: -1, it.instance)
    }
    var effect = EndpointUpdates.Effect.NONE
    val now = System.currentTimeMillis() / 1000
    val state = EncryptedPush.ReplayState.parse(values.replayState)
    if (state == null) {
      Log.w(TAG, "Replay state unavailable. Encrypted push dropped.")
      return null
    }
    val activeInstance = candidateImport?.instance ?: currentContext?.instance ?: values.endpointInstance ?: "default"
    val activeV4 = candidateImport == null && receiverState != null && receiverState.instance == activeInstance
    val outcome = EncryptedPush.decide(
      content = message.content,
      decrypted = message.decrypted,
      now = now,
      locked = { appLocked },
      activeInstance = activeInstance,
      acceptedEpochs = when {
        candidateImport != null -> setOf(candidateImport.epoch)
        activeV4 -> setOfNotNull(receiverState?.epoch, receiverState?.pendingEpoch)
        else -> EndpointUpdates.acceptedEpochs(snapshot)
      },
      state = state,
      versionsForEpoch = { epoch -> when {
        candidateImport?.receiverId != null && epoch == candidateImport.epoch -> setOf(4)
        candidateImport != null && epoch == candidateImport.epoch -> setOf(3)
        activeV4 && (receiverState?.epoch == epoch || receiverState?.pendingEpoch == epoch) -> setOf(4)
        activeV4 -> emptySet()
        else -> EndpointUpdates.versionsForEpoch(snapshot, epoch)
      } },
      identityMatches = { event ->
        if (event.version == 4) receiverState?.let { receiver ->
          currentContext?.accountId?.let { receiver.identityMatches(event, it) } == true
        } == true && (candidateImport?.receiverId != null || activeV4)
        else !activeV4 && EndpointUpdates.identityMatches(snapshot, event, if (event.version == 3) currentRegistration else null)
      },
    )
    if (!outcome.decision.accepted) {
      Log.d(TAG, "Encrypted push: ${outcome.decision}")
    }
    if (outcome.decision.accepted) {
      // Replay and intent promotion share one acknowledged write before any effect starts.
      val event = outcome.event!!
      if (candidateImport != null) {
        val matchingCandidate = if (candidateImport.receiverId == null) {
          event.version == 3 && event.keyId == candidateImport.keyId
        } else {
          event.version == 4 && event.receiverId == candidateImport.receiverId
        }
        if (!matchingCandidate || event.kind != EncryptedPush.Kind.STATUS ||
          event.code != EncryptedPush.CODE_ENROLLMENT_IMPORTED || event.importId != candidateImport.requestId ||
          event.epoch != candidateImport.epoch || event.instance != candidateImport.instance) return null
      }
      effect = try {
        if (event.version == 4) {
          val nextReceiver = receiverState?.accepted(event, currentContext?.accountId ?: return null) ?: return null
          values.mutateControlWithReceiverAndReplay(
            outcome.state.serialize(), nextReceiver.serialize(), clearPendingV4ReceiverState = candidateImport?.receiverId != null,
            pairingEndpointRegistration = pairingEndpointRegistration,
          )
          when {
            event.code == EncryptedPush.CODE_ENROLLMENT_IMPORTED -> EndpointUpdates.Effect.PROMOTED
            event.code == EncryptedPush.CODE_SUBSCRIPTION_UPDATED && receiverState.pendingEpoch == event.epoch &&
              nextReceiver.epoch == event.epoch && nextReceiver.pendingEpoch == null &&
              nextReceiver.target == receiverState.pendingTarget -> EndpointUpdates.Effect.CONFIRMED
            else -> EndpointUpdates.Effect.NONE
          }
        } else EndpointUpdates.accepted(controlStore, outcome.state.serialize(), event, if (event.version == 3) currentRegistration else null)
      } catch (e: KeyValuePersistenceException) {
        Log.w(TAG, "Encrypted push state persistence was not acknowledged. Push dropped.", e)
        return null
      }
      if (effect == EndpointUpdates.Effect.PROMOTED) LockedPushState.setEncryptedEnrollment(context, true)
    }
    return EncryptedHandling(outcome, effect)
  }

  /** Candidate transition admission is a read-only exact check; the same message is rechecked before commit. */
  private fun candidateConfirmationPreflightLocked(
    message: PushMessage,
    import: PairingRegistrationGate.ImportBinding,
  ): Boolean {
    check(Thread.holdsLock(UnifiedPushLifecycle.monitor))
    if (!message.decrypted) return false
    val values = SignalStore.unifiedpush
    val controlStore = UnifiedPushControlStore(values)
    val control = try { controlStore.read() } catch (_: CorruptControlStateException) { return false }
    val replay = EncryptedPush.ReplayState.parse(values.replayState) ?: return false
    val receiver = if (import.receiverId != null) ReceiverControlState.parse(values.v4PendingReceiverState) else null
    if (import.receiverId != null && (receiver?.receiverId != import.receiverId || receiver.importId != import.requestId ||
        receiver.epoch != import.epoch || receiver.accountId != import.accountId || receiver.instance != import.instance ||
        receiver.target.endpoint != import.endpoint || receiver.target.p256dh != import.p256dh || receiver.target.auth != import.auth)) return false
    if (import.receiverId == null && control.intents.none { intent -> intent.eligible && intent.requestId == import.requestId &&
        intent.keyId == import.keyId && intent.epoch == import.epoch && intent.instance == import.instance &&
        intent.accountId == import.accountId && intent.deviceId == import.deviceId && intent.target == Target(import.endpoint, import.p256dh, import.auth) }) return false
    val outcome = EncryptedPush.decide(
      content = message.content,
      decrypted = true,
      now = System.currentTimeMillis() / 1000,
      locked = { appLocked },
      activeInstance = import.instance,
      acceptedEpochs = setOf(import.epoch),
      state = replay,
      versionsForEpoch = { epoch -> if (epoch == import.epoch) setOf(if (import.receiverId == null) 3 else 4) else emptySet() },
      identityMatches = { event -> if (import.receiverId != null) receiver!!.identityMatches(event, import.accountId)
        else EndpointUpdates.identityMatches(control, event, EndpointUpdates.RegistrationContext(import.accountId, import.deviceId, import.instance)) },
    )
    val event = outcome.event ?: return false
    return outcome.decision == EncryptedPush.Decision.ACCEPT_STATUS && event.kind == EncryptedPush.Kind.STATUS &&
      event.code == EncryptedPush.CODE_ENROLLMENT_IMPORTED && event.version == (if (import.receiverId == null) 3 else 4) &&
      (import.receiverId == null || event.receiverId == import.receiverId) &&
      (import.receiverId != null || event.keyId == import.keyId) && event.importId == import.requestId &&
      event.epoch == import.epoch && event.instance == import.instance
  }

  private fun ReceiverControlState.toImportBinding() = PairingRegistrationGate.ImportBinding(
    requestId = importId,
    keyId = "",
    epoch = epoch,
    endpoint = target.endpoint,
    p256dh = target.p256dh,
    auth = target.auth,
    accountId = accountId,
    deviceId = bridgeDeviceId,
    instance = instance,
    receiverId = receiverId,
  )

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
        if (PlainTextKeyValueStore.lockNotificationsEnabled) {
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
      if (SignalStore.account.isRegistered && (SignalStore.unifiedpush.enabled || hasConfirmedLinkedV4Route())) {
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

  private fun hasConfirmedLinkedV4Route(): Boolean {
    val values = SignalStore.unifiedpush
    val receiver = ReceiverControlState.parse(values.v4ReceiverState) ?: return false
    val binding = values.pairingEndpointBinding ?: return false
    val accountId = SignalStore.account.aci?.toString() ?: return false
    return SignalStore.account.isLinkedDevice && receiver.confirmed && receiver.accountId == accountId &&
      receiver.instance == values.endpointInstance && receiver.instance == binding.instance &&
      receiver.importId == binding.importId && binding.accountId == accountId &&
      binding.deviceId == SignalStore.account.deviceId && values.endpoint != null
  }

  private fun updateLastReceivedTime(timestamp: Long) {
    SignalStore.unifiedpush.lastReceivedTime = timestamp
  }
}
