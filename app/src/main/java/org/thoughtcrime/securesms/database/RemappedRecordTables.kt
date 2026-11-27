package org.thoughtcrime.securesms.database

import android.content.Context
import android.database.Cursor
import androidx.core.content.contentValuesOf
import org.signal.core.database.SqlUtil
import org.signal.core.database.delete
import org.signal.core.database.exists
import org.signal.core.database.select
import org.signal.core.database.withinTransaction
import org.signal.core.models.recipients.RecipientId
import org.signal.core.util.logging.Log
import org.signal.core.util.readToList
import org.signal.core.util.readToSingleObject
import org.signal.core.util.requireLong
import org.thoughtcrime.securesms.database.RemappedRecordTables.SharedColumns.ID
import org.thoughtcrime.securesms.database.RemappedRecordTables.SharedColumns.NEW_ID
import org.thoughtcrime.securesms.database.RemappedRecordTables.SharedColumns.OLD_ID

/**
 * The backing datastore for [RemappedRecords]. See that class for more details.
 */
class RemappedRecordTables internal constructor(context: Context?, databaseHelper: SignalDatabase?) : DatabaseTable(context, databaseHelper) {

  companion object {
    val TAG = Log.tag(RemappedRecordTables::class.java)

    val CREATE_TABLE = arrayOf(Recipients.CREATE_TABLE, Threads.CREATE_TABLE)
  }

  private object SharedColumns {
    const val ID = "_id"
    const val OLD_ID = "old_id"
    const val NEW_ID = "new_id"
  }

  object Recipients {
    const val TABLE_NAME = "remapped_recipients"
    const val CREATE_TABLE = """
      CREATE TABLE $TABLE_NAME (
        $ID INTEGER PRIMARY KEY AUTOINCREMENT, 
        $OLD_ID INTEGER UNIQUE, 
        $NEW_ID INTEGER
      )
    """
  }

  object Threads {
    const val TABLE_NAME = "remapped_threads"
    const val CREATE_TABLE = """
      CREATE TABLE $TABLE_NAME (
        $ID INTEGER PRIMARY KEY AUTOINCREMENT, 
        $OLD_ID INTEGER UNIQUE, 
        $NEW_ID INTEGER
      )
    """
  }

  fun getRecipientMapping(oldId: RecipientId): RecipientId? =
    getMapping(Recipients.TABLE_NAME, oldId.toLong())?.let { RecipientId.from(it) }

  fun getThreadMapping(oldId: Long): Long? = getMapping(Threads.TABLE_NAME, oldId)

  private fun getMapping(table: String, oldId: Long): Long? = readableDatabase
    .select(NEW_ID)
    .from(table)
    .where("$OLD_ID = ?", oldId)
    .run()
    .readToSingleObject { it.requireLong(NEW_ID) }

  fun hasRecipientMapping(oldIds: Collection<RecipientId>): Boolean {
    if (oldIds.isEmpty()) return false

    val query = SqlUtil.buildFastCollectionQuery(OLD_ID, oldIds.map { it.toLong() })
    return readableDatabase.exists(Recipients.TABLE_NAME).where(query.where, query.whereArgs).run()
  }

  fun getRecipientMappings(oldIds: Collection<RecipientId>): Map<RecipientId, RecipientId> {
    if (oldIds.isEmpty()) return emptyMap()

    val query = SqlUtil.buildFastCollectionQuery(OLD_ID, oldIds.map { it.toLong() })
    return readableDatabase.select(OLD_ID, NEW_ID)
      .from(Recipients.TABLE_NAME)
      .where(query.where, query.whereArgs)
      .run()
      .readToList { RecipientId.from(it.requireLong(OLD_ID)) to RecipientId.from(it.requireLong(NEW_ID)) }
      .toMap()
  }

  fun getAllThreadMappings(): Map<Long, Long> {
    val threadMap: MutableMap<Long, Long> = HashMap()

    val mappings = getAllMappings(readableDatabase, Threads.TABLE_NAME)
    for (mapping in mappings) {
      threadMap[mapping.oldId] = mapping.newId
    }

    return threadMap
  }

  fun trimStaleMappings() {
    val hasStaleRecipients = hasInvalidEntries(Recipients.TABLE_NAME, RecipientTable.TABLE_NAME)
    val hasStaleThreads = hasInvalidEntries(Threads.TABLE_NAME, ThreadTable.TABLE_NAME)

    if (!hasStaleRecipients && !hasStaleThreads) {
      return
    }

    writableDatabase.withinTransaction { db ->
      if (hasStaleRecipients) {
        trimInvalidRecipientEntries(db)
      }
      if (hasStaleThreads) {
        trimInvalidThreadEntries(db)
      }
    }
  }

  fun addRecipientMapping(oldId: RecipientId, newId: RecipientId) {
    addMapping(Recipients.TABLE_NAME, Mapping(oldId.toLong(), newId.toLong()))
  }

  fun addThreadMapping(oldId: Long, newId: Long) {
    addMapping(Threads.TABLE_NAME, Mapping(oldId, newId))
  }

  fun getAllRecipients(): Cursor {
    return readableDatabase
      .select()
      .from(Recipients.TABLE_NAME)
      .run()
  }

  fun getAllThreads(): Cursor {
    return readableDatabase
      .select()
      .from(Threads.TABLE_NAME)
      .run()
  }

  fun deleteThreadMapping(oldId: Long, expectedNewId: Long) {
    writableDatabase.delete(Threads.TABLE_NAME)
      .where("$OLD_ID = ? AND $NEW_ID = ?", oldId, expectedNewId)
      .run()
  }

  /** A mapping is stale while its source row still exists. Call before deleting a merged row. */
  fun deleteStaleRecipientMapping(oldId: RecipientId) {
    deleteStaleMapping(Recipients.TABLE_NAME, RecipientTable.TABLE_NAME, oldId.toLong())
  }

  fun deleteStaleThreadMapping(oldId: Long) {
    deleteStaleMapping(Threads.TABLE_NAME, ThreadTable.TABLE_NAME, oldId)
  }

  private fun deleteStaleMapping(table: String, sourceTable: String, oldId: Long) {
    writableDatabase.delete(table)
      .where("$OLD_ID = ? AND EXISTS (SELECT 1 FROM $sourceTable WHERE $ID = ?)", oldId, oldId)
      .run()
  }

  private fun hasInvalidEntries(table: String, sourceTable: String): Boolean {
    return readableDatabase
      .exists(table)
      .where("$OLD_ID IN (SELECT $ID FROM $sourceTable)")
      .run()
  }

  private fun trimInvalidRecipientEntries(db: SQLiteDatabase) {
    val count = db.delete(Recipients.TABLE_NAME)
      .where("$OLD_ID IN (SELECT $ID FROM ${RecipientTable.TABLE_NAME})")
      .run()

    if (count > 0) {
      Log.w(TAG, "Trimmed $count invalid recipient entries.", true)
    }
  }

  private fun trimInvalidThreadEntries(db: SQLiteDatabase) {
    val count = db.delete(Threads.TABLE_NAME)
      .where("$OLD_ID IN (SELECT $ID FROM ${ThreadTable.TABLE_NAME})")
      .run()

    if (count > 0) {
      Log.w(TAG, "Trimmed $count invalid thread entries.", true)
    }
  }

  private fun getAllMappings(db: SQLiteDatabase, table: String): List<Mapping> {
    return db.select()
      .from(table)
      .run()
      .readToList { cursor ->
        Mapping(
          oldId = cursor.requireLong(OLD_ID),
          newId = cursor.requireLong(NEW_ID)
        )
      }
  }

  private fun addMapping(table: String, mapping: Mapping) {
    val values = contentValuesOf(
      OLD_ID to mapping.oldId,
      NEW_ID to mapping.newId
    )
    databaseHelper.signalWritableDatabase.insertOrThrow(table, null, values)
  }

  private class Mapping(val oldId: Long, val newId: Long)
}
