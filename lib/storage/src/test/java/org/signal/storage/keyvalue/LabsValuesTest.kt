package org.signal.storage.keyvalue

import assertk.assertThat
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import org.junit.Test

class LabsValuesTest {

  private val storage = MockKeyValuePersistentStorage.withDataSet(KeyValueDataSet())

  @Test
  fun `public experiments require opt in for every build`() {
    for (internalUser in listOf(false, true)) {
      val values = values(internalUser)

      assertThat(values.storyArchive).isFalse()
      assertThat(values.betterSearch).isFalse()
      assertThat(values.starredMessages).isFalse()
    }
  }

  @Test
  fun `ordinary users can enable each public experiment independently`() {
    val values = values()

    values.storyArchive = true
    assertThat(values.storyArchive).isTrue()
    assertThat(values.betterSearch).isFalse()
    assertThat(values.starredMessages).isFalse()

    values.betterSearch = true
    assertThat(values.storyArchive).isTrue()
    assertThat(values.betterSearch).isTrue()
    assertThat(values.starredMessages).isFalse()

    values.starredMessages = true
    assertThat(values.storyArchive).isTrue()
    assertThat(values.betterSearch).isTrue()
    assertThat(values.starredMessages).isTrue()
  }

  @Test
  fun `public experiment choices survive a new store`() {
    values().apply {
      storyArchive = true
      betterSearch = true
      starredMessages = true
    }

    val restored = values()
    assertThat(restored.storyArchive).isTrue()
    assertThat(restored.betterSearch).isTrue()
    assertThat(restored.starredMessages).isTrue()

    restored.storyArchive = false
    restored.betterSearch = false
    restored.starredMessages = false

    val disabled = values()
    assertThat(disabled.storyArchive).isFalse()
    assertThat(disabled.betterSearch).isFalse()
    assertThat(disabled.starredMessages).isFalse()
  }

  @Test
  fun `existing choices are preserved when an internal user becomes an ordinary user`() {
    values(internalUser = true).apply {
      storyArchive = true
      betterSearch = false
      starredMessages = true
    }

    val external = values()
    assertThat(external.storyArchive).isTrue()
    assertThat(external.betterSearch).isFalse()
    assertThat(external.starredMessages).isTrue()
  }

  @Test
  fun `other experiments retain their internal user gate`() {
    val external = values()
    external.individualChatPlaintextExport = true
    external.incognito = true
    external.improvedMessageDeletion = true

    assertThat(external.individualChatPlaintextExport).isFalse()
    assertThat(external.incognito).isFalse()
    assertThat(external.improvedMessageDeletion).isFalse()

    val internal = values(internalUser = true)
    assertThat(internal.individualChatPlaintextExport).isTrue()
    assertThat(internal.incognito).isTrue()
    assertThat(internal.improvedMessageDeletion).isTrue()
  }

  private fun values(internalUser: Boolean = false): LabsValues {
    return LabsValues(KeyValueStore(storage) { it.run() }) { internalUser }
  }
}
