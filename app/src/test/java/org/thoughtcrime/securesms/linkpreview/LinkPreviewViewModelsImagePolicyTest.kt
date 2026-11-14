/*
 * Copyright 2026 Collie contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.linkpreview

import android.app.Application
import android.os.Looper
import androidx.lifecycle.Observer
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.subjects.SingleSubject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.signal.core.util.Result
import org.thoughtcrime.securesms.attachments.Attachment
import org.thoughtcrime.securesms.keyvalue.SettingsValues
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.net.RequestController
import org.thoughtcrime.securesms.testutil.MockAppDependenciesRule
import org.thoughtcrime.securesms.testutil.RxPluginsRule
import java.time.Duration
import java.util.Optional

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class LinkPreviewViewModelsImagePolicyTest {

  companion object {
    private const val WEB_URL = "https://example.org/article"
    private const val STICKER_URL = "https://signal.art/addstickers/#pack_id=aabb&pack_key=ccdd"
  }

  @get:Rule val rx = RxPluginsRule()
  @get:Rule val appDependencies = MockAppDependenciesRule()

  private val settings = mockk<SettingsValues>()
  private val repository = mockk<LinkPreviewRepository>()
  private val viewModels = ViewModelStore()
  private val removeObservers = mutableListOf<() -> Unit>()
  private var includeImages = true
  private var nextModel = 0

  @Before
  fun setUp() {
    mockkObject(SignalStore)
    mockkStatic(SignalStore::class)
    every { SignalStore.settings } returns settings
    every { settings.isLinkPreviewsEnabled } returns true
    every { settings.isWebLinkPreviewImagesEnabled } answers { includeImages }
    every { repository.getLinkPreview(any<String>()) } returns Single.never()
  }

  @After
  fun tearDown() {
    removeObservers.forEach { it() }
    viewModels.clear()
    rx.defaultScheduler.triggerActions()
    unmockkAll()
  }

  @Test
  fun `restored preview is text-only before the first state is exposed`() {
    includeImages = false
    val original = imagePreview()
    val model = v2(SavedStateHandle(mapOf("link_preview_state" to LinkPreviewState.forPreview(original), "active_url" to WEB_URL)))

    assertFalse(model.linkPreviewStateSnapshot.linkPreview.get().thumbnail.isPresent)
    assertFalse(model.onSend().single().thumbnail.isPresent)
    assertTrue(original.thumbnail.isPresent)
    verify(exactly = 0) { repository.getLinkPreview(any<String>()) }
  }

  @Test
  fun `adopted preview and same-url reuse apply the current image preference`() {
    includeImages = false
    val model = v2()
    model.onLinkPreviewResolved(LinkPreviewState.forPreview(imagePreview()))
    drain()
    model.onTextChanged(WEB_URL, WEB_URL.length, WEB_URL.length)
    drain()

    assertFalse(model.linkPreviewStateSnapshot.linkPreview.get().thumbnail.isPresent)
    verify(exactly = 0) { repository.getLinkPreview(any<String>()) }
  }

  @Test
  fun `send rechecks images even without a resume or text change`() {
    val original = imagePreview()
    val model = v2()
    model.onLinkPreviewResolved(LinkPreviewState.forPreview(original))
    drain()
    includeImages = false

    val sent = model.onSend().single()

    assertFalse(sent.thumbnail.isPresent)
    assertEquals(original.url, sent.url)
    assertTrue(original.thumbnail.isPresent)
  }

  @Test
  fun `image-only restored state retains its URL for the story fallback`() {
    includeImages = false
    val model = v2(SavedStateHandle(mapOf("link_preview_state" to LinkPreviewState.forPreview(imagePreview(title = "", description = "")))))

    assertFalse(model.linkPreviewStateSnapshot.linkPreview.isPresent)
    assertEquals(WEB_URL, model.linkPreviewStateSnapshot.activeUrlForError)
    assertTrue(model.onSend().isEmpty())
  }

  @Test
  fun `late completed request cannot restore a disabled thumbnail`() {
    val pending = SingleSubject.create<Result<LinkPreview, LinkPreviewRepository.Error>>()
    every { repository.getLinkPreview(WEB_URL) } returns pending
    val model = v2()
    model.onTextChanged(WEB_URL, WEB_URL.length, WEB_URL.length)
    drain()
    assertTrue(pending.hasObservers())

    includeImages = false
    pending.onSuccess(Result.success(imagePreview()))
    drain()

    assertFalse(model.linkPreviewStateSnapshot.linkPreview.get().thumbnail.isPresent)
    assertFalse(model.onSend().single().thumbnail.isPresent)
  }

  @Test
  fun `refresh cancels a pending web request and retries under the changed preference`() {
    val first = SingleSubject.create<Result<LinkPreview, LinkPreviewRepository.Error>>()
    val second = SingleSubject.create<Result<LinkPreview, LinkPreviewRepository.Error>>()
    every { repository.getLinkPreview(WEB_URL) } returnsMany listOf(first, second)
    val model = v2()
    model.onTextChanged(WEB_URL, WEB_URL.length, WEB_URL.length)
    drain()

    includeImages = false
    model.refreshImagePreference()
    drain()

    assertFalse(first.hasObservers())
    assertTrue(second.hasObservers())
    second.onSuccess(Result.success(imagePreview()))
    drain()
    assertFalse(model.linkPreviewStateSnapshot.linkPreview.get().thumbnail.isPresent)
    verify(exactly = 2) { repository.getLinkPreview(WEB_URL) }
  }

  @Test
  fun `web preference changes do not restart a pending Signal card`() {
    val pending = SingleSubject.create<Result<LinkPreview, LinkPreviewRepository.Error>>()
    every { repository.getLinkPreview(STICKER_URL) } returns pending
    val model = v2()
    model.onTextChanged(STICKER_URL, STICKER_URL.length, STICKER_URL.length)
    drain()

    includeImages = false
    model.refreshImagePreference()
    drain()

    assertTrue(pending.hasObservers())
    verify(exactly = 1) { repository.getLinkPreview(STICKER_URL) }
    val original = imagePreview(STICKER_URL)
    pending.onSuccess(Result.success(original))
    drain()
    assertSame(original, model.onSend().single())
  }

  @Test
  fun `default-enabled composer sends its original preview`() {
    val original = imagePreview()
    val model = v2()
    model.onLinkPreviewResolved(LinkPreviewState.forPreview(original))
    drain()

    assertSame(original, model.onSend().single())
  }

  @Test
  fun `legacy send rechecks a thumbnail cached before images were disabled`() {
    val callbacks = mutableListOf<LinkPreviewRepository.Callback>()
    val controller = mockk<RequestController>(relaxed = true)
    every { repository.getLinkPreview(any(), WEB_URL, capture(callbacks)) } returns controller
    val model = legacy()
    model.onTextChanged(WEB_URL, WEB_URL.length, WEB_URL.length)
    drain()
    val original = imagePreview()
    callbacks.single().onSuccess(original)
    drain()
    includeImages = false

    assertFalse(model.onSend().single().thumbnail.isPresent)
    assertTrue(original.thumbnail.isPresent)
  }

  @Test
  fun `legacy refresh ignores a stale callback without losing the new request controller`() {
    val callbacks = mutableListOf<LinkPreviewRepository.Callback>()
    val first = mockk<RequestController>(relaxed = true)
    val second = mockk<RequestController>(relaxed = true)
    every { repository.getLinkPreview(any(), WEB_URL, capture(callbacks)) } returnsMany listOf(first, second)
    val model = legacy()
    model.onTextChanged(WEB_URL, WEB_URL.length, WEB_URL.length)
    drain()

    includeImages = false
    model.refreshImagePreference()
    callbacks.first().onSuccess(imagePreview(title = "Stale"))
    drain()

    assertTrue(model.linkPreviewState.value!!.isLoading)
    model.onSend()
    verify(exactly = 1) { first.cancel() }
    verify(exactly = 1) { second.cancel() }
  }

  @Test
  fun `legacy web preference changes leave a pending Signal card alone`() {
    val callbacks = mutableListOf<LinkPreviewRepository.Callback>()
    val controller = mockk<RequestController>(relaxed = true)
    every { repository.getLinkPreview(any(), STICKER_URL, capture(callbacks)) } returns controller
    val model = legacy()
    model.onTextChanged(STICKER_URL, STICKER_URL.length, STICKER_URL.length)
    drain()
    includeImages = false
    model.refreshImagePreference()

    verify(exactly = 0) { controller.cancel() }
    verify(exactly = 1) { repository.getLinkPreview(any(), STICKER_URL, any()) }
    val original = imagePreview(STICKER_URL)
    callbacks.single().onSuccess(original)
    drain()
    assertSame(original, model.onSend().single())
  }

  private fun v2(savedState: SavedStateHandle = SavedStateHandle()): LinkPreviewViewModelV2 {
    val model = LinkPreviewViewModelV2(savedState, repository, enablePlaceholder = false)
    viewModels.put("model-${nextModel++}", model)
    return model
  }

  private fun legacy(): LinkPreviewViewModel {
    val model = LinkPreviewViewModel.Factory(repository).create(LinkPreviewViewModel::class.java)
    viewModels.put("model-${nextModel++}", model)
    val observer = Observer<LinkPreviewState> { }
    model.linkPreviewState.observeForever(observer)
    removeObservers += { model.linkPreviewState.removeObserver(observer) }
    return model
  }

  private fun drain() {
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
    rx.defaultScheduler.triggerActions()
    shadowOf(Looper.getMainLooper()).idle()
    rx.defaultScheduler.triggerActions()
  }

  private fun imagePreview(url: String = WEB_URL, title: String = "Title", description: String = "Description"): LinkPreview {
    return LinkPreview(url, title, description, 123, Optional.of(mockk<Attachment>()))
  }
}
