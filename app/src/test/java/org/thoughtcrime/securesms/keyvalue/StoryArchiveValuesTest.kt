package org.thoughtcrime.securesms.keyvalue

import android.app.Application
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.remoteconfig.RemoteConfig
import org.signal.storage.keyvalue.KeyValueStore
import org.signal.storage.keyvalue.LabsValues
import org.thoughtcrime.securesms.stories.archive.StoryArchiveDuration
import org.thoughtcrime.securesms.testutil.SnapshotKeyValuePersistentStorage

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class StoryArchiveValuesTest {

  private val storage = SnapshotKeyValuePersistentStorage()
  private var previousInternalUserDisabled = false

  @Before
  fun setUp() {
    previousInternalUserDisabled = RemoteConfig.internalUserDisabled
    RemoteConfig.internalUserDisabled = true
  }

  @After
  fun tearDown() {
    RemoteConfig.internalUserDisabled = previousInternalUserDisabled
  }

  @Test
  fun `archive retention requires its own explicit opt in`() {
    val store = store()
    val stories = StoryValues(store)
    val labs = LabsValues(store) { RemoteConfig.internalUser }

    assertThat(RemoteConfig.internalUser).isFalse()
    assertThat(stories.isArchiveEnabled).isFalse()
    assertThat(stories.archiveDuration).isEqualTo(StoryArchiveDuration.THIRTY_DAYS)

    labs.storyArchive = true

    assertThat(labs.storyArchive).isTrue()
    assertThat(stories.isArchiveEnabled).isFalse()
  }

  @Test
  fun `ordinary users can persist archive retention and duration`() {
    StoryValues(store()).apply {
      isArchiveEnabled = true
      archiveDuration = StoryArchiveDuration.SIX_MONTHS
    }

    val restored = StoryValues(store())
    assertThat(restored.isArchiveEnabled).isTrue()
    assertThat(restored.archiveDuration).isEqualTo(StoryArchiveDuration.SIX_MONTHS)

    restored.isArchiveEnabled = false

    assertThat(StoryValues(store()).isArchiveEnabled).isFalse()
  }

  @Test
  fun `hiding the archive experiment preserves its retention settings`() {
    val store = store()
    val stories = StoryValues(store)
    val labs = LabsValues(store) { RemoteConfig.internalUser }
    labs.storyArchive = true
    stories.isArchiveEnabled = true
    stories.archiveDuration = StoryArchiveDuration.THIRTY_DAYS

    labs.storyArchive = false

    val restored = StoryValues(store())
    assertThat(restored.isArchiveEnabled).isTrue()
    assertThat(restored.archiveDuration).isEqualTo(StoryArchiveDuration.THIRTY_DAYS)
  }

  private fun store(): KeyValueStore = KeyValueStore(storage) { it.run() }
}
