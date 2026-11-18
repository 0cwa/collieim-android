package im.molly.unifiedpush.lifecycle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class StructuralAccountCapabilityTest {
  @Test fun `nested synchronous import reuses owner without allowing a competing writer`() {
    val gate = StructuralAccountOwnership(initiallyReady = true)
    val writer = checkNotNull(gate.beginChange())
    var saved: StructuralAccountOwnership.Capability? = null
    val result = writer.runOwned(readyAfter = { true }) { capability ->
      saved = capability
      gate.withCapability(capability) {
        assertNull(gate.beginChange())
        assertNull(gate.acquire { "account-a" })
        "imported"
      }
    }
    assertEquals("imported", result)
    assertTrue(runCatching { gate.withCapability(checkNotNull(saved)) { Unit } }.isFailure)
    checkNotNull(gate.acquire { "account-a" }).close()
  }

  @Test fun `capability cannot authorize another owner or another thread`() {
    val gate = StructuralAccountOwnership(initiallyReady = true)
    val other = StructuralAccountOwnership(initiallyReady = true)
    val writer = checkNotNull(gate.beginChange())
    writer.runOwned(readyAfter = { true }) { capability ->
      assertTrue(runCatching { other.withCapability(capability) { Unit } }.isFailure)
      val done = CountDownLatch(1)
      val failure = AtomicReference<Throwable?>()
      val worker = thread {
        try { gate.withCapability(capability) { error("must not run") } } catch (t: Throwable) { failure.set(t) } finally { done.countDown() }
      }
      assertTrue(done.await(5, TimeUnit.SECONDS))
      worker.join()
      assertTrue(failure.get() is IllegalStateException)
    }
  }

  @Test fun `cancelled capability cannot start another nested mutation`() {
    val gate = StructuralAccountOwnership(initiallyReady = true)
    val writer = checkNotNull(gate.beginChange())
    val failure = runCatching {
      writer.runOwned(readyAfter = { true }) { capability ->
        writer.close()
        assertTrue(runCatching { gate.withCapability(capability) { error("must not run") } }.isFailure)
      }
    }.exceptionOrNull()
    assertTrue(failure is IllegalStateException)
    assertNull(gate.acquire { "account-a" })
    checkNotNull(gate.beginChange()).run({ false }) { Unit }
    assertNull(gate.acquire { "account-a" })
  }
}
