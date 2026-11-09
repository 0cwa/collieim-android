/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.components.voice

import android.app.Application
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.service.KeyCachingService

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [33])
class VoiceNotePlaybackServiceTest {

  @After
  fun tearDown() {
    KeyCachingService.clearMasterSecret()
  }

  @Test
  fun `locked startup stops before creating player or media session and destroys safely`() {
    KeyCachingService.clearMasterSecret()
    mockkObject(AppDependencies)
    mockkStatic(AppDependencies::class)
    try {
      val controller = Robolectric.buildService(VoiceNotePlaybackService::class.java).create()
      val service = controller.get()

      try {
        assertTrue(KeyCachingService.isLocked())
        assertNull(service.javaClass.getDeclaredField("player").apply { isAccessible = true }.get(service))
        assertNull(service.javaClass.getDeclaredField("mediaSession").apply { isAccessible = true }.get(service))
        assertTrue(shadowOf(service).isStoppedBySelf)
      } finally {
        controller.destroy()
      }
      verify(exactly = 0) { AppDependencies.databaseObserver }
    } finally {
      unmockkStatic(AppDependencies::class)
      unmockkObject(AppDependencies)
    }
  }
}
