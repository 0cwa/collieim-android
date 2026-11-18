package im.molly.unifiedpush

import android.app.Application
import im.molly.unifiedpush.model.MollySocketDevice
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.models.ServiceId
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.testutil.MockSignalStoreRule
import org.thoughtcrime.securesms.testutil.SystemOutLogger
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class MollySocketRedirectTest {
  @get:Rule val signalStore = MockSignalStoreRule()

  @Before fun setUp() {
    Log.initialize(SystemOutLogger())
    mockkObject(AppDependencies)
    mockkStatic(AppDependencies::class)
    every { AppDependencies.okHttpClient } returns OkHttpClient()
    val aci = mockk<ServiceId.ACI>()
    every { aci.toString() } returns "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    every { signalStore.account.requireAci() } returns aci
  }

  @After fun tearDown() {
    unmockkStatic(AppDependencies::class)
    unmockkObject(AppDependencies)
  }

  @Test fun `credential posts never follow cross host 307 or 308 redirects`() {
    for (code in listOf(307, 308)) {
      ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { source ->
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { target ->
          val failure = AtomicReference<Throwable?>()
          val server = thread {
            try {
              source.accept().use { socket ->
                val input = socket.getInputStream().bufferedReader()
                var length = 0
                while (true) {
                  val line = input.readLine() ?: error("incomplete request")
                  if (line.isEmpty()) break
                  if (line.startsWith("Content-Length:", ignoreCase = true)) length = line.substringAfter(':').trim().toInt()
                }
                repeat(length) { check(input.read() >= 0) }
                val reply = "HTTP/1.1 $code Redirect\r\nLocation: http://localhost:${target.localPort}/capture\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                socket.getOutputStream().write(reply.toByteArray())
              }
            } catch (t: Throwable) { failure.set(t) }
          }
          val sourceUrl = "http://127.0.0.1:${source.localPort}/relay".toHttpUrl()
          val result = MollySocketRepository.registerDeviceOnServer(sourceUrl, MollySocketDevice(2, "synthetic-password"), "https://push.example.invalid")
          assertNull(result)
          server.join(5000)
          assertEquals(false, server.isAlive)
          assertNull(failure.get())
          target.soTimeout = 1000
          val redirected = try { target.accept().use { true } } catch (_: SocketTimeoutException) { false }
          assertEquals(false, redirected)
        }
      }
    }
  }
}
