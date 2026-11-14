/*
 * Copyright 2026 Collie contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package org.thoughtcrime.securesms.components.settings.app.collie

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.findFragment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import org.signal.core.ui.biometrics.BiometricDeviceAuthentication
import org.signal.core.ui.biometrics.BiometricDeviceLockContract

/** Never transfers an authorization or pending action to a recreated composition. */
@Composable
fun rememberBackupKeyPhoneAuthentication(title: String): ((() -> Unit) -> Unit) {
  val context = LocalContext.current
  val view = LocalView.current
  val fragment = remember(view) { runCatching { view.findFragment<androidx.fragment.app.Fragment>() }.getOrNull() }
  val activity = fragment?.requireActivity() ?: (context as FragmentActivity)
  // Only the device-credential activity is expected to return a restored Activity Result.
  // The biometric pending state is retained across configuration changes, not process death.
  var credentialOutstanding by rememberSaveable { mutableStateOf(false) }
  val attempt: PhoneAuthenticationAttempt = viewModel()
  attempt.initialize(credentialOutstanding)
  fun finish(success: Boolean) = attempt.finish(success)
  val credentialLauncher = rememberLauncherForActivityResult(BiometricDeviceLockContract()) { result ->
    credentialOutstanding = false
    finish(result == BiometricDeviceAuthentication.AUTHENTICATED)
  }
  val prompt = remember(activity, fragment) {
    val callback = object : BiometricPrompt.AuthenticationCallback() {
      override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = finish(true)
      override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = finish(false)
    }
    if (fragment != null) BiometricPrompt(fragment, ContextCompat.getMainExecutor(context), callback)
    else BiometricPrompt(activity, ContextCompat.getMainExecutor(context), callback)
  }
  val authentication = remember(prompt) {
    BiometricDeviceAuthentication(
      BiometricManager.from(context),
      prompt,
      BiometricDeviceAuthentication.createPromptInfoBuilder().setTitle(title).build()
    )
  }
  DisposableEffect(authentication) {
    onDispose {
      attempt.detach()
      authentication.cancelAuthentication()
    }
  }
  return { onAuthenticated ->
    if (attempt.start(onAuthenticated)) {
      try {
        if (!authentication.authenticate(context, true) {
            credentialOutstanding = true
            credentialLauncher.launch(title)
          }
        ) finish(false)
      } catch (_: RuntimeException) {
        credentialOutstanding = false
        finish(false)
      }
    }
  }
}

/** Retained during configuration changes, but never retains the originating screen's action after detachment. */
internal class PhoneAuthenticationAttempt : ViewModel() {
  var pending: Boolean = false
    private set
  private var initialized = false
  private var action: (() -> Unit)? = null

  fun initialize(credentialOutstanding: Boolean) {
    if (!initialized) {
      pending = credentialOutstanding
      initialized = true
    }
  }

  fun start(onAuthenticated: () -> Unit): Boolean {
    if (pending) return false
    pending = true
    action = onAuthenticated
    return true
  }

  fun finish(success: Boolean) {
    val authenticatedAction = if (success) action else null
    action = null
    pending = false
    authenticatedAction?.invoke()
  }

  fun detach() {
    action = null
  }
}
