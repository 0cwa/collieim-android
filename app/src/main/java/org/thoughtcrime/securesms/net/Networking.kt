package org.thoughtcrime.securesms.net

import okhttp3.Call
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Protocol
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.Socket
import java.net.SocketAddress
import java.net.URI
import java.net.UnknownHostException
import java.util.Collections
import java.util.WeakHashMap
import java.util.function.BooleanSupplier
import java.util.function.Consumer
import javax.net.SocketFactory

object Networking {

  private val routeLock = Any()
  private val routeCalls = mutableSetOf<Call>()

  @Volatile
  internal var routing = Routing(false, null)
    private set

  @JvmStatic
  var isEnabled: Boolean
    get() = routing.enabled
    set(value) = updateRoute { current ->
      if (current.enabled == value) current else Routing(value, current.socksProxy)
    }

  @JvmStatic
  val socketFactory: SocketFactory = ProxySocketFactory {
    ensureNetworkEnabled()
    proxy.also { validateProxyAddress(it) }
  }

  @JvmStatic
  var socksProxy: SocksProxy?
    get() = routing.socksProxy
    set(value) = updateRoute { current ->
      if (current.socksProxy == value) current else Routing(current.enabled, value)
    }

  /** Start a non-blocking platform lookup before a route change can supersede its direct route. */
  @JvmStatic
  fun dispatchPlatformGeocoding(start: Consumer<BooleanSupplier>): Boolean = synchronized(routeLock) {
    val current = routing
    if (!current.enabled || current.socksProxy != null) return@synchronized false
    start.accept(BooleanSupplier { routing === current })
    true
  }

  /**
   * A SOCKS [Proxy] instance pointing to the "discard" service on localhost (port 9),
   * effectively a black hole for outgoing network traffic.
   */
  @JvmStatic
  val DUMMY_PROXY = Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved("localhost", 9))

  /**
   * Retrieves the current proxy configuration.
   *
   * It checks if a proxy is configured and attempts to create a [Proxy] instance
   * using the specified socket address. If the address is invalid or unavailable,
   * it defaults to [DUMMY_PROXY] to prevent IP leaks.
   *
   * If no proxy is configured, it returns [Proxy.NO_PROXY] to indicate direct
   * connection without a proxy.
   */
  @JvmStatic
  val proxy: Proxy
    get() = routing.proxy

  @JvmStatic
  val proxySelectorForSocks = proxySelector { socksProxy != null }

  @JvmStatic
  val dns: Dns = DnsProxyAware(socketFactory, proxySelectorForSocks) {
    ensureNetworkEnabled()
    socksProxy != null
  }

  private fun proxySelector(hasSocksProxy: () -> Boolean): ProxySelector = object : ProxySelector() {
    private val systemDefault = getDefault()

    override fun select(uri: URI?): List<Proxy> {
      return if (hasSocksProxy()) {
        // Do not chain to system proxy if SOCKS proxy is selected.
        listOf(Proxy.NO_PROXY)
      } else {
        systemDefault.select(uri)
      }
    }

    override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) =
      systemDefault.connectFailed(uri, sa, ioe)
  }

  private fun updateRoute(update: (Routing) -> Routing) {
    synchronized(routeLock) {
      val replacement = update(routing)
      if (replacement === routing) return
      // A concurrent Call.cancel() can return before its socket is closed. Close sockets ourselves before publishing.
      routing.closeExchangeSockets()
      routeCalls.forEach(Call::cancel)
      routeCalls.clear()
      routing = replacement
    }
  }

  /**
   * One published configuration per network enablement or proxy change. Identity, rather
   * than proxy equality, distinguishes a superseded route even after switching back.
   */
  internal class Routing(val enabled: Boolean, val socksProxy: SocksProxy?) {
    private val exchangeSockets = Collections.newSetFromMap(WeakHashMap<Socket, Boolean>())

    val proxy: Proxy = socksProxy?.let { it.makeProxy() ?: DUMMY_PROXY } ?: Proxy.NO_PROXY
    val socketFactory: SocketFactory by lazy {
      ProxySocketFactory {
        ensureCurrent()
        proxy
      }
    }
    val proxySelector: ProxySelector by lazy {
      val delegate = Networking.proxySelector { socksProxy != null }
      object : ProxySelector() {
        override fun select(uri: URI?): List<Proxy> {
          ensureCurrent()
          val selected = resolveHttpProxyAddresses(delegate.select(uri), Dns.SYSTEM)
          ensureCurrent()
          return selected
        }

        override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) =
          delegate.connectFailed(uri, sa, ioe)
      }
    }
    val dns: Dns by lazy {
      // System DNS would bypass the pinned call machinery. Both map routes use bound DoH.
      DnsProxyAware(socketFactory, proxySelector, ::configureDoh) {
        ensureCurrent()
        true
      }
    }

    fun configureDoh(builder: OkHttpClient.Builder) {
      builder.protocols(listOf(Protocol.HTTP_1_1)).addInterceptor { chain ->
        val call = chain.call()
        track(call)
        try {
          chain.proceed(chain.request())
        } finally {
          untrack(call)
        }
      }.addNetworkInterceptor { chain ->
        trackExchange(chain.connection()?.socket())
        chain.proceed(chain.request())
      }
    }

    fun trackExchange(socket: Socket?) = synchronized(routeLock) {
      ensureCurrent()
      exchangeSockets.add(checkNotNull(socket))
    }

    fun closeExchangeSockets() {
      check(Thread.holdsLock(routeLock))
      for (socket in exchangeSockets) {
        try {
          socket.close()
        } catch (failure: IOException) {
          if (!socket.isClosed) throw failure
        }
      }
      exchangeSockets.clear()
    }

    fun ensureCurrent() {
      if (this !== routing) throw IOException("Map network route changed")
      if (!enabled) throw IOException("Map networking is disabled")
      if (proxy == DUMMY_PROXY) throw IOException("Map proxy is unavailable")
    }

    fun track(call: Call) = synchronized(routeLock) {
      ensureCurrent()
      routeCalls.add(call)
    }

    fun untrack(call: Call) = synchronized(routeLock) {
      routeCalls.remove(call)
    }
  }

  /** Resolve configured HTTP proxy names locally without sending them to the map DoH providers. */
  internal fun resolveHttpProxyAddresses(proxies: List<Proxy>, resolver: Dns): List<Proxy> {
    var failure: UnknownHostException? = null
    val resolved = proxies.flatMap { proxy ->
      val address = proxy.address() as? InetSocketAddress
      if (proxy.type() != Proxy.Type.HTTP || address == null || !address.isUnresolved) {
        listOf(proxy)
      } else {
        try {
          val addresses = resolver.lookup(address.hostString)
          if (addresses.isEmpty()) throw UnknownHostException("System HTTP proxy hostname has no addresses")
          addresses.map { Proxy(Proxy.Type.HTTP, InetSocketAddress(it, address.port)) }
        } catch (error: UnknownHostException) {
          failure = error
          emptyList()
        }
      }
    }
    if (resolved.isEmpty()) failure?.let { throw it }
    return resolved
  }

  private fun ensureNetworkEnabled() {
    if (!isEnabled) throw IOException("Network is disabled")
  }

  private fun validateProxyAddress(proxy: Proxy) {
    if (proxy == DUMMY_PROXY) throw IOException("Proxy socket address not available yet")
  }

  private class DnsProxyAware(
    socketFactory: SocketFactory,
    proxySelectorForSocks: ProxySelector,
    configure: Consumer<OkHttpClient.Builder> = Consumer {},
    private val useDohOnly: () -> Boolean
  ) : Dns {
    private val cloudflare: Dns = DohClient("https://1.1.1.1/dns-query", socketFactory, proxySelectorForSocks, configure)
    private val quad9: Dns = DohClient("https://9.9.9.9/dns-query", socketFactory, proxySelectorForSocks, configure)

    private val systemDnsResolver: Dns = SequentialDns(Dns.SYSTEM, cloudflare, quad9)
    private val dohDnsResolver: Dns = SequentialDns(cloudflare, quad9)

    override fun lookup(hostname: String): List<InetAddress> {
      return if (!useDohOnly()) {
        systemDnsResolver.lookup(hostname)
      } else {
        dohDnsResolver.lookup(hostname)
      }
    }
  }
}
