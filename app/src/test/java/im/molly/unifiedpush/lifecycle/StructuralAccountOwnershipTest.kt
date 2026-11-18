package im.molly.unifiedpush.lifecycle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class StructuralAccountOwnershipTest {
  @Test fun `writer closes admission and drains existing leases before mutation`() {
    val gate = StructuralAccountOwnership(initiallyReady = true)
    val old = checkNotNull(gate.acquire { "account-a" })
    val writer = checkNotNull(gate.beginChange())
    assertFalse(old.isCurrent())
    assertNull(gate.acquire { "account-a" })
    assertNull(gate.beginChange())
    val started = CountDownLatch(1)
    val mutated = CountDownLatch(1)
    val failure = AtomicReference<Throwable?>()
    val worker = thread {
      try { started.countDown(); writer.run({ true }) { mutated.countDown() } } catch (t: Throwable) { failure.set(t) }
    }
    try {
      assertTrue(started.await(5, TimeUnit.SECONDS))
      assertFalse(mutated.await(100, TimeUnit.MILLISECONDS))
    } finally { old.close() }
    worker.join(5000)
    assertFalse(worker.isAlive)
    assertNull(failure.get())
    assertEquals(0L, mutated.count)
    val current = checkNotNull(gate.acquire { "account-b" })
    assertEquals("account-b", current.snapshot)
    current.close()
    assertNull(gate.acquire(old.epoch) { "account-a" })
  }

  @Test fun `failed or abandoned writer leaves admission closed until a successor completes`() {
    val gate = StructuralAccountOwnership(initiallyReady = true)
    val first = checkNotNull(gate.beginChange())
    first.close()
    assertNull(gate.acquire { "account-a" })
    val recovery = checkNotNull(gate.beginChange())
    assertThrows(IllegalStateException::class.java) { first.run({ true }) { Unit } }
    recovery.run({ true }) { Unit }
    checkNotNull(gate.acquire { "account-b" }).close()
  }

  @Test fun `capture racing writer never returns a usable old snapshot`() {
    val gate = StructuralAccountOwnership(initiallyReady = true)
    val entered = CountDownLatch(1)
    val resume = CountDownLatch(1)
    val result = AtomicReference<StructuralAccountOwnership.Lease<String>?>()
    val failure = AtomicReference<Throwable?>()
    val worker = thread {
      try { result.set(gate.acquire { entered.countDown(); check(resume.await(5, TimeUnit.SECONDS)); "account-a" }) } catch (t: Throwable) { failure.set(t) }
    }
    assertTrue(entered.await(5, TimeUnit.SECONDS))
    val writer = checkNotNull(gate.beginChange())
    resume.countDown()
    worker.join(5000)
    assertFalse(worker.isAlive)
    assertNull(failure.get())
    assertNull(result.get())
    writer.run({ true }) { Unit }
  }

  @Test fun `capture failure releases lease and same account identity cannot revive an old epoch`() {
    val gate = StructuralAccountOwnership(initiallyReady = true)
    val old = checkNotNull(gate.acquire { "account-a" })
    old.close()
    assertThrows(IllegalArgumentException::class.java) { gate.acquire<String> { throw IllegalArgumentException("synthetic") } }
    checkNotNull(gate.beginChange()).run({ true }) { Unit }
    checkNotNull(gate.beginChange()).run({ true }) { Unit }
    assertNull(gate.acquire(old.epoch) { "account-a" })
    val current = gate.acquire { "account-a" }
    assertNotNull(current)
    current?.close()
  }

  @Test fun `writer failure or cancelled completion remains closed and releases ownership`() {
    val gate = StructuralAccountOwnership(initiallyReady = true)
    val writer = checkNotNull(gate.beginChange())
    assertThrows(IllegalArgumentException::class.java) { writer.run({ true }) { throw IllegalArgumentException("synthetic") } }
    assertNull(gate.acquire { "account-a" })
    checkNotNull(gate.beginChange()).run({ true }) { Unit }
    checkNotNull(gate.acquire { "account-a" }).close()
  }

  @Test fun `writer and completion callbacks do not run under owner monitor`() {
    val gate = StructuralAccountOwnership(initiallyReady = true)
    val writer = checkNotNull(gate.beginChange())
    fun assertOtherThreadCanEnter() {
      val completed = CountDownLatch(1)
      val failure = AtomicReference<Throwable?>()
      val worker = thread {
        try { assertNull(gate.acquire { "account-a" }) } catch (t: Throwable) { failure.set(t) } finally { completed.countDown() }
      }
      assertTrue(completed.await(5, TimeUnit.SECONDS))
      worker.join()
      assertNull(failure.get())
    }
    writer.run(readyAfter = { assertOtherThreadCanEnter(); true }) { assertOtherThreadCanEnter() }
  }

}
