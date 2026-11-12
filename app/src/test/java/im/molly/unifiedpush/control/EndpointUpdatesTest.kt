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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Plain JVM tests (no libsignal) of the endpoint-update state machine, over an in-memory [ControlStore]. */
class EndpointUpdatesTest {

  private val keyOld = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"
  private val keyNew = "HR4fICEiIyQlJicoKSorLC0uLzAxMjM0NTY3ODk6Ozw"

  private class Memory(var state: ControlState) : ControlStore {
    var replay: String? = null
    var writes = 0
    @Synchronized override fun read() = state
    @Synchronized override fun mutate(replay: String?, block: (ControlState) -> ControlState): ControlState {
      state = block(state)
      if (replay != null) this.replay = replay
      writes++
      return state
    }
  }

  private val fixture = ObjectMapper().readTree(javaClass.getResourceAsStream("/colliesocket-contract-v2/control-v2.json")!!.readBytes())
  private val plain = ObjectMapper().readTree(fixture["plaintext_utf8"].asText())
  private val account = fixture["account_id"].asText()
  private fun sub(path: String) = ControlFrame.Subscription("https://push.example.invalid/$path", plain["p256dh"].asText(), plain["auth"].asText())
  private fun target(path: String) = Target("https://push.example.invalid/$path", plain["p256dh"].asText(), plain["auth"].asText())

  /** A keyed enrollment the server holds at epoch 1 with endpoint "old". */
  private fun enrolled(block: ControlState.() -> ControlState = { this }) =
    Memory(ControlState(key = keyOld, activeEpoch = 1, highestEpoch = 1, serverTarget = target("old")).block())

  private fun reconcile(store: ControlStore, path: String, now: Long = 100, nonce: ByteArray? = null, force: Boolean = false) =
    EndpointUpdates.reconcile(store, account, 2, sub(path), now, nonce, force)

  private fun event(epoch: Long, code: String? = EncryptedPush.CODE_SUBSCRIPTION_UPDATED, kind: EncryptedPush.Kind = EncryptedPush.Kind.STATUS) =
    EncryptedPush.Event(2, "33333333-3333-4333-8333-333333333333", "default", epoch, kind, code, 10, 20)

  // --- reconcile ---

  @Test
  fun `a subscription the server does not hold gets an update one epoch above the highest, stored before it is returned`() {
    val store = enrolled { copy(highestEpoch = 4) }
    val r = reconcile(store, "new")
    assertTrue(r.built)
    assertEquals(5, r.update!!.epoch)
    assertEquals(5, store.state.highestEpoch)
    assertEquals(r.update!!.epoch, store.state.update!!.epoch)
  }

  @Test
  fun `the epoch is also above the active epoch and an export awaiting import`() {
    assertEquals(8, reconcile(enrolled { copy(activeEpoch = 2, pendingEpoch = 7, highestEpoch = 5) }, "new").update!!.epoch)
    assertEquals(10, reconcile(enrolled { copy(activeEpoch = 9, highestEpoch = 0) }, "new").update!!.epoch)
  }

  @Test
  fun `calling again resends the stored blob without a new epoch or nonce`() {
    val store = enrolled()
    val first = reconcile(store, "new").update!!
    val again = reconcile(store, "new", now = 500)
    assertFalse(again.built)
    assertArrayEquals(first.frame, again.update!!.frame)
    assertEquals(first.epoch, store.state.highestEpoch)
  }

  @Test
  fun `a newer endpoint supersedes the pending update with a higher epoch and a fresh blob`() {
    val store = enrolled()
    val first = reconcile(store, "a").update!!
    val second = reconcile(store, "b").update!!
    assertEquals(first.epoch + 1, second.epoch)
    assertFalse(first.frame.contentEquals(second.frame))
    assertEquals(setOf(1L, second.epoch), EndpointUpdates.acceptedEpochs(store.state))
  }

  @Test
  fun `going back to the endpoint the server holds replaces a pending update for another one`() {
    val store = enrolled()
    reconcile(store, "a")
    val back = reconcile(store, "old")
    assertTrue(back.built)
    assertEquals(3, back.update!!.epoch)
    assertTrue(back.update!!.sameTarget(sub("old")))
  }

  @Test
  fun `nothing is built without a key, before the server proved the enrollment, with nothing to send, or when the server already holds it`() {
    assertFalse(reconcile(Memory(ControlState(activeEpoch = 1)), "new").built)
    assertFalse(reconcile(Memory(ControlState(key = keyOld, pendingEpoch = 1, pendingKey = keyNew)), "new").built)
    assertFalse(EndpointUpdates.reconcile(enrolled(), account, 2, null, 1).built)
    val held = enrolled()
    val before = held.state
    assertFalse(reconcile(held, "old").built)
    assertEquals(before, held.state)
  }

  @Test
  fun `force builds an update for the subscription the server already holds`() {
    val r = reconcile(enrolled(), "old", force = true)
    assertTrue(r.built)
    assertEquals(2, r.update!!.epoch)
  }

  @Test
  fun `the frame carries the allocated epoch and the new endpoint under the stored key`() {
    val store = enrolled()
    val update = reconcile(store, "a", nonce = ByteArray(12) { it.toByte() }).update!!
    assertArrayEquals(ControlFrame.build(keyOld, account, 2, 2, sub("a"), ByteArray(12) { it.toByte() }), update.frame)
  }

  // --- recovery: the edge trigger used to lose these ---

  @Test
  fun `an update lost after the endpoint was stored is rebuilt by the next reconcile`() {
    // The endpoint was written, the process died before the update was committed: nothing records the change.
    val store = enrolled()
    assertNull(store.state.update)
    assertTrue(reconcile(store, "stored-before-the-kill").built)
  }

  @Test
  fun `an endpoint that changed after the export is rebuilt after promotion under the new key`() {
    val store = enrolled { copy(pendingEpoch = 2, pendingKey = keyNew, pendingTarget = target("exported"), update = null) }
    // the endpoint rotated to "rotated" after the file was written, while the server holds nothing yet
    assertEquals(EndpointUpdates.Effect.PROMOTED, EndpointUpdates.accepted(store, "replay-1", event(2, null, EncryptedPush.Kind.WAKE)))
    assertEquals(keyNew, store.state.key)
    assertEquals(2, store.state.activeEpoch)
    assertTrue(store.state.serverTarget!!.matches(sub("exported")))
    val r = reconcile(store, "rotated")
    assertTrue(r.built)
    assertEquals(3, r.update!!.epoch)
    assertArrayEquals(ControlFrame.build(keyNew, account, 2, 3, sub("rotated"), r.update!!.frame.copyOfRange(46, 58)), r.update!!.frame)
  }

  @Test
  fun `a rotation before the first keyed import is picked up once the import is proven`() {
    val store = Memory(ControlState(pendingEpoch = 1, pendingKey = keyNew, pendingTarget = target("exported")))
    assertFalse("no key yet", reconcile(store, "rotated").built)
    EndpointUpdates.accepted(store, "r", event(1, null, EncryptedPush.Kind.WAKE))
    assertTrue(reconcile(store, "rotated").built)
  }

  @Test
  fun `promotion drops an update built under the old key and keeps one when there was no new key`() {
    val withKey = enrolled { copy(pendingEpoch = 2, pendingKey = keyNew, pendingTarget = target("x")) }
    reconcile(withKey, "a")
    EndpointUpdates.accepted(withKey, "r", event(2, null, EncryptedPush.Kind.WAKE))
    // epoch 2 is the export's; the update built meanwhile is at 3 and is under the old key
    assertNull(withKey.state.update)
    val keepsIt = enrolled { copy(pendingEpoch = 2, pendingKey = null, pendingTarget = target("x")) }
    reconcile(keepsIt, "a")
    EndpointUpdates.accepted(keepsIt, "r", event(2, null, EncryptedPush.Kind.WAKE))
    assertNotNull(keepsIt.state.update)
  }

  // --- races, reproduced with interleaved calls ---

  @Test
  fun `a job recording an attempt for an older update cannot overwrite a newer one`() {
    val store = enrolled()
    val older = reconcile(store, "a").update!!
    val jobSaw = store.read().update!!                      // the job reads the update ...
    val newer = reconcile(store, "b").update!!               // ... a newer endpoint replaces it ...
    EndpointUpdates.recordAttempt(store, jobSaw.epoch, 200, null) // ... and the job records its attempt afterwards
    assertEquals(newer.epoch, store.state.update!!.epoch)
    assertEquals(0, store.state.update!!.attempts)
    assertTrue(store.state.update!!.sameTarget(sub("b")))
    assertEquals(older.epoch + 1, newer.epoch)
  }

  @Test
  fun `a recorded attempt is kept for the update it belongs to`() {
    val store = enrolled()
    val update = reconcile(store, "a").update!!
    EndpointUpdates.recordAttempt(store, update.epoch, 60, "SocketTimeoutException")
    assertEquals("SocketTimeoutException", store.state.update!!.failure)
    assertEquals(0, store.state.update!!.sentAt)
    EndpointUpdates.recordAttempt(store, update.epoch, 70, null)
    assertEquals(70, store.state.update!!.sentAt)
    assertEquals(2, store.state.update!!.attempts)
  }

  @Test
  fun `a confirmation for a superseded update does not clear the newer one`() {
    val store = enrolled()
    val first = reconcile(store, "a").update!!
    // the receiver checked the event against this snapshot, then the endpoint changed before it wrote
    val snapshot = store.read()
    assertTrue(EndpointUpdates.confirms(snapshot.update, event(first.epoch)))
    val second = reconcile(store, "b").update!!
    assertEquals(EndpointUpdates.Effect.NONE, EndpointUpdates.accepted(store, "replay-2", event(first.epoch)))
    assertEquals(second.epoch, store.state.update!!.epoch)
    assertEquals(1, store.state.activeEpoch)
    assertEquals("the event is still recorded as seen, in the same write", "replay-2", store.replay)
  }

  @Test
  fun `a confirmation clears exactly the update it names and moves the server's known subscription`() {
    val store = enrolled()
    val update = reconcile(store, "a").update!!
    store.replay = null
    assertEquals(EndpointUpdates.Effect.CONFIRMED, EndpointUpdates.accepted(store, "replay-3", event(update.epoch)))
    assertNull(store.state.update)
    assertEquals(update.epoch, store.state.activeEpoch)
    assertTrue(store.state.serverTarget!!.matches(sub("a")))
    assertEquals("replay-3", store.replay)
    assertFalse("nothing left to send", reconcile(store, "a").built)
  }

  @Test
  fun `only subscription_updated at the pending epoch confirms`() {
    val update = reconcile(enrolled(), "a").update!!
    assertTrue(EndpointUpdates.confirms(update, event(update.epoch)))
    assertFalse(EndpointUpdates.confirms(update, event(update.epoch - 1)))
    assertFalse(EndpointUpdates.confirms(update, event(update.epoch + 1)))
    assertFalse(EndpointUpdates.confirms(update, event(update.epoch, "connection_conflict")))
    assertFalse(EndpointUpdates.confirms(update, event(update.epoch, null, EncryptedPush.Kind.WAKE)))
    assertFalse(EndpointUpdates.confirms(null, event(update.epoch)))
  }

  @Test
  fun `a confirmed update retires an export awaiting import at the same or a lower epoch and leaves a higher one`() {
    fun run(pendingEpoch: Long): ControlState {
      val store = enrolled { copy(pendingEpoch = pendingEpoch, pendingKey = keyNew, pendingTarget = target("exported"), highestEpoch = 1) }
      val update = ControlUpdate(3, sub("a").endpoint, sub("a").p256dh, sub("a").auth, ByteArray(80), 1)
      store.mutate { it.copy(update = update, highestEpoch = 3) }
      EndpointUpdates.accepted(store, "r", event(3))
      return store.state
    }
    run(3).let { assertEquals(0, it.pendingEpoch); assertNull(it.pendingKey); assertNull(it.pendingTarget); assertEquals(keyOld, it.key) }
    run(2).let { assertEquals(0, it.pendingEpoch); assertNull(it.pendingKey) }
    run(4).let { assertEquals(4, it.pendingEpoch); assertEquals(keyNew, it.pendingKey) }
    // and a later event at the retired export's epoch promotes nothing
    val store = enrolled { copy(pendingEpoch = 3, pendingKey = keyNew, pendingTarget = target("exported")) }
    store.mutate { it.copy(update = ControlUpdate(3, "https://push.example.invalid/a", sub("a").p256dh, sub("a").auth, ByteArray(80), 1)) }
    EndpointUpdates.accepted(store, "r", event(3))
    assertEquals(EndpointUpdates.Effect.NONE, EndpointUpdates.accepted(store, "r2", event(3, null, EncryptedPush.Kind.WAKE)))
    assertEquals(keyOld, store.state.key)
  }

  @Test
  fun `an export is exactly one above the server's epoch, whatever this phone allocated`() {
    assertEquals(2, EndpointUpdates.exportEpoch(1))
    assertEquals(10, EndpointUpdates.exportEpoch(9))
    assertEquals(1, EndpointUpdates.exportEpoch(null))
    assertEquals(1, EndpointUpdates.exportEpoch(0))
    // exporting again before an import reuses the epoch
    val store = Memory(ControlState(key = keyOld, activeEpoch = 1, highestEpoch = 1))
    EndpointUpdates.recordExport(store, 2, keyNew, target("x"))
    assertEquals(2, EndpointUpdates.exportEpoch(1))
  }

  @Test
  fun `an update stuck above the server does not move the export, which only warns`() {
    // The server is at epoch 4. An update at epoch 5 never reached it. The operator enters 4.
    val store = enrolled { copy(activeEpoch = 4, highestEpoch = 4, serverTarget = target("old")) }
    val stuck = reconcile(store, "new").update!!
    assertEquals(5, stuck.epoch)
    val epoch = EndpointUpdates.exportEpoch(4)
    assertEquals("the only epoch the server accepts", 5, epoch)
    assertTrue("but the operator is warned", EndpointUpdates.exportCollides(epoch, store.state))
    assertFalse(EndpointUpdates.exportCollides(6, store.state))
    assertFalse(EndpointUpdates.exportCollides(2, enrolled().state.copy(highestEpoch = 1)))
  }

  @Test
  fun `if the import wins at the stuck update's epoch, promotion drops the update and reconcile goes above it`() {
    val store = enrolled { copy(activeEpoch = 4, highestEpoch = 4, serverTarget = target("old")) }
    reconcile(store, "new")                                       // update at 5 under keyOld, never arrived
    EndpointUpdates.recordExport(store, 5, keyNew, target("new"))  // export at 5
    assertEquals(EndpointUpdates.Effect.PROMOTED, EndpointUpdates.accepted(store, "r", event(5, null, EncryptedPush.Kind.WAKE)))
    assertEquals(keyNew, store.state.key)
    assertNull("the old-key update is dropped", store.state.update)
    assertEquals(5, store.state.activeEpoch)
    // the server holds the exported subscription; a different current one is sent above everything used
    val r = reconcile(store, "newer")
    assertEquals(6, r.update!!.epoch)
    assertArrayEquals(ControlFrame.build(keyNew, account, 2, 6, sub("newer"), r.update!!.frame.copyOfRange(46, 58)), r.update!!.frame)
  }

  @Test
  fun `if the update wins at the stuck epoch, its confirmation retires the export and the old key stays`() {
    val store = enrolled { copy(activeEpoch = 4, highestEpoch = 4, serverTarget = target("old")) }
    val stuck = reconcile(store, "new").update!!
    EndpointUpdates.recordExport(store, 5, keyNew, target("new"))
    assertEquals(EndpointUpdates.Effect.CONFIRMED, EndpointUpdates.accepted(store, "r", event(stuck.epoch)))
    assertEquals(0, store.state.pendingEpoch)
    assertNull(store.state.pendingKey)
    assertEquals(keyOld, store.state.key)
    assertEquals(5, store.state.activeEpoch)
  }

  @Test
  fun `a repeated export at the stuck epoch keeps its key`() {
    val store = enrolled { copy(activeEpoch = 4, highestEpoch = 4) }
    reconcile(store, "new")
    val first = EndpointUpdates.recordExport(store, 5, keyNew, target("new"))
    assertEquals(first, EndpointUpdates.recordExport(store, 5, keyOld, target("new")))
    EndpointUpdates.accepted(store, "r", event(5, null, EncryptedPush.Kind.WAKE))
    assertEquals(keyNew, store.state.key)
  }

  @Test
  fun `a failed publish undoes its export unless a newer one replaced it`() {
    val store = enrolled { copy(pendingEpoch = 2, pendingKey = keyOld, pendingTarget = target("first")) }
    val before = store.state
    EndpointUpdates.recordExport(store, 3, keyNew, target("second"))
    EndpointUpdates.retireExport(store, 3, before)
    assertEquals(2, store.state.pendingEpoch)
    assertEquals(keyOld, store.state.pendingKey)
    EndpointUpdates.recordExport(store, 3, keyNew, target("second"))
    EndpointUpdates.recordExport(store, 4, keyOld, target("third"))
    EndpointUpdates.retireExport(store, 3, before)
    assertEquals(4, store.state.pendingEpoch)
  }

  @Test
  fun `accepted epochs and event versions follow the state`() {
    val store = enrolled()
    assertEquals(setOf(1L), EndpointUpdates.acceptedEpochs(store.state))
    assertEquals(2, EndpointUpdates.versionForEpoch(store.state, 1))
    assertEquals(1, EndpointUpdates.versionForEpoch(Memory(ControlState(activeEpoch = 1)).state, 1))
    val pending = ControlState(activeEpoch = 1, pendingEpoch = 3, pendingKey = keyNew)
    assertEquals(setOf(1L, 3L), EndpointUpdates.acceptedEpochs(pending))
    assertEquals(2, EndpointUpdates.versionForEpoch(pending, 3))
    reconcile(store, "a")
    assertEquals(setOf(1L, 2L), EndpointUpdates.acceptedEpochs(store.state))
    assertEquals(2, EndpointUpdates.versionForEpoch(store.state, 2))
    assertEquals(emptySet<Long>(), EndpointUpdates.acceptedEpochs(ControlState()))
  }

  // --- concurrency: one lock, one re-read ---

  @Test
  fun `concurrent endpoint changes get distinct consecutive epochs and the record stays consistent`() {
    val store = enrolled()
    val threads = 12
    val pool = Executors.newFixedThreadPool(threads)
    val start = CountDownLatch(1)
    val built = java.util.Collections.synchronizedList(mutableListOf<Long>())
    repeat(threads) { i ->
      pool.execute {
        start.await()
        reconcile(store, "endpoint-$i").let { if (it.built) built += it.update!!.epoch }
      }
    }
    start.countDown()
    pool.shutdown()
    assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS))
    assertEquals(threads, built.size)
    assertEquals((2L..(threads + 1L)).toList(), built.sorted())
    assertEquals(threads + 1L, store.state.highestEpoch)
    assertEquals(threads + 1L, store.state.update!!.epoch)
  }

  // --- serialization ---

  @Test
  fun `the whole record survives serialization and never prints its secrets`() {
    val store = enrolled { copy(pendingEpoch = 5, pendingKey = keyNew, pendingTarget = target("p")) }
    reconcile(store, "a").update!!.also { EndpointUpdates.recordAttempt(store, it.epoch, 9, "X") }
    val back = ControlState.parse(store.state.serialize())!!
    assertEquals(store.state.key, back.key)
    assertEquals(store.state.pendingKey, back.pendingKey)
    assertEquals(store.state.pendingEpoch, back.pendingEpoch)
    assertEquals(store.state.pendingTarget, back.pendingTarget)
    assertEquals(store.state.serverTarget, back.serverTarget)
    assertEquals(store.state.highestEpoch, back.highestEpoch)
    assertArrayEquals(store.state.update!!.frame, back.update!!.frame)
    assertEquals(1, back.update!!.attempts)
    assertFalse(store.state.toString().contains(keyOld))
    assertEquals("Target", target("x").toString())
    assertNotNull(back.update)
    assertNull(ControlState.parse("{"))
    assertNull(ControlState.parse(null))
  }

  @Test
  fun `unreadable stored updates parse to null and the string form hides the blob`() {
    assertNull(ControlUpdate.parse(null))
    assertNull(ControlUpdate.parse("{"))
    assertNull(ControlUpdate.parse("""{"epoch":0,"endpoint":"x","p256dh":"x","auth":"x","frame":"AAAA","created":1,"attempts":0,"last":0}"""))
    val update = reconcile(enrolled(), "a").update!!
    assertEquals("ControlUpdate(epoch=${update.epoch})", update.toString())
  }

  @Test
  fun `a ping repeats the current subscription at a higher epoch and is marked as a ping`() {
    val store = enrolled { copy(activeEpoch = 2, highestEpoch = 2) }
    val ping = reconcile(store, "old", force = true).update!!
    assertEquals(3, ping.epoch)
    assertTrue(ping.ping)
    assertEquals(sub("old").endpoint, ping.endpoint)
    // an unconfirmed ping for the same subscription is reused, not stacked
    assertArrayEquals(ping.frame, reconcile(store, "old", force = true).update!!.frame)
    assertEquals(3, store.state.highestEpoch)
    // a real endpoint change replaces it
    val real = reconcile(store, "b").update!!
    assertEquals(4, real.epoch)
    assertFalse(real.ping)
  }

  @Test
  fun `a send that raced a confirmation cannot bring the update back`() {
    val store = enrolled()
    val update = reconcile(store, "a").update!!
    EndpointUpdates.accepted(store, "r", event(update.epoch))
    EndpointUpdates.recordAttempt(store, update.epoch, 99, null)
    assertNull(store.state.update)
  }

  // --- exporting again before an event arrives ---

  @Test
  fun `a re-export at the same epoch keeps the pending key, so the epoch names one key`() {
    val store = enrolled()
    val first = EndpointUpdates.recordExport(store, 2, keyNew, target("a"))
    val second = EndpointUpdates.recordExport(store, 2, "fresh-key-that-must-not-be-used", target("a"))
    assertEquals(keyNew, first)
    assertEquals(keyNew, second)
    assertEquals(keyNew, store.state.pendingKey)
    assertEquals(target("a"), store.state.pendingTarget)
    // the event of the first import promotes exactly the key both files carry
    assertEquals(EndpointUpdates.Effect.PROMOTED, EndpointUpdates.accepted(store, "r", event(2, null, EncryptedPush.Kind.WAKE)))
    assertEquals(keyNew, store.state.key)
  }

  @Test
  fun `an export at another epoch gets a new key`() {
    val store = enrolled()
    EndpointUpdates.recordExport(store, 2, keyNew, target("a"))
    assertEquals(keyOld, EndpointUpdates.recordExport(store, 3, keyOld, target("a")))
    assertEquals(3, store.state.pendingEpoch)
    // and the first export with nothing pending takes the key it was given
    assertEquals(keyNew, EndpointUpdates.recordExport(enrolled(), 2, keyNew, target("a")))
  }

  @Test
  fun `two exports at one epoch with different subscriptions leave the server's subscription unknown, and reconcile sends the current one`() {
    val store = enrolled()
    EndpointUpdates.recordExport(store, 2, keyNew, target("a"))
    EndpointUpdates.recordExport(store, 2, keyOld, target("b"))
    assertNull(store.state.pendingTarget)
    EndpointUpdates.accepted(store, "r", event(2, null, EncryptedPush.Kind.WAKE))
    assertEquals(keyNew, store.state.key)
    assertNull("unknown, not the old one", store.state.serverTarget)
    // whichever file the server imported, the current subscription is sent
    assertTrue(reconcile(store, "b").built)
  }

  @Test
  fun `a re-export after a failed publish does not keep a key nobody saw`() {
    val store = enrolled()
    val before = store.state
    EndpointUpdates.recordExport(store, 2, keyNew, target("a"))
    EndpointUpdates.retireExport(store, 2, before)
    assertNull(store.state.pendingKey)
    assertEquals(keyOld, EndpointUpdates.recordExport(store, 2, keyOld, target("a")))
  }
}
