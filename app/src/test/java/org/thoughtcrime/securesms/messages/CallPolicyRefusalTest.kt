package org.thoughtcrime.securesms.messages

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallPolicyRefusalTest {
  @Test
  fun `installation-local policy does not cancel ringing on permitted linked devices`() {
    assertFalse(CallPolicyRejector.shouldReply(policyDenied = true, hasLinkedDevices = true))
  }

  @Test
  fun `single device policy refusal uses existing encrypted Signal transport`() {
    assertTrue(CallPolicyRejector.shouldReply(policyDenied = true, hasLinkedDevices = false))
  }

  @Test
  fun `existing trust and PNI refusal keeps upstream multi-device behavior`() {
    assertTrue(CallPolicyRejector.shouldReply(policyDenied = false, hasLinkedDevices = true))
    assertTrue(CallPolicyRejector.shouldReply(policyDenied = false, hasLinkedDevices = false))
  }
}
