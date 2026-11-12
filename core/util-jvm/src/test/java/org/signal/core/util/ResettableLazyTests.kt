/*
 * Copyright 2024 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class ResettableLazyTests {

  @Test
  fun `value only computed once`() {
    var counter = 0
    val lazy: Int by resettableLazy {
      counter++
    }

    assertEquals(0, lazy)
    assertEquals(0, lazy)
    assertEquals(0, lazy)
  }

  @Test
  fun `value recomputed after a reset`() {
    var counter = 0
    val _lazy = resettableLazy {
      counter++
    }
    val lazy by _lazy

    assertEquals(0, lazy)
    _lazy.reset()

    assertEquals(1, lazy)
    _lazy.reset()

    assertEquals(2, lazy)
  }

  @Test
  fun `isInitialized - general`() {
    val _lazy = resettableLazy { 1 }
    val lazy: Int by _lazy

    assertFalse(_lazy.isInitialized())

    val x = lazy + 1
    assertEquals(2, x)
    assertTrue(_lazy.isInitialized())

    _lazy.reset()
    assertFalse(_lazy.isInitialized())
  }

  /**
   * I've verified that without the synchronization inside of resettableLazy, this test usually fails.
   */
  @Test
  fun `ensure synchronization works`() {
    val numRounds = 100
    val numThreads = 5

    for (i in 1..numRounds) {
      var counter = 0
      val lazy: Int by resettableLazy {
        counter++
      }

      val latch = CountDownLatch(numThreads)

      for (j in 1..numThreads) {
        Thread {
          val x = lazy + 1
          latch.countDown()
        }.start()
      }

      latch.await()

      assertEquals(1, counter)
    }
  }

  @Test
  fun `reset waits for an in-flight initializer before invalidating its publication`() {
    val initializerStarted = CountDownLatch(1)
    val allowInitializerToReturn = CountDownLatch(1)
    val resetAttempted = CountDownLatch(1)
    val resetFinished = CountDownLatch(1)
    val values = AtomicInteger()
    val lazyDelegate = resettableLazy {
      val value = values.getAndIncrement()
      if (value == 0) {
        initializerStarted.countDown()
        check(allowInitializerToReturn.await(5, TimeUnit.SECONDS))
      }
      value
    }
    val holder = object {
      val value by lazyDelegate
    }
    val firstValue = AtomicReference<Int?>()
    val reader = Thread { firstValue.set(holder.value) }
    val resetter = Thread {
      resetAttempted.countDown()
      lazyDelegate.reset()
      resetFinished.countDown()
    }

    reader.start()
    assertTrue(initializerStarted.await(5, TimeUnit.SECONDS))
    resetter.start()
    assertTrue(resetAttempted.await(5, TimeUnit.SECONDS))

    while (resetFinished.count == 1L && resetter.state != Thread.State.BLOCKED) {
      Thread.yield()
    }
    val resetWaitedForInitializer = resetter.state == Thread.State.BLOCKED
    allowInitializerToReturn.countDown()
    reader.join(5000)
    resetter.join(5000)

    assertTrue("reset must wait for the current initializer", resetWaitedForInitializer)
    assertTrue("reader must finish", !reader.isAlive)
    assertTrue("reset must finish", !resetter.isAlive)
    assertEquals(0, firstValue.get())
    assertEquals(1, holder.value)
  }
}
