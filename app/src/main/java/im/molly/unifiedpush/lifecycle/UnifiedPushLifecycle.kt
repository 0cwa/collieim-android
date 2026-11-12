package im.molly.unifiedpush.lifecycle

import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore

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
    block: suspend () -> T,
  ): T {
    require(!keepIncompleteMarker || !completesAccountRegistration) {
      "A transition cannot both retain the incomplete marker and complete registration"
    }

    var ownership: TransitionOwnership? = null
    try {
      // Store ownership before leaving NonCancellable. withContext has a prompt cancellation guarantee when
      // dispatching back to the caller; cancellation there must still reach cleanup with the acquired token.
      withContext(NonCancellable + Dispatchers.IO) {
        ownership = beginTransition(hasIncompleteMarker, persistIncompleteMarker, expectedGeneration)
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
      return result
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
        if (leaseCount == 0) null else CountDownLatch(1).also { drainSignal = it }
      }

      persistIncompleteMarker(true)
      drain?.await()
      return checkNotNull(token)
    } catch (failure: Throwable) {
      token?.let(::failTransition)
      transitionWriter.release()
      throw failure
    }
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
    }
  }

  class StaleLifecycleGenerationException : IllegalStateException("Account context changed before the transition could commit")
}
