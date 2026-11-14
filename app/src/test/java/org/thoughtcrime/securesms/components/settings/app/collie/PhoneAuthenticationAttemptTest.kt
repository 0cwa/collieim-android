package org.thoughtcrime.securesms.components.settings.app.collie

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneAuthenticationAttemptTest {
  @Test
  fun recreatedScreenDiscardsOldResultBeforeAllowingNewPrompt() {
    var disclosures = 0
    val old = PhoneAuthenticationAttempt(false)
    assertTrue(old.start { disclosures++ })
    val restored = PhoneAuthenticationAttempt(old.pending)
    old.dispose()
    assertFalse(restored.start { disclosures++ })
    old.finish(true)
    assertEquals(0, disclosures)
    restored.finish(true)
    assertEquals(0, disclosures)
    assertTrue(restored.start { disclosures++ })
    restored.finish(true)
    assertEquals(1, disclosures)
  }

  @Test
  fun cancellationDoesNotRunActionAndAllowsRetry() {
    var changes = 0
    val attempt = PhoneAuthenticationAttempt(false)
    assertTrue(attempt.start { changes++ })
    attempt.finish(false)
    assertEquals(0, changes)
    assertTrue(attempt.start { changes++ })
    attempt.finish(true)
    assertEquals(1, changes)
  }
}
