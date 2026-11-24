/*
 * Copyright 2026 Collie contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.mediasend.v2.text

import android.app.Application
import android.graphics.Typeface
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import io.mockk.verify
import io.reactivex.rxjava3.android.plugins.RxAndroidPlugins
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.plugins.RxJavaPlugins
import io.reactivex.rxjava3.schedulers.Schedulers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.models.database.AttachmentId
import org.signal.core.models.recipients.RecipientId
import org.signal.core.util.logging.Log
import org.signal.remoteconfig.RemoteConfig
import org.thoughtcrime.securesms.contacts.paged.ContactSearchKey
import org.thoughtcrime.securesms.database.FakeMessageRecords
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.fonts.TypefaceCache
import org.thoughtcrime.securesms.keyvalue.SettingsValues
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.linkpreview.LinkPreview
import org.thoughtcrime.securesms.mediasend.v2.text.send.TextStoryPostSendRepository
import org.thoughtcrime.securesms.mediasend.v2.text.send.TextStoryPostSendResult
import org.thoughtcrime.securesms.testutil.EmptyLogger

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class TextStoryPreviewSubmissionTest {

  private val settings = mockk<SettingsValues>()
  private val repository = mockk<TextStoryPostSendRepository>()
  private val viewModelStore = ViewModelStore()
  private val sentState = slot<TextStoryPostCreationState>()
  private val contacts = setOf<ContactSearchKey>(ContactSearchKey.RecipientSearchKey(RecipientId.from(1), true))
  private var submittedPreview: LinkPreview? = null
  private var imagesEnabled = true

  @Before
  fun setUp() {
    Log.initialize(EmptyLogger())
    RxJavaPlugins.setIoSchedulerHandler { Schedulers.trampoline() }
    RxJavaPlugins.setComputationSchedulerHandler { Schedulers.trampoline() }
    RxAndroidPlugins.setInitMainThreadSchedulerHandler { Schedulers.trampoline() }
    RxAndroidPlugins.setMainThreadSchedulerHandler { Schedulers.trampoline() }

    mockkObject(SignalStore, TypefaceCache, RemoteConfig)
    mockkStatic(SignalStore::class, AppDependencies::class)
    every { SignalStore.settings } returns settings
    every { settings.isWebLinkPreviewImagesEnabled } answers { imagesEnabled }
    every { RemoteConfig.storiesTextFunctions } returns false
    every { AppDependencies.application } returns ApplicationProvider.getApplicationContext()
    every { TypefaceCache.get(any(), any(), any()) } returns Single.just(Typeface.DEFAULT)
    every { repository.send(contacts, capture(sentState), any(), 456L) } answers {
      submittedPreview = thirdArg()
      Single.just<TextStoryPostSendResult>(TextStoryPostSendResult.Success)
    }
  }

  @After
  fun tearDown() {
    viewModelStore.clear()
    unmockkStatic(SignalStore::class, AppDependencies::class)
    unmockkObject(SignalStore, TypefaceCache, RemoteConfig)
    RxAndroidPlugins.reset()
    RxJavaPlugins.reset()
  }

  @Test
  fun `story submission rechecks the preference after composing an image preview`() {
    val original = databasePreview()
    val viewModel = createViewModel()
    viewModel.setBody("A story with context")
    viewModel.setLinkPreview(original.url)

    imagesEnabled = false
    assertSame(TextStoryPostSendResult.Success, viewModel.send(contacts, original).blockingGet())

    val submitted = requireNotNull(submittedPreview)
    assertNotSame(original, submitted)
    assertFalse(submitted.thumbnail.isPresent)
    assertNull(submitted.attachmentId)
    assertEquals(original.url, submitted.url)
    assertEquals(original.title, submitted.title)
    assertEquals(original.description, submitted.description)
    assertEquals(original.date, submitted.date)
    assertEquals("A story with context", sentState.captured.body.toString())
    assertEquals(original.url, sentState.captured.linkPreviewUri)
    assertTrue(original.thumbnail.isPresent)
    assertEquals(AttachmentId(42), original.attachmentId)
    verify(exactly = 1) { repository.send(contacts, any(), any(), 456L) }
  }

  @Test
  fun `story submission with images enabled preserves the composed preview`() {
    val original = databasePreview()
    val viewModel = createViewModel()
    viewModel.setLinkPreview(original.url)

    assertSame(TextStoryPostSendResult.Success, viewModel.send(contacts, original).blockingGet())

    assertSame(original, submittedPreview)
    assertTrue(requireNotNull(submittedPreview).thumbnail.isPresent)
    assertEquals(AttachmentId(42), submittedPreview?.attachmentId)
    assertEquals(original.url, sentState.captured.linkPreviewUri)
    verify(exactly = 1) { repository.send(contacts, any(), any(), 456L) }
  }

  @Test
  fun `image-only link story retains a visible URL after images are disabled`() {
    val original = databasePreview(title = "", description = "")
    val viewModel = createViewModel()
    viewModel.setLinkPreview(original.url)

    imagesEnabled = false
    assertSame(TextStoryPostSendResult.Success, viewModel.send(contacts, original).blockingGet())

    val submitted = requireNotNull(submittedPreview)
    assertEquals("", sentState.captured.body.toString())
    assertEquals(original.url, sentState.captured.linkPreviewUri)
    assertEquals(original.url, submitted.url)
    assertEquals(original.url, submitted.title)
    assertFalse(submitted.thumbnail.isPresent)
    assertNull(submitted.attachmentId)
    assertEquals("", submitted.description)
    assertTrue(original.thumbnail.isPresent)
    assertEquals(AttachmentId(42), original.attachmentId)
    assertEquals("", original.title)
    verify(exactly = 1) { repository.send(contacts, any(), any(), 456L) }
  }

  private fun createViewModel(): TextStoryPostCreationViewModel {
    return ViewModelProvider(
      viewModelStore,
      viewModelFactory {
        initializer {
          TextStoryPostCreationViewModel(SavedStateHandle(), repository, identityChangesSince = 456L)
        }
      }
    )[TextStoryPostCreationViewModel::class.java]
  }

  private fun databasePreview(title: String = "Article title", description: String = "Article description"): LinkPreview {
    val image = FakeMessageRecords.buildDatabaseAttachment(attachmentId = AttachmentId(42), hasData = true, contentType = "image/png")
    return LinkPreview("https://example.org/article", title, description, 123L, image)
  }
}
