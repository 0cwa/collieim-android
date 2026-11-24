package org.thoughtcrime.securesms.maps

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.thoughtcrime.securesms.net.Networking
import org.thoughtcrime.securesms.net.SocksProxy
import java.io.DataInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 35)
class MapLibreProxyRouteProofTest {

  @Test
  fun realMapClientUsesConfiguredSocksForDnsAndClosesTransportOnRouteChange() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val previousEnabled = Networking.isEnabled
    val previousProxy = Networking.socksProxy
    val worker = Executors.newSingleThreadExecutor()
    try {
      ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { listener ->
        listener.soTimeout = 10_000
        Networking.socksProxy = SocksProxy("127.0.0.1", listener.localPort)
        Networking.isEnabled = true

        val request = Request.Builder()
          .url("https://tile.openstreetmap.org/19/0/0.png")
          .header("Cache-Control", "no-store")
          .build()
        val call = MapResourceClient.create(context).newCall(request)
        val terminal = worker.submit<IOException?> {
          try {
            call.execute().close()
            null
          } catch (failure: IOException) {
            failure
          }
        }

        listener.accept().use { socket ->
          socket.soTimeout = 10_000
          val input = DataInputStream(socket.getInputStream())
          val output = socket.getOutputStream()
          assertEquals(5, input.readUnsignedByte())
          val methods = ByteArray(input.readUnsignedByte()).also(input::readFully)
          assertTrue(methods.contains(0.toByte()))
          output.write(byteArrayOf(5, 0))
          output.flush()

          assertEquals(5, input.readUnsignedByte())
          assertEquals(1, input.readUnsignedByte())
          assertEquals(0, input.readUnsignedByte())
          assertEquals(1, input.readUnsignedByte())
          val address = InetAddress.getByAddress(ByteArray(4).also(input::readFully)).hostAddress
          assertTrue(address in setOf("1.1.1.1", "9.9.9.9"))
          assertEquals(443, input.readUnsignedShort())

          // The proxy never acknowledges CONNECT or opens an upstream socket.
          Networking.isEnabled = false
          assertEquals(-1, input.read())
        }

        assertTrue(call.isCanceled())
        assertTrue(terminal.get(10, TimeUnit.SECONDS) is IOException)
      }
    } finally {
      Networking.isEnabled = false
      Networking.socksProxy = previousProxy
      Networking.isEnabled = previousEnabled
      worker.shutdownNow()
    }
  }
}
