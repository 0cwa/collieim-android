package im.molly.unifiedpush.lifecycle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class CredentialSendHandoffTest {
  private class FakeCall(
    private val onEnqueue: () -> Unit = {},
    private val onCancel: () -> Unit = {},
  ) : UnifiedPushLifecycle.CredentialSendHandoff.Call {
    val enqueues = AtomicInteger()
    val cancellations = AtomicInteger()
    override fun enqueue() { enqueues.incrementAndGet(); onEnqueue() }
    override fun cancel() { cancellations.incrementAndGet(); onCancel() }
  }

  @Test
  fun `rejection orders against enqueue and prevents subsequent admission`() {
    val gate = UnifiedPushLifecycle.CredentialSendHandoff()
    gate.beginAccountChange(1)
    gate.completeAccountChange(1, true)
    assertTrue(gate.beginAttempt(1, 10))
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val call = FakeCall(onEnqueue = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) })
    val started = AtomicBoolean(false)
    val failure = AtomicReference<Throwable?>()
    val sender = thread {
      try { started.set(gate.start(1, call) { Thread.holdsLock(UnifiedPushLifecycle.monitor) }) } catch (t: Throwable) { failure.compareAndSet(null, t) }
    }
    assertTrue(entered.await(5, TimeUnit.SECONDS))
    val rejected = AtomicBoolean(false)
    val rejector = thread { try { rejected.set(gate.reject(1, 10)) } catch (t: Throwable) { failure.compareAndSet(null, t) } }
    release.countDown()
    sender.join(5000)
    rejector.join(5000)
    assertFalse(sender.isAlive)
    assertFalse(rejector.isAlive)
    assertNull(failure.get())
    assertTrue(started.get())
    assertTrue(rejected.get())
    assertEquals(1, call.cancellations.get())
    val later = FakeCall()
    assertFalse(gate.start(1, later) { true })
    assertEquals(0, later.enqueues.get())
  }

  @Test
  fun `a stale rejection cannot revoke a replacement account`() {
    val gate = UnifiedPushLifecycle.CredentialSendHandoff()
    gate.beginAccountChange(1)
    gate.completeAccountChange(1, true)
    assertTrue(gate.beginAttempt(1, 10))
    val old = FakeCall()
    assertTrue(gate.start(1, old) { true })
    gate.beginAccountChange(2)
    assertEquals(1, old.cancellations.get())
    assertThrows(IllegalStateException::class.java) { gate.completeAccountChange(2, true) }
    gate.finish(old)
    gate.completeAccountChange(2, true)
    assertTrue(gate.beginAttempt(2, 20))
    assertFalse(gate.reject(1, 10))
    assertTrue(gate.beginAttempt(2, 21))
    assertFalse(gate.reject(2, 20))
    assertFalse(gate.authorize(2, 20))
    val current = FakeCall()
    assertTrue(gate.start(2, current) { true })
    gate.finish(current)
    assertTrue(gate.reject(2, 21))
    assertFalse(gate.authorize(2, 21))
    assertFalse(gate.beginAttempt(2, 21))
    assertThrows(IllegalArgumentException::class.java) { gate.beginAccountChange(1) }
    assertEquals(0, current.cancellations.get())
  }

  @Test
  fun `failed enqueue retains cleanup ownership until terminal work is acknowledged`() {
    val gate = UnifiedPushLifecycle.CredentialSendHandoff()
    gate.beginAccountChange(1)
    gate.completeAccountChange(1, true)
    assertTrue(gate.beginAttempt(1, 10))
    val broken = FakeCall(onEnqueue = { throw IllegalStateException("synthetic failure") })
    assertThrows(IllegalStateException::class.java) { gate.start(1, broken) { true } }
    assertEquals(1, broken.cancellations.get())
    val next = FakeCall()
    assertFalse(gate.start(1, next) { true })
    gate.beginAccountChange(2)
    assertThrows(IllegalStateException::class.java) { gate.completeAccountChange(2, true) }
    gate.finish(broken) // The test stands in for the owner's proven terminal callback.
    gate.completeAccountChange(2, true)
    assertTrue(gate.start(2, next) { true })
    gate.finish(next)
  }

  @Test
  fun `persisted rejection stays closed until current attempt authenticates and route validates`() {
    val gate = UnifiedPushLifecycle.CredentialSendHandoff()
    gate.beginAccountChange(1)
    gate.completeAccountChange(1, false)
    assertTrue(gate.beginAttempt(1, 10))
    val call = FakeCall()
    assertFalse(gate.start(1, call) { true })
    assertTrue(gate.authorize(1, 10))
    assertFalse(gate.start(1, call) { false })
    assertEquals(0, call.enqueues.get())
    assertTrue(gate.start(1, call) { true })
    gate.finish(call)
  }

  @Test
  fun `rejected call remains stale after the same account reauthenticates`() {
    val gate = UnifiedPushLifecycle.CredentialSendHandoff()
    gate.beginAccountChange(1)
    gate.completeAccountChange(1, true)
    assertTrue(gate.beginAttempt(1, 10))
    val old = FakeCall()
    assertTrue(gate.start(1, old) { true })
    assertTrue(gate.isCurrent(1, old) { true })
    assertTrue(gate.reject(1, 10))
    assertTrue(gate.beginAttempt(1, 11))
    assertTrue(gate.authorize(1, 11))
    assertFalse(gate.isCurrent(1, old) { true })
    val current = FakeCall()
    assertTrue(gate.start(1, current) { true })
    assertTrue(gate.isCurrent(1, current) { true })
    gate.finish(old)
    gate.finish(current)
  }

  @Test
  fun `reverse lock order is rejected before acquiring the handoff`() {
    val gate = UnifiedPushLifecycle.CredentialSendHandoff()
    gate.beginAccountChange(1)
    gate.completeAccountChange(1, true)
    synchronized(UnifiedPushLifecycle.monitor) {
      assertThrows(IllegalStateException::class.java) { gate.start(1, FakeCall()) { true } }
    }
  }

  @Test
  fun `cancellation runs outside both locks and attempts every call`() {
    val gate = UnifiedPushLifecycle.CredentialSendHandoff()
    gate.beginAccountChange(1)
    gate.completeAccountChange(1, true)
    assertTrue(gate.beginAttempt(1, 10))
    val first = FakeCall(onCancel = {
      assertFalse(Thread.holdsLock(UnifiedPushLifecycle.monitor))
      val checked = CountDownLatch(1)
      val failure = AtomicReference<Throwable?>()
      val worker = thread { try { gate.finish(FakeCall()) } catch (t: Throwable) { failure.set(t) } finally { checked.countDown() } }
      assertTrue(checked.await(5, TimeUnit.SECONDS))
      worker.join()
      assertNull(failure.get())
      throw IllegalStateException("synthetic cancellation failure")
    })
    val second = FakeCall()
    assertTrue(gate.start(1, first) { true })
    assertTrue(gate.start(1, second) { true })
    assertThrows(IllegalStateException::class.java) { gate.reject(1, 10) }
    assertEquals(1, first.cancellations.get())
    assertEquals(1, second.cancellations.get())
    assertFalse(gate.start(1, FakeCall()) { true })
    assertThrows(IllegalStateException::class.java) { gate.beginAccountChange(2) }
    assertThrows(IllegalStateException::class.java) { gate.completeAccountChange(2, true) }
    gate.finish(first)
    gate.finish(second)
    gate.completeAccountChange(2, true)
    assertTrue(gate.start(2, FakeCall()) { true })
  }
}
