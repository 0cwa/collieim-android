/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.components.settings.app.privacy

import android.app.Application
import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thoughtcrime.securesms.net.NetworkManager
import org.thoughtcrime.securesms.util.SecurePreferenceManager
import java.util.function.Consumer

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class NetworkSettingsControllerTest {

  private val networkManager = mockk<NetworkManager>(relaxUnitFun = true)
  private val lifecycle = mockk<Lifecycle>()
  private val context: Context = ApplicationProvider.getApplicationContext()
  private val preferences = context.getSharedPreferences("network-settings-controller-test", Context.MODE_PRIVATE)

  @Before
  fun setUp() {
    preferences.edit().clear().commit()
    mockkStatic(SecurePreferenceManager::class)
    every { SecurePreferenceManager.getSecurePreferences(any()) } returns preferences
  }

  @After
  fun tearDown() {
    unmockkStatic(SecurePreferenceManager::class)
  }

  @Test
  fun `connection check applies and restarts the selected network before a ten second test`() {
    every { networkManager.isProxyEnabled } returns true
    every { networkManager.applyProxyConfig() } returns false

    var timeoutMillis = 0L
    var resetCount = 0
    var completeCheck: ((Boolean) -> Unit)? = null
    val checkingProxyStates = mutableListOf<Boolean>()
    val results = mutableListOf<Boolean>()
    val controller = NetworkSettingsController(
      networkManager = networkManager,
      resetNetwork = { resetCount++ },
      connectionTest = { timeout ->
        timeoutMillis = timeout
        true
      },
      runTask = { _, task, onComplete ->
        assertTrue(task())
        completeCheck = onComplete
      }
    )

    controller.startConnectionCheck(lifecycle, Consumer { checkingProxyStates += it }, Consumer { results += it })

    assertEquals(listOf(true), checkingProxyStates)
    assertTrue(results.isEmpty())
    assertEquals(10_000L, timeoutMillis)
    verify { networkManager.applyProxyConfig() }
    verify { networkManager.setNetworkEnabled(true) }
    assertEquals(1, resetCount)

    completeCheck!!(true)
    assertEquals(listOf(true), results)
  }

  @Test
  fun `a result from a cleared check is ignored`() {
    every { networkManager.isProxyEnabled } returns false
    every { networkManager.applyProxyConfig() } returns false

    var completeCheck: ((Boolean) -> Unit)? = null
    val results = mutableListOf<Boolean>()
    val controller = NetworkSettingsController(
      networkManager = networkManager,
      resetNetwork = {},
      runTask = { _, _, onComplete -> completeCheck = onComplete }
    )

    controller.startConnectionCheck(lifecycle, Consumer {}, Consumer { results += it })
    controller.invalidateConnectionCheck()
    completeCheck!!(true)

    assertTrue(results.isEmpty())
  }

  @Test
  fun `unchanged proxy configuration does not restart networking unless requested`() {
    every { networkManager.applyProxyConfig() } returns false
    val controller = NetworkSettingsController(networkManager, resetNetwork = {})

    controller.applyProxyChanges(alwaysRestart = false)

    verify(exactly = 0) { networkManager.setNetworkEnabled(any()) }
  }
}
