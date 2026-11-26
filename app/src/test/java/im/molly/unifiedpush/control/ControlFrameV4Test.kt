package im.molly.unifiedpush.control

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import im.molly.unifiedpush.receiver.EncryptedPush
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlFrameV4Test {
  private val mapper = ObjectMapper()
  private fun fixture(name: String) = mapper.readTree(checkNotNull(javaClass.getResourceAsStream("/colliesocket-encrypted-push-v4/fixtures/$name.json")).use { it.readBytes() })

  @Test fun `pinned control fixture matches header ciphertext and tag byte for byte`() {
    val vector = fixture("control-vector")
    val plain = fixture("control-plaintext")
    val subscription = ControlFrame.Subscription(plain["endpoint"].asText(), plain["p256dh"].asText(), plain["auth"].asText())
    val frame = ControlFrameV4.build(
      vector["control_key"].asText(), vector["account_id"].asText(), vector["device_id"].intValue(),
      vector["receiver_id"].asText(), vector["epoch"].longValue(), subscription,
      vector["nonce_hex"].asText().hexBytes(),
    )
    assertEquals(vector["frame_hex"].asText(), frame.toHex())
    assertEquals(vector["aad_hex"].asText(), frame.copyOfRange(0, ControlFrameV4.HEADER_SIZE).toHex())
    assertTrue(ControlFrameV4.matchesIdentity(frame, vector["account_id"].asText(), 2, vector["receiver_id"].asText(), 2))
    assertFalse(ControlFrameV4.matchesIdentity(frame, vector["account_id"].asText(), 2, "f0000000-0000-4000-8000-000000000001", 2))
    val changedTag = frame.clone().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
    assertFalse(changedTag.contentEquals(frame))
  }

  @Test fun `v4 imported wake and rotated confirmation fixtures are identity bound`() {
    val imported = event("imported")
    val wake = event("wake")
    val updated = event("updated")
    val initial = ReceiverControlState(
      receiverId = imported.receiverId!!, importId = imported.importId!!,
      accountId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", bridgeDeviceId = 2,
      instance = imported.instance, hostLabel = "Fixture host", controlKey = fixture("receiver")["control_key"].asText(), epoch = 1,
      target = Target("https://push.example.test/second", fixture("receiver")["subscription"]["p256dh"].asText(), fixture("receiver")["subscription"]["auth"].asText()),
    )
    assertTrue(initial.identityMatches(imported, initial.accountId))
    val confirmed = initial.accepted(imported, initial.accountId)!!
    assertTrue(confirmed.confirmed)
    assertTrue(confirmed.identityMatches(wake, confirmed.accountId))
    assertFalse(confirmed.identityMatches(updated, confirmed.accountId))
    val changed = ControlFrame.Subscription("HTTPS://push.example.test/rotated", fixture("receiver")["subscription"]["p256dh"].asText(), fixture("receiver")["subscription"]["auth"].asText())
    val pending = ReceiverControlState.reconcile(confirmed, confirmed.accountId, 2, changed, 1_800_000_001L)!!
    assertNotNull(ReceiverControlState.parse(pending.serialize()))
    assertNull(ReceiverControlState.parse(pending.copy(pendingTarget = null).serialize()))
    assertTrue(pending.identityMatches(updated, pending.accountId))
    val complete = pending.accepted(updated, pending.accountId)!!
    assertEquals(2, complete.epoch)
    assertNull(complete.pendingEpoch)
    assertFalse(complete.identityMatches(wake.copy(receiverId = "f0000000-0000-4000-8000-000000000001"), complete.accountId))
    assertFalse(complete.identityMatches(wake, "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"))
    assertNotNull(ReceiverControlState.parse(complete.serialize()))
  }

  @Test fun `v4 event fixtures enforce identity replay clock and event handling`() {
    val now = 1_800_000_001L
    val importedBytes = fixtureBytes("imported")
    val imported = checkNotNull(EncryptedPush.parse(importedBytes, now, setOf(4)))
    val receiver = fixture("receiver")
    val initial = ReceiverControlState(
      receiverId = receiver["receiver_id"].asText(), importId = receiver["request_id"].asText(),
      accountId = receiver["account_id"].asText(), bridgeDeviceId = receiver["device_id"].intValue(),
      instance = receiver["subscription"]["instance"].asText(), hostLabel = receiver["label"].asText(),
      controlKey = receiver["control_key"].asText(), epoch = receiver["subscription"]["subscription_epoch"].longValue(),
      target = Target(receiver["subscription"]["endpoint"].asText(), receiver["subscription"]["p256dh"].asText(), receiver["subscription"]["auth"].asText()),
    )
    fun decide(bytes: ByteArray, state: EncryptedPush.ReplayState, at: Long = now, identity: (EncryptedPush.Event) -> Boolean = { initial.identityMatches(it, initial.accountId) }) =
      EncryptedPush.decide(
        bytes, decrypted = true, now = at, locked = { false }, activeInstance = initial.instance,
        acceptedEpochs = setOf(initial.epoch), state = state, versionsForEpoch = { setOf(4) }, identityMatches = identity,
      )
    val first = decide(importedBytes, EncryptedPush.ReplayState(now - 1, emptyList()))
    assertEquals(EncryptedPush.Decision.ACCEPT_STATUS, first.decision)
    val confirmed = initial.accepted(first.event!!, initial.accountId)!!
    assertTrue(confirmed.confirmed)

    val wakeBytes = fixtureBytes("wake")
    val wake = decide(wakeBytes, first.state, identity = { confirmed.identityMatches(it, confirmed.accountId) })
    assertEquals(EncryptedPush.Decision.ACCEPT_WAKE, wake.decision)
    assertEquals(EncryptedPush.Decision.DROP_DUPLICATE, decide(wakeBytes, wake.state, at = now + 1, identity = { confirmed.identityMatches(it, confirmed.accountId) }).decision)
    assertEquals(EncryptedPush.Decision.DROP_CLOCK_ROLLBACK, decide(wakeBytes, wake.state.copy(clockHighWater = now + 2), identity = { confirmed.identityMatches(it, confirmed.accountId) }).decision)
    assertEquals(EncryptedPush.Decision.DROP_ENROLLMENT_MISMATCH, decide(wakeBytes, first.state, identity = { confirmed.identityMatches(it, "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb") }).decision)

    val testNode = fixture("wake").deepCopy<ObjectNode>().put("kind", "test").put("event_id", "77777777-7777-4777-8777-777777777777")
    val test = decide(testNode.toString().toByteArray(), wake.state, at = now + 1, identity = { confirmed.identityMatches(it, confirmed.accountId) })
    assertEquals(EncryptedPush.Decision.ACCEPT_TEST, test.decision)
    assertNull(EncryptedPush.parse(importedBytes, 1_800_000_300L, setOf(4)))
    assertNull(EncryptedPush.parse(importedBytes, 1_799_999_939L, setOf(4)))
  }

  @Test fun `pinned v4 invalid event mutations are rejected by the runtime parser`() {
    val cases = fixture("invalid")
    var checked = 0
    for (case in cases) {
      if (case["schema"].asText() != "event") continue
      val source = fixture(case["source"].asText()).deepCopy<ObjectNode>()
      if (case.has("remove")) {
        source.remove(case["remove"].asText())
      } else {
        val parts = case["field"].asText().split('.')
        var target: ObjectNode = source
        for (part in parts.dropLast(1)) target = target[part] as ObjectNode
        target.set<com.fasterxml.jackson.databind.JsonNode>(parts.last(), case["value"])
      }
      assertNull("accepted pinned invalid event case: $case", EncryptedPush.parse(source.toString().toByteArray(), 1_800_000_001L, setOf(4)))
      checked++
    }
    assertTrue("fixture should cover multiple event failures", checked >= 6)
  }

  private fun event(name: String): EncryptedPush.Event = checkNotNull(EncryptedPush.parse(
    fixtureBytes(name),
    1_800_000_001L, setOf(4),
  ))

  private fun fixtureBytes(name: String) = checkNotNull(javaClass.getResourceAsStream("/colliesocket-encrypted-push-v4/fixtures/$name.json")).use { it.readBytes() }

  private fun String.hexBytes() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
  private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
}
