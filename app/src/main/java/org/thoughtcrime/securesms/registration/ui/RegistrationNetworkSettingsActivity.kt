/*
 * Copyright 2026 Molly Project
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.registration.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.FrameLayout
import org.thoughtcrime.securesms.PassphraseRequiredActivity
import org.thoughtcrime.securesms.preferences.NetworkPreferenceFragment
import org.thoughtcrime.securesms.util.DynamicNoActionBarTheme

/** Lets an unregistered user configure Molly's SOCKS or Orbot proxy before connecting. */
class RegistrationNetworkSettingsActivity : PassphraseRequiredActivity() {

  private val dynamicTheme = DynamicNoActionBarTheme()

  override fun onPreCreate() {
    super.onPreCreate()
    dynamicTheme.onCreate(this)
  }

  override fun isRegistrationFlow(): Boolean = true

  override fun onCreate(savedInstanceState: Bundle?, ready: Boolean) {
    super.onCreate(savedInstanceState, ready)
    setContentView(FrameLayout(this))
    if (savedInstanceState == null) {
      supportFragmentManager.beginTransaction()
        .replace(android.R.id.content, NetworkPreferenceFragment())
        .commit()
    }
  }

  override fun onResume() {
    super.onResume()
    dynamicTheme.onResume(this)
  }

  companion object {
    @JvmStatic
    fun createIntent(context: Context): Intent = Intent(context, RegistrationNetworkSettingsActivity::class.java)
  }
}
