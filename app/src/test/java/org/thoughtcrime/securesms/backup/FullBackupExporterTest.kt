package org.thoughtcrime.securesms.backup

import android.database.Cursor
import io.mockk.every
import io.mockk.mockk
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.thoughtcrime.securesms.database.MessageTable
import org.junit.Test

class FullBackupExporterTest {

  @Test
  fun `started disappearing messages are excluded even with substantial time remaining`() {
    val row = messageCursor(id = 1, expiresIn = 7 * 86_400_000L, expireStarted = System.currentTimeMillis())

    assertFalse(FullBackupExporter.isNonExpiringMessage(mockk(), row))
  }

  @Test
  fun `an old revision is excluded when the latest revision has started expiring`() {
    val old = messageCursor(id = 1, latestRevisionId = 2)
    val latest = messageCursor(id = 2, expiresIn = 86_400_000L, expireStarted = System.currentTimeMillis())
    val db = mockk<SQLiteDatabase>()
    every { db.query(MessageTable.TABLE_NAME, any<Array<String>>(), any<String>(), any<Array<String>>(), null, null, null) } returns latest

    assertFalse(FullBackupExporter.isNonExpiringMessage(db, old))
  }

  @Test
  fun `a revision cycle stops at the repeated message`() {
    val first = messageCursor(id = 1, latestRevisionId = 2)
    val second = messageCursor(id = 2, latestRevisionId = 1)
    val db = mockk<SQLiteDatabase>()
    every { db.query(MessageTable.TABLE_NAME, any<Array<String>>(), any<String>(), any<Array<String>>(), null, null, null) } returnsMany listOf(second, first)

    assertTrue(FullBackupExporter.isNonExpiringMessage(db, first))
  }

  @Test
  fun `messages whose disappearing timer has not started remain exportable`() {
    assertTrue(FullBackupExporter.isNonExpiringMessage(mockk(), messageCursor(id = 1, expiresIn = 60_000L)))
  }

  @Test
  fun `an older started timer does not override the latest revision`() {
    val old = messageCursor(id = 1, expiresIn = 60_000L, expireStarted = 1, latestRevisionId = 2)
    val latest = messageCursor(id = 2)
    val db = mockk<SQLiteDatabase>()
    every { db.query(MessageTable.TABLE_NAME, any<Array<String>>(), any<String>(), any<Array<String>>(), null, null, null) } returns latest

    assertTrue(FullBackupExporter.isNonExpiringMessage(db, old))
  }

  @Test
  fun `a missing revision is excluded`() {
    val missing = messageCursor(id = 2)
    every { missing.moveToFirst() } returns false
    val db = mockk<SQLiteDatabase>()
    every { db.query(MessageTable.TABLE_NAME, any<Array<String>>(), any<String>(), any<Array<String>>(), null, null, null) } returns missing

    assertFalse(FullBackupExporter.isNonExpiringMessage(db, messageCursor(id = 1, latestRevisionId = 2)))
  }

  @Test
  fun `a self reference uses the current message timer`() {
    assertTrue(FullBackupExporter.isNonExpiringMessage(mockk(), messageCursor(id = 1, latestRevisionId = 1)))
    assertFalse(FullBackupExporter.isNonExpiringMessage(mockk(), messageCursor(id = 1, expiresIn = 60_000L, expireStarted = 1, latestRevisionId = 1)))
  }

  private fun messageCursor(id: Long, expiresIn: Long = 0, expireStarted: Long = 0, latestRevisionId: Long = 0): Cursor {
    val values = linkedMapOf(
      MessageTable.ID to id,
      MessageTable.EXPIRES_IN to expiresIn,
      MessageTable.EXPIRE_STARTED to expireStarted,
      MessageTable.LATEST_REVISION_ID to latestRevisionId
    )
    return mockk<Cursor>(relaxUnitFun = true) {
      values.entries.forEachIndexed { index, entry ->
        every { getColumnIndexOrThrow(entry.key) } returns index
        every { getLong(index) } returns entry.value
      }
      every { moveToFirst() } returns true
    }
  }

  @Test
  fun `computeTableOrder - empty`() {
    val order = FullBackupExporter.computeTableOrder(mapOf())

    assertEquals(listOf<String>(), order)
  }

  /**
   * A B C
   */
  @Test
  fun `computeTableOrder - no dependencies`() {
    val order = FullBackupExporter.computeTableOrder(
      mapOf(
        "A" to setOf(),
        "B" to setOf(),
        "C" to setOf()
      )
    )

    assertEquals(listOf("A", "B", "C"), order)
  }

  /**
   * C
   * |
   * B
   * |
   * A
   */
  @Test
  fun `computeTableOrder - single chain`() {
    val order = FullBackupExporter.computeTableOrder(
      mapOf(
        "C" to setOf("B"),
        "B" to setOf("A")
      )
    )

    assertEquals(listOf("A", "B", "C"), order)
  }

  /**
   *   ┌──F──┐    G   H
   * ┌─B   ┌─E─┐
   * A     C   D
   */
  @Test
  fun `computeTableOrder - complex 1`() {
    val order = FullBackupExporter.computeTableOrder(
      mapOf(
        "F" to setOf("B", "E"),
        "B" to setOf("A"),
        "E" to setOf("C", "D"),
        "G" to setOf(),
        "H" to setOf(),
        "A" to setOf(),
        "C" to setOf(),
        "D" to setOf()
      )
    )

    assertEquals(listOf("A", "B", "C", "D", "E", "F", "G", "H"), order)
  }

  /**
   *   ┌────I────┐
   *   │    |    │
   * ┌─C─┐  E  ┌─H─┐
   * │   │  |  │   │
   * A   B  D  F   G
   */
  @Test
  fun `computeTableOrder - complex 2`() {
    val order = FullBackupExporter.computeTableOrder(
      mapOf(
        "I" to setOf("C", "E", "H"),
        "C" to setOf("A", "B"),
        "E" to setOf("D"),
        "H" to setOf("F", "G"),
        "A" to setOf(),
        "B" to setOf(),
        "D" to setOf(),
        "F" to setOf(),
        "G" to setOf()
      )
    )

    assertEquals(listOf("A", "B", "C", "D", "E", "F", "G", "H", "I"), order)
  }

  /**
   * ┌─C─┐  E  ┌─H─┐
   * │   │  |  │   │
   * A   B  D  F   G
   */
  @Test
  fun `computeTableOrder - multiple roots`() {
    val order = FullBackupExporter.computeTableOrder(
      mapOf(
        "C" to setOf("A", "B"),
        "E" to setOf("D"),
        "H" to setOf("F", "G"),
        "A" to setOf(),
        "B" to setOf(),
        "D" to setOf(),
        "F" to setOf(),
        "G" to setOf()
      )
    )

    assertEquals(listOf("A", "B", "C", "D", "E", "F", "G", "H"), order)
  }

  /**
   * ┌─C─┐  D  ┌─E─┐
   * │   │  |  │   │
   * A   B  A  A   B
   */
  @Test
  fun `computeTableOrder - multiple roots, dupes across graphs`() {
    val order = FullBackupExporter.computeTableOrder(
      mapOf(
        "C" to setOf("A", "B"),
        "D" to setOf("A"),
        "E" to setOf("A", "B"),
        "A" to setOf(),
        "B" to setOf()
      )
    )

    assertEquals(listOf("A", "B", "C", "D", "E"), order)
  }
}
