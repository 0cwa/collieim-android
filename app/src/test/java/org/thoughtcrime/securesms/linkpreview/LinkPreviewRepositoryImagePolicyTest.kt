/*
 * Copyright 2026 Collie contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.linkpreview

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thoughtcrime.securesms.keyvalue.SettingsValues
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.net.RequestController
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Runs the repository with an in-memory HTTP interceptor: no sockets or real website requests. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class LinkPreviewRepositoryImagePolicyTest {

  companion object {
    private const val PAGE = "https://example.org/article"
    private const val IMAGE = "https://images.example.org/preview.png"
    private const val PAGE_WITH_IMAGE = """<meta property="og:title" content="Article"><meta property="og:description" content="Details"><meta property="og:image" content="$IMAGE">"""
  }

  private val includeImages = AtomicBoolean(false)
  private val requests = Collections.synchronizedList(mutableListOf<String>())
  private val clients = mutableListOf<OkHttpClient>()
  private val settings = mockk<SettingsValues>()

  @Before
  fun setUp() {
    mockkObject(SignalStore)
    mockkStatic(SignalStore::class)
    every { SignalStore.settings } returns settings
    every { settings.isLinkPreviewsEnabled } returns true
    every { settings.isWebLinkPreviewImagesEnabled } answers { includeImages.get() }
  }

  @After
  fun tearDown() {
    for (client in clients) {
      client.dispatcher.cancelAll()
      client.dispatcher.executorService.shutdownNow()
      client.dispatcher.executorService.awaitTermination(5, TimeUnit.SECONDS)
      client.connectionPool.evictAll()
    }
    unmockkAll()
  }

  @Test
  fun `disabled images request metadata only and return text`() {
    val pending = begin { request -> response(request, PAGE_WITH_IMAGE) }

    val outcome = pending.await()

    assertNull(outcome.error)
    assertEquals("Article", outcome.preview!!.title)
    assertEquals("Details", outcome.preview.description)
    assertFalse(outcome.preview.thumbnail.isPresent)
    assertEquals(listOf(PAGE), requests.toList())
  }

  @Test
  fun `enabled images still request the thumbnail and retain text when that request fails`() {
    includeImages.set(true)
    val pending = begin { request ->
      if (request.url.toString() == PAGE) response(request, PAGE_WITH_IMAGE) else response(request, "", code = 404)
    }

    val outcome = pending.await()

    assertNull(outcome.error)
    assertEquals("Article", outcome.preview!!.title)
    assertFalse(outcome.preview.thumbnail.isPresent)
    assertEquals(listOf(PAGE, IMAGE), requests.toList())
  }

  @Test
  fun `disabled images omit a preview with no text without requesting its image`() {
    val pending = begin { request -> response(request, """<meta property="og:image" content="$IMAGE">""") }

    val outcome = pending.await()

    assertNull(outcome.preview)
    assertEquals(LinkPreviewRepository.Error.PREVIEW_NOT_AVAILABLE, outcome.error)
    assertEquals(listOf(PAGE), requests.toList())
  }

  @Test
  fun `description-only metadata is usable in text-only mode`() {
    val pending = begin { request -> response(request, """<meta property="og:description" content="Description only">""") }

    val outcome = pending.await()

    assertNull(outcome.error)
    assertEquals("Description only", outcome.preview!!.description)
    assertFalse(outcome.preview.thumbnail.isPresent)
    assertEquals(listOf(PAGE), requests.toList())
  }

  @Test
  fun `enabled images retain the existing description-only rejection`() {
    includeImages.set(true)
    val pending = begin { request -> response(request, """<meta property="og:description" content="Description only">""") }

    assertEquals(LinkPreviewRepository.Error.PREVIEW_NOT_AVAILABLE, pending.await().error)
    assertEquals(listOf(PAGE), requests.toList())
  }

  @Test
  fun `direct image URL closes its metadata response without reading or requesting another image`() {
    val body = TrackedBody("image/png")
    val pending = begin(IMAGE) { request -> response(request, body) }

    val outcome = pending.await()

    assertNull(outcome.error)
    assertEquals("preview.png", outcome.preview!!.title)
    assertFalse(outcome.preview.thumbnail.isPresent)
    assertTrue(body.closed.await(5, TimeUnit.SECONDS))
    assertEquals(0L, body.bytesRead.get())
    assertEquals(listOf(IMAGE), requests.toList())
  }

  @Test
  fun `disabling images while metadata is pending prevents the thumbnail request`() {
    includeImages.set(true)
    val metadataRequested = CountDownLatch(1)
    val releaseMetadata = CountDownLatch(1)
    val pending = begin { request ->
      metadataRequested.countDown()
      check(releaseMetadata.await(5, TimeUnit.SECONDS))
      response(request, PAGE_WITH_IMAGE)
    }

    try {
      assertTrue(metadataRequested.await(5, TimeUnit.SECONDS))
      includeImages.set(false)
    } finally {
      releaseMetadata.countDown()
    }

    assertFalse(pending.await().preview!!.thumbnail.isPresent)
    assertEquals(listOf(PAGE), requests.toList())
  }

  @Test
  fun `disabling images during the thumbnail request skips decoding and returns text`() {
    includeImages.set(true)
    val imageRequested = CountDownLatch(1)
    val releaseImage = CountDownLatch(1)
    val body = TrackedBody("image/png")
    val pending = begin { request ->
      if (request.url.toString() == PAGE) {
        response(request, PAGE_WITH_IMAGE)
      } else {
        imageRequested.countDown()
        check(releaseImage.await(5, TimeUnit.SECONDS))
        response(request, body)
      }
    }

    try {
      assertTrue(imageRequested.await(5, TimeUnit.SECONDS))
      includeImages.set(false)
    } finally {
      releaseImage.countDown()
    }

    val outcome = pending.await()
    assertNull(outcome.error)
    assertEquals("Article", outcome.preview!!.title)
    assertFalse(outcome.preview.thumbnail.isPresent)
    assertTrue(body.closed.await(5, TimeUnit.SECONDS))
    assertEquals(0L, body.bytesRead.get())
    assertEquals(listOf(PAGE, IMAGE), requests.toList())
  }

  @Test
  fun `canceling a pending metadata request cannot start a thumbnail or publish a preview`() {
    includeImages.set(true)
    val metadataRequested = CountDownLatch(1)
    val releaseMetadata = CountDownLatch(1)
    val pending = begin { request ->
      metadataRequested.countDown()
      check(releaseMetadata.await(5, TimeUnit.SECONDS))
      response(request, PAGE_WITH_IMAGE)
    }

    try {
      assertTrue(metadataRequested.await(5, TimeUnit.SECONDS))
      pending.controller.cancel()
    } finally {
      releaseMetadata.countDown()
    }

    pending.client.dispatcher.executorService.shutdown()
    assertTrue(pending.client.dispatcher.executorService.awaitTermination(5, TimeUnit.SECONDS))
    assertFalse(pending.outcome.isDone)
    assertEquals(listOf(PAGE), requests.toList())
  }

  private fun begin(url: String = PAGE, respond: (Request) -> Response): Pending {
    val client = OkHttpClient.Builder()
      .addInterceptor { chain ->
        requests += chain.request().url.toString()
        respond(chain.request())
      }
      .build()
    clients += client
    val outcome = CompletableFuture<Outcome>()
    val controller = LinkPreviewRepository(client).getLinkPreview(
      ApplicationProvider.getApplicationContext(),
      url,
      object : LinkPreviewRepository.Callback {
        override fun onSuccess(linkPreview: LinkPreview) {
          outcome.complete(Outcome(preview = linkPreview))
        }

        override fun onError(error: LinkPreviewRepository.Error) {
          outcome.complete(Outcome(error = error))
        }
      }
    )
    return Pending(requireNotNull(controller), outcome, client)
  }

  private fun response(request: Request, text: String, code: Int = 200): Response {
    return response(request, text.toResponseBody("text/html".toMediaType()), code)
  }

  private fun response(request: Request, body: ResponseBody, code: Int = 200): Response {
    return Response.Builder()
      .request(request)
      .protocol(Protocol.HTTP_1_1)
      .code(code)
      .message(if (code == 200) "OK" else "Not Found")
      .header("Content-Type", body.contentType()?.toString() ?: "application/octet-stream")
      .body(body)
      .build()
  }

  private data class Outcome(val preview: LinkPreview? = null, val error: LinkPreviewRepository.Error? = null)

  private data class Pending(val controller: RequestController, val outcome: CompletableFuture<Outcome>, val client: OkHttpClient) {
    fun await(): Outcome = outcome.get(5, TimeUnit.SECONDS)
  }

  private class TrackedBody(private val type: String) : ResponseBody() {
    val closed = CountDownLatch(1)
    val bytesRead = AtomicLong()
    private val trackedSource = object : ForwardingSource(Buffer().writeUtf8("image bytes must not be decoded")) {
      override fun read(sink: Buffer, byteCount: Long): Long {
        val read = super.read(sink, byteCount)
        if (read > 0) bytesRead.addAndGet(read)
        return read
      }

      override fun close() {
        super.close()
        closed.countDown()
      }
    }.buffer()

    override fun contentType() = type.toMediaType()
    override fun contentLength(): Long = -1
    override fun source(): BufferedSource = trackedSource
  }
}
