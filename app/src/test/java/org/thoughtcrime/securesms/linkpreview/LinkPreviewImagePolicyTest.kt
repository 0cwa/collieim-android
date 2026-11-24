/*
 * Copyright 2026 Collie contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.linkpreview

import android.app.Application
import android.os.Parcel
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.models.database.AttachmentId
import org.thoughtcrime.securesms.attachments.Attachment
import org.thoughtcrime.securesms.database.FakeMessageRecords
import java.util.Optional

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class LinkPreviewImagePolicyTest {

  @Test
  fun `enabled images preserve the original preview`() {
    val preview = previewWithImage("https://example.org/article")

    assertSame(preview, LinkPreviewImagePolicy.sanitize(preview, true))
  }

  @Test
  fun `text-only copy preserves raw text and clears image data and database identity`() {
    val attachment = FakeMessageRecords.buildDatabaseAttachment(attachmentId = AttachmentId(12))
    val preview = LinkPreview("https://example.org/article", "A &amp;amp; B", "<b>Raw</b> &amp; text", 123, attachment)

    val sanitized = requireNotNull(LinkPreviewImagePolicy.sanitize(preview, false))

    assertFalse(sanitized.thumbnail.isPresent)
    assertNull(sanitized.attachmentId)
    assertEquals(preview.title, sanitized.title)
    assertEquals(preview.description, sanitized.description)
    assertEquals(preview.date, sanitized.date)
    val parcel = Parcel.obtain()
    try {
      sanitized.writeToParcel(parcel, 0)
      parcel.setDataPosition(0)
      assertEquals(preview.url, parcel.readString())
      assertEquals("A &amp;amp; B", parcel.readString())
      assertEquals("<b>Raw</b> &amp; text", parcel.readString())
      assertEquals(preview.date, parcel.readLong())
    } finally {
      parcel.recycle()
    }
    assertTrue(preview.thumbnail.isPresent)
    assertEquals(AttachmentId(12), preview.attachmentId)
    assertSame(sanitized, LinkPreviewImagePolicy.sanitize(sanitized, false))
  }

  @Test
  fun `deserialized preview loses its attachment reference without changing raw text`() {
    val preview = LinkPreview.deserialize("""{"url":"https://example.org/article","title":"A &amp;amp; B","description":"details","date":12,"attachmentId":null}""")
    val withReference = LinkPreview(preview.url, "A &amp;amp; B", "details", preview.date, AttachmentId(42))

    val sanitized = requireNotNull(LinkPreviewImagePolicy.sanitize(withReference, false))

    assertEquals(preview.serialize(), sanitized.serialize())
    assertNull(sanitized.attachmentId)
    assertFalse(sanitized.thumbnail.isPresent)
  }

  @Test
  fun `image-only and visually empty web previews are omitted`() {
    val imageOnly = previewWithImage("https://example.org/article", title = "", description = "")
    val blank = previewWithImage("https://example.org/article", title = "<br>", description = " ")

    assertNull(LinkPreviewImagePolicy.sanitize(imageOnly, false))
    assertNull(LinkPreviewImagePolicy.sanitize(blank, false))
    assertSame(imageOnly, LinkPreviewImagePolicy.sanitize(imageOnly, true))
    assertNull(LinkPreviewImagePolicy.sanitize(null, false))
  }

  @Test
  fun `description-only web previews retain their text`() {
    val preview = previewWithImage("https://example.org/article", title = "", description = "Useful description")

    val sanitized = requireNotNull(LinkPreviewImagePolicy.sanitize(preview, false))

    assertEquals("Useful description", sanitized.description)
    assertFalse(sanitized.thumbnail.isPresent)
  }

  @Test
  fun `Signal sticker group and call cards keep their images`() {
    val specialUrls = listOf(
      "https://signal.art/addstickers/#pack_id=aabb&pack_key=ccdd",
      "https://signal.group/#test",
      "https://signal.link/call/#key=test"
    )

    for (url in specialUrls) {
      val preview = previewWithImage(url, title = "", description = "")
      assertFalse(LinkPreviewImagePolicy.isWebLinkPreview(url))
      assertSame(preview, LinkPreviewImagePolicy.sanitize(preview, false))
    }

    assertTrue(LinkPreviewImagePolicy.isWebLinkPreview("https://signal.org/blog/"))
    assertNotNull(LinkPreviewImagePolicy.sanitize(previewWithImage("https://signal.org/blog/"), false))
  }

  private fun previewWithImage(url: String, title: String = "Title", description: String = "Description"): LinkPreview {
    return LinkPreview(url, title, description, 123, Optional.of(mockk<Attachment>()))
  }
}
