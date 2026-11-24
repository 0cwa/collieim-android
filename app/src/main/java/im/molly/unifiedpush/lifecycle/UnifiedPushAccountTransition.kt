package im.molly.unifiedpush.lifecycle

import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.keyvalue.UnifiedPushValues
import org.whispersystems.signalservice.api.push.SignalServiceAddress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.Callable
import org.signal.core.util.concurrent.SignalExecutors
import org.signal.core.util.logging.Log
import org.signal.storage.keyvalue.KeyValuePersistenceException
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.jobs.UnifiedPushRefreshJob
import im.molly.unifiedpush.receiver.UnifiedPushReceiver

/** SignalStore-facing setup for the dependency-free lifecycle coordinator. */
object UnifiedPushAccountTransition {

  private val TAG = Log.tag(UnifiedPushAccountTransition::class)

  /** Snapshot captured under [UnifiedPushLifecycle.monitor] before a credentialed push operation begins. */
  data class AccountContext(
    val accountId: String,
    val localDeviceId: Int,
    val pushDevice: im.molly.unifiedpush.model.MollySocketDevice?,
    val endpoint: String?,
    val endpointP256dh: String?,
    val endpointAuth: String?,
    val instance: String,
  ) {
    override fun toString() = "AccountContext(localDeviceId=$localDeviceId, instance=$instance)"
  }

  /** Opaque, process-scoped authorization carried by UnifiedPush-origin message fetch work. */
  data class FetchAuthorization internal constructor(
    val processInstanceId: String,
    val generation: Long,
    val accountId: String,
    val localDeviceId: Int,
    val pushDeviceId: Int,
    val instance: String,
  ) {
    override fun toString() = "FetchAuthorization(generation=$generation, localDeviceId=$localDeviceId, instance=$instance)"
  }

  /** Legacy stores have no transition marker. They remain usable only when registered identity and credentials agree. */
  @JvmStatic
  fun initializeFromSignalStore() {
    val values = SignalStore.unifiedpush
    val marker = values.hasIncompleteAccountTransition || values.pendingRelayCleanup != null
    UnifiedPushLifecycle.initialize(
      incompleteTransition = marker,
      legacyContextIsCoherent = !marker && hasCoherentRegisteredContext(),
    )
  }

  suspend fun <T> withTransition(
    expectedGeneration: Long? = null,
    keepIncompleteMarker: Boolean = false,
    completesAccountRegistration: Boolean = false,
    completesInvalidation: Boolean = false,
    readyAfter: () -> Boolean = ::hasCoherentRegisteredContext,
    block: suspend () -> T,
  ): T = withTransitionAndGeneration(
    expectedGeneration = expectedGeneration,
    keepIncompleteMarker = keepIncompleteMarker,
    completesAccountRegistration = completesAccountRegistration,
    completesInvalidation = completesInvalidation,
    readyAfter = readyAfter,
    block = block,
  ).value

  /**
   * Runs a transition and returns its generation. A non-null [relayCleanup] selects a credential-only
   * transition. The provider returns the owned intent when it is eligible for retirement.
   */
  suspend fun <T> withTransitionAndGeneration(
    expectedGeneration: Long? = null,
    keepIncompleteMarker: Boolean = false,
    completesAccountRegistration: Boolean = false,
    completesInvalidation: Boolean = false,
    readyAfter: () -> Boolean = ::hasCoherentRegisteredContext,
    relayCleanup: (() -> UnifiedPushValues.RelayCleanup?)? = null,
    block: suspend () -> T,
  ): UnifiedPushLifecycle.TransitionResult<T> {
    val values = SignalStore.unifiedpush
    val result = UnifiedPushLifecycle.withTransitionAndGeneration(
      hasIncompleteMarker = { relayCleanup == null && values.hasIncompleteAccountTransition },
      persistIncompleteMarker = { pending ->
        if (relayCleanup == null) values.setIncompleteAccountTransition(pending)
        else if (!pending) {
          synchronized(UnifiedPushLifecycle.monitor) {
            val owned = relayCleanup()
            val current = values.pendingRelayCleanup
            if (owned != null && current != null && current.hasSameOwner(owned)) values.setRelayCleanup(current, pending = false)
          }
        }
      },
      readyAfter = { readyAfter() && (relayCleanup == null || !values.hasIncompleteAccountTransition) },
      expectedGeneration = expectedGeneration,
      keepIncompleteMarker = keepIncompleteMarker,
      completesAccountRegistration = completesAccountRegistration,
      completesInvalidation = completesInvalidation,
      invalidationGenerationToComplete = relayCleanup?.invoke()?.invalidationGeneration,
      credentialOnly = relayCleanup != null,
      admissionAllowed = { values.pendingRelayCleanup == null },
      afterTransitionStarted = {
        if (AppDependencies.isInitialized) {
          AppDependencies.incomingMessageObserver.notifyUnifiedPushFetchStateChanged()
        }
      },
      block = block,
    )
    val canProcessEndpoint = synchronized(UnifiedPushLifecycle.monitor) {
      UnifiedPushLifecycle.isReadyLocked() ||
        (UnifiedPushLifecycle.hasPendingEndpointInvalidationLocked() && values.pendingRelayCleanup == null &&
          !values.hasIncompleteAccountTransition && hasCoherentRegisteredContext())
    }
    if (!keepIncompleteMarker && !completesInvalidation && canProcessEndpoint && AppDependencies.isInitialized) {
      SignalExecutors.BOUNDED.execute { UnifiedPushReceiver.applyPendingEndpoint(AppDependencies.application) }
    }
    return result
  }

  /** Acquires the account and route atomically before any asynchronous push work starts. */
  fun acquireLease(expectedGeneration: Long? = null): UnifiedPushLifecycle.Lease<AccountContext>? =
    UnifiedPushLifecycle.acquireLease(expectedGeneration, ::captureContextLocked)

  /**
   * Removes a user-selected linked device. When it is the locally saved relay, serialize the remote
   * removal with UnifiedPush work and clear that exact saved relay only after the server confirms removal.
   */
  suspend fun removeLinkedDeviceAndClearRelayIfMatching(
    deviceId: Int,
    removeDevice: suspend (Int) -> Boolean,
  ): Boolean {
    val (mightBeSavedRelay, context) = synchronized(UnifiedPushLifecycle.monitor) {
      (SignalStore.unifiedpush.device?.deviceId == deviceId) to captureContextLocked()
    }
    if (!mightBeSavedRelay) return removeDevice(deviceId)
    val savedRelay = context?.pushDevice ?: return false

    return withContext(NonCancellable) {
      val values = SignalStore.unifiedpush
      var intent: UnifiedPushValues.RelayCleanup? = null
      var completedIntent: UnifiedPushValues.RelayCleanup? = null
      try {
        withTransitionAndGeneration(relayCleanup = { completedIntent }) {
          val cleanup = synchronized(UnifiedPushLifecycle.monitor) {
            if (values.hasIncompleteAccountTransition || values.pendingRelayCleanup != null ||
              !accountAndLocalDeviceMatchLocked(context) || values.device != savedRelay
            ) return@withTransitionAndGeneration false

            UnifiedPushValues.RelayCleanup(context.accountId, context.localDeviceId, savedRelay, unlinkConfirmed = false).also {
              intent = it
              values.setRelayCleanup(it, pending = true)
            }
          }

          val removed = try {
            removeDevice(deviceId)
          } catch (e: CancellationException) {
            throw e
          } catch (e: Exception) {
            Log.w(TAG, "Could not confirm removal of the saved relay")
            false
          }
          if (removed) {
            synchronized(UnifiedPushLifecycle.monitor) {
              clearConfirmedRelayCleanupLocked(cleanup)
              completedIntent = cleanup
            }
          }
          removed
        }.value
      } catch (e: KeyValuePersistenceException) {
        Log.w(TAG, "Could not persist saved relay cleanup", e)
        false
      } finally {
        val needsRecovery = synchronized(UnifiedPushLifecycle.monitor) {
          intent?.let { values.pendingRelayCleanup?.hasSameOwner(it) } == true
        }
        if (needsRecovery && AppDependencies.isInitialized) {
          AppDependencies.jobManager.add(UnifiedPushRefreshJob())
        }
      }
    }
  }

  /** Checks that the cleanup still owns the account and the exact saved credential. */
  internal fun ownsRelayCleanupLocked(cleanup: UnifiedPushValues.RelayCleanup): Boolean {
    val values = SignalStore.unifiedpush
    val current = values.device
    if (values.pendingRelayCleanup?.hasSameOwner(cleanup) != true ||
      !accountAndLocalDeviceMatchLocked(cleanup.accountId, cleanup.localDeviceId) ||
      (current != null && current != cleanup.device)
    ) return false
    if (values.hasIncompleteAccountTransition) throw IOException("UnifiedPush account context is transitioning")
    return true
  }

  /** Records remote confirmation before clearing the credential and its pending control update. */
  internal fun clearConfirmedRelayCleanupLocked(cleanup: UnifiedPushValues.RelayCleanup): Boolean {
    if (!ownsRelayCleanupLocked(cleanup)) return false
    val values = SignalStore.unifiedpush
    val current = values.device
    val clearControlUpdate = cleanup.clearControlUpdate || current != null
    if (!cleanup.unlinkConfirmed || clearControlUpdate != cleanup.clearControlUpdate) {
      values.setRelayCleanup(cleanup.copy(unlinkConfirmed = true, clearControlUpdate = clearControlUpdate), pending = true)
    }
    if (current != null) values.commitClearedDevice()
    // An earlier clear may have landed without acknowledgement. The intent still owns the control update.
    if (clearControlUpdate) values.mutateControl { it.copy(update = null) }
    return true
  }

  fun fetchAuthorization(lease: UnifiedPushLifecycle.Lease<AccountContext>): FetchAuthorization =
    FetchAuthorization(
      processInstanceId = UnifiedPushLifecycle.processInstanceId(),
      generation = lease.generation,
      accountId = lease.snapshot.accountId,
      localDeviceId = lease.snapshot.localDeviceId,
      pushDeviceId = lease.snapshot.pushDevice?.deviceId ?: -1,
      instance = lease.snapshot.instance,
    )

  /** Reacquires authorization at the actual fetch/fallback execution boundary. */
  @JvmStatic
  fun acquireFetchLease(authorization: FetchAuthorization): UnifiedPushLifecycle.Lease<AccountContext>? {
    if (authorization.processInstanceId != UnifiedPushLifecycle.processInstanceId()) return null
    return UnifiedPushLifecycle.acquireLease(authorization.generation) {
      if (!SignalStore.unifiedpush.enabled) return@acquireLease null
      captureContextLocked()?.takeIf {
        it.accountId == authorization.accountId &&
          it.localDeviceId == authorization.localDeviceId &&
          (it.pushDevice?.deviceId ?: -1) == authorization.pushDeviceId &&
          it.instance == authorization.instance
      }
    }
  }

  /** Registers the authorized fetch as observer demand while its lease pins the live context. */
  fun registerFetchAdmission(
    lease: UnifiedPushLifecycle.Lease<AccountContext>,
    authorization: FetchAuthorization,
  ): UnifiedPushLifecycle.FetchAdmission? {
    val context = lease.snapshot
    if (authorization.processInstanceId != UnifiedPushLifecycle.processInstanceId() ||
      authorization.generation != lease.generation || authorization.accountId != context.accountId ||
      authorization.localDeviceId != context.localDeviceId ||
      authorization.pushDeviceId != (context.pushDevice?.deviceId ?: -1) ||
      authorization.instance != context.instance) return null

    return UnifiedPushLifecycle.registerFetchAdmission(
      lease = lease,
      processId = authorization.processInstanceId,
      accountId = authorization.accountId,
      localDeviceId = authorization.localDeviceId,
      pushDeviceId = authorization.pushDeviceId,
      instance = authorization.instance,
    )
  }

  /** Claims queued demands only at the executing observer seam, rechecking live context under its monitor. */
  fun claimPendingFetchAdmissionsLocked(): List<UnifiedPushLifecycle.FetchAdmission> {
    check(Thread.holdsLock(UnifiedPushLifecycle.monitor))
    val context = if (SignalStore.unifiedpush.enabled && !SignalStore.account.isUnauthorizedReceived) {
      captureContextLocked()
    } else {
      null
    }
    return UnifiedPushLifecycle.claimPendingFetchAdmissionsLocked(
      processId = UnifiedPushLifecycle.processInstanceId(),
      accountId = context?.accountId ?: "",
      localDeviceId = context?.localDeviceId ?: -1,
      pushDeviceId = context?.pushDevice?.deviceId ?: -2,
      instance = context?.instance ?: "",
    )
  }

  /** Removes observer demand after drain, timeout or revocation, then wakes the observer outside the monitor. */
  fun finishFetchAdmission(admission: UnifiedPushLifecycle.FetchAdmission) {
    UnifiedPushLifecycle.finishFetchAdmission(admission)
    if (AppDependencies.isInitialized) {
      AppDependencies.incomingMessageObserver.notifyUnifiedPushFetchStateChanged()
    }
  }

  /** Reads only the registration flag while the same monitor that gates identity writers is held. */
  fun isRegisteredLocked(): Boolean {
    check(Thread.holdsLock(UnifiedPushLifecycle.monitor))
    return SignalStore.account.isRegistered
  }

  @JvmStatic
  fun <T> withTransitionBlocking(
    keepIncompleteMarker: Boolean = false,
    completesInvalidation: Boolean = false,
    expectedGeneration: Long? = null,
    block: () -> T,
  ): T = runBlocking {
    withTransition(
      keepIncompleteMarker = keepIncompleteMarker,
      completesInvalidation = completesInvalidation,
      expectedGeneration = expectedGeneration,
    ) { block() }
  }

  @JvmStatic
  fun runTransitionBlocking(keepIncompleteMarker: Boolean, block: Runnable) {
    withTransitionBlocking(keepIncompleteMarker) {
      block.run()
      Unit
    }
  }

  @JvmStatic
  @Throws(Exception::class)
  fun <T> callTransitionBlocking(keepIncompleteMarker: Boolean, block: Callable<T>): T = runBlocking {
    withTransition(keepIncompleteMarker = keepIncompleteMarker) { block.call() }
  }

  /** Returns a coherent account and UnifiedPush route snapshot while the shared lifecycle monitor is held. */
  fun captureContextLocked(): AccountContext? {
    check(Thread.holdsLock(UnifiedPushLifecycle.monitor))
    val account = SignalStore.account
    if (!account.isRegistered || !account.hasAciIdentityKey()) return null
    val aci = account.aci ?: return null
    if (account.servicePassword == null || account.deviceId < SignalServiceAddress.DEFAULT_DEVICE_ID) return null
    val values = SignalStore.unifiedpush
    return AccountContext(
      accountId = aci.toString(),
      localDeviceId = account.deviceId,
      pushDevice = values.device,
      endpoint = values.endpoint,
      endpointP256dh = values.endpointP256dh,
      endpointAuth = values.endpointAuth,
      instance = values.endpointInstance ?: "default",
    )
  }

  fun hasCoherentRegisteredContext(): Boolean {
    val account = SignalStore.account
    return account.isRegistered &&
      account.aci != null &&
      account.hasAciIdentityKey() &&
      account.servicePassword != null &&
      account.deviceId >= SignalServiceAddress.DEFAULT_DEVICE_ID
  }

  /** Complete account-context equality for an operation that must publish a result. */
  fun contextStillMatchesLocked(expected: AccountContext): Boolean {
    check(Thread.holdsLock(UnifiedPushLifecycle.monitor))
    return captureContextLocked() == expected
  }

  /** Account/device match used to decide whether a remote device-creation result can be published. */
  fun accountAndLocalDeviceMatchLocked(expected: AccountContext): Boolean {
    check(Thread.holdsLock(UnifiedPushLifecycle.monitor))
    return accountAndLocalDeviceMatchLocked(expected.accountId, expected.localDeviceId)
  }

  fun accountAndLocalDeviceMatchLocked(accountId: String, localDeviceId: Int): Boolean {
    check(Thread.holdsLock(UnifiedPushLifecycle.monitor))
    val account = SignalStore.account
    return account.isRegistered && account.aci?.toString() == accountId && account.deviceId == localDeviceId &&
      account.hasAciIdentityKey() && account.servicePassword != null
  }
}
