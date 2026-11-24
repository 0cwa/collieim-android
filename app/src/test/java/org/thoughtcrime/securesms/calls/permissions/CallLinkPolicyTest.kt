package org.thoughtcrime.securesms.calls.permissions

import io.mockk.every
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.reactivex.rxjava3.core.Single
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CallLinkPolicyTest {
  private var generation = 7L
  private var enabled = true

  @Before
  fun setUp() {
    mockkObject(CallPermissions)
    mockkStatic(CallPermissions::class)
    every { CallPermissions.generation() } answers { generation }
    every { CallPermissions.runIfCurrent(any(), any()) } answers {
      if (enabled && firstArg<Long>() == generation) {
        secondArg<Runnable>().run()
        true
      } else {
        false
      }
    }
  }

  @After
  fun tearDown() = unmockkAll()

  @Test
  fun `disabled link operation never obtains the native manager`() {
    enabled = false
    var obtainedManager = false
    val operation = CallLinkPolicy.guarded("denied") {
      obtainedManager = true
      Single.just("allowed")
    }
    operation.test().assertResult("denied")
    assertFalse(obtainedManager)
  }

  @Test
  fun `revocation after scheduling prevents queued link operation even after reallowing`() {
    var obtainedManager = false
    val operation = CallLinkPolicy.guarded("denied") {
      obtainedManager = true
      Single.just("allowed")
    }
    generation += 2 // A restriction and a later relaxation still invalidate the original request.
    operation.test().assertResult("denied")
    assertFalse(obtainedManager)
  }

  @Test
  fun `unchanged default policy permits explicitly requested link operation`() {
    var obtainedManager = false
    CallLinkPolicy.guarded("denied") {
      obtainedManager = true
      Single.just("allowed")
    }.test().assertResult("allowed")
    assertTrue(obtainedManager)
  }
}
