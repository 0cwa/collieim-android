/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.util

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.util.logging.Log
import org.signal.remoteconfig.RemoteConfig
import org.signal.storage.keyvalue.PlainTextKeyValueStore
import org.thoughtcrime.securesms.BuildConfig
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.testutil.LogRecorder
import org.thoughtcrime.securesms.testutil.SignalStoreRule

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE)
class RemoteConfigOverrideTest {

  companion object {
    private const val INTERNAL_USER = "android.internalUser"
    private const val BOOLEAN_FLAG = "android.contactSharingV2.2"
    private const val INT_FLAG = "global.pinnedChatLimit"
  }

  private val preferences = object : ExternalResource() {
    override fun before() {
      val context = ApplicationProvider.getApplicationContext<Application>()
      val sharedPreferences = context.getSharedPreferences("remote-config-test", Context.MODE_PRIVATE)
      sharedPreferences.edit().clear().commit()
      mockkStatic(SecurePreferenceManager::class)
      every { SecurePreferenceManager.getSecurePreferences(any()) } returns sharedPreferences
      PlainTextKeyValueStore.init(sharedPreferences, BuildConfig.VERSION_CODE)
    }

    override fun after() {
      unmockkStatic(SecurePreferenceManager::class)
    }
  }

  @get:Rule
  val rules: RuleChain = RuleChain.outerRule(preferences).around(SignalStoreRule())

  @Before
  fun setup() {
    Log.initialize(LogRecorder())
    RemoteConfig.underTest = true
    reset()
  }

  @After
  fun tearDown() {
    reset()
    RemoteConfig.underTest = false
  }

  @Test
  fun `override wins over the value from the service`() {
    setServiceValues(INTERNAL_USER to true, BOOLEAN_FLAG to false)
    SignalStore.remoteConfig.overrides = mapOf(BOOLEAN_FLAG to "true")

    RemoteConfig.init()

    assertThat(RemoteConfig.contactSharingV2).isTrue()
  }

  @Test
  fun `override string is coerced by the config transformer`() {
    setServiceValues(INTERNAL_USER to true, INT_FLAG to 4)
    SignalStore.remoteConfig.overrides = mapOf(INT_FLAG to "9")

    RemoteConfig.init()

    assertThat(RemoteConfig.pinnedChatLimit).isEqualTo(9)
  }

  @Test
  fun `overrides are ignored for non-internal users`() {
    setServiceValues(INTERNAL_USER to false, BOOLEAN_FLAG to false)
    SignalStore.remoteConfig.overrides = mapOf(BOOLEAN_FLAG to "true")

    RemoteConfig.init()

    assertThat(RemoteConfig.overrides).isEmpty()
    assertThat(RemoteConfig.contactSharingV2).isFalse()
  }

  @Test
  fun `internalUser cannot be overridden`() {
    setServiceValues(INTERNAL_USER to true)
    SignalStore.remoteConfig.overrides = mapOf(INTERNAL_USER to "false")

    RemoteConfig.init()

    assertThat(RemoteConfig.overrides).isEmpty()
    assertThat(RemoteConfig.internalUser).isTrue()
  }

  @Test
  fun `overrides for unknown keys are dropped`() {
    setServiceValues(INTERNAL_USER to true)
    SignalStore.remoteConfig.overrides = mapOf("android.notARealFlag" to "true")

    RemoteConfig.init()

    assertThat(RemoteConfig.overrides).isEmpty()
  }

  @Test
  fun `setting overrides applies immediately`() {
    setServiceValues(INTERNAL_USER to true, BOOLEAN_FLAG to false)
    RemoteConfig.init()

    RemoteConfig.overrides = mapOf(BOOLEAN_FLAG to "true")
    assertThat(RemoteConfig.contactSharingV2).isTrue()

    RemoteConfig.overrides = emptyMap()
    assertThat(RemoteConfig.contactSharingV2).isFalse()
  }

  @Test
  fun `overridableConfigs excludes internalUser`() {
    assertThat(RemoteConfig.overridableConfigs.containsKey(INTERNAL_USER)).isFalse()
    assertThat(RemoteConfig.overridableConfigs.containsKey(BOOLEAN_FLAG)).isTrue()
  }

  @Test
  fun `persisted overrides round-trip through the store`() {
    val overrides = mapOf(BOOLEAN_FLAG to "true", INT_FLAG to "9")

    SignalStore.remoteConfig.overrides = overrides

    assertThat(SignalStore.remoteConfig.overrides).isEqualTo(overrides)
  }

  private fun setServiceValues(vararg values: Pair<String, Any>) {
    val json = JSONObject()
    values.forEach { (key, value) -> json.put(key, value) }
    SignalStore.remoteConfig.currentConfig = json.toString()
  }

  private fun reset() {
    RemoteConfig.overrides = emptyMap()
    RemoteConfig.REMOTE_VALUES.clear()
    RemoteConfig.initialized = false
  }
}
