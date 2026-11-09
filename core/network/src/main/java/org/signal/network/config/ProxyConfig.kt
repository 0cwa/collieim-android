/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.network.config

import org.signal.core.util.logging.Log
import org.signal.libsignal.net.Network
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.URI

sealed interface ProxyConfig {
  data object Direct : ProxyConfig

  data class ProxyAddress(
    val scheme: ProxyScheme,
    val host: String,
    val port: Int,
    val source: ProxySource = ProxySource.SYSTEM
  ) : ProxyConfig

  /** Supported proxy schemes by [Network]. */
  enum class ProxyScheme(val value: String) {
    TLS(Network.SIGNAL_TLS_PROXY_SCHEME),
    SOCKS("socks5"),
    SOCKS_REMOTE_DNS("socks5h"),
    HTTP("http")
  }

  enum class ProxySource {
    USER, SYSTEM
  }

  companion object {
    private val TAG = Log.tag(ProxyConfig::class)

    @JvmStatic
    @JvmOverloads
    fun resolve(
      configuration: SignalServiceConfiguration,
      targetUrl: String,
      proxySelector: ProxySelector? = ProxySelector.getDefault(),
      userProxy: Proxy? = null
    ): ProxyConfig {
      return when {
        userProxy != null && userProxy != Proxy.NO_PROXY -> checkNotNull(fromProxy(userProxy, ProxySource.USER))
        configuration.censored -> Direct
        else -> resolveSystemProxy(targetUrl, proxySelector)?.let { fromSystemProxy(it) } ?: Direct
      }
    }

    /**
     * Resolves the system-configured [Proxy] for the given URL using [ProxySelector].
     *
     * @return null if no proxy is configured.
     */
    private fun resolveSystemProxy(targetUrl: String, proxySelector: ProxySelector?): Proxy? {
      return try {
        proxySelector?.select(URI.create(targetUrl))?.firstOrNull()
      } catch (e: Exception) {
        Log.w(TAG, "Failed to resolve the system proxy, falling back to direct.", e)
        null
      }
    }

    @JvmStatic
    fun fromProxy(proxy: Proxy, source: ProxySource): ProxyConfig? {
      val scheme = when (proxy.type()) {
        Proxy.Type.HTTP -> ProxyScheme.HTTP
        Proxy.Type.SOCKS -> if (source == ProxySource.USER) ProxyScheme.SOCKS_REMOTE_DNS else ProxyScheme.SOCKS
        Proxy.Type.DIRECT -> return null
      }

      val socketAddress = proxy.address() as? InetSocketAddress ?: return null

      return ProxyAddress(scheme, socketAddress.hostString, socketAddress.port, source)
    }

    private fun fromSystemProxy(proxy: Proxy): ProxyConfig? {
      // The generic system resolver skips Android's PAC bridge. Molly's explicit proxy resolver uses fromProxy
      // directly so an existing local SOCKS/HTTP configuration continues to work.
      if ((proxy.address() as? InetSocketAddress)?.isLoopback() == true) {
        return null
      }
      return fromProxy(proxy, ProxySource.SYSTEM)
    }

    /** [ProxySelector] returns unresolved addresses, so [InetSocketAddress.getAddress] is normally null and only the host string is available. */
    private fun InetSocketAddress.isLoopback(): Boolean {
      address?.let { return it.isLoopbackAddress }

      val host = hostString.removeSurrounding("[", "]")
      return host.equals("localhost", ignoreCase = true) || host.startsWith("127.") || host == "::1" || host == "0:0:0:0:0:0:0:1"
    }
  }
}
