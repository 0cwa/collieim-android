package org.thoughtcrime.securesms.messages

import okio.ByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.thoughtcrime.securesms.calls.permissions.DirectMessageKind
import org.whispersystems.signalservice.internal.push.CallMessage

class CallMessageShapeTest {
  private val offer = CallMessage.Offer(id = 41, type = CallMessage.Offer.Type.OFFER_AUDIO_CALL, opaque = ByteString.EMPTY)

  @Test
  fun `each direct category retains its exact call identifier`() {
    val messages = listOf(
      CallMessage(offer = offer) to DirectMessageKind.OFFER,
      CallMessage(answer = CallMessage.Answer(id = 41, opaque = ByteString.EMPTY)) to DirectMessageKind.ANSWER,
      CallMessage(iceUpdate = listOf(CallMessage.IceUpdate(id = 41, opaque = ByteString.EMPTY))) to DirectMessageKind.ICE,
      CallMessage(hangup = CallMessage.Hangup(id = 41)) to DirectMessageKind.HANGUP,
      CallMessage(busy = CallMessage.Busy(id = 41)) to DirectMessageKind.BUSY
    )
    messages.forEach { (message, kind) ->
      assertEquals(CallMessageShape(kind, 41), CallMessageShape.parse(message))
    }
  }

  @Test
  fun `multiple populated categories are rejected regardless of precedence`() {
    listOf(
      CallMessage(offer = offer, answer = CallMessage.Answer(id = 41, opaque = ByteString.EMPTY)),
      CallMessage(offer = offer, opaque = CallMessage.Opaque(data_ = ByteString.EMPTY)),
      CallMessage(hangup = CallMessage.Hangup(id = 41), busy = CallMessage.Busy(id = 41)),
      CallMessage(iceUpdate = listOf(CallMessage.IceUpdate(id = 41, opaque = ByteString.EMPTY)), opaque = CallMessage.Opaque(data_ = ByteString.EMPTY))
    ).forEach { assertNull(CallMessageShape.parse(it)) }
  }

  @Test
  fun `ICE updates cannot smuggle another call under the final identifier`() {
    assertNull(
      CallMessageShape.parse(
        CallMessage(iceUpdate = listOf(CallMessage.IceUpdate(id = 41, opaque = ByteString.EMPTY), CallMessage.IceUpdate(id = 42, opaque = ByteString.EMPTY)))
      )
    )
    assertNull(CallMessageShape.parse(CallMessage(iceUpdate = listOf(CallMessage.IceUpdate(id = 41)))))
  }

  @Test
  fun `missing required fields never open a session`() {
    listOf(
      CallMessage(),
      CallMessage(offer = offer.copy(id = null)),
      CallMessage(offer = offer.copy(opaque = null)),
      CallMessage(answer = CallMessage.Answer(id = 41)),
      CallMessage(hangup = CallMessage.Hangup()),
      CallMessage(busy = CallMessage.Busy()),
      CallMessage(opaque = CallMessage.Opaque())
    ).forEach { assertNull(CallMessageShape.parse(it)) }
    assertNotNull(CallMessageShape.parse(CallMessage(opaque = CallMessage.Opaque(data_ = ByteString.EMPTY))))
  }
}
