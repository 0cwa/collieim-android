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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.findFragment
import org.signal.core.ui.biometrics.BiometricDeviceAuthentication
import org.signal.core.ui.biometrics.BiometricDeviceLockContract

/** Never transfers an authorization or pending action to a recreated composition. */
@Composable
fun rememberBackupKeyPhoneAuthentication(title: String): ((() -> Unit) -> Unit) {
  val context = LocalContext.current
  val view = LocalView.current
  val fragment = remember(view) { runCatching { view.findFragment<androidx.fragment.app.Fragment>() }.getOrNull() }
  val activity = fragment?.requireActivity() ?: (context as FragmentActivity)
  val attempt = rememberSaveable(saver = Saver(
    save = { it.pending },
    restore = { PhoneAuthenticationAttempt(it) }
  )) { PhoneAuthenticationAttempt(false) }
  fun finish(success: Boolean) = attempt.finish(success)
  val credentialLauncher = rememberLauncherForActivityResult(BiometricDeviceLockContract()) { result ->
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
      attempt.dispose()
      authentication.cancelAuthentication()
    }
  }
  return { onAuthenticated ->
    if (attempt.start(onAuthenticated)) {
      try {
        if (!authentication.authenticate(context, true) { credentialLauncher.launch(title) }) finish(false)
      } catch (_: RuntimeException) {
        finish(false)
      }
    }
  }
}

/** Only the instance that initiated a prompt may run its callback. */
internal class PhoneAuthenticationAttempt(var pending: Boolean) {
  private var action: (() -> Unit)? = null
  private var disposed = false

  fun start(onAuthenticated: () -> Unit): Boolean {
    if (pending || disposed) return false
    pending = true
    action = onAuthenticated
    return true
  }

  fun finish(success: Boolean) {
    if (disposed) return
    val authenticatedAction = if (success) action else null
    action = null
    pending = false
    authenticatedAction?.invoke()
  }

  fun dispose() {
    disposed = true
    action = null
  }
}
