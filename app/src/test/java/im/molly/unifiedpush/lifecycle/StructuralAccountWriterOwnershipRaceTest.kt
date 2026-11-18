package im.molly.unifiedpush.lifecycle

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class StructuralAccountWriterOwnershipRaceTest {
  @Test fun `closing a draining writer cannot release ownership to a successor`() {
    val gate = StructuralAccountOwnership(initiallyReady = true)
    val lease = checkNotNull(gate.acquire { "account-a" })
    val oldWriter = checkNotNull(gate.beginChange())
    val started = CountDownLatch(1)
    val mutation = CountDownLatch(1)
    val failure = AtomicReference<Throwable?>()
    val worker = thread {
      try { started.countDown(); oldWriter.run({ true }) { mutation.countDown() } } catch (t: Throwable) { failure.set(t) }
    }
    try {
      assertTrue(started.await(5, TimeUnit.SECONDS))
      val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
      while (worker.state != Thread.State.WAITING && worker.isAlive && System.nanoTime() < deadline) Thread.yield()
      assertTrue("old writer did not enter the drain wait", worker.state == Thread.State.WAITING)
      oldWriter.close()
      assertNull(gate.beginChange())
      lease.close()
      worker.join(5000)
      assertFalse(worker.isAlive)
      assertTrue(failure.get() is IllegalStateException)
      assertTrue(mutation.count == 1L)
      checkNotNull(gate.beginChange()).run({ true }) { Unit }
    } finally { lease.close(); oldWriter.close(); worker.join(5000) }
  }

  @Test fun `closing during mutation cannot admit successor or reopen account`() {
    val gate = StructuralAccountOwnership(initiallyReady = true)
    val writer = checkNotNull(gate.beginChange())
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val failure = AtomicReference<Throwable?>()
    val worker = thread {
      try { writer.run({ true }) { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) } } catch (t: Throwable) { failure.set(t) }
    }
    try {
      assertTrue(entered.await(5, TimeUnit.SECONDS))
      writer.close()
      assertNull(gate.beginChange())
      assertNull(gate.acquire { "account-a" })
    } finally { release.countDown() }
    worker.join(5000)
    assertFalse(worker.isAlive)
    assertTrue(failure.get() is IllegalStateException)
    assertNull(gate.acquire { "account-a" })
    checkNotNull(gate.beginChange()).run({ true }) { Unit }
  }

  @Test fun `interrupted drain releases writer and leaves admission closed`() {
    val gate = StructuralAccountOwnership(initiallyReady = true)
    val lease = checkNotNull(gate.acquire { "account-a" })
    val writer = checkNotNull(gate.beginChange())
    val started = CountDownLatch(1)
    val failure = AtomicReference<Throwable?>()
    val worker = thread {
      try { started.countDown(); writer.run({ true }) { error("must not mutate") } } catch (t: Throwable) { failure.set(t) }
    }
    try {
      assertTrue(started.await(5, TimeUnit.SECONDS))
      val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
      while (worker.state != Thread.State.WAITING && worker.isAlive && System.nanoTime() < deadline) Thread.yield()
      assertTrue(worker.state == Thread.State.WAITING)
      worker.interrupt()
      worker.join(5000)
      assertFalse(worker.isAlive)
      assertTrue(failure.get() is InterruptedException)
      assertNull(gate.acquire { "account-a" })
    } finally { lease.close() }
    checkNotNull(gate.beginChange()).run({ true }) { Unit }
  }


  @Test fun `close during readiness evaluation retains writer until callback returns`() {
    val gate = StructuralAccountOwnership(initiallyReady = true)
    val writer = checkNotNull(gate.beginChange())
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val failure = AtomicReference<Throwable?>()
    val worker = thread {
      try {
        writer.run(readyAfter = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); true }) { Unit }
      } catch (t: Throwable) { failure.set(t) }
    }
    try {
      assertTrue(entered.await(5, TimeUnit.SECONDS))
      writer.close()
      assertNull(gate.beginChange())
      assertNull(gate.acquire { "account-a" })
    } finally { release.countDown() }
    worker.join(5000)
    assertFalse(worker.isAlive)
    assertTrue(failure.get() is IllegalStateException)
    assertNull(gate.acquire { "account-a" })
    checkNotNull(gate.beginChange()).run({ true }) { Unit }
  }

  @Test fun `already interrupted writer cannot mutate when there is no drain`() {
    val gate = StructuralAccountOwnership(initiallyReady = true)
    val writer = checkNotNull(gate.beginChange())
    val mutation = CountDownLatch(1)
    val failure = AtomicReference<Throwable?>()
    val worker = thread {
      try {
        Thread.currentThread().interrupt()
        writer.run({ true }) { mutation.countDown() }
      } catch (t: Throwable) { failure.set(t) }
    }
    worker.join(5000)
    assertFalse(worker.isAlive)
    assertTrue(failure.get() is InterruptedException)
    assertTrue(mutation.count == 1L)
    assertNull(gate.acquire { "account-a" })
    checkNotNull(gate.beginChange()).run({ true }) { Unit }
  }

  @Test fun `readiness callback failure closes and releases the writer`() {
    val gate = StructuralAccountOwnership(initiallyReady = true)
    val writer = checkNotNull(gate.beginChange())
    val failure = runCatching { writer.run<Unit>({ throw IllegalStateException("synthetic readiness failure") }) { Unit } }.exceptionOrNull()
    assertTrue(failure is IllegalStateException)
    assertNull(gate.acquire { "account-a" })
    checkNotNull(gate.beginChange()).run({ true }) { Unit }
  }

}
