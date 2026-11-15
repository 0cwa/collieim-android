package org.thoughtcrime.securesms.jobs

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.signal.blurhash.BlurHashEncoder
import org.signal.core.util.crypto.AttachmentSecret
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AttachmentUploadSnapshotDeviceTest {
  @Test fun imageAndVideoVisualHashesUseEncryptedSnapshot() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val directory = File(context.cacheDir, "snapshot-test-" + UUID.randomUUID())
    val store = AttachmentUploadSnapshot(directory, AttachmentSecret(ByteArray(32), ByteArray(32), ByteArray(32) { 7 }))
    val image = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
    image.eraseColor(0xff44aa33.toInt())
    val output = ByteArrayOutputStream()
    image.compress(Bitmap.CompressFormat.PNG, 100, output)
    image.recycle()
    val png = output.toByteArray()
    val imageId = store.create(png.size.toLong(), "image", byteArrayOf(1)) { ByteArrayInputStream(png) }
    assertEquals(BlurHashEncoder.encode(ByteArrayInputStream(png)), store.visualHash(imageId, "image/png"))
    store.delete(imageId)
    val video = InstrumentationRegistry.getInstrumentation().context.assets.open("videos/theme_sample.mp4").use { it.readBytes() }
    val videoId = store.create(video.size.toLong(), "video", byteArrayOf(2)) { ByteArrayInputStream(video) }
    assertNotNull(store.visualHash(videoId, "video/mp4"))
    store.delete(videoId)
    directory.delete()
  }
  @Test fun glideClosesEveryFactoryStreamOnSuccessAndDecodeFailure() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
    bitmap.eraseColor(0xff33aa55.toInt())
    val output = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
    bitmap.recycle()
    for (bytes in listOf(output.toByteArray(), byteArrayOf(1, 2, 3))) {
      val opened = java.util.concurrent.atomic.AtomicInteger()
      val closed = java.util.concurrent.atomic.AtomicInteger()
      val factory = object : org.signal.glide.common.io.InputStreamFactory {
        override fun create(): java.io.InputStream {
          opened.incrementAndGet()
          return object : java.io.FilterInputStream(ByteArrayInputStream(bytes)) {
            private var didClose = false
            override fun close() {
              if (!didClose) { didClose = true; closed.incrementAndGet() }
              super.close()
            }
          }
        }
      }
      try {
        org.thoughtcrime.securesms.util.ImageCompressionUtil.compressWithinConstraints(context, "image/png", factory, 32, 1024 * 1024, 100)
      } catch (_: org.signal.core.util.bitmaps.BitmapDecodingException) { }
      org.junit.Assert.assertTrue(opened.get() > 0)
      assertEquals(opened.get(), closed.get())
    }
  }

  @Test fun glideClosesFactoryStreamsAfterWaitingCallerIsInterrupted() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
    val output = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
    bitmap.recycle()
    val entered = java.util.concurrent.CountDownLatch(1)
    val release = java.util.concurrent.CountDownLatch(1)
    val closed = java.util.concurrent.CountDownLatch(1)
    val factory = object : org.signal.glide.common.io.InputStreamFactory {
      override fun create(): java.io.InputStream = object : java.io.FilterInputStream(ByteArrayInputStream(output.toByteArray())) {
        private var blocked = false
        private fun waitOnce() { if (!blocked) { blocked = true; entered.countDown(); release.await() } }
        override fun read(): Int { waitOnce(); return super.read() }
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int { waitOnce(); return super.read(bytes, offset, length) }
        override fun close() { try { super.close() } finally { closed.countDown() } }
      }
    }
    val caller = Thread {
      try { org.thoughtcrime.securesms.util.ImageCompressionUtil.compressWithinConstraints(context, "image/png", factory, 32, 1024 * 1024, 100) } catch (_: Exception) { }
    }
    caller.start()
    try {
      org.junit.Assert.assertTrue(entered.await(10, java.util.concurrent.TimeUnit.SECONDS))
      caller.interrupt()
    } finally { release.countDown() }
    caller.join(10000)
    org.junit.Assert.assertFalse(caller.isAlive)
    org.junit.Assert.assertTrue(closed.await(10, java.util.concurrent.TimeUnit.SECONDS))
  }

  @Test fun terminalProgressFinishesOnlyItsOwnUploadAndCopyHandoff() {
    val id = org.signal.core.models.database.AttachmentId(987654321)
    val old = org.thoughtcrime.securesms.backup.ArchiveUploadProgress.onAttachmentStarted(id, 1)
    val current = org.thoughtcrime.securesms.backup.ArchiveUploadProgress.onAttachmentStarted(id, 2)
    org.thoughtcrime.securesms.backup.ArchiveUploadProgress.onAttachmentProgressIfOwned(id, old, 1)
    org.junit.Assert.assertFalse(org.thoughtcrime.securesms.backup.ArchiveUploadProgress.onAttachmentFinishedIfOwned(id, old))
    org.junit.Assert.assertTrue(org.thoughtcrime.securesms.backup.ArchiveUploadProgress.onAttachmentFinishedIfOwned(id, current))
    org.junit.Assert.assertFalse(org.thoughtcrime.securesms.backup.ArchiveUploadProgress.onAttachmentFinishedIfOwned(id, current))
  }

}
