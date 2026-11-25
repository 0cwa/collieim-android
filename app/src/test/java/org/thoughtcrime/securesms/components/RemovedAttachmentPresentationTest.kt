package org.thoughtcrime.securesms.components

import android.app.Application
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import com.bumptech.glide.RequestManager
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.attachments.Attachment
import org.thoughtcrime.securesms.mms.AudioSlide
import org.thoughtcrime.securesms.mms.DocumentSlide
import org.thoughtcrime.securesms.mms.ImageSlide
import org.thoughtcrime.securesms.mms.Slide
import org.thoughtcrime.securesms.testutil.MockSignalStoreRule
import java.util.Optional

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class RemovedAttachmentPresentationTest {

  @get:Rule
  val signalStore = MockSignalStoreRule()

  private lateinit var context: Context

  @Before
  fun setUp() {
    context = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_Molly_Material3_Light)
    every { signalStore.internal.showArchiveStateHint } returns false
  }

  @Test
  fun `removed image shows a readable marker and explanation instead of playback or transfer controls`() {
    val thumbnail = ThumbnailView(context)
    val slide = imageSlide(removed = true)

    thumbnail.setImageResource(mockk<RequestManager>(relaxed = true), slide, true, false, 320, 240, 0)
    thumbnail.setPlayOverlayForced(true)

    val marker = thumbnail.findViewById<TextView>(R.id.thumbnail_removed_label)
    assertEquals(View.VISIBLE, marker.visibility)
    assertEquals(context.getString(R.string.CollieAttachmentRemoval_removed), marker.text.toString())
    assertEquals(context.getString(R.string.CollieAttachmentRemoval_removed_explanation), thumbnail.contentDescription)
    assertEquals(View.GONE, thumbnail.findViewById<View>(R.id.play_overlay).visibility)
    assertEquals(View.GONE, thumbnail.findViewById<View>(R.id.transfer_controls_stub).visibility)
    assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, thumbnail.findViewById<View>(R.id.thumbnail_image).importantForAccessibility)
  }

  @Test
  fun `reusing a thumbnail for a different unavailable image clears the intentional removal marker`() {
    val thumbnail = ThumbnailView(context)
    val requests = mockk<RequestManager>(relaxed = true)
    thumbnail.setImageResource(requests, imageSlide(removed = true), false, false)

    thumbnail.setImageResource(requests, imageSlide(removed = false), false, false)

    assertEquals(View.GONE, thumbnail.findViewById<View>(R.id.thumbnail_removed_label).visibility)
    assertEquals(View.VISIBLE, thumbnail.findViewById<View>(R.id.thumbnail_error).visibility)
    assertNull(thumbnail.contentDescription)
    assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO, thumbnail.findViewById<View>(R.id.thumbnail_image).importantForAccessibility)
  }

  @Test
  fun `reusing a removed thumbnail for ordinary content restores forced playback overlays`() {
    val thumbnail = ThumbnailView(context)
    val requests = mockk<RequestManager>(relaxed = true)
    thumbnail.setImageResource(requests, imageSlide(removed = true), false, false)
    thumbnail.setPlayOverlayForced(true)

    thumbnail.setImageDrawable(requests, ColorDrawable(Color.BLACK))
    thumbnail.setPlayOverlayForced(true)

    assertEquals(View.GONE, thumbnail.findViewById<View>(R.id.thumbnail_removed_label).visibility)
    assertEquals(View.VISIBLE, thumbnail.findViewById<View>(R.id.play_overlay).visibility)
  }

  @Test
  fun `compact album cell keeps a short visible label and full accessibility explanation`() {
    val thumbnail = ThumbnailView(context)
    thumbnail.setImageResource(mockk<RequestManager>(relaxed = true), imageSlide(removed = true), false, false)
    val size = (80 * context.resources.displayMetrics.density).toInt()
    thumbnail.measure(View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY))
    thumbnail.layout(0, 0, size, size)

    val marker = thumbnail.findViewById<TextView>(R.id.thumbnail_removed_label)
    assertEquals(context.getString(R.string.CollieAttachmentRemoval_removed_short), marker.text.toString())
    assertNull(marker.compoundDrawables[1])
    assertEquals(context.getString(R.string.CollieAttachmentRemoval_removed_explanation), thumbnail.contentDescription)
  }

  @Test
  fun `removed document keeps its filename and opens its explanation without a file uri`() {
    val document = DocumentView(context)
    val slide = namedSlide(mockk<DocumentSlide>(relaxed = true), "minutes.pdf")
    val clicks = mutableListOf<Slide>()
    document.setDocumentClickListener { _, clicked -> clicks += clicked }

    document.setDocument(slide, true, true)
    document.performClick()

    assertEquals("minutes.pdf", document.findViewById<TextView>(R.id.file_name).text.toString())
    assertEquals(context.getString(R.string.CollieAttachmentRemoval_removed), document.findViewById<TextView>(R.id.file_size).text.toString())
    assertEquals(View.GONE, document.findViewById<View>(R.id.download).visibility)
    assertEquals(View.GONE, document.findViewById<View>(R.id.upload).visibility)
    assertEquals(View.GONE, document.findViewById<View>(R.id.stop_upload_progress_container).visibility)
    assertEquals(listOf(slide), clicks)
  }

  @Test
  fun `removed audio renders a named nonplayable document marker and remains explainable`() {
    val document = DocumentView(context)
    val slide = namedSlide(mockk<AudioSlide>(relaxed = true), null)
    every { slide.hasAudio() } returns true
    val clicks = mutableListOf<Slide>()
    document.setDocumentClickListener { _, clicked -> clicks += clicked }

    document.setDocument(slide, true, true)
    document.performClick()

    assertEquals(context.getString(R.string.Slide_audio), document.findViewById<TextView>(R.id.file_name).text.toString())
    assertEquals(context.getString(R.string.CollieAttachmentRemoval_removed), document.findViewById<TextView>(R.id.file_size).text.toString())
    assertEquals(listOf(slide), clicks)
  }

  private fun imageSlide(removed: Boolean): Slide {
    val attachment = mockk<Attachment>(relaxed = true)
    every { attachment.isLocallyRemoved } returns removed
    every { attachment.isPermanentlyFailed } returns true
    return mockk<ImageSlide>(relaxed = true).apply {
      every { asAttachment() } returns attachment
    }
  }

  private fun <T : Slide> namedSlide(slide: T, fileName: String?): T {
    val attachment = mockk<Attachment>(relaxed = true)
    every { attachment.isLocallyRemoved } returns true
    every { slide.asAttachment() } returns attachment
    every { slide.fileName } returns Optional.ofNullable(fileName)
    every { slide.caption } returns Optional.empty()
    every { slide.uri } returns null
    every { slide.getFileType(any()) } returns Optional.empty()
    return slide
  }
}
