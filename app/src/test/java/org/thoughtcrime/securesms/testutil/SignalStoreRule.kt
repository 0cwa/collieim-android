/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.testutil

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.junit.rules.ExternalResource
import org.signal.remoteconfig.RemoteConfig
import org.signal.storage.keyvalue.KeyValueDataSet
import org.signal.storage.keyvalue.KeyValueStore
import org.signal.storage.keyvalue.MockKeyValuePersistentStorage
import org.signal.storage.keyvalue.PlainTextKeyValueStore
import org.thoughtcrime.securesms.BuildConfig
import org.thoughtcrime.securesms.dependencies.AppRemoteConfigDependencies
import org.thoughtcrime.securesms.keyvalue.SignalStore

/**
 * Installs a real [SignalStore] backed by in-memory storage, letting tests arrange and assert on actual
 * stored state rather than stubbing interactions. Use [MockSignalStoreRule] instead when you need
 * interaction-based mocking.
 */
class SignalStoreRule : ExternalResource() {

  private lateinit var preferences: SharedPreferences

  override fun before() {
    val application = ApplicationProvider.getApplicationContext<Application>()
    preferences = application.getSharedPreferences("signal-store-test", Context.MODE_PRIVATE)
    preferences.edit().clear().commit()
    // AccountValues migrates legacy preferences when the in-memory store is created.
    PlainTextKeyValueStore.init(preferences, BuildConfig.VERSION_CODE)
    SignalStore.testInject(SignalStore(application, KeyValueStore(MockKeyValuePersistentStorage.withDataSet(KeyValueDataSet()), DirectExecutor())))
    RemoteConfig.initDependencies(AppRemoteConfigDependencies)
  }

  override fun after() {
    SignalStore.testInject(null)
    preferences.edit().clear().commit()
  }
}
