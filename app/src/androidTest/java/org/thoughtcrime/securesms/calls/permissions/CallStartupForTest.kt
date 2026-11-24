/*
 * Copyright 2026 Collie contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package org.thoughtcrime.securesms.calls.permissions

import org.thoughtcrime.securesms.keyvalue.SignalStore

/** Persist a test's startup choices before the real ApplicationContext startup decision runs. */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class CallStartupForTest(val onDemand: Boolean, val allCallingDisabled: Boolean = false)

internal object CallStartupTestConfiguration {
  private var pending: CallStartupForTest? = null

  fun stage(classArgument: String?, loader: ClassLoader) {
    pending = null
    // Orchestrator invokes each method in its own process. Discovery/suite invocations retain
    // ordinary defaults; only the exact annotated method may select a no-native-load fixture.
    val selector = classArgument?.takeUnless { ',' in it }?.split('#', limit = 2) ?: return
    if (selector.size != 2 || selector[0] != "org.thoughtcrime.securesms.calls.permissions.ColdCallRuntimeTest") return
    val type = Class.forName(selector[0], false, loader)
    pending = type.getDeclaredMethod(selector[1]).getAnnotation(CallStartupForTest::class.java)
  }

  fun applyBeforeAppDependencies() {
    pending?.let {
      check(SignalStore.isInitialized)
      SignalStore.callPermissions.setOnDemandCallingEnabled(it.onDemand)
      CallPermissions.setAllCallingDisabled(it.allCallingDisabled)
    }
  }
}
