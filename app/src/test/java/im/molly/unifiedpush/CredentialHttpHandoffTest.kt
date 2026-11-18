package im.molly.unifiedpush

import android.app.Application
import im.molly.unifiedpush.lifecycle.UnifiedPushLifecycle
import okhttp3.Dispatcher
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.testutil.SystemOutLogger
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class CredentialHttpHandoffTest {
  @Test
  fun `interruption keeps the call registered until the dispatcher reports cancellation`() {
    Log.initialize(SystemOutLogger())
    val executor = Executors.newSingleThreadExecutor()
    val occupied = CountDownLatch(1)
    val release = CountDownLatch(1)
    executor.execute { occupied.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
    assertTrue(occupied.await(5, TimeUnit.SECONDS))
    val client = OkHttpClient.Builder().dispatcher(Dispatcher(executor)).build()
    val handoff = UnifiedPushLifecycle.CredentialSendHandoff()
    handoff.beginAccountChange(1)
    handoff.completeAccountChange(1, true)
    val checked = CountDownLatch(1)
    val done = CountDownLatch(1)
    val interrupted = AtomicBoolean(false)
    val failure = AtomicReference<Throwable?>()
    val request = Request.Builder().url("http://127.0.0.1:1/relay".toHttpUrl()).build()
    val sender = thread {
      try {
        failure.set(runCatching { MollySocketRepository.executeBoundCall(client, request, handoff, 1, { checked.countDown(); true }) { it.code } }.exceptionOrNull())
        interrupted.set(Thread.currentThread().isInterrupted)
      } finally { done.countDown() }
    }
    try {
      assertTrue(checked.await(5, TimeUnit.SECONDS))
      sender.interrupt()
      handoff.beginAccountChange(2)
      assertFalse(done.await(100, TimeUnit.MILLISECONDS))
      assertThrows(IllegalStateException::class.java) { handoff.completeAccountChange(2, true) }
    } finally {
      release.countDown()
      sender.join(5000)
      executor.shutdownNow()
      client.connectionPool.evictAll()
    }
    assertFalse(sender.isAlive)
    assertTrue(failure.get() is IOException)
    assertTrue(interrupted.get())
    handoff.completeAccountChange(2, true)
  }

  @Test
  fun `current account can complete an actual handoff request`() {
    Log.initialize(SystemOutLogger())
    val client = OkHttpClient()
    val handoff = UnifiedPushLifecycle.CredentialSendHandoff()
    handoff.beginAccountChange(1)
    handoff.completeAccountChange(1, true)
    assertTrue(handoff.beginAttempt(1, 1))
    try {
      ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
        server.soTimeout = 5000
        val failure = AtomicReference<Throwable?>()
        val receiver = thread {
          try {
            server.accept().use { socket ->
              val input = socket.getInputStream().bufferedReader()
              var length = 0
              while (true) {
                val line = input.readLine() ?: error("incomplete request")
                if (line.isEmpty()) break
                if (line.startsWith("Content-Length:", ignoreCase = true)) length = line.substringAfter(':').trim().toInt()
              }
              repeat(length) { check(input.read() >= 0) }
              socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            }
          } catch (t: Throwable) { failure.set(t) }
        }
        val request = Request.Builder()
          .url("http://127.0.0.1:${server.localPort}/relay".toHttpUrl())
          .post("synthetic-password".toRequestBody("text/plain".toMediaType()))
          .build()
        assertEquals(200, MollySocketRepository.executeBoundCall(client, request, handoff, 1, { true }) { it.code })
        receiver.join(5000)
        assertFalse(receiver.isAlive)
        assertNull(failure.get())
      }
    } finally {
      client.dispatcher.executorService.shutdownNow()
      client.connectionPool.evictAll()
    }
  }

  @Test
  fun `rejection cancels a queued real call before any body reaches the server`() {
    Log.initialize(SystemOutLogger())
    val executor = Executors.newSingleThreadExecutor()
    val occupied = CountDownLatch(1)
    val release = CountDownLatch(1)
    executor.execute { occupied.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
    assertTrue(occupied.await(5, TimeUnit.SECONDS))
    val client = OkHttpClient.Builder().dispatcher(Dispatcher(executor)).build()
    val handoff = UnifiedPushLifecycle.CredentialSendHandoff()
    handoff.beginAccountChange(1)
    handoff.completeAccountChange(1, true)
    assertTrue(handoff.beginAttempt(1, 1))
    try {
      ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
        val request = Request.Builder()
          .url("http://127.0.0.1:${server.localPort}/relay".toHttpUrl())
          .post("synthetic-password".toRequestBody("text/plain".toMediaType()))
          .build()
        val checked = CountDownLatch(1)
        val outcome = AtomicReference<Throwable?>()
        val sender = thread {
          outcome.set(runCatching {
            MollySocketRepository.executeBoundCall(client, request, handoff, 1, { checked.countDown(); true }) { it.code }
          }.exceptionOrNull())
        }
        try {
          assertTrue(checked.await(5, TimeUnit.SECONDS))
          assertTrue(handoff.reject(1, 1))
          handoff.beginAccountChange(2)
          assertThrows(IllegalStateException::class.java) { handoff.completeAccountChange(2, true) }
        } finally {
          release.countDown()
          sender.join(5000)
        }
        assertFalse(sender.isAlive)
        assertTrue(outcome.get() is IOException)
        handoff.completeAccountChange(2, true)
        server.soTimeout = 1000
        val received = try { server.accept().use { true } } catch (_: SocketTimeoutException) { false }
        assertFalse(received)
      }
    } finally {
      release.countDown()
      executor.shutdownNow()
      client.connectionPool.evictAll()
    }
  }
}
