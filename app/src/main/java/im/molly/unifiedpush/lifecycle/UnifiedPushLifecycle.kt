package im.molly.unifiedpush.lifecycle

import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Orders UnifiedPush effects against account identity changes. The monitor is also the receiver's replay lock.
 * This object intentionally has no SignalStore or AppDependencies access; callers supply persistence and snapshots.
 */
object UnifiedPushLifecycle {

  /**
   * Serializes credential-call admission with an account rejection. This is separate from structural
   * leases: cleanup may still need those after credential sends have been stopped. Callers must bind
   * [epoch] to the authenticated connection attempt and revalidate the route before starting a call.
   * The start operation must only enqueue work; cancellation happens after the handoff lock is released.
   */
  internal class CredentialSendHandoff {
    internal interface Call {
      fun enqueue()
      fun cancel()
    }

    class AccountLease<T : Any> internal constructor(
      internal val owner: CredentialSendHandoff,
      val epoch: Long,
      val snapshot: T,
    ) : AutoCloseable {
      internal var closed = false
      override fun close() = owner.releaseAccount(this)
    }

    private val lock = Any()
    private var epoch: Long? = null
    private var attempt: Long? = null
    private var attemptRejected = false
    private var changingAccount = false
    private var admitted = false
    private var accountReady = false
    private var accountLeaseCount = 0
    private var drain: CountDownLatch? = null
    private var authorizationVersion = Any()
    private val calls = LinkedHashMap<Call, Any>()

    /** Close admission and cancel old calls; the owner must drain work before completing the change. */
    fun beginAccountChange(newEpoch: Long) {
      checkLockOrder()
      val revoked = synchronized(lock) {
        check(!changingAccount) { "Account ownership change is incomplete" }
        require(newEpoch > 0 && (epoch == null || newEpoch > checkNotNull(epoch))) { "Account epoch must advance" }
        val old = calls.keys.toList()
        authorizationVersion = Any()
        epoch = newEpoch
        attempt = null
        attemptRejected = false
        admitted = false
        accountReady = false
        changingAccount = true
        old
      }
      cancelAll(revoked)
    }

    /** Call only after old structural/account leases have drained and the new account is coherent. */
    fun completeAccountChange(newEpoch: Long, allowSends: Boolean) {
      checkLockOrder()
      synchronized(lock) {
        check(epoch == newEpoch && changingAccount && calls.isEmpty() && accountLeaseCount == 0) { "Old account work has not drained" }
        admitted = allowSends
        accountReady = true
        changingAccount = false
      }
    }

    /** Account callbacks remain available when only the push route is unavailable; capture must only read local state. */
    fun <T : Any> acquireAccountLease(expectedEpoch: Long? = null, capture: () -> T?): AccountLease<T>? {
      checkLockOrder()
      return synchronized(lock) {
        val currentEpoch = epoch
        if (changingAccount || !accountReady || currentEpoch == null || (expectedEpoch != null && currentEpoch != expectedEpoch)) return@synchronized null
        val snapshot = synchronized(monitor) { capture() } ?: return@synchronized null
        accountLeaseCount++
        AccountLease(this, currentEpoch, snapshot)
      }
    }

    /** Wait outside both locks after closing account admission and cancelling old calls. */
    @Throws(InterruptedException::class)
    fun awaitAccountWorkDrained() {
      checkLockOrder()
      val signal = synchronized(lock) {
        check(changingAccount) { "Account ownership is not changing" }
        if (calls.isEmpty() && accountLeaseCount == 0) null else drain ?: CountDownLatch(1).also { drain = it }
      }
      signal?.await()
    }

    /** Register and enqueue under one handoff; [validUnderLifecycleMonitor] must be a short local check. */
    fun start(expectedEpoch: Long, call: Call, validUnderLifecycleMonitor: () -> Boolean): Boolean {
      checkLockOrder()
      var enqueueFailure: Throwable? = null
      val started = synchronized(lock) {
        if (changingAccount || !admitted || epoch != expectedEpoch) return@synchronized false
        val valid = synchronized(monitor) { validUnderLifecycleMonitor() }
        if (!valid) return@synchronized false
        check(!calls.containsKey(call)) { "Credential call already admitted" }
        calls[call] = authorizationVersion
        try {
          call.enqueue()
          true
        } catch (failure: Throwable) {
          // Enqueue failure alone does not prove the underlying call never started. Keep the
          // cleanup token until its owner has evidence that the call is terminal.
          admitted = false
          enqueueFailure = failure
          false
        }
      }
      enqueueFailure?.let { failure ->
        try { call.cancel() } catch (cancelFailure: Throwable) { failure.addSuppressed(cancelFailure) }
        throw failure
      }
      return started
    }

    fun finish(call: Call) {
      checkLockOrder()
      synchronized(lock) { calls.remove(call); signalDrainedLocked() }
    }

    /** A revoked call stays stale after reauthorization. A later publication still needs atomic ownership. */
    fun isCurrent(expectedEpoch: Long, call: Call, validUnderLifecycleMonitor: () -> Boolean): Boolean {
      checkLockOrder()
      return synchronized(lock) {
        if (changingAccount || !admitted || epoch != expectedEpoch || calls[call] !== authorizationVersion) return@synchronized false
        synchronized(monitor) { validUnderLifecycleMonitor() }
      }
    }

    private fun releaseAccount(lease: AccountLease<*>) {
      checkLockOrder()
      synchronized(lock) {
        if (lease.closed) return
        lease.closed = true
        accountLeaseCount--
        check(accountLeaseCount >= 0)
        signalDrainedLocked()
      }
    }

    private fun signalDrainedLocked() {
      if (calls.isEmpty() && accountLeaseCount == 0) {
        drain?.countDown()
        drain = null
      }
    }

    /** Registers the particular connection attempt whose result may alter send authorization. */
    fun beginAttempt(expectedEpoch: Long, attemptId: Long): Boolean {
      checkLockOrder()
      return synchronized(lock) {
        if (changingAccount || epoch != expectedEpoch || attemptId <= 0 || (attempt != null && attemptId <= checkNotNull(attempt))) return@synchronized false
        attempt = attemptId
        attemptRejected = false
        true
      }
    }

    fun authorize(expectedEpoch: Long, attemptId: Long): Boolean {
      checkLockOrder()
      return synchronized(lock) {
        if (changingAccount || epoch != expectedEpoch || attempt != attemptId || attemptRejected) return@synchronized false
        admitted = true
        true
      }
    }

    /** A stale connection result must never revoke a successor account or connection attempt. */
    fun reject(expectedEpoch: Long, attemptId: Long): Boolean {
      checkLockOrder()
      val revoked = synchronized(lock) {
        if (changingAccount || epoch != expectedEpoch || attempt != attemptId || attemptRejected) return false
        attemptRejected = true
        admitted = false
        authorizationVersion = Any()
        calls.keys.toList()
      }
      cancelAll(revoked)
      return true
    }

    private fun cancelAll(revoked: List<Call>) {
      var firstFailure: Throwable? = null
      revoked.forEach { call ->
        try { call.cancel() } catch (failure: Throwable) {
          if (firstFailure == null) firstFailure = failure else firstFailure?.addSuppressed(failure)
        }
      }
      firstFailure?.let { throw it }
    }

    private fun checkLockOrder() {
      check(!Thread.holdsLock(monitor)) { "Credential handoff must precede the lifecycle monitor" }
    }
  }

  @JvmField
  val monitor = Any()

  private val transitionWriter = Semaphore(1, true)
  private val processInstanceId = UUID.randomUUID().toString()

  private var initialized = false
  private var ready = false
  private var generation = 0L
  private var transitionGeneration: Long? = null
  private var invalidationPending = false
  private var pendingEndpointToken: String? = null
  private var consumedEndpointToken: String? = null
  private var leaseCount = 0
  private var drainSignal: CountDownLatch? = null
  private val pendingFetchAdmissions = LinkedHashSet<FetchAdmission>()
  private val activeFetchAdmissions = LinkedHashSet<FetchAdmission>()

  private data class TransitionOwnership(
    val generation: Long,
    val inheritedIncompleteMarker: Boolean,
  )

  internal class WriterReservation internal constructor(val generation: Long) : AutoCloseable {
    // 0 reserved, 1 claimed, 2 close requested while claimed, 3 released.
    private val state = AtomicInteger(0)
    internal fun claim() {
      check(state.compareAndSet(0, 1)) { "UnifiedPush writer reservation already used or released" }
    }
    internal fun finishClaim() {
      while (true) {
        val current = state.get()
        check(current == 1 || current == 2) { "UnifiedPush writer claim is not active" }
        if (state.compareAndSet(current, 3)) {
          transitionWriter.release()
          return
        }
      }
    }
    override fun close() {
      while (true) {
        when (state.get()) {
          0 -> if (state.compareAndSet(0, 3)) { transitionWriter.release(); return }
          1 -> if (state.compareAndSet(1, 2)) return
          else -> return
        }
      }
    }
  }

  class Lease<T : Any> internal constructor(
    internal val owner: UnifiedPushLifecycle,
    val generation: Long,
    val snapshot: T,
  ) : AutoCloseable {
    internal var closed = false
    override fun close() = owner.release(this)
  }

  enum class FetchAdmissionState { PENDING, CLAIMED, ADMITTED, REVOKED, FAILED, TIMED_OUT }

  /** One UnifiedPush fetch awaiting handoff to the executing ordinary receive observer. */
  class FetchAdmission internal constructor(
    val processInstanceId: String,
    val generation: Long,
    val accountId: String,
    val localDeviceId: Int,
    val pushDeviceId: Int,
    val instance: String,
  ) {
    private val handoff = CountDownLatch(1)
    private val cancellationWaiters = CopyOnWriteArrayList<CountDownLatch>()

    @Volatile
    var transitionRevoked: Boolean = false
      private set

    @Volatile
    var state: FetchAdmissionState = FetchAdmissionState.PENDING
      internal set

    @Throws(InterruptedException::class)
    fun awaitHandoff(timeoutMs: Long): Boolean = handoff.await(timeoutMs, TimeUnit.MILLISECONDS)

    @Throws(InterruptedException::class)
    fun awaitHandoffCompletion() = handoff.await()

    internal fun signalHandoff() = handoff.countDown()

    fun addTransitionCancellationWaiter(waiter: CountDownLatch) {
      cancellationWaiters.add(waiter)
      if (transitionRevoked) waiter.countDown()
    }

    fun removeTransitionCancellationWaiter(waiter: CountDownLatch) {
      cancellationWaiters.remove(waiter)
    }

    internal fun revokeForTransition() {
      transitionRevoked = true
      cancellationWaiters.forEach(CountDownLatch::countDown)
    }

    override fun toString() = "FetchAdmission(generation=$generation, localDeviceId=$localDeviceId, instance=$instance, state=$state)"
  }

  /** Result tied to the generation owned by one completed transition. */
  data class TransitionResult<T>(
    val value: T,
    val generation: Long,
    val retainedMarker: Boolean,
  )

  /** Called after SignalStore initialization and before startup jobs or push work can run. */
  @JvmStatic
  fun initialize(incompleteTransition: Boolean, legacyContextIsCoherent: Boolean, pendingEndpoint: String? = null) {
    synchronized(monitor) {
      check(leaseCount == 0 && transitionGeneration == null) { "Cannot initialize UnifiedPush lifecycle while work is active" }
      initialized = true
      generation = nextGeneration(generation)
      invalidationPending = pendingEndpoint != null
      pendingEndpointToken = pendingEndpoint
      consumedEndpointToken = null
      ready = !incompleteTransition && legacyContextIsCoherent && pendingEndpoint == null
    }
  }

  /** Creates a lease and captures all caller-supplied context while the lifecycle monitor is held. */
  fun <T : Any> acquireLease(
    expectedGeneration: Long? = null,
    capture: () -> T?,
  ): Lease<T>? = synchronized(monitor) {
    if (!initialized || !ready || transitionGeneration != null || (expectedGeneration != null && generation != expectedGeneration)) {
      return@synchronized null
    }

    val snapshot = capture() ?: return@synchronized null
    leaseCount++
    Lease(this, generation, snapshot)
  }

  /** Registers one UP demand while its execution lease still pins the authorized account context. */
  fun registerFetchAdmission(
    lease: Lease<*>,
    processId: String,
    accountId: String,
    localDeviceId: Int,
    pushDeviceId: Int,
    instance: String,
  ): FetchAdmission? = synchronized(monitor) {
    if (!isReadyLocked() || lease.closed || lease.generation != generation || processId != processInstanceId) return@synchronized null
    FetchAdmission(processId, generation, accountId, localDeviceId, pushDeviceId, instance).also(pendingFetchAdmissions::add)
  }

  fun hasPendingFetchAdmissions(): Boolean = synchronized(monitor) { pendingFetchAdmissions.isNotEmpty() }

  fun hasActiveFetchAdmissions(): Boolean = synchronized(monitor) { activeFetchAdmissions.isNotEmpty() }

  /** Must be called at the observer receive seam while [monitor] is held. */
  fun claimPendingFetchAdmissionsLocked(
    processId: String,
    accountId: String,
    localDeviceId: Int,
    pushDeviceId: Int,
    instance: String,
  ): List<FetchAdmission> {
    check(Thread.holdsLock(monitor))
    if (!isReadyLocked()) return emptyList()

    val claimed = mutableListOf<FetchAdmission>()
    for (admission in pendingFetchAdmissions.toList()) {
      pendingFetchAdmissions.remove(admission)
      if (admission.generation == generation && admission.processInstanceId == processId &&
        admission.accountId == accountId && admission.localDeviceId == localDeviceId &&
        admission.pushDeviceId == pushDeviceId && admission.instance == instance &&
        admission.state == FetchAdmissionState.PENDING) {
        admission.state = FetchAdmissionState.CLAIMED
        activeFetchAdmissions.add(admission)
        claimed += admission
      } else {
        admission.state = FetchAdmissionState.REVOKED
        admission.signalHandoff()
        admission.revokeForTransition()
      }
    }
    return claimed
  }

  fun revokePendingFetchAdmissionsLocked() {
    check(Thread.holdsLock(monitor))
    for (admission in pendingFetchAdmissions) {
      admission.state = FetchAdmissionState.REVOKED
      admission.signalHandoff()
      admission.revokeForTransition()
    }
    pendingFetchAdmissions.clear()
  }

  fun completeFetchAdmission(admission: FetchAdmission): Boolean = synchronized(monitor) {
    if (admission.state != FetchAdmissionState.CLAIMED) return@synchronized false
    admission.state = FetchAdmissionState.ADMITTED
    admission.signalHandoff()
    true
  }

  fun failFetchAdmission(admission: FetchAdmission) = synchronized(monitor) {
    if (admission.state == FetchAdmissionState.CLAIMED) {
      admission.state = FetchAdmissionState.FAILED
      activeFetchAdmissions.remove(admission)
      admission.signalHandoff()
    }
  }

  /** A timeout can cancel only a request the observer has not claimed. */
  fun timeoutFetchAdmission(admission: FetchAdmission): FetchAdmissionState = synchronized(monitor) {
    if (admission.state == FetchAdmissionState.PENDING) {
      pendingFetchAdmissions.remove(admission)
      admission.state = FetchAdmissionState.TIMED_OUT
      admission.signalHandoff()
    }
    admission.state
  }

  /** Removes an admitted observer demand after its receive waiter and demand token have been cleaned up. */
  fun finishFetchAdmission(admission: FetchAdmission) = synchronized(monitor) {
    pendingFetchAdmissions.remove(admission)
    activeFetchAdmissions.remove(admission)
    if (admission.state == FetchAdmissionState.PENDING) {
      admission.state = FetchAdmissionState.TIMED_OUT
      admission.signalHandoff()
    } else if (admission.state == FetchAdmissionState.CLAIMED) {
      admission.state = FetchAdmissionState.FAILED
      admission.signalHandoff()
    }
  }

  /** Runs a short local publication only if this lease still belongs to the ready generation. */
  fun <T : Any> withCurrentLease(lease: Lease<*>, action: () -> T): T? = synchronized(monitor) {
    if (lease.closed || lease.generation != generation || !ready || transitionGeneration != null) null else action()
  }

  /**
   * Runs old-context cleanup while the lease still pins the pre-transition account. A transition cannot publish new
   * identity values until every such lease closes.
   */
  fun <T : Any> withLeasedContext(lease: Lease<*>, action: () -> T): T? = synchronized(monitor) {
    if (lease.closed || leaseCount == 0) null else action()
  }

  fun isCurrentGeneration(expectedGeneration: Long): Boolean = synchronized(monitor) {
    initialized && ready && transitionGeneration == null && generation == expectedGeneration
  }

  fun isReady(): Boolean = synchronized(monitor) { isReadyLocked() }

  /** Inspect durable transition state after any current writer has finished, without starting a transition. */
  internal suspend fun <T> readAfterTransitions(read: () -> T): T = withContext(NonCancellable + Dispatchers.IO) {
    transitionWriter.acquire()
    try {
      read()
    } finally {
      transitionWriter.release()
    }
  }

  /** Must be called from inside [monitor]. */
  fun isReadyLocked(): Boolean {
    check(Thread.holdsLock(monitor))
    return initialized && ready && transitionGeneration == null && !invalidationPending
  }

  /** Immediately revokes effect authorization; the caller must queue a transition that completes the invalidation. */
  fun invalidateLocked() {
    check(Thread.holdsLock(monitor))
    generation = nextGeneration(generation)
    ready = false
    invalidationPending = true
    revokeFetchAdmissionsForTransitionLocked()
  }

  /** Every accepted endpoint event gets an opaque token, including identical tuples. */
  internal fun noteEndpointPendingLocked(token: String) {
    check(Thread.holdsLock(monitor))
    require(token.isNotEmpty())
    pendingEndpointToken = token
    invalidateLocked()
  }

  /** Called only after the exact pending record was durably applied and conditionally removed. */
  internal fun noteEndpointConsumedLocked(token: String) {
    check(Thread.holdsLock(monitor))
    check(transitionGeneration != null)
    consumedEndpointToken = token
  }

  fun hasPendingInvalidationLocked(): Boolean {
    check(Thread.holdsLock(monitor))
    return invalidationPending
  }

  fun currentGeneration(): Long = synchronized(monitor) { generation }

  /** Reserve the writer without waiting while a lease is still held; callers release that lease before draining. */
  internal fun reserveTransitionForLeaseLocked(lease: Lease<*>): WriterReservation? {
    check(Thread.holdsLock(monitor))
    if (!isReadyLocked() || lease.closed || lease.generation != generation) return null
    val next = nextGeneration(generation)
    if (!transitionWriter.tryAcquire()) return null
    try {
      generation = next
      ready = false
      revokeFetchAdmissionsForTransitionLocked()
      return WriterReservation(generation)
    } catch (failure: Throwable) {
      transitionWriter.release()
      throw failure
    }
  }

  fun processInstanceId(): String = processInstanceId

  /**
   * Closes push admission, attempts to persist the incomplete transition, drains old work, and runs the mutation
   * without holding [monitor]. Exceptions leave this process unavailable. A durable marker cannot be guaranteed
   * if persistence fails, including when a failed clear cannot be repaired; callers must handle that indeterminate
   * recovery state. A caller staging registration can retain a successfully persisted marker with [keepIncompleteMarker].
   */
  suspend fun <T> withTransition(
    hasIncompleteMarker: () -> Boolean = { false },
    persistIncompleteMarker: (Boolean) -> Unit,
    readyAfter: () -> Boolean,
    expectedGeneration: Long? = null,
    keepIncompleteMarker: Boolean = false,
    completesAccountRegistration: Boolean = false,
    completesInvalidation: Boolean = false,
    retainMarkerWhenNotReady: Boolean = false,
    commitReceipt: AtomicReference<T?>? = null,
    afterTransitionStarted: () -> Unit = {},
    block: suspend () -> T,
  ): T = withTransitionAndGeneration(
    hasIncompleteMarker = hasIncompleteMarker,
    persistIncompleteMarker = persistIncompleteMarker,
    readyAfter = readyAfter,
    expectedGeneration = expectedGeneration,
    keepIncompleteMarker = keepIncompleteMarker,
    completesAccountRegistration = completesAccountRegistration,
    completesInvalidation = completesInvalidation,
    retainMarkerWhenNotReady = retainMarkerWhenNotReady,
    commitReceipt = commitReceipt,
    afterTransitionStarted = afterTransitionStarted,
    block = block,
  ).value

  /**
   * Like [withTransition], while retaining the generation assigned to this transition. Callers can
   * later reacquire work only for this result; reading [currentGeneration] after return could instead
   * authorize the operation under a competing account transition.
   */
  internal suspend fun <T> withTransitionAndGeneration(
    hasIncompleteMarker: () -> Boolean = { false },
    persistIncompleteMarker: (Boolean) -> Unit,
    readyAfter: () -> Boolean,
    expectedGeneration: Long? = null,
    keepIncompleteMarker: Boolean = false,
    completesAccountRegistration: Boolean = false,
    completesInvalidation: Boolean = false,
    retainMarkerWhenNotReady: Boolean = false,
    commitReceipt: AtomicReference<T?>? = null,
    afterTransitionStarted: () -> Unit = {},
    beforeWriterRelease: () -> Unit = {},
    writerReservation: WriterReservation? = null,
    requireCleanMarker: Boolean = false,
    block: suspend () -> T,
  ): TransitionResult<T> {
    require(!keepIncompleteMarker || !completesAccountRegistration) {
      "A transition cannot both retain the incomplete marker and complete registration"
    }
    require(writerReservation == null || expectedGeneration == writerReservation.generation) {
      "Reserved writer must use its own generation"
    }

    var ownership: TransitionOwnership? = null
    var reservationClaimed = false
    var transitionFailure: Throwable? = null
    var transitionPublished = false
    try {
      // Store ownership before leaving NonCancellable. withContext has a prompt cancellation guarantee when
      // dispatching back to the caller; cancellation there must still reach cleanup with the acquired token.
      withContext(NonCancellable + Dispatchers.IO) {
        writerReservation?.let { it.claim(); reservationClaimed = true }
        ownership = beginTransition(hasIncompleteMarker, persistIncompleteMarker, expectedGeneration, afterTransitionStarted, beforeWriterRelease, writerReservation != null, requireCleanMarker)
      }
      val token = checkNotNull(ownership)
      val result = block()
      var retainedMarker = false
      if (keepIncompleteMarker) {
        retainedMarker = true
        withContext(NonCancellable + Dispatchers.IO) {
          beforeWriterRelease()
          finishTransition(token, readyAfter = false, completesInvalidation = false)
          transitionPublished = true
          commitReceipt?.set(result)
        }
      } else {
        val canStartPushWork = readyAfter()
        val preserveInheritedMarker = token.inheritedIncompleteMarker && !completesAccountRegistration
        val incompleteFullRegistration = completesAccountRegistration && !canStartPushWork
        val incompleteOwnedOperation = retainMarkerWhenNotReady && !canStartPushWork
        if (preserveInheritedMarker || incompleteFullRegistration || incompleteOwnedOperation) {
          retainedMarker = true
          withContext(NonCancellable + Dispatchers.IO) {
            beforeWriterRelease()
            finishTransition(token, readyAfter = false, completesInvalidation = false)
            transitionPublished = true
            commitReceipt?.set(result)
          }
        } else {
          // The durable marker and in-memory publication are one non-cancellable commit point.
          // Otherwise prompt cancellation after the write could leave disk open while memory fails closed.
          withContext(NonCancellable + Dispatchers.IO) {
            try {
              persistIncompleteMarker(false)
              beforeWriterRelease()
              finishTransition(token, canStartPushWork, completesInvalidation || completesAccountRegistration)
              transitionPublished = true
              commitReceipt?.set(result)
            } catch (failure: Throwable) {
              // A persistence failure may have happened after the write reached disk. Attempt to
              // restore the closed marker before allowing this failed owner to release the writer.
              try {
                persistIncompleteMarker(true)
              } catch (repairFailure: Throwable) {
                if (repairFailure !== failure) failure.addSuppressed(repairFailure)
              }
              throw failure
            }
          }
        }
      }
      return TransitionResult(result, token.generation, retainedMarker)
    } catch (failure: Throwable) {
      transitionFailure = failure
      ownership?.let(::failTransition)
      throw failure
    } finally {
      try {
        if (ownership != null && !transitionPublished) withContext(NonCancellable) {
          withContext(Dispatchers.IO) { beforeWriterRelease() }
        }
      } catch (cleanupFailure: Throwable) {
        // The writer is held and this transition has not published readiness.
        synchronized(monitor) { ready = false }
        ownership?.let(::failTransition)
        if (transitionFailure != null) {
          if (cleanupFailure !== transitionFailure) transitionFailure.addSuppressed(cleanupFailure)
        } else {
          throw cleanupFailure
        }
      } finally {
        if (reservationClaimed) checkNotNull(writerReservation).finishClaim()
        else if (ownership != null && writerReservation == null) transitionWriter.release()
      }
    }
  }

  private fun beginTransition(
    hasIncompleteMarker: () -> Boolean,
    persistIncompleteMarker: (Boolean) -> Unit,
    expectedGeneration: Long?,
    afterTransitionStarted: () -> Unit,
    beforeWriterRelease: () -> Unit,
    writerReserved: Boolean,
    requireCleanMarker: Boolean,
  ): TransitionOwnership {
    if (!writerReserved) transitionWriter.acquire()
    var token: TransitionOwnership? = null
    try {
      val inheritedIncompleteMarker = hasIncompleteMarker()
      if (requireCleanMarker && inheritedIncompleteMarker) throw IncompleteAccountTransitionException()
      val drain = synchronized(monitor) {
        if (expectedGeneration != null && generation != expectedGeneration) {
          throw StaleLifecycleGenerationException()
        }
        val next = nextGeneration(generation)
        generation = next
        initialized = true
        ready = false
        transitionGeneration = next
        consumedEndpointToken = null
        token = TransitionOwnership(next, inheritedIncompleteMarker)
        revokeFetchAdmissionsForTransitionLocked()
        if (leaseCount == 0) null else CountDownLatch(1).also { drainSignal = it }
      }

      try {
        persistIncompleteMarker(true)
      } catch (persistenceFailure: Throwable) {
        try {
          afterTransitionStarted()
        } catch (notificationFailure: Throwable) {
          if (notificationFailure !== persistenceFailure) persistenceFailure.addSuppressed(notificationFailure)
        }
        throw persistenceFailure
      }
      afterTransitionStarted()
      drain?.await()
      return checkNotNull(token)
    } catch (failure: Throwable) {
      token?.let(::failTransition)
      if (token != null) {
        try {
          beforeWriterRelease()
        } catch (cleanupFailure: Throwable) {
          if (cleanupFailure !== failure) failure.addSuppressed(cleanupFailure)
        }
      }
      if (!writerReserved) transitionWriter.release()
      throw failure
    }
  }

  private fun revokeFetchAdmissionsForTransitionLocked() {
    check(Thread.holdsLock(monitor))
    revokePendingFetchAdmissionsLocked()
    // Claimed requests have already handed responsibility to the ordinary observer. They may finish the
    // current callback, but an old UP demand cannot keep later observer loop iterations connected. A
    // claimed admission is cancelled only after its token registration settles and handoff can release
    // the lease that this transition is draining.
    activeFetchAdmissions.forEach(FetchAdmission::revokeForTransition)
    activeFetchAdmissions.clear()
  }

  private fun finishTransition(token: TransitionOwnership, readyAfter: Boolean, completesInvalidation: Boolean) {
    synchronized(monitor) {
      check(transitionGeneration == token.generation) { "UnifiedPush lifecycle transition ownership changed" }
      if (completesInvalidation && readyAfter && (pendingEndpointToken == null || consumedEndpointToken == pendingEndpointToken)) {
        invalidationPending = false
        pendingEndpointToken = null
      }
      consumedEndpointToken = null
      transitionGeneration = null
      ready = readyAfter && !invalidationPending
    }
  }

  private fun failTransition(token: TransitionOwnership) {
    synchronized(monitor) {
      if (transitionGeneration == token.generation) {
        transitionGeneration = null
        ready = false
      }
    }
  }

  private fun release(lease: Lease<*>) {
    synchronized(monitor) {
      if (lease.closed) return
      lease.closed = true
      leaseCount--
      check(leaseCount >= 0)
      if (leaseCount == 0) {
        drainSignal?.countDown()
        drainSignal = null
      }
    }
  }

  private fun nextGeneration(value: Long): Long {
    check(value < Long.MAX_VALUE) { "UnifiedPush lifecycle generation exhausted" }
    return value + 1
  }

  @VisibleForTesting
  fun resetForTests(initiallyReady: Boolean = true) {
    synchronized(monitor) {
      check(leaseCount == 0 && transitionGeneration == null)
      initialized = true
      ready = initiallyReady
      generation = 1
      invalidationPending = false
      pendingEndpointToken = null
      consumedEndpointToken = null
      drainSignal = null
      pendingFetchAdmissions.clear()
      activeFetchAdmissions.clear()
    }
  }

  class StaleLifecycleGenerationException : IllegalStateException("Account context changed before the transition could commit")

  class IncompleteAccountTransitionException : IllegalStateException("Account registration is incomplete")
}
