/* SPDX-License-Identifier: AGPL-3.0-only */
package org.thoughtcrime.securesms.calls.permissions

import io.reactivex.rxjava3.core.Observable
import kotlinx.coroutines.rx3.asObservable
import org.signal.core.models.recipients.RecipientId
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.service.webrtc.CallLinkPeekInfo

/** Reading call UI state must not acquire the native manager while calling is prohibited. */
internal object CallPeekState {
  fun observe(): Observable<Map<RecipientId, CallLinkPeekInfo>> = CallPermissions.changes.asObservable()
    .switchMap { generation ->
      var source = Observable.just(emptyMap<RecipientId, CallLinkPeekInfo>())
      CallPermissions.runIfCurrent(generation) {
        source = AppDependencies.signalCallManager.peekInfoCache.toObservable()
          .filter { CallPermissions.isCurrent(generation) }
      }
      source
    }

  fun snapshot(): Map<RecipientId, CallLinkPeekInfo> {
    var result = emptyMap<RecipientId, CallLinkPeekInfo>()
    CallPermissions.runIfCurrent(CallPermissions.generation()) {
      result = AppDependencies.signalCallManager.peekInfoSnapshot
    }
    return result
  }
}
