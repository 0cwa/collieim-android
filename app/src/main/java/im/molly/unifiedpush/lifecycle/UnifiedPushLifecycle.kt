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

/**
 * Orders UnifiedPush effects against account identity changes. The monitor is also the receiver's replay lock.
 * This object intentionally has no SignalStore or AppDependencies access; callers supply persistence and snapshots.
 */
object UnifiedPushLifecycle {

  @JvmField
  val monitor = Any()

  private val transitionWriter = Semaphore(1, true)
  private val processInstanceId = UUID.randomUUID().toString()

  private var initialized = false
  private var ready = false
  private var generation = 0L
  private var transitionGeneration: Long? = null
  private var invalidationPending = false
  private var leaseCount = 0
  private var drainSignal: CountDownLatch? = null
  private val pendingFetchAdmissions = LinkedHashSet<FetchAdmission>()
  private val activeFetchAdmissions = LinkedHashSet<FetchAdmission>()

  private data class TransitionOwnership(
    val generation: Long,
    val inheritedIncompleteMarker: Boolean,
  )

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
  )

  /** Called after SignalStore initialization and before startup jobs or push work can run. */
  @JvmStatic
  fun initialize(incompleteTransition: Boolean, legacyContextIsCoherent: Boolean) {
    synchronized(monitor) {
      check(leaseCount == 0 && transitionGeneration == null) { "Cannot initialize UnifiedPush lifecycle while work is active" }
      initialized = true
      generation = nextGeneration(generation)
      invalidationPending = false
      ready = !incompleteTransition && legacyContextIsCoherent
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

  fun hasPendingInvalidationLocked(): Boolean {
    check(Thread.holdsLock(monitor))
    return invalidationPending
  }

  fun currentGeneration(): Long = synchronized(monitor) { generation }

  fun processInstanceId(): String = processInstanceId

  /**
   * Marks push work unavailable, durably records the incomplete transition, drains old account-credentialed work,
   * then runs the mutation without holding [monitor]. Any exception leaves the lifecycle unavailable and leaves the
   * durable marker set. A caller that intentionally stages registration can retain that marker with
   * [keepIncompleteMarker].
   */
  suspend fun <T> withTransition(
    hasIncompleteMarker: () -> Boolean = { false },
    persistIncompleteMarker: (Boolean) -> Unit,
    readyAfter: () -> Boolean,
    expectedGeneration: Long? = null,
    keepIncompleteMarker: Boolean = false,
    completesAccountRegistration: Boolean = false,
    completesInvalidation: Boolean = false,
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
    afterTransitionStarted = afterTransitionStarted,
    block = block,
  ).value

  /**
   * Like [withTransition], while retaining the generation assigned to this transition. Callers can
   * later reacquire work only for this result; reading [currentGeneration] after return could instead
   * authorize the operation under a competing account transition.
   */
  suspend fun <T> withTransitionAndGeneration(
    hasIncompleteMarker: () -> Boolean = { false },
    persistIncompleteMarker: (Boolean) -> Unit,
    readyAfter: () -> Boolean,
    expectedGeneration: Long? = null,
    keepIncompleteMarker: Boolean = false,
    completesAccountRegistration: Boolean = false,
    completesInvalidation: Boolean = false,
    afterTransitionStarted: () -> Unit = {},
    block: suspend () -> T,
  ): TransitionResult<T> {
    require(!keepIncompleteMarker || !completesAccountRegistration) {
      "A transition cannot both retain the incomplete marker and complete registration"
    }

    var ownership: TransitionOwnership? = null
    try {
      // Store ownership before leaving NonCancellable. withContext has a prompt cancellation guarantee when
      // dispatching back to the caller; cancellation there must still reach cleanup with the acquired token.
      withContext(NonCancellable + Dispatchers.IO) {
        ownership = beginTransition(hasIncompleteMarker, persistIncompleteMarker, expectedGeneration, afterTransitionStarted)
      }
      val token = checkNotNull(ownership)
      val result = block()
      if (keepIncompleteMarker) {
        finishTransition(token, readyAfter = false, completesInvalidation = false)
      } else {
        val canStartPushWork = readyAfter()
        val preserveInheritedMarker = token.inheritedIncompleteMarker && !completesAccountRegistration
        val incompleteFullRegistration = completesAccountRegistration && !canStartPushWork
        if (preserveInheritedMarker || incompleteFullRegistration) {
          finishTransition(token, readyAfter = false, completesInvalidation = false)
        } else {
          withContext(NonCancellable + Dispatchers.IO) { persistIncompleteMarker(false) }
          finishTransition(token, canStartPushWork, completesInvalidation || completesAccountRegistration)
        }
      }
      return TransitionResult(result, token.generation)
    } catch (failure: Throwable) {
      ownership?.let(::failTransition)
      throw failure
    } finally {
      if (ownership != null) transitionWriter.release()
    }
  }

  private fun beginTransition(
    hasIncompleteMarker: () -> Boolean,
    persistIncompleteMarker: (Boolean) -> Unit,
    expectedGeneration: Long?,
    afterTransitionStarted: () -> Unit,
  ): TransitionOwnership {
    transitionWriter.acquire()
    var token: TransitionOwnership? = null
    try {
      val inheritedIncompleteMarker = hasIncompleteMarker()
      val drain = synchronized(monitor) {
        if (expectedGeneration != null && generation != expectedGeneration) {
          throw StaleLifecycleGenerationException()
        }
        val next = nextGeneration(generation)
        generation = next
        initialized = true
        ready = false
        transitionGeneration = next
        token = TransitionOwnership(next, inheritedIncompleteMarker)
        revokeFetchAdmissionsForTransitionLocked()
        if (leaseCount == 0) null else CountDownLatch(1).also { drainSignal = it }
      }

      try {
        persistIncompleteMarker(true)
      } finally {
        afterTransitionStarted()
      }
      drain?.await()
      return checkNotNull(token)
    } catch (failure: Throwable) {
      token?.let(::failTransition)
      transitionWriter.release()
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
      if (completesInvalidation && readyAfter) invalidationPending = false
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
      drainSignal = null
      pendingFetchAdmissions.clear()
      activeFetchAdmissions.clear()
    }
  }

  class StaleLifecycleGenerationException : IllegalStateException("Account context changed before the transition could commit")
}
