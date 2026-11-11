package org.thoughtcrime.securesms.keyvalue

import im.molly.unifiedpush.control.ControlState
import im.molly.unifiedpush.control.Target
import im.molly.unifiedpush.control.UnifiedPushControlStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import android.app.Application
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.testutil.SystemOutLogger
import org.signal.storage.keyvalue.KeyValueDataSet
import org.signal.storage.keyvalue.KeyValueStore
import org.signal.storage.keyvalue.MockKeyValuePersistentStorage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
/** The real [UnifiedPushValues] over an in-memory key-value store: the control record is written under one lock. */
class UnifiedPushValuesControlTest {

  @Before
  fun setUp() = Log.initialize(SystemOutLogger())


  private fun values(initial: KeyValueDataSet = KeyValueDataSet()) =
    UnifiedPushValues(KeyValueStore(MockKeyValuePersistentStorage.withDataSet(initial)))

  @Test
  fun `the record and the replay state are written together and read back`() {
    val values = values()
    val state = values.mutateControl("replay-json") { ControlState(key = "k", activeEpoch = 3, highestEpoch = 4, serverTarget = Target("https://e/x", "p", "a")) }
    assertEquals(3, values.enrollmentEpoch)
    assertEquals("replay-json", values.replayState)
    assertEquals(state.serverTarget, values.controlState.serverTarget)
    assertEquals("k", values.controlKey)
  }

  @Test
  fun `mutate hands the block the state as it is now, so a stale read cannot win`() {
    val values = values()
    values.mutateControl { it.copy(activeEpoch = 1) }
    val stale = values.controlState
    values.mutateControl { it.copy(highestEpoch = 9) }
    values.mutateControl { old -> old.copy(activeEpoch = stale.activeEpoch + 1) }
    assertEquals(9, values.controlState.highestEpoch)
    assertEquals(2, values.controlState.activeEpoch)
  }

  @Test
  fun `a state written by an older version is read from its two epoch keys until the record exists`() {
    val data = KeyValueDataSet().apply { putLong("up.enrollment.epoch", 3); putLong("up.enrollment.pendingEpoch", 4) }
    val values = values(data)
    assertEquals(3, values.enrollmentEpoch)
    assertEquals(4, values.pendingEnrollmentEpoch)
    assertNull(values.controlKey)
    values.enrollmentEpoch = 5
    assertEquals(5, values.enrollmentEpoch)
    assertEquals("the old pending epoch is carried into the record", 4, values.pendingEnrollmentEpoch)
  }

  @Test
  fun `clearControl forgets everything`() {
    val values = values()
    values.mutateControl { ControlState(key = "k", pendingKey = "p", pendingEpoch = 2, activeEpoch = 1, highestEpoch = 7) }
    values.clearControl()
    assertEquals(ControlState(), values.controlState)
    assertEquals(0, values.enrollmentEpoch)
    assertEquals(0, values.pendingEnrollmentEpoch)
  }

  @Test
  fun `many threads changing the record lose no change`() {
    val values = values()
    val store = UnifiedPushControlStore(values)
    val threads = 8
    val perThread = 60
    val pool = Executors.newFixedThreadPool(threads)
    val start = CountDownLatch(1)
    repeat(threads) { t ->
      pool.execute {
        start.await()
        repeat(perThread) {
          store.mutate { it.copy(highestEpoch = it.highestEpoch + 1) }
          // a second kind of writer interleaves with the first, through the property setters
          if (it % 10 == 0) values.enrollmentEpoch = values.controlState.activeEpoch
        }
      }
    }
    start.countDown()
    pool.shutdown()
    assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS))
    assertEquals(threads * perThread.toLong(), values.controlState.highestEpoch)
  }
}
