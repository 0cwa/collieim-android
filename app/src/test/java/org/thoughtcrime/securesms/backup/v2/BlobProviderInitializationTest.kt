/*
 * Copyright 2026 Collie Messenger
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.backup.v2

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.util.contentproviders.BlobProvider
import org.signal.core.util.crypto.AttachmentSecretStore
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class BlobProviderInitializationTest {
  @Test
  fun `session file allocation waits until startup cleanup finishes`() {
    val context = ApplicationProvider.getApplicationContext<Application>()
    val provider = BlobProvider(context, mockk<AttachmentSecretStore>())
    val stale = File(context.getDir("single_session_blobs", Context.MODE_PRIVATE), "stale.blob")
    stale.writeText("stale")
    val cleanupEntered = CountDownLatch(1)
    val releaseCleanup = CountDownLatch(1)
    val task = FutureTask { provider.forNonAutoEncryptingInitializedSessionOnDisk(context) }
    val allocator = Thread(task).apply { isDaemon = true }

    try {
      allocator.start()
      val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
      while (allocator.state != Thread.State.WAITING && !task.isDone && System.nanoTime() < deadline) Thread.yield()
      assertTrue("Allocator must wait for initialization", allocator.state == Thread.State.WAITING)
      provider.initialize(context) {
        cleanupEntered.countDown()
        check(releaseCleanup.await(5, TimeUnit.SECONDS))
      }
      assertTrue("Cleanup did not start", cleanupEntered.await(5, TimeUnit.SECONDS))
      assertFalse("Allocator returned before cleanup finished", task.isDone)
      assertFalse("Prior session file was not removed", stale.exists())
      releaseCleanup.countDown()
      val allocated = task.get(5, TimeUnit.SECONDS)
      assertTrue(allocated.createNewFile())
      assertTrue(allocated.delete())
    } finally {
      releaseCleanup.countDown()
      allocator.join(TimeUnit.SECONDS.toMillis(5))
    }
  }
}
