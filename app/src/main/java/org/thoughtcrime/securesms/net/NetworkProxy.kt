/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.net

import androidx.annotation.WorkerThread
import org.signal.core.util.net.resolveSystemProxy
import org.signal.libsignal.net.Network
import org.signal.network.config.ProxyConfig
import org.signal.network.config.ProxyConfig.ProxySource
import org.thoughtcrime.securesms.BuildConfig
import java.net.Proxy
import org.signal.network.config.configureProxy as configureCoreProxy

/**
 * Resolves the appropriate proxy configuration or system defaults.
 * Falls back to direct connection if no valid proxy is found.
 *
 * @return The resolved proxy configuration
 */
@WorkerThread
fun resolveProxyConfig(): ProxyConfig {
  val userProxy = Networking.proxy
  if (userProxy == Proxy.NO_PROXY) {
    val systemProxy = resolveSystemProxy(targetUrl = BuildConfig.SIGNAL_URL)
    return systemProxy?.let { ProxyConfig.fromProxy(it, ProxySource.SYSTEM) }
      ?: ProxyConfig.Direct
  }
  return checkNotNull(ProxyConfig.fromProxy(userProxy, ProxySource.USER))
}

/** Applies the shared proxy policy, including fail-closed handling of user-configured proxies. */
fun Network.configureProxy(config: ProxyConfig) {
  configureCoreProxy(config)
}
