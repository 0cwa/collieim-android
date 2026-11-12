package im.molly.unifiedpush.lifecycle

import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.keyvalue.UnifiedPushValues
import org.whispersystems.signalservice.api.push.SignalServiceAddress
import kotlinx.coroutines.runBlocking
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
    block: suspend () -> T,
  ): T {
    val result = UnifiedPushLifecycle.withTransition(
      persistIncompleteMarker = SignalStore.unifiedpush::setIncompleteAccountTransition,
      readyAfter = ::hasCoherentRegisteredContext,
      expectedGeneration = expectedGeneration,
      keepIncompleteMarker = keepIncompleteMarker,
      block = block,
    )
    if (!keepIncompleteMarker && UnifiedPushLifecycle.isReady() && AppDependencies.isInitialized) {
      SignalExecutors.BOUNDED.execute { UnifiedPushReceiver.applyPendingEndpoint(AppDependencies.application) }
    }
    return result
  }

  /** Acquires the account and route atomically before any asynchronous push work starts. */
  fun acquireLease(expectedGeneration: Long? = null): UnifiedPushLifecycle.Lease<AccountContext>? =
    UnifiedPushLifecycle.acquireLease(expectedGeneration, ::captureContextLocked)

  /** Reads only the registration flag while the same monitor that gates identity writers is held. */
  fun isRegisteredLocked(): Boolean {
    check(Thread.holdsLock(UnifiedPushLifecycle.monitor))
    return SignalStore.account.isRegistered
  }

  @JvmStatic
  fun <T> withTransitionBlocking(keepIncompleteMarker: Boolean, block: () -> T): T = runBlocking {
    withTransition(keepIncompleteMarker = keepIncompleteMarker) { block() }
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
