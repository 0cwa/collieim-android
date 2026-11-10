/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.components.settings.app.privacy

import android.content.Context
import androidx.lifecycle.Lifecycle
import org.signal.core.util.concurrent.SimpleTask
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.net.NetworkManager
import org.thoughtcrime.securesms.net.ProxyType
import org.thoughtcrime.securesms.util.SignalProxyUtil
import org.thoughtcrime.securesms.util.TextSecurePreferences
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.function.Consumer

/** Shared proxy apply and connection-check behavior for Privacy and the standalone Network screen. */
class NetworkSettingsController(
  private val networkManager: NetworkManager = AppDependencies.networkManager,
  private val resetNetwork: () -> Unit = { AppDependencies.resetNetwork(true) },
  private val connectionTest: (Long) -> Boolean = SignalProxyUtil::testWebsocketConnection,
  private val runTask: (Lifecycle, () -> Boolean, (Boolean) -> Unit) -> Unit = { lifecycle, task, onComplete ->
    SimpleTask.run(lifecycle, { task() }, { result -> onComplete(result) })
  }
) {

  companion object {
    private val CONNECTION_TEST_TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(10)
  }

  private val checkGeneration = AtomicLong()

  fun applyProxyChanges(alwaysRestart: Boolean) {
    val changed = networkManager.applyProxyConfig()
    if (changed || alwaysRestart) {
      networkManager.setNetworkEnabled(true)
      resetNetwork()
    }
  }

  fun invalidateConnectionCheck() {
    checkGeneration.incrementAndGet()
  }

  /** Runs the same ten-second unauthenticated test used by the registration and standalone Network screens. */
  fun startConnectionCheck(
    lifecycle: Lifecycle,
    onChecking: Consumer<Boolean>,
    onResult: Consumer<Boolean>
  ) {
    val generation = checkGeneration.incrementAndGet()
    val isProxyConfigured = networkManager.isProxyEnabled
    onChecking.accept(isProxyConfigured)

    applyProxyChanges(alwaysRestart = true)

    runTask(lifecycle, { connectionTest(CONNECTION_TEST_TIMEOUT_MILLIS) }) { succeeded ->
      if (checkGeneration.get() == generation) {
        onResult.accept(succeeded)
      }
    }
  }

  fun getProxyConfigurationSummary(context: Context): String {
    return when (TextSecurePreferences.getProxyType(context)) {
      ProxyType.NONE -> context.getString(R.string.network_privacy_inline__proxy_not_configured)
      ProxyType.ORBOT -> context.getString(R.string.network_privacy_inline__proxy_orbot_configured)
      ProxyType.SOCKS5 -> context.getString(
        R.string.network_privacy_inline__proxy_socks5_configured,
        TextSecurePreferences.getProxySocksHost(context),
        TextSecurePreferences.getProxySocksPort(context)
      )
    }
  }
}
