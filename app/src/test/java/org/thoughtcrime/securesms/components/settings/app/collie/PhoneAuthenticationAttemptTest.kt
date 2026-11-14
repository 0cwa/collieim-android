package org.thoughtcrime.securesms.components.settings.app.collie

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneAuthenticationAttemptTest {
  @Test
  fun cancellationBetweenDisposalAndReattachmentDrainsRetainedAttempt() {
    var disclosures = 0
    val attempt = PhoneAuthenticationAttempt()
    attempt.initialize(false)
    assertTrue(attempt.start { disclosures++ })
    attempt.detach()
    attempt.finish(false)
    attempt.initialize(false)
    assertTrue(attempt.start { disclosures++ })
    attempt.finish(true)
    assertEquals(1, disclosures)
  }

  @Test
  fun resultAfterReattachmentCannotAuthorizeReplacement() {
    var disclosures = 0
    val attempt = PhoneAuthenticationAttempt()
    attempt.initialize(false)
    assertTrue(attempt.start { disclosures++ })
    attempt.detach()
    attempt.initialize(false)
    assertFalse(attempt.start { disclosures++ })
    attempt.finish(true)
    assertEquals(0, disclosures)
    assertTrue(attempt.start { disclosures++ })
    attempt.finish(true)
    assertEquals(1, disclosures)
  }

  @Test
  fun processRecreationWaitsForRestoredCredentialResultButNotBiometricPrompt() {
    var changes = 0
    val restoredCredential = PhoneAuthenticationAttempt()
    restoredCredential.initialize(true)
    assertFalse(restoredCredential.start { changes++ })
    restoredCredential.finish(true)
    assertEquals(0, changes)
    assertTrue(restoredCredential.start { changes++ })
    restoredCredential.finish(true)
    assertEquals(1, changes)

    val afterBiometricProcessDeath = PhoneAuthenticationAttempt()
    afterBiometricProcessDeath.initialize(false)
    assertTrue(afterBiometricProcessDeath.start { changes++ })
    afterBiometricProcessDeath.finish(false)
    assertEquals(1, changes)
  }

  @Test
  fun drainedCredentialMarkerDoesNotReturnAfterLaterProcessDeath() {
    val retained = PhoneAuthenticationAttempt()
    retained.initialize(false)
    assertTrue(retained.start {})
    retained.onCredentialLaunched()
    assertTrue(retained.credentialOutstanding)
    retained.detach()
    retained.finish(false)

    // Restoration replays a previously saved marker; composition saves authoritative state.
    retained.initialize(true)
    val resavedMarker = retained.credentialOutstanding
    assertFalse(resavedMarker)

    val afterProcessDeath = PhoneAuthenticationAttempt()
    afterProcessDeath.initialize(resavedMarker)
    assertTrue(afterProcessDeath.start {})
  }

  @Test
  fun retainedAttemptDoesNotReinstateAnAlreadyDrainedCredential() {
    val attempt = PhoneAuthenticationAttempt()
    attempt.initialize(false)
    assertTrue(attempt.start {})
    attempt.detach()
    attempt.finish(false)
    attempt.initialize(true)
    assertTrue(attempt.start {})
  }
}
