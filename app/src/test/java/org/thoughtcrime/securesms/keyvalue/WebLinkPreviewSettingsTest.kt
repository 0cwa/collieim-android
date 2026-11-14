/*
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.keyvalue

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.util.logging.Log
import org.signal.storage.keyvalue.KeyValueDataSet
import org.signal.storage.keyvalue.KeyValueStore
import org.signal.storage.keyvalue.MockKeyValuePersistentStorage
import org.thoughtcrime.securesms.testutil.DirectExecutor
import org.thoughtcrime.securesms.testutil.EmptyLogger

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class WebLinkPreviewSettingsTest {

  private val context: Application = ApplicationProvider.getApplicationContext()

  @Before
  fun setUp() {
    Log.initialize(EmptyLogger())
  }

  @Test
  fun `existing installs and old backups without an image preference retain images`() {
    val settings = settings(KeyValueDataSet())

    assertTrue(settings.isWebLinkPreviewImagesEnabled)
  }

  @Test
  fun `restored text-only choice is retained while link previews are enabled`() {
    val settings = settings(
      KeyValueDataSet().apply {
        putBoolean(SettingsValues.LINK_PREVIEWS, true)
        putBoolean(SettingsValues.WEB_LINK_PREVIEW_IMAGES, false)
      }
    )

    assertFalse(settings.isWebLinkPreviewImagesEnabled)
    assertTrue(settings.isLinkPreviewsEnabled)
    assertTrue(settings.keysToIncludeInBackup.contains(SettingsValues.WEB_LINK_PREVIEW_IMAGES))
  }

  @Test
  fun `image choice survives a new settings reader without changing the parent setting`() {
    val data = KeyValueDataSet().apply {
      putBoolean(SettingsValues.LINK_PREVIEWS, true)
      putBoolean(SettingsValues.SCREEN_SECURITY_ENABLED, true)
    }
    val storage = MockKeyValuePersistentStorage.withDataSet(data)
    val initial = SettingsValues(KeyValueStore(storage, DirectExecutor()), context)

    initial.isWebLinkPreviewImagesEnabled = false

    val reopened = SettingsValues(KeyValueStore(storage, DirectExecutor()), context)
    assertFalse(reopened.isWebLinkPreviewImagesEnabled)
    assertTrue(reopened.isLinkPreviewsEnabled)

    reopened.isLinkPreviewsEnabled = false
    reopened.isLinkPreviewsEnabled = true
    assertFalse(reopened.isWebLinkPreviewImagesEnabled)
  }

  private fun settings(data: KeyValueDataSet): SettingsValues {
    // The existing migration marker avoids unrelated legacy shared-preference setup.
    data.putBoolean(SettingsValues.SCREEN_SECURITY_ENABLED, true)
    return SettingsValues(KeyValueStore(MockKeyValuePersistentStorage.withDataSet(data), DirectExecutor()), context)
  }
}
