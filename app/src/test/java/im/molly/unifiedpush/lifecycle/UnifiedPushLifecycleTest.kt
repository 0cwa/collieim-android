package im.molly.unifiedpush.lifecycle

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
