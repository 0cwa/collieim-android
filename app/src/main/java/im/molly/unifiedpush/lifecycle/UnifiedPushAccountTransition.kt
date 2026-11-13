package im.molly.unifiedpush.lifecycle

import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.keyvalue.UnifiedPushValues
import org.whispersystems.signalservice.api.push.SignalServiceAddress
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.concurrent.Callable
import org.signal.core.util.concurrent.SignalExecutors
import org.thoughtcrime.securesms.dependencies.AppDependencies
import im.molly.unifiedpush.receiver.UnifiedPushReceiver

/** SignalStore-facing setup for the dependency-free lifecycle coordinator. */
object UnifiedPushAccountTransition {

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
    val marker = values.hasIncompleteAccountTransition
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

  /** Runs a transition and returns the generation owned by that transition, not a later global sample. */
  suspend fun <T> withTransitionAndGeneration(
    expectedGeneration: Long? = null,
    keepIncompleteMarker: Boolean = false,
    completesAccountRegistration: Boolean = false,
    completesInvalidation: Boolean = false,
    readyAfter: () -> Boolean = ::hasCoherentRegisteredContext,
    block: suspend () -> T,
  ): UnifiedPushLifecycle.TransitionResult<T> {
    val result = UnifiedPushLifecycle.withTransitionAndGeneration(
      hasIncompleteMarker = SignalStore.unifiedpush::hasIncompleteAccountTransition,
      persistIncompleteMarker = SignalStore.unifiedpush::setIncompleteAccountTransition,
      readyAfter = readyAfter,
      expectedGeneration = expectedGeneration,
      keepIncompleteMarker = keepIncompleteMarker,
      completesAccountRegistration = completesAccountRegistration,
      completesInvalidation = completesInvalidation,
      afterTransitionStarted = {
        if (AppDependencies.isInitialized) {
          AppDependencies.incomingMessageObserver.notifyUnifiedPushFetchStateChanged()
        }
      },
      block = block,
    )
    if (!keepIncompleteMarker && !completesInvalidation && UnifiedPushLifecycle.isReady() && AppDependencies.isInitialized) {
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
    val mightBeSavedRelay = synchronized(UnifiedPushLifecycle.monitor) {
      SignalStore.unifiedpush.device?.deviceId == deviceId
    }
    if (!mightBeSavedRelay) return removeDevice(deviceId)

    return withContext(NonCancellable) {
      withTransition {
        val context = synchronized(UnifiedPushLifecycle.monitor) { captureContextLocked() }
        val savedRelay = context?.pushDevice?.takeIf { it.deviceId == deviceId }
        val removed = removeDevice(deviceId)

        if (removed && context != null && savedRelay != null) {
          synchronized(UnifiedPushLifecycle.monitor) {
            if (accountAndLocalDeviceMatchLocked(context) && SignalStore.unifiedpush.device == savedRelay) {
              SignalStore.unifiedpush.device = null
              SignalStore.unifiedpush.mutateControl { it.copy(update = null) }
            }
          }
        }

        removed
      }
    }
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
    val account = SignalStore.account
    return account.isRegistered && account.aci?.toString() == expected.accountId && account.deviceId == expected.localDeviceId &&
      account.hasAciIdentityKey() && account.servicePassword != null
  }
}
