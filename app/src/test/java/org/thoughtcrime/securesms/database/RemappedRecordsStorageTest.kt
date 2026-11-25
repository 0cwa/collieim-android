package org.thoughtcrime.securesms.database

import android.app.Application
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.database.withinTransaction
import org.signal.core.models.recipients.RecipientId
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.testutil.MockAppDependenciesRule
import org.thoughtcrime.securesms.testutil.MockSignalStoreRule
import org.thoughtcrime.securesms.testutil.SignalDatabaseRule
import org.thoughtcrime.securesms.testutil.SystemOutLogger
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE)
class RemappedRecordsStorageTest {
  private val store = MockSignalStoreRule()
  private val dependencies = MockAppDependenciesRule()
  private val database = SignalDatabaseRule()
  @get:Rule val rules: RuleChain = RuleChain.outerRule(store).around(dependencies).around(database)

  @Before fun setup() {
    Log.initialize(SystemOutLogger())
  }


  @Test fun `uncommitted mappings are not published after rollback`() {
    val records = RemappedRecords.getInstance()
    val db = SignalDatabase.writableDatabase
    db.beginTransaction()
    try {
      records.addThread(51L, 52L)
      records.addRecipient(RecipientId.from(61), RecipientId.from(62))
      assertEquals(52L, records.getThread(51L).get())
      assertEquals(RecipientId.from(62), records.getRecipient(RecipientId.from(61)).get())
    } finally {
      db.endTransaction()
    }
    assertFalse(records.getThread(51L).isPresent)
    assertFalse(records.getRecipient(RecipientId.from(61)).isPresent)
  }
  @Test fun `committed and direct writes and deletes are immediately visible`() {
    val records = RemappedRecords.getInstance()
    val db = SignalDatabase.writableDatabase
    db.beginTransaction()
    try {
      records.addThread(71L, 72L)
      records.addRecipient(RecipientId.from(81), RecipientId.from(82))
      assertEquals(72L, records.getThread(71L).get())
      assertEquals(RecipientId.from(82), records.getRecipient(RecipientId.from(81)).get())
      db.setTransactionSuccessful()
    } finally {
      db.endTransaction()
    }
    assertEquals(72L, records.getThread(71L).get())
    assertTrue(records.areAnyRemapped(listOf(RecipientId.from(81))))
    assertTrue(records.buildRemapDescription(listOf(RecipientId.from(81))).contains(" -> "))
    db.execSQL("UPDATE remapped_threads SET new_id = 73 WHERE old_id = 71")
    db.execSQL("UPDATE remapped_recipients SET new_id = 83 WHERE old_id = 81")
    assertEquals(73L, records.getThread(71L).get())
    assertEquals(RecipientId.from(83), records.getRecipient(RecipientId.from(81)).get())
    db.execSQL("DELETE FROM remapped_threads WHERE old_id = 71")
    db.execSQL("DELETE FROM remapped_recipients WHERE old_id = 81")
    assertFalse(records.getThread(71L).isPresent)
    assertFalse(records.getRecipient(RecipientId.from(81)).isPresent)
  }

  @Test fun `indexed thread lookup cost is measured on the test database`() {
    val db = SignalDatabase.writableDatabase
    db.beginTransaction()
    try {
      repeat(1000) { db.execSQL("INSERT INTO remapped_threads (old_id, new_id) VALUES (?, ?)", arrayOf(it + 10000, it + 20000)) }
      db.setTransactionSuccessful()
    } finally {
      db.endTransaction()
    }
    val records = RemappedRecords.getInstance()
    db.rawQuery("EXPLAIN QUERY PLAN SELECT new_id FROM remapped_threads WHERE old_id = 10000", null).use { cursor ->
      assertTrue(cursor.moveToFirst())
      assertTrue(cursor.getString(cursor.getColumnIndexOrThrow("detail")).contains("INDEX", ignoreCase = true))
    }
    val start = System.nanoTime()
    repeat(1000) { assertEquals((it + 20000).toLong(), records.getThread((it + 10000).toLong()).get()) }
    println("1000 indexed remap lookups: ${(System.nanoTime() - start) / 1_000_000} ms (sqlite-jdbc host test)")
  }

  @Test fun `failed trim scheduling and execution can retry without breaking lookups`() {
    SignalDatabase.writableDatabase.execSQL("INSERT INTO remapped_threads (old_id, new_id) VALUES (101, 102)")
    val attempts = AtomicInteger()
    val trimCalls = AtomicInteger()
    val records = RemappedRecords(
      { task ->
        if (attempts.incrementAndGet() == 1) throw RejectedExecutionException("synthetic rejection")
        task.run()
      },
      {
        if (trimCalls.incrementAndGet() == 1) throw IllegalStateException("synthetic failure")
      }
    )
    repeat(3) { assertEquals(102L, records.getThread(101L).get()) }
    assertEquals(3, attempts.get())
    assertEquals(2, trimCalls.get())
  }

  @Test fun `trimmed mappings disappear on the next lookup`() {
    val db = SignalDatabase.writableDatabase
    db.execSQL("INSERT INTO recipient (_id) VALUES (91)")
    db.execSQL("INSERT INTO thread (_id, recipient_id) VALUES (92, 91)")
    db.execSQL("INSERT INTO remapped_recipients (old_id, new_id) VALUES (91, 93)")
    db.execSQL("INSERT INTO remapped_threads (old_id, new_id) VALUES (92, 94)")
    val records = RemappedRecords({ _ -> }, {})
    assertEquals(RecipientId.from(93), records.getRecipient(RecipientId.from(91)).get())
    assertEquals(94L, records.getThread(92L).get())
    SignalDatabase.remappedRecords.trimStaleMappings()
    assertFalse(records.getRecipient(RecipientId.from(91)).isPresent)
    assertFalse(records.getThread(92L).isPresent)
  }

  @Test fun `reader on another thread sees a completed direct write`() {
    val db = SignalDatabase.writableDatabase
    db.execSQL("INSERT INTO remapped_threads (old_id, new_id) VALUES (111, 112)")
    val records = RemappedRecords({ _ -> }, {})
    assertEquals(112L, records.getThread(111L).get())
    val readAllowed = CountDownLatch(1)
    val executor = Executors.newSingleThreadExecutor()
    try {
      val reader = executor.submit<Long> {
        check(readAllowed.await(5, TimeUnit.SECONDS))
        records.getThread(111L).get()
      }
      db.execSQL("UPDATE remapped_threads SET new_id = 113 WHERE old_id = 111")
      readAllowed.countDown()
      assertEquals(113L, reader.get(5, TimeUnit.SECONDS))
    } finally {
      readAllowed.countDown()
      executor.shutdownNow()
    }
  }

  @Test fun `deleting an observed stale mapping preserves a replacement`() {
    val db = SignalDatabase.writableDatabase
    db.execSQL("INSERT INTO remapped_threads (old_id, new_id) VALUES (121, 122)")
    val records = RemappedRecords({ _ -> }, {})
    val observed = records.getThread(121).get()
    db.execSQL("UPDATE remapped_threads SET new_id = 123 WHERE old_id = 121")
    db.beginTransaction()
    try {
      records.deleteThread(121, observed)
      db.setTransactionSuccessful()
    } finally {
      db.endTransaction()
    }
    assertEquals(123L, records.getThread(121).get())
    db.beginTransaction()
    try {
      records.deleteThread(121, 123)
      db.setTransactionSuccessful()
    } finally {
      db.endTransaction()
    }
    assertFalse(records.getThread(121).isPresent)
  }

  @Test fun `duplicate remap insertion fails without replacing existing mapping`() {
    val db = SignalDatabase.writableDatabase
    db.execSQL("INSERT INTO remapped_threads (old_id, new_id) VALUES (131, 132)")
    val records = RemappedRecords({ _ -> }, {})
    db.beginTransaction()
    try {
      assertThrows(Exception::class.java) { records.addThread(131, 133) }
    } finally {
      db.endTransaction()
    }
    assertEquals(132L, records.getThread(131).get())
  }

  @Test fun `thread merge replaces the stale mapping for its live secondary row`() {
    val db = SignalDatabase.writableDatabase
    db.execSQL("INSERT INTO recipient (_id) VALUES (141), (142)")
    db.execSQL("INSERT INTO thread (_id, recipient_id) VALUES (151, 141), (152, 142)")
    db.execSQL("INSERT INTO remapped_threads (old_id, new_id) VALUES (152, 153), (154, 155)")

    db.withinTransaction {
      val merged = SignalDatabase.threads.merge(RecipientId.from(141), RecipientId.from(142))
      assertEquals(151L, merged.threadId)
      assertTrue(merged.neededMerge)
    }

    assertEquals(151L, SignalDatabase.remappedRecords.getThreadMapping(152))
    assertEquals(155L, SignalDatabase.remappedRecords.getThreadMapping(154))
    assertNull(SignalDatabase.threads.getThreadRecord(152L))
  }

  @Test fun `recipient cleanup removes only the stale mapping for the live source row`() {
    val db = SignalDatabase.writableDatabase
    db.execSQL("INSERT INTO recipient (_id) VALUES (161)")
    db.execSQL("INSERT INTO remapped_recipients (old_id, new_id) VALUES (161, 162), (163, 164)")

    db.withinTransaction {
      SignalDatabase.remappedRecords.deleteStaleRecipientMapping(RecipientId.from(161))
      SignalDatabase.remappedRecords.deleteStaleRecipientMapping(RecipientId.from(163))
    }

    assertNull(SignalDatabase.remappedRecords.getRecipientMapping(RecipientId.from(161)))
    assertEquals(RecipientId.from(164), SignalDatabase.remappedRecords.getRecipientMapping(RecipientId.from(163)))
  }

  @Test fun `recipient matching handles empty large and unrelated ID sets`() {
    val db = SignalDatabase.writableDatabase
    db.execSQL("INSERT INTO remapped_recipients (old_id, new_id) VALUES (10000, 10001), (20000, 20001)")
    val records = RemappedRecords({ _ -> }, {})
    val nonmatches = (1L..1200L).map(RecipientId::from)
    val match = RecipientId.from(10000)

    assertFalse(records.areAnyRemapped(emptyList()))
    assertFalse(records.areAnyRemapped(nonmatches))
    assertTrue(records.areAnyRemapped(nonmatches + match))
    assertEquals("$match -> ${RecipientId.from(10001)} ", records.buildRemapDescription(nonmatches + match))
  }

}
