package org.thoughtcrime.securesms.jobmanager

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.testutil.SystemOutLogger
import java.time.Duration
import java.util.function.LongSupplier

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class InAppSchedulerTest {

  private val manager = mockk<JobManager>(relaxed = true)
  private lateinit var handler: Handler
  private lateinit var scheduler: InAppScheduler

  @Before
  fun setUp() {
    Log.initialize(SystemOutLogger())
    handler = Handler(Looper.getMainLooper())
    scheduler = InAppScheduler(manager, handler, LongSupplier { SystemClock.uptimeMillis() })
  }

  @After
  fun tearDown() {
    handler.removeCallbacksAndMessages(null)
  }

  @Test
  fun `repeated equal and later deadlines produce only one wakeup`() {
    scheduler.schedule(2 * MINUTE, emptyList())
    repeat(150) {
      scheduler.schedule(2 * MINUTE, emptyList())
      scheduler.schedule(120 * MINUTE, emptyList())
    }

    advance(2 * MINUTE - 1)
    verify(exactly = 0) { manager.wakeUp() }
    advance(1)
    verify(exactly = 1) { manager.wakeUp() }
    advance(120 * MINUTE)
    verify(exactly = 1) { manager.wakeUp() }
  }

  @Test
  fun `an earlier deadline replaces the existing callback`() {
    scheduler.schedule(5 * MINUTE, emptyList())
    advance(MINUTE)
    scheduler.schedule(MINUTE, emptyList())

    advance(MINUTE - 1)
    verify(exactly = 0) { manager.wakeUp() }
    advance(1)
    verify(exactly = 1) { manager.wakeUp() }
    advance(3 * MINUTE)
    verify(exactly = 1) { manager.wakeUp() }
  }

  @Test
  fun `callback clears its pending state before waking jobs so draining can rearm it`() {
    var wakes = 0
    every { manager.wakeUp() } answers {
      wakes++
      if (wakes == 1) {
        scheduler.schedule(10 * MINUTE, emptyList())
      }
    }
    scheduler.schedule(MINUTE, emptyList())

    advance(MINUTE)
    verify(exactly = 1) { manager.wakeUp() }
    advance(10 * MINUTE - 1)
    verify(exactly = 1) { manager.wakeUp() }
    advance(1)
    verify(exactly = 2) { manager.wakeUp() }
  }

  @Test
  fun `nonpositive delays and unmet constraints do not schedule callbacks`() {
    val met = mockk<Constraint> { every { isMet } returns true }
    val unmet = mockk<Constraint> { every { isMet } returns false }

    scheduler.schedule(0, emptyList())
    scheduler.schedule(-1, emptyList())
    scheduler.schedule(MINUTE, listOf(met, unmet))
    advance(120 * MINUTE)

    verify(exactly = 0) { manager.wakeUp() }
  }

  @Test
  fun `an unmet earlier request does not cancel an already scheduled valid wakeup`() {
    val unmet = mockk<Constraint> { every { isMet } returns false }
    scheduler.schedule(5 * MINUTE, emptyList())
    scheduler.schedule(MINUTE, listOf(unmet))

    advance(MINUTE)
    verify(exactly = 0) { manager.wakeUp() }
    advance(4 * MINUTE)
    verify(exactly = 1) { manager.wakeUp() }
  }

  @Test
  fun `a canceled callback that already left the queue cannot clear its replacement`() {
    scheduler.schedule(5 * MINUTE, emptyList())
    // Simulate a callback already dequeued by another thread when removeCallbacks is called.
    val canceled = InAppScheduler::class.java.getDeclaredField("pendingWakeup")
      .apply { isAccessible = true }
      .get(scheduler) as Runnable
    scheduler.schedule(MINUTE, emptyList())

    canceled.run()
    verify(exactly = 0) { manager.wakeUp() }
    advance(MINUTE)
    verify(exactly = 1) { manager.wakeUp() }
    advance(4 * MINUTE)
    verify(exactly = 1) { manager.wakeUp() }
  }

  private fun advance(milliseconds: Long) {
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(milliseconds))
  }

  companion object {
    private const val MINUTE = 60_000L
  }
}
