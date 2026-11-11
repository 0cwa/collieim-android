package im.molly.unifiedpush.control

import com.fasterxml.jackson.databind.ObjectMapper
import im.molly.unifiedpush.receiver.EncryptedPush
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plain JVM tests (no libsignal) of the endpoint-update state machine, over an in-memory [ControlStore]. */
class EndpointUpdatesTest {

  private class Memory(
    override var controlKey: String? = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8",
    override var enrollmentEpoch: Long = 1,
    override var pendingEnrollmentEpoch: Long = 0,
    override var highestEpoch: Long = 0,
  ) : ControlStore {
    override var update: ControlUpdate? = null
    var commits = 0
    override fun commit(update: ControlUpdate) {
      this.update = update
      highestEpoch = update.epoch
      commits++
    }
    override fun replace(update: ControlUpdate) {
      if (this.update?.epoch == update.epoch) this.update = update
    }
  }

  private val fixture = ObjectMapper().readTree(javaClass.getResourceAsStream("/colliesocket-contract-v2/control-v2.json")!!.readBytes())
  private val plain = ObjectMapper().readTree(fixture["plaintext_utf8"].asText())
  private val account = fixture["account_id"].asText()
  private fun sub(path: String) = ControlFrame.Subscription("https://push.example.invalid/$path", plain["p256dh"].asText(), plain["auth"].asText())
  private fun begin(store: ControlStore, path: String, now: Long = 100, nonce: ByteArray? = null) =
    EndpointUpdates.begin(store, account, 2, sub(path), now, nonce)

  private fun statusEvent(epoch: Long, code: String? = EncryptedPush.CODE_SUBSCRIPTION_UPDATED, kind: EncryptedPush.Kind = EncryptedPush.Kind.STATUS) =
    EncryptedPush.Event(2, "33333333-3333-4333-8333-333333333333", "default", epoch, kind, code, 10, 20)

  @Test
  fun `the first update is allocated one above the active epoch and stored before it is returned`() {
    val store = Memory(enrollmentEpoch = 4)
    val update = begin(store, "a")!!
    assertEquals(5, update.epoch)
    assertEquals(update, store.update)
    assertEquals(5, store.highestEpoch)
    assertEquals(1, store.commits)
  }

  @Test
  fun `epochs go above everything already allocated including an export awaiting import`() {
    val store = Memory(enrollmentEpoch = 2, pendingEnrollmentEpoch = 7, highestEpoch = 5)
    assertEquals(8, begin(store, "a")!!.epoch)
    val higher = Memory(enrollmentEpoch = 2, pendingEnrollmentEpoch = 0, highestEpoch = 9)
    assertEquals(10, begin(higher, "a")!!.epoch)
  }

  @Test
  fun `the same endpoint again resends the stored blob without a new epoch or nonce`() {
    val store = Memory()
    val first = begin(store, "a")!!
    val again = begin(store, "a", now = 500)!!
    assertEquals(first.epoch, again.epoch)
    assertArrayEquals(first.frame, again.frame)
    assertEquals(1, store.commits)
  }

  @Test
  fun `a newer endpoint supersedes the pending update with a higher epoch and a fresh blob`() {
    val store = Memory()
    val first = begin(store, "a")!!
    val second = begin(store, "b")!!
    assertEquals(first.epoch + 1, second.epoch)
    assertFalse(first.frame.contentEquals(second.frame))
    assertEquals(second.epoch, store.update!!.epoch)
    // the superseded epoch is not accepted any more, the new one is
    assertEquals(setOf(1L, second.epoch), EndpointUpdates.acceptedEpochs(store, 1, 0))
  }

  @Test
  fun `a confirmed update leaves the next endpoint one epoch higher still`() {
    val store = Memory()
    val first = begin(store, "a")!!
    store.enrollmentEpoch = first.epoch
    store.update = null
    assertEquals(first.epoch + 1, begin(store, "b")!!.epoch)
  }

  @Test
  fun `nothing is built without an imported control key or before the server proved the enrollment`() {
    assertNull(begin(Memory(controlKey = null), "a"))
    assertNull(begin(Memory(enrollmentEpoch = 0, pendingEnrollmentEpoch = 1), "a"))
    assertEquals(0, Memory(controlKey = null).commits)
  }

  @Test
  fun `the frame carries the allocated epoch and the new endpoint under the stored key`() {
    val store = Memory(enrollmentEpoch = 1)
    val update = begin(store, "a", nonce = ByteArray(12) { it.toByte() })!!
    val expected = ControlFrame.build(store.controlKey!!, account, 2, 2, sub("a"), ByteArray(12) { it.toByte() })
    assertArrayEquals(expected, update.frame)
  }

  @Test
  fun `only subscription_updated at the pending epoch confirms`() {
    val store = Memory()
    val update = begin(store, "a")!!
    assertTrue(EndpointUpdates.confirms(update, statusEvent(update.epoch)))
    assertFalse("old epoch", EndpointUpdates.confirms(update, statusEvent(update.epoch - 1)))
    assertFalse("newer epoch", EndpointUpdates.confirms(update, statusEvent(update.epoch + 1)))
    assertFalse("other status", EndpointUpdates.confirms(update, statusEvent(update.epoch, "connection_conflict")))
    assertFalse("a wake", EndpointUpdates.confirms(update, statusEvent(update.epoch, null, EncryptedPush.Kind.WAKE)))
    assertFalse("a test", EndpointUpdates.confirms(update, statusEvent(update.epoch, null, EncryptedPush.Kind.TEST)))
    assertFalse("nothing pending", EndpointUpdates.confirms(null, statusEvent(update.epoch)))
  }

  @Test
  fun `a delayed confirmation of a superseded update does not confirm the newer one`() {
    val store = Memory()
    val first = begin(store, "a")!!
    val second = begin(store, "b")!!
    assertFalse(EndpointUpdates.confirms(store.update, statusEvent(first.epoch)))
    assertTrue(EndpointUpdates.confirms(store.update, statusEvent(second.epoch)))
  }

  @Test
  fun `accepted epochs are the active one, an export awaiting import and the update awaiting confirmation`() {
    val store = Memory()
    assertEquals(setOf(1L), EndpointUpdates.acceptedEpochs(store, 1, 0))
    assertEquals(setOf(1L, 3L), EndpointUpdates.acceptedEpochs(store, 1, 3))
    begin(store, "a")
    assertEquals(setOf(1L, 2L), EndpointUpdates.acceptedEpochs(store, 1, 0))
    assertEquals(emptySet<Long>(), EndpointUpdates.acceptedEpochs(Memory(), 0, 0))
  }

  @Test
  fun `an update survives serialization byte for byte and attempts are counted`() {
    val update = begin(Memory(), "a")!!.afterAttempt(777)
    val back = ControlUpdate.parse(update.serialize())!!
    assertEquals(update.epoch, back.epoch)
    assertEquals(update.endpoint, back.endpoint)
    assertArrayEquals(update.frame, back.frame)
    assertEquals(1, back.attempts)
    assertEquals(777, back.lastAttemptAt)
    assertTrue(back.sameTarget(sub("a")))
    assertFalse(back.sameTarget(sub("b")))
  }

  @Test
  fun `unreadable stored updates parse to null and the string form hides the blob`() {
    assertNull(ControlUpdate.parse(null))
    assertNull(ControlUpdate.parse("{"))
    assertNull(ControlUpdate.parse("""{"epoch":0,"endpoint":"x","p256dh":"x","auth":"x","frame":"AAAA","created":1,"attempts":0,"last":0}"""))
    val update = begin(Memory(), "a")!!
    assertNotNull(update)
    assertEquals("ControlUpdate(epoch=${update.epoch})", update.toString())
  }
}
