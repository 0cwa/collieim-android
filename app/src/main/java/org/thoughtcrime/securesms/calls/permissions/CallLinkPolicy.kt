/*
 * Copyright 2026 Collie contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package org.thoughtcrime.securesms.calls.permissions

import io.reactivex.rxjava3.core.Single

/** Capture authorization when an operation is requested, and check it again on subscription. */
internal object CallLinkPolicy {
  const val DENIED_STATUS: Short = -2

  fun <T : Any> guarded(failure: T, generation: Long = CallPermissions.generation(), operation: () -> Single<T>): Single<T> {
    return Single.defer {
      var result = Single.just(failure)
      CallPermissions.runIfCurrent(generation) {
        result = operation()
      }
      result
    }
  }
}
