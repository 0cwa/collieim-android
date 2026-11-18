package im.molly.unifiedpush.lifecycle

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class UnifiedPushLifecycleTest {

  @Before
  fun setUp() {
    UnifiedPushLifecycle.resetForTests()
  }

  @Test
  fun `lease cannot reserve writer already draining it`() {
    val lease = checkNotNull(UnifiedPushLifecycle.acquireLease { "account-a" })
    val started = CountDownLatch(1)
    val finished = CountDownLatch(1)
    val worker = Thread {
      try {
        runBlocking {
          UnifiedPushLifecycle.withTransition(persistIncompleteMarker = { if (it) started.countDown() }, readyAfter = { true }) { Unit }
        }
      } finally { finished.countDown() }
    }
    worker.start()
    try {
      assertTrue(started.await(5, TimeUnit.SECONDS))
      val reserved = synchronized(UnifiedPushLifecycle.monitor) { UnifiedPushLifecycle.reserveTransitionForLeaseLocked(lease) }
      assertEquals(null, reserved)
    } finally {
      lease.close()
    }
    assertTrue(finished.await(5, TimeUnit.SECONDS))
    assertTrue(UnifiedPushLifecycle.isReady())
  }

  @Test
  fun `reserved writer closes admission and drains other leases before mutation`() {
    val owner = checkNotNull(UnifiedPushLifecycle.acquireLease { "account-a" })
    val other = checkNotNull(UnifiedPushLifecycle.acquireLease { "account-a" })
    val reserved = synchronized(UnifiedPushLifecycle.monitor) { UnifiedPushLifecycle.reserveTransitionForLeaseLocked(owner) }
    assertNotNull(reserved)
    owner.close()
    val started = CountDownLatch(1)
    val mutated = CountDownLatch(1)
    val finished = CountDownLatch(1)
    val competitorStarted = CountDownLatch(1)
    val competitorFinished = CountDownLatch(1)
    val competitorOvertook = AtomicBoolean(false)
    val competitor = Thread {
      competitorStarted.countDown()
      try {
        runBlocking {
          UnifiedPushLifecycle.withTransition(persistIncompleteMarker = {}, readyAfter = { true }) {
            competitorOvertook.set(mutated.count != 0L)
          }
        }
      } finally { competitorFinished.countDown() }
    }
    competitor.start()
    assertTrue(competitorStarted.await(5, TimeUnit.SECONDS))
    val worker = Thread {
      try {
        runBlocking {
          UnifiedPushLifecycle.withTransitionAndGeneration(
            expectedGeneration = reserved?.generation,
            writerReservation = reserved,
            persistIncompleteMarker = { if (it) started.countDown() },
            readyAfter = { true },
          ) { mutated.countDown() }
        }
      } finally {
        reserved?.close()
        finished.countDown()
      }
    }
    worker.start()
    try {
      assertTrue(started.await(5, TimeUnit.SECONDS))
      assertEquals(null, UnifiedPushLifecycle.acquireLease { "account-a" })
      assertEquals(1L, mutated.count)
    } finally { other.close() }
    assertTrue(finished.await(5, TimeUnit.SECONDS))
    assertTrue(competitorFinished.await(5, TimeUnit.SECONDS))
    assertFalse("a competing writer must not overtake the reservation", competitorOvertook.get())
    assertEquals(0L, mutated.count)
    assertTrue(UnifiedPushLifecycle.isReady())
  }

  @Test
  fun `failed reserved transition releases its writer and remains closed`() {
    val lease = checkNotNull(UnifiedPushLifecycle.acquireLease { "account-a" })
    val reserved = synchronized(UnifiedPushLifecycle.monitor) { UnifiedPushLifecycle.reserveTransitionForLeaseLocked(lease) }
    assertNotNull(reserved)
    lease.close()
    assertThrows(IllegalStateException::class.java) {
      try {
        runBlocking {
          UnifiedPushLifecycle.withTransitionAndGeneration(
            expectedGeneration = reserved?.generation,
            writerReservation = reserved,
            persistIncompleteMarker = {},
            readyAfter = { true },
          ) { throw IllegalStateException("synthetic failure") }
        }
      } finally { reserved?.close() }
    }
    assertFalse(UnifiedPushLifecycle.isReady())
    runBlocking {
      UnifiedPushLifecycle.withTransition(persistIncompleteMarker = {}, readyAfter = { true }) { Unit }
    }
    assertTrue(UnifiedPushLifecycle.isReady())
  }

  @Test
  fun `closing a claimed reservation cannot release its writer early`() {
    val lease = checkNotNull(UnifiedPushLifecycle.acquireLease { "account-a" })
    val reservation = checkNotNull(synchronized(UnifiedPushLifecycle.monitor) { UnifiedPushLifecycle.reserveTransitionForLeaseLocked(lease) })
    lease.close()
    val inside = CountDownLatch(1)
    val release = CountDownLatch(1)
    val competitorAttempting = CountDownLatch(1)
    val competitorEntered = CountDownLatch(1)
    val failure = AtomicReference<Throwable?>()
    val owner = Thread {
      try {
        runBlocking {
          UnifiedPushLifecycle.withTransitionAndGeneration(
            expectedGeneration = reservation.generation,
            writerReservation = reservation,
            persistIncompleteMarker = {},
            readyAfter = { true },
          ) {
            inside.countDown()
            check(release.await(5, TimeUnit.SECONDS))
          }
        }
      } catch (t: Throwable) { failure.compareAndSet(null, t) }
    }
    val competitor = Thread {
      try {
        competitorAttempting.countDown()
        runBlocking {
          UnifiedPushLifecycle.withTransition(persistIncompleteMarker = {}, readyAfter = { true }) { competitorEntered.countDown() }
        }
      } catch (t: Throwable) { failure.compareAndSet(null, t) }
    }
    owner.start()
    try {
      assertTrue(inside.await(5, TimeUnit.SECONDS))
      reservation.close()
      competitor.start()
      assertTrue(competitorAttempting.await(5, TimeUnit.SECONDS))
      assertFalse(competitorEntered.await(100, TimeUnit.MILLISECONDS))
    } finally { release.countDown() }
    owner.join(5000)
    competitor.join(5000)
    assertFalse(owner.isAlive)
    assertFalse(competitor.isAlive)
    assertEquals(null, failure.get())
    assertEquals(0L, competitorEntered.count)
  }

  @Test
  fun `endpoint transition cannot run across an inherited account registration marker`() {
    val called = AtomicBoolean(false)
    UnifiedPushLifecycle.initialize(incompleteTransition = true, legacyContextIsCoherent = true)
    assertThrows(UnifiedPushLifecycle.IncompleteAccountTransitionException::class.java) {
      runBlocking {
        UnifiedPushLifecycle.withTransitionAndGeneration(
          hasIncompleteMarker = { true },
          persistIncompleteMarker = {},
          readyAfter = { true },
          requireCleanMarker = true,
        ) { called.set(true) }
      }
    }
    assertFalse(called.get())
    assertFalse(UnifiedPushLifecycle.isReady())
    runBlocking {
      UnifiedPushLifecycle.withTransition(hasIncompleteMarker = { true }, persistIncompleteMarker = {}, readyAfter = { true }, completesAccountRegistration = true) { Unit }
    }
    assertTrue(UnifiedPushLifecycle.isReady())
  }

  @Test
  fun `receiver invalidation during reserved clear remains pending`() {
    val lease = checkNotNull(UnifiedPushLifecycle.acquireLease { "account-a" })
    val reserved = synchronized(UnifiedPushLifecycle.monitor) { UnifiedPushLifecycle.reserveTransitionForLeaseLocked(lease) }
    assertNotNull(reserved)
    lease.close()
    try {
      runBlocking {
        UnifiedPushLifecycle.withTransitionAndGeneration(
          expectedGeneration = reserved?.generation,
          writerReservation = reserved,
          persistIncompleteMarker = {},
          readyAfter = { true },
        ) {
          synchronized(UnifiedPushLifecycle.monitor) { UnifiedPushLifecycle.invalidateLocked() }
        }
      }
    } finally { reserved?.close() }
    assertFalse(UnifiedPushLifecycle.isReady())
    assertTrue(synchronized(UnifiedPushLifecycle.monitor) { UnifiedPushLifecycle.hasPendingInvalidationLocked() })
    runBlocking {
      UnifiedPushLifecycle.withTransition(persistIncompleteMarker = {}, readyAfter = { true }, completesInvalidation = true) { Unit }
    }
    assertTrue(UnifiedPushLifecycle.isReady())
  }

  @Test
  fun `receiver completion acknowledges only the exact consumed event`() {
    synchronized(UnifiedPushLifecycle.monitor) { UnifiedPushLifecycle.noteEndpointPendingLocked("event-a") }
    runBlocking {
      UnifiedPushLifecycle.withTransition(persistIncompleteMarker = {}, readyAfter = { true }, completesInvalidation = true) {
        synchronized(UnifiedPushLifecycle.monitor) {
          UnifiedPushLifecycle.noteEndpointConsumedLocked("event-a")
          UnifiedPushLifecycle.noteEndpointPendingLocked("event-b")
        }
      }
    }
    assertFalse(UnifiedPushLifecycle.isReady())
    runBlocking {
      UnifiedPushLifecycle.withTransition(persistIncompleteMarker = {}, readyAfter = { true }, completesInvalidation = true) {
        synchronized(UnifiedPushLifecycle.monitor) { UnifiedPushLifecycle.noteEndpointConsumedLocked("event-b") }
      }
    }
    assertTrue(UnifiedPushLifecycle.isReady())
  }

  @Test
  fun `account registration cannot acknowledge an unconsumed endpoint event`() {
    synchronized(UnifiedPushLifecycle.monitor) { UnifiedPushLifecycle.noteEndpointPendingLocked("event-a") }
    runBlocking {
      UnifiedPushLifecycle.withTransition(persistIncompleteMarker = {}, readyAfter = { true }, completesAccountRegistration = true) { Unit }
    }
    assertFalse(UnifiedPushLifecycle.isReady())
    runBlocking {
      UnifiedPushLifecycle.withTransition(persistIncompleteMarker = {}, readyAfter = { true }, completesInvalidation = true) {
        synchronized(UnifiedPushLifecycle.monitor) { UnifiedPushLifecycle.noteEndpointConsumedLocked("event-a") }
      }
    }
    assertTrue(UnifiedPushLifecycle.isReady())
  }

  @Test
  fun `restored pending endpoint closes admission until its token is consumed`() {
    UnifiedPushLifecycle.initialize(incompleteTransition = false, legacyContextIsCoherent = true, pendingEndpoint = "restored-event")
    assertFalse(UnifiedPushLifecycle.isReady())
    assertEquals(null, UnifiedPushLifecycle.acquireLease { "account-a" })
    runBlocking {
      UnifiedPushLifecycle.withTransition(persistIncompleteMarker = {}, readyAfter = { true }, completesInvalidation = true) {
        synchronized(UnifiedPushLifecycle.monitor) { UnifiedPushLifecycle.noteEndpointConsumedLocked("restored-event") }
      }
    }
    assertTrue(UnifiedPushLifecycle.isReady())
  }

  @Test
  fun `transition closes admission then drains leased work before identity mutation`() {
    val lease = UnifiedPushLifecycle.acquireLease { "account-a" }!!
    val markerSet = CountDownLatch(1)
    val mutationStarted = CountDownLatch(1)
    val allowMutationToFinish = CountDownLatch(1)
    val transitionFinished = CountDownLatch(1)
    val pending = AtomicBoolean(false)
    val failure = AtomicReference<Throwable?>()

    Thread {
      try {
        runBlocking {
          UnifiedPushLifecycle.withTransition(
            persistIncompleteMarker = {
              pending.set(it)
              if (it) markerSet.countDown()
            },
            readyAfter = { true },
          ) {
            mutationStarted.countDown()
            check(allowMutationToFinish.await(5, TimeUnit.SECONDS))
          }
        }
      } catch (t: Throwable) {
        failure.set(t)
      } finally {
        transitionFinished.countDown()
      }
    }.start()

    assertTrue(markerSet.await(5, TimeUnit.SECONDS))
    assertFalse(UnifiedPushLifecycle.isReady())
    assertEquals(1L, mutationStarted.count)
    lease.close()
    assertTrue(mutationStarted.await(5, TimeUnit.SECONDS))
    assertTrue(pending.get())
    allowMutationToFinish.countDown()
    assertTrue(transitionFinished.await(5, TimeUnit.SECONDS))

    assertEquals(null, failure.get())
    assertFalse(pending.get())
    assertTrue(UnifiedPushLifecycle.isReady())
    assertTrue(UnifiedPushLifecycle.currentGeneration() > lease.generation)
  }

  @Test
  fun `failed transition and persisted restart marker remain fail closed`() {
    val pending = AtomicBoolean(false)
    val failure = runCatching {
      runBlocking {
        UnifiedPushLifecycle.withTransition(
          persistIncompleteMarker = { pending.set(it) },
          readyAfter = { true },
        ) {
          throw IllegalStateException("synthetic partial registration")
        }
      }
    }.exceptionOrNull()

    assertTrue(failure is IllegalStateException)
    assertTrue(pending.get())
    assertFalse(UnifiedPushLifecycle.isReady())

    UnifiedPushLifecycle.initialize(incompleteTransition = true, legacyContextIsCoherent = true)
    assertFalse(UnifiedPushLifecycle.isReady())
  }

  @Test
  fun `stale generation cannot begin a second account transition`() {
    val oldGeneration = UnifiedPushLifecycle.currentGeneration()
    runBlocking {
      UnifiedPushLifecycle.withTransition(
        persistIncompleteMarker = {},
        readyAfter = { true },
      ) { Unit }
    }

    val failure = runCatching {
      runBlocking {
        UnifiedPushLifecycle.withTransition(
          persistIncompleteMarker = {},
          readyAfter = { true },
          expectedGeneration = oldGeneration,
        ) { Unit }
      }
    }.exceptionOrNull()

    assertTrue(failure is UnifiedPushLifecycle.StaleLifecycleGenerationException)
    assertTrue(UnifiedPushLifecycle.isReady())
  }

  @Test
  fun `cancellation after transition ownership is acquired releases the writer`() {
    val lease = UnifiedPushLifecycle.acquireLease { "account-a" }!!
    val markerSet = CountDownLatch(1)
    val pending = AtomicBoolean(false)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val transition = scope.launch {
      UnifiedPushLifecycle.withTransition(
        hasIncompleteMarker = { pending.get() },
        persistIncompleteMarker = {
          pending.set(it)
          if (it) markerSet.countDown()
        },
        readyAfter = { true },
      ) { error("the canceled transition must not run its mutation") }
    }

    assertTrue(markerSet.await(5, TimeUnit.SECONDS))
    transition.cancel()
    lease.close()
    runBlocking { transition.join() }
    assertTrue(transition.isCancelled)
    assertTrue(pending.get())
    assertFalse(UnifiedPushLifecycle.isReady())

    val nextTransitionEntered = CountDownLatch(1)
    runBlocking {
      UnifiedPushLifecycle.withTransition(
        hasIncompleteMarker = { pending.get() },
        persistIncompleteMarker = { pending.set(it) },
        readyAfter = { true },
        keepIncompleteMarker = true,
      ) {
        nextTransitionEntered.countDown()
      }
    }
    assertTrue("the next writer must not remain stranded", nextTransitionEntered.count == 0L)
    scope.cancel()
  }

  @Test
  fun `ordinary transitions preserve an inherited incomplete marker until full registration completes`() {
    val pending = AtomicBoolean(true)
    UnifiedPushLifecycle.initialize(incompleteTransition = true, legacyContextIsCoherent = true)

    runBlocking {
      UnifiedPushLifecycle.withTransition(
        hasIncompleteMarker = { pending.get() },
        persistIncompleteMarker = { pending.set(it) },
        readyAfter = { true },
      ) { Unit }
    }

    assertTrue("an unrelated successful transition must not clear the inherited marker", pending.get())
    assertFalse(UnifiedPushLifecycle.isReady())

    UnifiedPushLifecycle.initialize(incompleteTransition = true, legacyContextIsCoherent = true)
    runBlocking {
      UnifiedPushLifecycle.withTransition(
        hasIncompleteMarker = { pending.get() },
        persistIncompleteMarker = { pending.set(it) },
        readyAfter = { true },
        completesAccountRegistration = true,
      ) { Unit }
    }

    assertFalse(pending.get())
    assertTrue(UnifiedPushLifecycle.isReady())
  }

  @Test
  fun `cancellation during marker clearing still publishes the completed transition`() {
    val pending = AtomicBoolean(false)
    val scopeJob = SupervisorJob()
    val scope = CoroutineScope(Dispatchers.Default + scopeJob)
    val job = scope.launch {
      UnifiedPushLifecycle.withTransition(
        hasIncompleteMarker = pending::get,
        persistIncompleteMarker = { value ->
          pending.set(value)
          if (!value) scopeJob.cancel()
        },
        readyAfter = { true },
      ) { Unit }
    }
    runBlocking { job.join() }
    assertTrue(job.isCancelled)
    assertFalse(pending.get())
    assertTrue(UnifiedPushLifecycle.isReady())
  }

  @Test
  fun `marker clear failure after write attempts to restore fail closed persistence`() {
    val pending = AtomicBoolean(false)
    assertThrows(IllegalStateException::class.java) {
      runBlocking {
        UnifiedPushLifecycle.withTransition(
          hasIncompleteMarker = pending::get,
          persistIncompleteMarker = { value ->
            pending.set(value)
            if (!value) throw IllegalStateException("synthetic write failure after persistence")
          },
          readyAfter = { true },
        ) { Unit }
      }
    }
    assertTrue(pending.get())
    assertFalse(UnifiedPushLifecycle.isReady())
    UnifiedPushLifecycle.initialize(incompleteTransition = pending.get(), legacyContextIsCoherent = true)
    assertFalse(UnifiedPushLifecycle.isReady())
  }

  @Test
  fun `failed marker repair reports both errors and keeps memory closed`() {
    val pending = AtomicBoolean(false)
    var settingTrue = 0
    val failure = assertThrows(IllegalStateException::class.java) {
      runBlocking {
        UnifiedPushLifecycle.withTransition(
          hasIncompleteMarker = pending::get,
          persistIncompleteMarker = { value ->
            if (value && ++settingTrue > 1) throw IllegalArgumentException("synthetic repair failure")
            pending.set(value)
            if (!value) throw IllegalStateException("synthetic clear failure after write")
          },
          readyAfter = { true },
        ) { Unit }
      }
    }
    assertEquals(2, settingTrue)
    val reported = generateSequence(failure as Throwable?) { it.cause }.flatMap { it.suppressed.asSequence() }.toList()
    assertTrue(reported.any { it is IllegalArgumentException })
    assertFalse(UnifiedPushLifecycle.isReady())
    // A second storage failure may leave durable state cleared; restart safety is not asserted.
  }

  @Test
  fun `owned operation returning failure retains its durable marker`() {
    val pending = AtomicBoolean(false)
    val result = runBlocking {
      UnifiedPushLifecycle.withTransition(
        hasIncompleteMarker = pending::get,
        persistIncompleteMarker = pending::set,
        readyAfter = { false },
        retainMarkerWhenNotReady = true,
      ) { "failure" }
    }
    assertEquals("failure", result)
    assertTrue(pending.get())
    assertFalse(UnifiedPushLifecycle.isReady())
    UnifiedPushLifecycle.initialize(incompleteTransition = pending.get(), legacyContextIsCoherent = true)
    assertFalse(UnifiedPushLifecycle.isReady())
  }

  @Test
  fun `owned operation can reopen only when coherent and no inherited marker exists`() {
    val pending = AtomicBoolean(false)
    runBlocking {
      UnifiedPushLifecycle.withTransition(
        hasIncompleteMarker = pending::get,
        persistIncompleteMarker = pending::set,
        readyAfter = { true },
        retainMarkerWhenNotReady = true,
      ) { Unit }
    }
    assertFalse(pending.get())
    assertTrue(UnifiedPushLifecycle.isReady())
    pending.set(true)
    runBlocking {
      UnifiedPushLifecycle.withTransition(
        hasIncompleteMarker = pending::get,
        persistIncompleteMarker = pending::set,
        readyAfter = { true },
        retainMarkerWhenNotReady = true,
      ) { Unit }
    }
    assertTrue(pending.get())
    assertFalse(UnifiedPushLifecycle.isReady())
  }

  @Test
  fun `incomplete full registration retains the marker`() {
    val pending = AtomicBoolean(true)
    UnifiedPushLifecycle.initialize(incompleteTransition = true, legacyContextIsCoherent = true)

    runBlocking {
      UnifiedPushLifecycle.withTransition(
        hasIncompleteMarker = { pending.get() },
        persistIncompleteMarker = { pending.set(it) },
        readyAfter = { false },
        completesAccountRegistration = true,
      ) { Unit }
    }

    assertTrue(pending.get())
    assertFalse(UnifiedPushLifecycle.isReady())
  }
}
