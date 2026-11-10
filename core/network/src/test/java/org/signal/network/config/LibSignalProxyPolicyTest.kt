/*
 * Copyright 2026 Molly Project
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.network.config

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test
import org.signal.libsignal.net.Network
import java.io.IOException

class LibSignalProxyPolicyTest {

  @Test
  fun `a rejected user SOCKS proxy fails closed without a direct fallback`() {
    val network = mockk<Network>(relaxed = true)
    every { network.setProxy("socks5h", "localhost", 9050, null, null) } throws IOException("Invalid proxy")

    network.configureProxy(ProxyConfig.ProxyAddress(ProxyConfig.ProxyScheme.SOCKS_REMOTE_DNS, "localhost", 9050, ProxyConfig.ProxySource.USER))

    verify(exactly = 1) { network.setInvalidProxy() }
    verify(exactly = 0) { network.clearProxy() }
  }

  @Test
  fun `an unavailable optional system proxy retains the existing direct fallback`() {
    val network = mockk<Network>(relaxed = true)
    every { network.setProxy("http", "proxy.example.com", 8080, null, null) } throws IOException("Invalid proxy")

    network.configureProxy(ProxyConfig.ProxyAddress(ProxyConfig.ProxyScheme.HTTP, "proxy.example.com", 8080))

    verify(exactly = 1) { network.clearProxy() }
    verify(exactly = 0) { network.setInvalidProxy() }
  }
}
