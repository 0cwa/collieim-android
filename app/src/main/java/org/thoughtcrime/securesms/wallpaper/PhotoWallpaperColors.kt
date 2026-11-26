package org.thoughtcrime.securesms.wallpaper

import android.content.ContentUris
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import androidx.annotation.ColorInt
import androidx.annotation.VisibleForTesting
import androidx.annotation.WorkerThread
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import org.signal.core.models.database.AttachmentId
import org.signal.core.util.concurrent.SignalExecutors
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.conversation.colors.ChatColors
import org.thoughtcrime.securesms.database.AttachmentTable
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.mms.PartAuthority
import java.io.File
import java.io.InputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Derives the Automatic outgoing bubble color for photo wallpapers.
 *
 * A seed is sampled from a small software bitmap on a worker thread, then darkened until every outgoing bubble
 * foreground passes 4.5:1 in light and dark mode. The result is stored locally by wallpaper attachment ID in
 * [org.thoughtcrime.securesms.keyvalue.WallpaperValues], outside backups and sync, and removed with the attachment.
 * [getAutoChatColors] only reads that in-memory value, so synchronous color getters never decode or touch disk.
 */
object PhotoWallpaperColors {

  private val TAG = Log.tag(PhotoWallpaperColors::class.java)

  /** Stored when sampling ran but found nothing usable, so the attachment keeps the default Automatic color. */
  const val NO_COLOR = 0

  private const val SAMPLE_DIMENSION = 64
  private const val MAX_DECODE_DIMENSION = 256
  private const val MIN_OPAQUE_ALPHA = 128
  private const val NEUTRAL_CHROMA = 0.12
  private const val HUE_BUCKETS = 36
  private const val MAX_SATURATION = 0.8f
  private const val MAX_START_LIGHTNESS = 0.5f
  private const val MIN_CONTRAST = 4.5

  private val FOREGROUND_COLOR_IDS = intArrayOf(
    R.color.conversation_outgoing_body_color,
    R.color.conversation_outgoing_footer_color,
    R.color.conversation_item_sent_text_primary_color,
    R.color.conversation_item_sent_text_secondary_color
  )

  private val lock = Any()

  /** Detects any wallpaper deletion between a database identity check and publishing its result. */
  @Volatile
  private var deletionVersion = 0L

  @Volatile
  private var cachedForegrounds: IntArray? = null

  /** The derived Automatic color for a photo wallpaper, or null to keep the existing fallback. No I/O. */
  @JvmStatic
  fun getAutoChatColors(uri: Uri): ChatColors? {
    val attachmentId = attachmentIdOf(uri) ?: return null
    val entry = SignalStore.wallpaper.getPhotoAutoColor(attachmentId.id) ?: return null
    return if (entry.color == NO_COLOR) null else ChatColors.forColor(ChatColors.Id.Auto, entry.color)
  }

  /** True when [wallpaper] is a photo with no derived entry yet. No I/O. */
  @JvmStatic
  fun needsBackfill(wallpaper: ChatWallpaper?): Boolean {
    if (wallpaper !is UriChatWallpaper) return false
    val attachmentId = attachmentIdOf(wallpaper.uri) ?: return false
    return SignalStore.wallpaper.getPhotoAutoColor(attachmentId.id) == null
  }

  /** Called on the wallpaper-save worker after insertion and before the wallpaper is published. Never throws. */
  @JvmStatic
  @WorkerThread
  fun onWallpaperSaved(wallpaper: ChatWallpaper, bytes: ByteArray) {
    if (wallpaper !is UriChatWallpaper) return
    val attachmentId = attachmentIdOf(wallpaper.uri) ?: return
    runCatchingAll("save") {
      val fingerprint = currentFingerprint(attachmentId) ?: return@runCatchingAll
      val color = bubbleColorFor(decodeSample { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, it) })
      storeIfCurrent(attachmentId, fingerprint, color)
    }
  }

  /**
   * Reuses the conversation's software bitmap when available. Otherwise, samples on the wallpaper worker
   * and lets the next normal reload pick up the color.
   */
  @JvmStatic
  @WorkerThread
  fun backfillFromPrefetched(wallpaper: ChatWallpaper?) {
    if (wallpaper !is UriChatWallpaper) return
    val attachmentId = attachmentIdOf(wallpaper.uri) ?: return
    runCatchingAll("prefetch backfill") {
      val fingerprint = currentFingerprint(attachmentId) ?: return@runCatchingAll
      if (SignalStore.wallpaper.getPhotoAutoColor(attachmentId.id)?.fingerprint == fingerprint) return@runCatchingAll
      val bitmap = wallpaper.cachedBitmap
      if (bitmap == null || (Build.VERSION.SDK_INT >= 26 && bitmap.config == Bitmap.Config.HARDWARE)) {
        SignalExecutors.BOUNDED.execute { backfill(listOf(attachmentId)) }
      } else {
        storeIfCurrent(attachmentId, fingerprint, bubbleColorFor(sample(bitmap)))
      }
    }
  }

  @JvmStatic
  @WorkerThread
  fun backfill(wallpaper: ChatWallpaper?) {
    if (wallpaper !is UriChatWallpaper) return
    val attachmentId = attachmentIdOf(wallpaper.uri) ?: return
    backfill(listOf(attachmentId))
  }

  /** Backfills saved wallpapers from the picker's existing worker load. Decodes a bounded sample when needed. */
  @JvmStatic
  @WorkerThread
  fun backfill(attachmentIds: List<AttachmentId>) {
    val context = AppDependencies.application
    for (attachmentId in attachmentIds) {
      runCatchingAll("picker backfill") {
        val fingerprint = currentFingerprint(attachmentId) ?: return@runCatchingAll
        if (SignalStore.wallpaper.getPhotoAutoColor(attachmentId.id)?.fingerprint == fingerprint) return@runCatchingAll
        val uri = PartAuthority.getAttachmentDataUri(attachmentId)
        val pixels = decodeSample { options -> PartAuthority.getAttachmentStream(context, uri).use { decodeStream(it, options) } }
        storeIfCurrent(attachmentId, fingerprint, bubbleColorFor(pixels))
      }
    }
  }

  @JvmStatic
  fun onAttachmentDeleted(attachmentId: AttachmentId) {
    synchronized(lock) {
      deletionVersion++
      SignalStore.wallpaper.removePhotoAutoColor(attachmentId.id)
    }
  }

  @JvmStatic
  fun onAllAttachmentsDeleted() {
    synchronized(lock) {
      deletionVersion++
      SignalStore.wallpaper.clearPhotoAutoColors()
    }
  }

  private fun storeIfCurrent(attachmentId: AttachmentId, fingerprint: Int, @ColorInt color: Int) {
    val version = deletionVersion
    if (currentFingerprint(attachmentId) != fingerprint) {
      Log.i(TAG, "Wallpaper attachment changed while sampling; not storing a color.")
      return
    }

    synchronized(lock) {
      if (version == deletionVersion) {
        SignalStore.wallpaper.setPhotoAutoColor(attachmentId.id, color, fingerprint)
      }
    }
  }

  /** Identifies the wallpaper's current data file, or null when it is not a wallpaper with local data. */
  private fun currentFingerprint(attachmentId: AttachmentId): Int? {
    val attachment = SignalDatabase.attachments.getAttachment(attachmentId) ?: return null
    if (attachment.mmsId != AttachmentTable.WALLPAPER_MESSAGE_ID) return null
    val path = SignalDatabase.attachments.getDataFilePath(attachmentId) ?: return null
    return File(path).name.hashCode()
  }

  private fun bubbleColorFor(pixels: IntArray?): Int {
    val seed = pixels?.let { seedFromPixels(it) } ?: return NO_COLOR
    return bubbleColorForSeed(seed, outgoingForegrounds(AppDependencies.application))
  }

  private inline fun runCatchingAll(operation: String, block: () -> Unit) {
    try {
      block()
    } catch (e: Exception) {
      Log.w(TAG, "Photo wallpaper color $operation failed; keeping the default Automatic color.", e)
    } catch (e: OutOfMemoryError) {
      Log.w(TAG, "Photo wallpaper color $operation ran out of memory; keeping the default Automatic color.")
    }
  }

  private fun decodeStream(stream: InputStream, options: BitmapFactory.Options): Bitmap? = BitmapFactory.decodeStream(stream, null, options)

  /** Decodes bounds, then a power-of-two subsampled software ARGB_8888 bitmap, and returns its sampled pixels. */
  @VisibleForTesting
  internal fun decodeSample(decode: (BitmapFactory.Options) -> Bitmap?): IntArray? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    decode(bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    var sampleSize = 1
    while (max(bounds.outWidth, bounds.outHeight) / (sampleSize * 2) >= MAX_DECODE_DIMENSION) {
      sampleSize *= 2
    }

    val options = BitmapFactory.Options().apply {
      inSampleSize = sampleSize
      inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    val bitmap = decode(options) ?: return null
    return try {
      sample(bitmap)
    } finally {
      bitmap.recycle()
    }
  }

  /** Scales a software bitmap to at most [SAMPLE_DIMENSION] pixels per side and returns its ARGB pixels. */
  @VisibleForTesting
  internal fun sample(bitmap: Bitmap): IntArray? {
    if (bitmap.width <= 0 || bitmap.height <= 0) return null
    val scale = min(1f, SAMPLE_DIMENSION.toFloat() / max(bitmap.width, bitmap.height))
    val width = max(1, (bitmap.width * scale).toInt())
    val height = max(1, (bitmap.height * scale).toInt())
    val scaled = if (width == bitmap.width && height == bitmap.height) bitmap else Bitmap.createScaledBitmap(bitmap, width, height, true)
    return try {
      IntArray(width * height).also { scaled.getPixels(it, 0, width, 0, 0, width, height) }
    } finally {
      if (scaled !== bitmap) scaled.recycle()
    }
  }

  /**
   * Picks the dominant hue weighted by chroma, ignoring mostly transparent pixels. Mostly neutral photos yield their
   * average color. Returns null when no pixel is opaque enough.
   */
  @VisibleForTesting
  internal fun seedFromPixels(pixels: IntArray): Int? {
    val weights = DoubleArray(HUE_BUCKETS)
    val reds = DoubleArray(HUE_BUCKETS)
    val greens = DoubleArray(HUE_BUCKETS)
    val blues = DoubleArray(HUE_BUCKETS)
    var opaque = 0
    var sumR = 0.0
    var sumG = 0.0
    var sumB = 0.0
    var chromaTotal = 0.0

    for (pixel in pixels) {
      if ((pixel ushr 24) < MIN_OPAQUE_ALPHA) continue
      val r = (pixel shr 16) and 0xFF
      val g = (pixel shr 8) and 0xFF
      val b = pixel and 0xFF
      opaque++
      sumR += r
      sumG += g
      sumB += b

      val maxC = max(r, max(g, b))
      val minC = min(r, min(g, b))
      val chroma = (maxC - minC) / 255.0
      if (chroma < NEUTRAL_CHROMA) continue

      val bucket = (hueOf(r, g, b, maxC, minC) / (360.0 / HUE_BUCKETS)).toInt().coerceIn(0, HUE_BUCKETS - 1)
      weights[bucket] += chroma
      reds[bucket] += r * chroma
      greens[bucket] += g * chroma
      blues[bucket] += b * chroma
      chromaTotal += chroma
    }

    if (opaque == 0) return null
    if (chromaTotal < opaque * 0.05) {
      return rgb((sumR / opaque).roundToInt(), (sumG / opaque).roundToInt(), (sumB / opaque).roundToInt())
    }

    var best = 0
    var bestWeight = -1.0
    for (i in 0 until HUE_BUCKETS) {
      val w = weights[(i + HUE_BUCKETS - 1) % HUE_BUCKETS] + weights[i] + weights[(i + 1) % HUE_BUCKETS]
      if (w > bestWeight) {
        bestWeight = w
        best = i
      }
    }

    var w = 0.0
    var r = 0.0
    var g = 0.0
    var b = 0.0
    for (offset in -1..1) {
      val i = (best + offset + HUE_BUCKETS) % HUE_BUCKETS
      w += weights[i]
      r += reds[i]
      g += greens[i]
      b += blues[i]
    }
    return rgb((r / w).roundToInt(), (g / w).roundToInt(), (b / w).roundToInt())
  }

  /** Keeps the seed's hue, limits saturation and lowers lightness until every [foregrounds] color passes 4.5:1. */
  @VisibleForTesting
  internal fun bubbleColorForSeed(@ColorInt seed: Int, foregrounds: IntArray): Int {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(seed, hsl)
    hsl[1] = min(hsl[1], MAX_SATURATION)
    hsl[2] = min(hsl[2], MAX_START_LIGHTNESS)
    while (true) {
      val candidate = ColorUtils.HSLToColor(hsl)
      if (foregrounds.all { ColorUtils.calculateContrast(it, candidate) >= MIN_CONTRAST }) {
        return candidate
      }
      if (hsl[2] <= 0f) return NO_COLOR
      hsl[2] = max(0f, hsl[2] - 0.01f)
    }
  }

  /** The outgoing bubble foregrounds the conversation actually draws, resolved for light and dark mode. */
  @VisibleForTesting
  internal fun outgoingForegrounds(context: Context): IntArray {
    cachedForegrounds?.let { return it }
    val colors = ArrayList<Int>()
    for (night in intArrayOf(Configuration.UI_MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_YES)) {
      val config = Configuration(context.resources.configuration).apply {
        uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
      }
      val themed = context.createConfigurationContext(config)
      FOREGROUND_COLOR_IDS.forEach { colors += ContextCompat.getColor(themed, it) }
    }
    return colors.toIntArray().also { cachedForegrounds = it }
  }

  private fun hueOf(r: Int, g: Int, b: Int, maxC: Int, minC: Int): Double {
    val d = (maxC - minC).toDouble()
    val h = when (maxC) {
      r -> ((g - b) / d) % 6
      g -> (b - r) / d + 2
      else -> (r - g) / d + 4
    } * 60
    return if (h < 0) h + 360 else h
  }

  private fun rgb(r: Int, g: Int, b: Int): Int = (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

  private fun attachmentIdOf(uri: Uri): AttachmentId? {
    return try {
      AttachmentId(ContentUris.parseId(uri)).takeIf { it.isValid && PartAuthority.getAttachmentDataUri(it) == uri }
    } catch (e: RuntimeException) {
      null
    }
  }
}
