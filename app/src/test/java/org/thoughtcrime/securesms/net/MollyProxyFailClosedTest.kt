package org.thoughtcrime.securesms.net

import android.app.Application
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class MollyProxyFailClosedTest {
  private val previousEnabled = Networking.isEnabled
  private val previousProxy = Networking.socksProxy

  @After
  fun restoreNetworkState() {
    Networking.isEnabled = previousEnabled
    Networking.socksProxy = previousProxy
  }

  @Test
  fun `unavailable SOCKS proxy refuses sockets instead of connecting directly`() {
    Networking.isEnabled = true
    Networking.socksProxy = SocksProxy("127.0.0.1", SocksProxy.INVALID_PORT)
    assertEquals(Networking.DUMMY_PROXY, Networking.proxy)
    assertThrows(IOException::class.java) { Networking.socketFactory.createSocket() }
  }

  @Test
  fun `disabled networking refuses sockets even with a valid proxy`() {
    Networking.isEnabled = false
    Networking.socksProxy = SocksProxy("proxy.example", 9050)
    assertThrows(IOException::class.java) { Networking.socketFactory.createSocket() }
  }

  @Test
  fun `configured SOCKS host is passed unresolved to the proxy`() {
    Networking.isEnabled = true
    Networking.socksProxy = SocksProxy("proxy.example", 9050)
    val proxy = Networking.proxy
    assertEquals(Proxy.Type.SOCKS, proxy.type())
    val address = proxy.address() as InetSocketAddress
    assertEquals("proxy.example", address.hostString)
    assertEquals(9050, address.port)
    assertTrue(address.isUnresolved)
  }
}
