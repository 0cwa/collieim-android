/* SPDX-License-Identifier: AGPL-3.0-only */
package org.thoughtcrime.securesms.calls.permissions

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.thoughtcrime.securesms.service.webrtc.links.CallLinkCredentials
import java.security.SecureRandom

class CallLinkCredentialsGenerationTest {
  private val crn = ByteArray(16) { (it + 1).toByte() }
  private val admin = ByteArray(16) { (it + 80).toByte() }

  @Test
  fun generatesPendingV1KeyAndIndependentAdminSecret() {
    val random = SequenceRandom(crn, admin)
    val credentials = CallLinkCredentials.generate(random)

    assertEquals(2, random.draws)
    assertEquals(21, credentials.linkKeyBytes.size)
    assertArrayEquals(crn, credentials.linkKeyBytes.copyOf(16))
    assertArrayEquals(byteArrayOf(0x81.toByte(), 0, 0, 0, 0), credentials.linkKeyBytes.copyOfRange(16, 21))
    assertArrayEquals(admin, credentials.adminPassBytes)
  }

  @Test
  fun resamplesRejectedCrnWithoutChangingAcceptedEntropy() {
    val rejected = crn.copyOf().also { it[6] = 0x55; it[7] = 0x55 }
    val random = SequenceRandom(rejected, crn, admin)
    val credentials = CallLinkCredentials.generate(random)

    assertEquals(3, random.draws)
    assertArrayEquals(crn, credentials.linkKeyBytes.copyOf(16))
    assertArrayEquals(admin, credentials.adminPassBytes)
  }

  @Test
  fun boundsFailureFromRepeatedInvalidEntropy() {
    var draws = 0
    val brokenRandom = object : SecureRandom() {
      override fun nextBytes(bytes: ByteArray) {
        draws++
        bytes.fill(0)
      }
    }

    assertThrows(IllegalStateException::class.java) { CallLinkCredentials.generate(brokenRandom) }
    assertEquals(16, draws)
  }

  @Test
  fun generatedMetadataDoesNotNeedNativeManager() {
    val credentials = CallLinkCredentials.generate(SequenceRandom(crn, ByteArray(16)))
    val managed = requireNotNull(ManagedCallLinkRootKey.fromBytes(credentials.linkKeyBytes))
    val roundTrip = requireNotNull(ManagedCallLinkRootKey.parse(managed.toFormattedString()))

    assertTrue(CallLinkKeyCodec.valid(credentials.linkKeyBytes))
    assertEquals(47, managed.toFormattedString().length)
    assertEquals(credentials.roomId, roundTrip.roomId)
    assertArrayEquals(credentials.linkKeyBytes, roundTrip.keyBytes)
    // Native places no repeated-chunk constraint on the independent admin secret.
    assertArrayEquals(ByteArray(16), credentials.adminPassBytes)
  }

  private class SequenceRandom(private vararg val values: ByteArray) : SecureRandom() {
    var draws = 0
      private set

    override fun nextBytes(bytes: ByteArray) {
      check(draws < values.size) { "Unexpected entropy request" }
      val value = values[draws++]
      check(value.size == bytes.size)
      value.copyInto(bytes)
    }
  }
}
