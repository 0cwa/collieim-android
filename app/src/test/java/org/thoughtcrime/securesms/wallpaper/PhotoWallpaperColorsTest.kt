package org.thoughtcrime.securesms.wallpaper

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.core.graphics.ColorUtils
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.signal.core.models.database.AttachmentId
import org.signal.core.util.logging.Log
import org.signal.storage.keyvalue.KeyValueDataSet
import org.signal.storage.keyvalue.KeyValueStore
import org.signal.storage.keyvalue.MockKeyValuePersistentStorage
import org.signal.storage.keyvalue.PlainTextKeyValueStore
import org.thoughtcrime.securesms.conversation.colors.ChatColors
import org.thoughtcrime.securesms.conversation.colors.ChatColorsPalette
import org.thoughtcrime.securesms.database.AttachmentTable
import org.thoughtcrime.securesms.database.FakeMessageRecords
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.mms.PartAuthority
import org.thoughtcrime.securesms.testutil.DirectExecutor
import org.thoughtcrime.securesms.testutil.MockAppDependenciesRule
import org.thoughtcrime.securesms.testutil.SystemOutLogger
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhotoWallpaperColorsTest {

  @get:Rule
  val appDependencies = MockAppDependenciesRule()

  private val app: Application get() = ApplicationProvider.getApplicationContext()
  private val attachments: AttachmentTable = mockk()
  private val signalDatabase: SignalDatabase = mockk()
  private val dataFiles = mutableMapOf<Long, String?>()
  private val messageIds = mutableMapOf<Long, Long>()

  @Before
  fun setUp() {
    Log.initialize(SystemOutLogger())
    PlainTextKeyValueStore.init(app.getSharedPreferences("photo-wallpaper-test", Context.MODE_PRIVATE), 0)
    SignalStore.testInject(SignalStore(app, KeyValueStore(MockKeyValuePersistentStorage.withDataSet(KeyValueDataSet()), DirectExecutor())))
    mockkObject(SignalDatabase)
    every { SignalDatabase.instance } returns signalDatabase
    every { signalDatabase.attachmentTable } returns attachments
    every { attachments.getAttachment(any<AttachmentId>()) } answers {
      val id = firstArg<AttachmentId>()
      messageIds[id.id]?.let { mmsId -> FakeMessageRecords.buildDatabaseAttachment(attachmentId = id, mmsId = mmsId) }
    }
    every { attachments.getDataFilePath(any()) } answers { dataFiles[firstArg<AttachmentId>().id] }
  }

  @After
  fun tearDown() {
    unmockkObject(SignalDatabase)
    SignalStore.testInject(null)
  }

  @Test
  fun `saved photo wallpaper uses a readable color from the photo for Automatic`() {
    val wallpaper = saveWallpaper(1, png(Color.rgb(250, 220, 40)))

    val colors = wallpaper.autoChatColors
    assertEquals(ChatColors.Id.Auto, colors.id)
    assertNotEquals(ChatColorsPalette.Bubbles.autoDefault.asSingleColor(), colors.asSingleColor())
    val hsl = FloatArray(3).also { ColorUtils.colorToHSL(colors.asSingleColor(), it) }
    assertTrue("Bubble keeps the photo hue, was ${hsl[0]}", hsl[0] in 40f..60f)
    assertReadable(colors.asSingleColor())
    assertFalse(PhotoWallpaperColors.needsBackfill(wallpaper))
    assertNull(PhotoWallpaperColors.getAutoChatColors(Uri.parse("content://other.provider/photos/1")))
  }

  @Test
  fun `bubble colors meet outgoing text contrast in light and dark for difficult seeds`() {
    val foregrounds = PhotoWallpaperColors.outgoingForegrounds(app)
    assertEquals("Light and dark body, footer, primary and secondary colors", 8, foregrounds.size)
    val seeds = listOf(Color.WHITE, Color.BLACK, Color.YELLOW, Color.CYAN, Color.GREEN, Color.rgb(255, 200, 220), Color.rgb(128, 128, 128), Color.BLUE, Color.RED)
    for (seed in seeds) {
      assertReadable(PhotoWallpaperColors.bubbleColorForSeed(seed, foregrounds))
    }
  }

  @Test
  fun `dominant chromatic hue wins and transparent pixels are ignored`() {
    val blue = Color.rgb(30, 80, 220)
    val red = Color.rgb(220, 40, 40)
    val pixels = IntArray(100) { i ->
      when {
        i < 50 -> blue
        i < 70 -> red
        else -> Color.argb(0, 255, 0, 0)
      }
    }
    val seed = PhotoWallpaperColors.seedFromPixels(pixels)!!
    assertEquals(blue, seed)
  }

  @Test
  fun `fully transparent or neutral photos are handled safely`() {
    assertNull(PhotoWallpaperColors.seedFromPixels(IntArray(16) { Color.TRANSPARENT }))
    assertNull(PhotoWallpaperColors.seedFromPixels(IntArray(0)))
    assertEquals(Color.rgb(100, 100, 100), PhotoWallpaperColors.seedFromPixels(IntArray(16) { Color.rgb(100, 100, 100) }))

    val transparent = saveWallpaper(2, png(Color.TRANSPARENT))
    assertEquals(ChatColorsPalette.Bubbles.autoDefault, transparent.autoChatColors)
    assertFalse("Sampled once; no repeated decoding", PhotoWallpaperColors.needsBackfill(transparent))
  }

  @Test
  fun `malformed photo keeps the default Automatic color`() {
    val wallpaper = saveWallpaper(3, byteArrayOf(1, 2, 3, 4))
    assertEquals(ChatColorsPalette.Bubbles.autoDefault, wallpaper.autoChatColors)
    assertFalse(PhotoWallpaperColors.needsBackfill(wallpaper))
  }

  @Test
  fun `color is not stored when the attachment is not a current wallpaper`() {
    messageIds[4] = 99L
    dataFiles[4] = "/data/part4.mms"
    val wallpaper = uriWallpaper(4)
    PhotoWallpaperColors.onWallpaperSaved(wallpaper, png(Color.RED))
    assertEquals(ChatColorsPalette.Bubbles.autoDefault, wallpaper.autoChatColors)
    assertTrue(PhotoWallpaperColors.needsBackfill(wallpaper))
  }

  @Test
  fun `deleting the attachment removes its derived color`() {
    val first = saveWallpaper(5, png(Color.RED))
    val second = saveWallpaper(6, png(Color.BLUE))

    PhotoWallpaperColors.onAttachmentDeleted(AttachmentId(5))
    assertEquals(ChatColorsPalette.Bubbles.autoDefault, first.autoChatColors)
    assertNotEquals(ChatColorsPalette.Bubbles.autoDefault, second.autoChatColors)

    PhotoWallpaperColors.onAllAttachmentsDeleted()
    assertEquals(ChatColorsPalette.Bubbles.autoDefault, second.autoChatColors)
  }

  @Test
  fun `picker load backfills old photos and rechecks a replaced data file`() {
    messageIds[7] = AttachmentTable.WALLPAPER_MESSAGE_ID
    dataFiles[7] = "/data/part7.mms"
    var bytes = png(Color.rgb(20, 160, 60))
    mockkStatic(PartAuthority::class)
    try {
      every { PartAuthority.getAttachmentStream(any(), any()) } answers { ByteArrayInputStream(bytes) }
      val wallpaper = uriWallpaper(7)
      assertTrue(PhotoWallpaperColors.needsBackfill(wallpaper))

      PhotoWallpaperColors.backfill(listOf(AttachmentId(7)))
      val green = wallpaper.autoChatColors.asSingleColor()
      assertNotEquals(ChatColorsPalette.Bubbles.autoDefault.asSingleColor(), green)

      // A restore that reuses the attachment ID with different data is resampled.
      dataFiles[7] = "/data/part7-restored.mms"
      bytes = png(Color.rgb(30, 80, 220))
      PhotoWallpaperColors.backfill(listOf(AttachmentId(7)))
      assertNotEquals(green, wallpaper.autoChatColors.asSingleColor())

      // Restored wallpapers without local data yet are left for a later load.
      messageIds[8] = AttachmentTable.WALLPAPER_MESSAGE_ID
      dataFiles[8] = null
      PhotoWallpaperColors.backfill(listOf(AttachmentId(8)))
      assertTrue(PhotoWallpaperColors.needsBackfill(uriWallpaper(8)))
    } finally {
      unmockkStatic(PartAuthority::class)
    }
  }

  @Test
  fun `derived colors stay out of backups and built-in mappings are unchanged`() {
    saveWallpaper(9, png(Color.RED))
    assertTrue(SignalStore.wallpaper.keysToIncludeInBackup.isEmpty())
    assertEquals(ChatColorsPalette.Bubbles.BLUE.withId(ChatColors.Id.Auto), SingleColorChatWallpaper.NAVY.autoChatColors)
    assertEquals(ChatColorsPalette.Bubbles.autoDefault, GradientChatWallpaper.SUNSET.autoChatColors)
  }

  private fun saveWallpaper(id: Long, bytes: ByteArray): UriChatWallpaper {
    messageIds[id] = AttachmentTable.WALLPAPER_MESSAGE_ID
    dataFiles[id] = "/data/part$id.mms"
    return uriWallpaper(id).also { PhotoWallpaperColors.onWallpaperSaved(it, bytes) }
  }

  private fun uriWallpaper(id: Long) = UriChatWallpaper(PartAuthority.getAttachmentDataUri(AttachmentId(id)), 0f)

  private fun png(color: Int): ByteArray {
    val bitmap = Bitmap.createBitmap(600, 400, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
    return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
  }

  private fun assertReadable(background: Int) {
    for (foreground in PhotoWallpaperColors.outgoingForegrounds(app)) {
      val visible = ColorUtils.compositeColors(foreground, background)
      val contrast = ColorUtils.calculateContrast(visible, background)
      assertTrue("Contrast %.2f for %08x on %08x".format(contrast, foreground, background), contrast >= 4.5)
    }
  }
}
