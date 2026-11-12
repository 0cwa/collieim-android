package org.thoughtcrime.securesms.keyvalue

import android.app.Application
import im.molly.unifiedpush.control.ControlState
import im.molly.unifiedpush.control.ControlStateRead
import im.molly.unifiedpush.control.CorruptControlStateException
import im.molly.unifiedpush.control.ImportIntent
import im.molly.unifiedpush.control.LegacyPending
import im.molly.unifiedpush.control.Target
import im.molly.unifiedpush.control.UnifiedPushControlStore
import im.molly.unifiedpush.enrollment.ControlKeyId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.util.logging.Log
import org.signal.storage.keyvalue.KeyValueDataSet
import org.signal.storage.keyvalue.KeyValuePersistentStorage
import org.signal.storage.keyvalue.KeyValueStore
import org.signal.storage.keyvalue.MockKeyValuePersistentStorage
import org.thoughtcrime.securesms.testutil.SystemOutLogger
import org.thoughtcrime.securesms.testutil.SnapshotKeyValuePersistentStorage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class UnifiedPushValuesControlTest {
  private val key = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"
  private val account = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
  private val instance = "fixture-instance"
  private val target = Target(
    "https://push.example.invalid/synthetic-endpoint",
    "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4",
    "BTBZMqHH6r4Tts7J_aSIgg",
  )

  @Before fun setUp() = Log.initialize(SystemOutLogger())

  private class Counting(private val inner: KeyValuePersistentStorage) : KeyValuePersistentStorage {
    @Volatile var writes = 0
    override fun writeDataSet(dataSet: KeyValueDataSet, removes: Collection<String>) { writes++; inner.writeDataSet(dataSet, removes) }
    override fun getDataSet() = inner.getDataSet()
  }

  private fun values(initial: KeyValueDataSet = KeyValueDataSet()) =
    UnifiedPushValues(KeyValueStore(MockKeyValuePersistentStorage.withDataSet(initial)))

  private fun intent(sequence: Long = 1) = ImportIntent(
    "22222222-2222-4222-8222-222222222222", sequence, 1, key,
    ControlKeyId.derive(key, account, 2, instance), target, account, 2,
    "SYNTHETIC-NOT-A-SIGNAL-CREDENTIAL", instance, false,
  )

  @Test
  fun `record and replay state are committed together`() {
    val values = values()
    val state = values.mutateControl("replay-json") {
      ControlState(key = key, activeEpoch = 3, highestEpoch = 4, serverTarget = target)
    }
    assertEquals(3, values.enrollmentEpoch)
    assertEquals("replay-json", values.replayState)
    assertEquals(state.serverTarget, values.controlState.serverTarget)
    assertEquals(key, values.controlKey)
  }

  @Test
  fun `a stale read cannot overwrite a newer mutation`() {
    val values = values()
    values.mutateControl { it.copy(activeEpoch = 1, highestEpoch = 1) }
    val stale = values.controlState
    values.mutateControl { it.copy(highestEpoch = 9) }
    values.mutateControl { old -> old.copy(activeEpoch = stale.activeEpoch + 1) }
    assertEquals(9, values.controlState.highestEpoch)
    assertEquals(2, values.controlState.activeEpoch)
  }

  @Test
  fun `legacy epoch keys become unknown identity and migrate on the next write`() {
    val data = KeyValueDataSet().apply { putLong("up.enrollment.epoch", 3); putLong("up.enrollment.pendingEpoch", 4) }
    val values = values(data)
    assertEquals(ControlStateRead.Legacy::class, values.controlStateRead::class)
    assertEquals(3, values.enrollmentEpoch)
    assertEquals(4, values.pendingEnrollmentEpoch)
    assertNull(values.controlKey)

    values.enrollmentEpoch = 5
    val migrated = values.controlStateRead as ControlStateRead.Current
    assertEquals(5, migrated.state.activeEpoch)
    assertEquals(4L, migrated.state.legacyPending?.epoch)
    assertNull(migrated.state.legacyPending?.key)
    assertNull(migrated.state.activeImportId)
  }

  @Test
  fun `legacy active epoch supplies the high water mark for an ordinary no-op wake mutation`() {
    val storage = SnapshotKeyValuePersistentStorage(
      KeyValueDataSet().apply { putLong("up.enrollment.epoch", 7); putLong("up.enrollment.pendingEpoch", 0) },
    )
    val values = UnifiedPushValues(KeyValueStore(storage, java.util.concurrent.Executor { it.run() }))

    val migrated = UnifiedPushControlStore(values).mutate("replay-after-legacy-wake") { it }

    assertEquals(7, migrated.activeEpoch)
    assertEquals(7, migrated.highestEpoch)
    assertNull(migrated.activeImportId)
    assertEquals("replay-after-legacy-wake", values.replayState)
    assertEquals(7, values.controlState.highestEpoch)
    assertTrue(storage.getDataSet().containsKey("up.control.state"))
    assertFalse(storage.getDataSet().containsKey("up.enrollment.epoch"))
    val restored = UnifiedPushValues(KeyValueStore(storage, java.util.concurrent.Executor { it.run() }))
    assertEquals(7, restored.controlState.highestEpoch)
    assertNull(restored.controlState.activeImportId)
  }

  @Test
  fun `legacy fallback rejects out of range active and pending epochs`() {
    val tooHigh = 9007199254740992L
    val badActive = values(KeyValueDataSet().apply { putLong("up.enrollment.epoch", tooHigh) })
    assertEquals(ControlStateRead.Corrupt, badActive.controlStateRead)

    val badPending = values(KeyValueDataSet().apply { putLong("up.enrollment.pendingEpoch", tooHigh) })
    assertEquals(ControlStateRead.Corrupt, badPending.controlStateRead)

    val oldRecord = """{"key":null,"pendingKey":null,"pendingEpoch":$tooHigh,"pendingTarget":null,"activeEpoch":0,"highestEpoch":0,"update":null,"serverTarget":null}"""
    assertEquals(ControlStateRead.Corrupt, ControlState.parse(oldRecord))
  }

  @Test
  fun `recognized old singleton preserves active key and unconfirmed pending export without inventing identity`() {
    val oldRecord = """{"key":"$key","pendingKey":null,"pendingEpoch":0,"pendingTarget":null,"activeEpoch":3,"highestEpoch":3,"update":null,"serverTarget":null}"""
    val values = values(KeyValueDataSet().apply { putString("up.control.state", oldRecord) })
    val legacy = values.controlStateRead as ControlStateRead.Legacy
    assertEquals(key, legacy.state.key)
    assertNull(legacy.state.activeImportId)

    values.mutateControl { it.copy(highestEpoch = 4, legacyPending = LegacyPending(key, 4, target)) }
    val current = values.controlStateRead as ControlStateRead.Current
    assertEquals(key, current.state.key)
    assertEquals(3, current.state.activeEpoch)
    assertEquals(4, current.state.highestEpoch)
    assertEquals(4L, current.state.legacyPending?.epoch)
    assertNull(current.state.activeImportId)
    assertEquals(0, current.state.activeImportSeq)
  }

  @Test
  fun `malformed JSON and incomplete v3 state stay corrupt without legacy fallback`() {
    val data = KeyValueDataSet().apply {
      putString("up.control.state", "{")
      putLong("up.enrollment.epoch", 3)
      putLong("up.enrollment.pendingEpoch", 4)
    }
    val values = values(data)
    assertEquals(ControlStateRead.Corrupt, values.controlStateRead)
    assertThrows(CorruptControlStateException::class.java) { values.controlState }
    assertThrows(CorruptControlStateException::class.java) { values.mutateControl { it.copy(highestEpoch = 9) } }

    val missingIntentAndCounter = """{"format_version":2,"key":null}"""
    val incomplete = values(KeyValueDataSet().apply { putString("up.control.state", missingIntentAndCounter) })
    assertEquals(ControlStateRead.Corrupt, incomplete.controlStateRead)
  }

  @Test
  fun `absent local state has no guessed server floor and begins local counter at one`() {
    val absent = values()
    assertEquals(ControlStateRead.Absent, absent.controlStateRead)
    assertEquals(0, absent.controlState.importSequence)
    assertEquals(0, absent.controlState.activeImportSeq)
    val candidate = intent()
    absent.mutateControl { it.copy(importSequence = 1, intents = listOf(candidate), highestEpoch = 1) }
    assertEquals(1, (absent.controlStateRead as ControlStateRead.Current).state.importSequence)
    // A retained server floor is unknowable after local state loss; the server's stale-sequence rejection is authoritative.
  }

  @Test
  fun `current candidate and sequence survive a new values instance`() {
    val storage = MockKeyValuePersistentStorage.withDataSet(KeyValueDataSet())
    UnifiedPushValues(KeyValueStore(storage)).mutateControl {
      ControlState(key = key, highestEpoch = 1, importSequence = 1, intents = listOf(intent()))
    }

    val restarted = UnifiedPushValues(KeyValueStore(storage))
    val state = (restarted.controlStateRead as ControlStateRead.Current).state
    assertEquals(key, state.intents.single().controlKey)
    assertEquals(1, state.importSequence)
    assertEquals(1, state.intents.single().sequence)
    assertEquals(1, state.intents.single().epoch)
  }

  @Test
  fun `v3 active identity requires a positive epoch and compatibility setter cannot clear it`() {
    val state = ControlState(
      key = key,
      activeKeyId = ControlKeyId.derive(key, account, 2, instance),
      activeImportId = "22222222-2222-4222-8222-222222222222",
      activeImportSeq = 1,
      activeAccountId = account,
      activeDeviceId = 2,
      activeInstance = instance,
      activeEpoch = 1,
      highestEpoch = 1,
      importSequence = 1,
    )
    assertTrue(ControlState.parse(state.serialize()) is ControlStateRead.Current)
    assertEquals(ControlStateRead.Corrupt, ControlState.parse(state.serialize().replace("\"active_epoch\":1", "\"active_epoch\":0")))

    val values = values()
    values.mutateControl { state }
    assertThrows(IllegalArgumentException::class.java) { values.enrollmentEpoch = 0 }
    assertEquals(1, values.enrollmentEpoch)

    values.clearControl()
    values.enrollmentEpoch = 0
    assertEquals(0, values.enrollmentEpoch)
  }

  @Test
  fun `explicit server removal is required before clearing a corrupt record`() {
    val data = KeyValueDataSet().apply { putString("up.control.state", "{"); putString("up.enrollment.replay", "replay-retained") }
    val values = values(data)
    assertTrue(values.clearCorruptControlAfterServerRemoval())
    assertEquals(ControlState(), values.controlState)
    assertEquals("replay-retained", values.replayState)
    assertTrue(values.controlStateRead is ControlStateRead.Current)
  }

  @Test
  fun `clear control removes the counter only through explicit lifecycle reset`() {
    val values = values()
    values.mutateControl { ControlState(key = key, legacyPending = LegacyPending(key, 2, target), activeEpoch = 1, highestEpoch = 7, importSequence = 6) }
    values.clearControl()
    assertEquals(ControlState(), values.controlState)
    assertEquals(0, values.enrollmentEpoch)
    assertEquals(0, values.pendingEnrollmentEpoch)
    assertEquals(0, values.controlState.importSequence)
  }

  @Test
  fun `changing server forgets its keys and epochs but does not lower the import sequence`() {
    val values = values()
    values.mutateControl {
      ControlState(key = key, activeEpoch = 4, highestEpoch = 8, importSequence = 7, legacyPending = LegacyPending(key, 5, target))
    }
    assertTrue(values.clearControlForServerChange())
    assertEquals(7, values.controlState.importSequence)
    assertEquals(0, values.controlState.activeEpoch)
    assertNull(values.controlState.key)
    assertNull(values.controlState.legacyPending)
    assertEquals(0, values.controlState.highestEpoch)
  }

  @Test
  fun `concurrent writers do not lose updates`() {
    val values = values()
    val store = UnifiedPushControlStore(values)
    val threads = 8
    val perThread = 60
    val pool = Executors.newFixedThreadPool(threads)
    val start = CountDownLatch(1)
    repeat(threads) {
      pool.execute {
        start.await()
        repeat(perThread) {
          store.mutate { state -> state.copy(highestEpoch = state.highestEpoch + 1) }
          if (it % 10 == 0) values.enrollmentEpoch = values.controlState.activeEpoch
        }
      }
    }
    start.countDown()
    pool.shutdown()
    assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS))
    assertEquals(threads * perThread.toLong(), values.controlState.highestEpoch)
  }

  @Test
  fun `unchanged mutations do not write unless replay state changes`() {
    val storage = Counting(MockKeyValuePersistentStorage.withDataSet(KeyValueDataSet()))
    val values = UnifiedPushValues(KeyValueStore(storage))
    values.mutateControl { it.copy(activeEpoch = 1, highestEpoch = 1) }
    assertEquals(1, storage.writes)
    values.mutateControl { it }
    values.mutateControl { it.copy(activeEpoch = 1, highestEpoch = 1) }
    assertEquals(1, storage.writes)
    values.mutateControl("replay") { it }
    assertEquals(2, storage.writes)
    assertEquals("replay", values.replayState)
  }
}
