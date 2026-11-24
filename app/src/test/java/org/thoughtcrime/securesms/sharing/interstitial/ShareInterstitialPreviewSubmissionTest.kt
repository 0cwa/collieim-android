/*
 * Copyright 2026 Collie contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.sharing.interstitial

import android.app.Application
import androidx.core.util.Consumer
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.models.database.AttachmentId
import org.signal.core.models.recipients.RecipientId
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.contacts.paged.ContactSearchKey
import org.thoughtcrime.securesms.database.FakeMessageRecords
import org.thoughtcrime.securesms.keyvalue.SettingsValues
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.linkpreview.LinkPreview
import org.thoughtcrime.securesms.sharing.MultiShareArgs
import org.thoughtcrime.securesms.sharing.MultiShareSender
import org.thoughtcrime.securesms.testutil.EmptyLogger
import org.thoughtcrime.securesms.util.livedata.LiveDataRule

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class ShareInterstitialPreviewSubmissionTest {

  @get:Rule
  val liveDataRule = LiveDataRule()

  private val settings = mockk<SettingsValues>()
  private val repository = mockk<ShareInterstitialRepository>(relaxed = true)
  private val sentArgs = slot<MultiShareArgs>()
  private val contacts = setOf<ContactSearchKey>(ContactSearchKey.RecipientSearchKey(RecipientId.from(1), false))
  private var imagesEnabled = true

  @Before
  fun setUp() {
    Log.initialize(EmptyLogger())
    mockkObject(SignalStore)
    mockkStatic(SignalStore::class, MultiShareSender::class)
    every { SignalStore.settings } returns settings
    every { settings.isWebLinkPreviewImagesEnabled } answers { imagesEnabled }
    every { MultiShareSender.send(capture(sentArgs), any()) } just Runs
  }

  @After
  fun tearDown() {
    unmockkStatic(SignalStore::class, MultiShareSender::class)
    unmockkObject(SignalStore)
  }

  @Test
  fun `submission rechecks the image preference after a preview was cached`() {
    val original = databasePreview()
    val args = MultiShareArgs.Builder(contacts).withDraftText(original.url).withLinkPreview(original).build()
    val viewModel = ShareInterstitialViewModel(args, repository)
    viewModel.onLinkPreviewChanged(original)
    viewModel.onDraftTextChanged("Updated draft ${original.url}")

    imagesEnabled = false
    viewModel.send(Consumer<MultiShareSender.MultiShareSendResultCollection> {})

    val submitted = requireNotNull(sentArgs.captured.linkPreview)
    assertNotSame(original, submitted)
    assertFalse(submitted.thumbnail.isPresent)
    assertNull(submitted.attachmentId)
    assertEquals(original.url, submitted.url)
    assertEquals(original.title, submitted.title)
    assertEquals(original.description, submitted.description)
    assertEquals(original.date, submitted.date)
    assertEquals("Updated draft ${original.url}", sentArgs.captured.draftText)
    assertEquals(contacts, sentArgs.captured.contactSearchKeys)
    assertSame(original, args.linkPreview)
    assertTrue(original.thumbnail.isPresent)
    assertEquals(AttachmentId(42), original.attachmentId)
    verify(exactly = 1) { MultiShareSender.send(any(), any()) }
  }

  @Test
  fun `submission with images enabled preserves the cached preview`() {
    val original = databasePreview()
    val args = MultiShareArgs.Builder(contacts).withDraftText(original.url).build()
    val viewModel = ShareInterstitialViewModel(args, repository)
    viewModel.onLinkPreviewChanged(original)

    viewModel.send(Consumer<MultiShareSender.MultiShareSendResultCollection> {})

    assertSame(original, sentArgs.captured.linkPreview)
    assertTrue(requireNotNull(sentArgs.captured.linkPreview).thumbnail.isPresent)
    assertEquals(AttachmentId(42), sentArgs.captured.linkPreview?.attachmentId)
    assertEquals(original.url, sentArgs.captured.draftText)
    verify(exactly = 1) { MultiShareSender.send(any(), any()) }
  }

  @Test
  fun `dropping an image-only card preserves the shared URL in the outgoing draft`() {
    val original = databasePreview(title = "", description = "")
    val args = MultiShareArgs.Builder(contacts).withDraftText(original.url).withLinkPreview(original).build()
    val viewModel = ShareInterstitialViewModel(args, repository)
    viewModel.onLinkPreviewChanged(original)

    imagesEnabled = false
    viewModel.send(Consumer<MultiShareSender.MultiShareSendResultCollection> {})

    assertNull(sentArgs.captured.linkPreview)
    assertEquals(original.url, sentArgs.captured.draftText)
    assertTrue(original.thumbnail.isPresent)
    assertEquals(AttachmentId(42), original.attachmentId)
    assertSame(original, args.linkPreview)
    verify(exactly = 1) { MultiShareSender.send(any(), any()) }
  }

  private fun databasePreview(title: String = "Article title", description: String = "Article description"): LinkPreview {
    val image = FakeMessageRecords.buildDatabaseAttachment(attachmentId = AttachmentId(42), hasData = true, contentType = "image/png")
    return LinkPreview("https://example.org/article", title, description, 123L, image)
  }
}
