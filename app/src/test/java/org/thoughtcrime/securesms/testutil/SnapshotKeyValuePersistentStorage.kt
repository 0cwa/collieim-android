package org.thoughtcrime.securesms.testutil

import org.signal.storage.keyvalue.KeyValueDataSet
import org.signal.storage.keyvalue.KeyValuePersistentStorage

/** A small persistent-storage test double that keeps disk data separate from KeyValueStore's cache. */
class SnapshotKeyValuePersistentStorage(initial: KeyValueDataSet = KeyValueDataSet()) : KeyValuePersistentStorage {
  private val lock = Any()
  private var persisted = copyOf(initial)

  @Volatile
  var nextWriteFailure: RuntimeException? = null

  @Volatile
  var lastWriteKeys: Set<String> = emptySet()
    private set

  override fun writeDataSet(dataSet: KeyValueDataSet, removes: Collection<String>) {
    nextWriteFailure?.also {
      nextWriteFailure = null
      throw it
    }

    synchronized(lock) {
      val next = copyOf(persisted)
      copyEntries(dataSet, next)
      val kept = KeyValueDataSet()
      copyEntries(next, kept, removes.toSet())
      persisted = kept
      lastWriteKeys = dataSet.values.keys.toSet()
    }
  }

  override fun getDataSet(): KeyValueDataSet = synchronized(lock) { copyOf(persisted) }

  fun removePersistedKey(key: String) {
    synchronized(lock) {
      val next = KeyValueDataSet()
      copyEntries(persisted, next, setOf(key))
      persisted = next
    }
  }

  private fun copyEntries(source: KeyValueDataSet, destination: KeyValueDataSet, excluded: Set<String> = emptySet()) {
    for ((key, value) in source.values) {
      if (key in excluded) continue
      when (source.getType(key)) {
        ByteArray::class.java -> destination.putBlob(key, (value as? ByteArray)?.copyOf())
        Boolean::class.javaObjectType -> destination.putBoolean(key, value as Boolean)
        Float::class.javaObjectType -> destination.putFloat(key, value as Float)
        Int::class.javaObjectType -> destination.putInteger(key, value as Int)
        Long::class.javaObjectType -> destination.putLong(key, value as Long)
        String::class.java -> destination.putString(key, value as String?)
        else -> error("Unsupported test key-value type for $key")
      }
    }
  }

  private fun copyOf(source: KeyValueDataSet): KeyValueDataSet = KeyValueDataSet().also { copyEntries(source, it) }
}
