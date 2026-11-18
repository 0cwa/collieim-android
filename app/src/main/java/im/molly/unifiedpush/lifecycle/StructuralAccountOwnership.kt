package im.molly.unifiedpush.lifecycle

import java.util.concurrent.CountDownLatch

/**
 * Process-local account ownership, independent of push routes and credential-send authorization.
 * A writer closes admission before waiting for leases. The writer owns all mutation and persistence
 * outside the monitor and must explicitly complete or abandon its transition.
 */
internal class StructuralAccountOwnership(initiallyReady: Boolean) {
  private val lock = Any()
  private var epoch = 0L
  private var ready = initiallyReady
  private var activeWriter: Writer? = null
  private var activeLeases = 0
  private var drain: CountDownLatch? = null

  internal class Lease<T : Any> internal constructor(
    internal val owner: StructuralAccountOwnership,
    val epoch: Long,
    val snapshot: T,
  ) : AutoCloseable {
    internal var closed = false
    fun isCurrent(): Boolean = owner.isCurrent(this)
    override fun close() = owner.release(this)
  }

  internal class Writer internal constructor(
    private val owner: StructuralAccountOwnership,
    internal val epoch: Long,
  ) : AutoCloseable {
    internal var closed = false
    internal var claimed = false
    internal var closeRequested = false
    fun <T> run(readyAfter: (T) -> Boolean, block: () -> T): T = owner.runWriter(this, readyAfter) { block() }
    fun <T> runOwned(readyAfter: (T) -> Boolean, block: (Capability) -> T): T = owner.runWriter(this, readyAfter, block)
    override fun close() = owner.abandon(this)
  }

  /** Synchronous nested work may use this capability only on the writer's thread and within its claim. */
  internal class Capability internal constructor(
    internal val owner: StructuralAccountOwnership,
    internal val writer: Writer,
    internal val thread: Thread,
  )

  /** Does not acquire another writer or hold the owner monitor while the nested operation runs. */
  fun <T> withCapability(capability: Capability, block: () -> T): T {
    synchronized(lock) {
      check(capability.owner === this && capability.thread === Thread.currentThread()) { "Wrong account capability owner" }
      requireOwner(capability.writer)
      check(capability.writer.claimed && !capability.writer.closeRequested) { "Account capability is no longer active" }
    }
    return block()
  }

  /** Capture local account data while the lease prevents a participating writer from mutating it. */
  fun <T : Any> acquire(expectedEpoch: Long? = null, capture: () -> T?): Lease<T>? {
    val observed = synchronized(lock) {
      if (!ready || activeWriter != null || (expectedEpoch != null && expectedEpoch != epoch)) return null
      activeLeases++
      epoch
    }
    var lease: Lease<T>? = null
    var handedOff = false
    try {
      val snapshot = capture() ?: return null
      lease = Lease(this, observed, snapshot)
      if (!lease.isCurrent()) return null
      handedOff = true
      return lease
    } finally {
      // On capture failure or invalidation, release the reserved count exactly once.
      if (!handedOff) {
        if (lease == null) releaseReserved() else lease.close()
      }
    }
  }

  /** Reserve a unique writer without waiting; a failed reservation does not mutate state. */
  fun beginChange(): Writer? = synchronized(lock) {
    if (activeWriter != null) return null
    check(epoch < Long.MAX_VALUE) { "Account epoch exhausted" }
    val writer = Writer(this, ++epoch)
    ready = false
    activeWriter = writer
    writer
  }

  private fun isCurrent(lease: Lease<*>): Boolean = synchronized(lock) {
    !lease.closed && ready && activeWriter == null && lease.epoch == epoch
  }

  /** The writer remains reserved until its active operation returns, even if another thread closes it. */
  private fun <T> runWriter(writer: Writer, readyAfter: (T) -> Boolean, block: (Capability) -> T): T {
    var completed = false
    val signal = synchronized(lock) {
      requireOwner(writer)
      check(!writer.claimed) { "Account writer already claimed" }
      writer.claimed = true
      if (activeLeases == 0) null else drain ?: CountDownLatch(1).also { drain = it }
    }
    try {
      signal?.await()
      // Even without a drain latch, interruption must stop before the mutation begins.
      if (Thread.currentThread().isInterrupted) throw InterruptedException("Account writer interrupted")
      synchronized(lock) {
        requireOwner(writer)
        check(!writer.closeRequested) { "Account writer was cancelled" }
      }
      val result = block(Capability(this, writer, Thread.currentThread()))
      val canReopen = readyAfter(result)
      synchronized(lock) {
        requireOwner(writer)
        check(!writer.closeRequested) { "Account writer was cancelled" }
        check(activeLeases == 0) { "Old account leases have not drained" }
        writer.closed = true
        activeWriter = null
        ready = canReopen
        completed = true
      }
      return result
    } finally {
      if (!completed) synchronized(lock) {
        if (!writer.closed && activeWriter === writer) {
          writer.closed = true
          activeWriter = null
          ready = false
        }
      }
    }
  }

  private fun abandon(writer: Writer) = synchronized(lock) {
    if (writer.closed) return
    requireOwner(writer)
    if (writer.claimed) {
      // The running owner, not this caller, releases the reservation in its finally block.
      writer.closeRequested = true
    } else {
      writer.closed = true
      activeWriter = null
      ready = false
    }
  }

  private fun requireOwner(writer: Writer) {
    check(!writer.closed && activeWriter === writer && writer.epoch == epoch) { "Stale account writer" }
  }

  private fun release(lease: Lease<*>) = synchronized(lock) {
    if (!lease.closed) {
      lease.closed = true
      releaseReservedLocked()
    }
  }

  private fun releaseReserved() = synchronized(lock) { releaseReservedLocked() }

  private fun releaseReservedLocked() {
    check(activeLeases > 0)
    activeLeases--
    if (activeLeases == 0) {
      drain?.countDown()
      drain = null
    }
  }
}
