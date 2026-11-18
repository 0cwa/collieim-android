package im.molly.unifiedpush.lifecycle

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class AccountOwnershipLeaseTest {
  private lateinit var gate: UnifiedPushLifecycle.CredentialSendHandoff

  @Before fun setUp() {
    UnifiedPushLifecycle.resetForTests()
    gate = UnifiedPushLifecycle.CredentialSendHandoff()
    gate.beginAccountChange(1)
    gate.completeAccountChange(1, false)
  }

  @Test fun `pending route does not block an account callback lease`() {
    UnifiedPushLifecycle.initialize(incompleteTransition = false, legacyContextIsCoherent = true, pendingEndpoint = "pending")
    assertFalse(UnifiedPushLifecycle.isReady())
    val account = gate.acquireAccountLease { "account-a" }
    assertNotNull(account)
    account?.close()
  }

  @Test fun `credential rejection does not prevent account cleanup lease`() {
    assertTrue(gate.beginAttempt(1, 10))
    assertTrue(gate.authorize(1, 10))
    assertTrue(gate.reject(1, 10))
    val account = gate.acquireAccountLease { "account-a" }
    assertNotNull(account)
    account?.close()
  }

  @Test fun `account lease cannot be closed in reverse lock order`() {
    val account = checkNotNull(gate.acquireAccountLease { "account-a" })
    synchronized(UnifiedPushLifecycle.monitor) {
      assertThrows(IllegalStateException::class.java) { account.close() }
    }
    account.close()
    gate.beginAccountChange(2)
    gate.awaitAccountWorkDrained()
    gate.completeAccountChange(2, false)
  }

  @Test fun `account change closes admission and waits for account callbacks`() {
    val old = checkNotNull(gate.acquireAccountLease { "account-a" })
    gate.beginAccountChange(2)
    assertNull(gate.acquireAccountLease { "account-a" })
    assertThrows(IllegalStateException::class.java) { gate.completeAccountChange(2, false) }
    val attempting = CountDownLatch(1)
    val completed = CountDownLatch(1)
    val failure = AtomicReference<Throwable?>()
    val worker = thread {
      try { attempting.countDown(); gate.awaitAccountWorkDrained() } catch (t: Throwable) { failure.set(t) } finally { completed.countDown() }
    }
    try {
      assertTrue(attempting.await(5, TimeUnit.SECONDS))
      assertFalse(completed.await(100, TimeUnit.MILLISECONDS))
    } finally { old.close() }
    assertTrue(completed.await(5, TimeUnit.SECONDS))
    worker.join()
    assertNull(failure.get())
    gate.completeAccountChange(2, false)
    assertNull(gate.acquireAccountLease(old.epoch) { "account-a" })
    val current = gate.acquireAccountLease { "account-b" }
    assertNotNull(current)
    current?.close()
  }

  @Test fun `route-only change neither drains nor advances account ownership`() {
    val account = checkNotNull(gate.acquireAccountLease { "account-a" })
    try {
      runBlocking { UnifiedPushLifecycle.withTransition(persistIncompleteMarker = {}, readyAfter = { true }) { Unit } }
      val same = gate.acquireAccountLease(account.epoch) { "account-a" }
      assertNotNull(same)
      same?.close()
    } finally { account.close() }
  }

  @Test fun `interrupted drain keeps the account closed until work finishes`() {
    val old = checkNotNull(gate.acquireAccountLease { "account-a" })
    gate.beginAccountChange(2)
    val attempting = CountDownLatch(1)
    val failure = AtomicReference<Throwable?>()
    val worker = thread {
      try { attempting.countDown(); gate.awaitAccountWorkDrained() } catch (t: Throwable) { failure.set(t) }
    }
    try {
      assertTrue(attempting.await(5, TimeUnit.SECONDS))
      worker.interrupt()
      worker.join(5000)
      assertFalse(worker.isAlive)
      assertTrue(failure.get() is InterruptedException)
      assertNull(gate.acquireAccountLease { "account-a" })
      assertThrows(IllegalStateException::class.java) { gate.completeAccountChange(2, false) }
    } finally { old.close() }
    gate.awaitAccountWorkDrained()
    gate.completeAccountChange(2, false)
    val current = gate.acquireAccountLease { "account-b" }
    assertNotNull(current)
    current?.close()
  }

  @Test fun `account change waits for both account leases and credential calls`() {
    gate.beginAccountChange(2)
    gate.completeAccountChange(2, true)
    val account = checkNotNull(gate.acquireAccountLease { "account-a" })
    val call = object : UnifiedPushLifecycle.CredentialSendHandoff.Call {
      override fun enqueue() = Unit
      override fun cancel() = Unit
    }
    assertTrue(gate.start(2, call) { true })
    gate.beginAccountChange(3)
    val attempting = CountDownLatch(1)
    val done = CountDownLatch(1)
    val failure = AtomicReference<Throwable?>()
    val worker = thread {
      try { attempting.countDown(); gate.awaitAccountWorkDrained() } catch (t: Throwable) { failure.set(t) } finally { done.countDown() }
    }
    try {
      assertTrue(attempting.await(5, TimeUnit.SECONDS))
      account.close()
      assertFalse(done.await(100, TimeUnit.MILLISECONDS))
    } finally { gate.finish(call) }
    assertTrue(done.await(5, TimeUnit.SECONDS))
    worker.join()
    assertNull(failure.get())
    gate.completeAccountChange(3, false)
  }
}
