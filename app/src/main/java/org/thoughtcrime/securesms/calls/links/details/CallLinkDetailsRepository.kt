/**
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.calls.links.details

import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.kotlin.subscribeBy
import io.reactivex.rxjava3.schedulers.Schedulers
import org.signal.core.util.concurrent.MaybeCompat
import org.signal.core.util.orNull
import org.thoughtcrime.securesms.calls.permissions.CallLinkPolicy
import org.thoughtcrime.securesms.calls.permissions.CallPermissions
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.service.webrtc.links.CallLinkRoomId
import org.thoughtcrime.securesms.service.webrtc.links.ReadCallLinkResult
import org.thoughtcrime.securesms.service.webrtc.links.SignalCallLinkManager

class CallLinkDetailsRepository(
  private val callLinkManager: SignalCallLinkManager? = null
) {
  fun refreshCallLinkState(callLinkRoomId: CallLinkRoomId): Disposable {
    val generation = CallPermissions.generation()
    return MaybeCompat.fromCallable { SignalDatabase.callLinks.getCallLinkByRoomId(callLinkRoomId) }
      .flatMapSingle { link ->
        CallLinkPolicy.guarded<ReadCallLinkResult>(ReadCallLinkResult.Failure(CallLinkPolicy.DENIED_STATUS), generation) {
          (callLinkManager ?: AppDependencies.signalCallManager.callLinkManager).readCallLink(link.credentials!!)
        }
      }
      .subscribeOn(Schedulers.io())
      .subscribeBy { result ->
        when (result) {
          is ReadCallLinkResult.Success -> SignalDatabase.callLinks.updateCallLinkState(callLinkRoomId, result.callLinkState)
          is ReadCallLinkResult.Failure -> Unit
        }
      }
  }

  fun watchCallLinkRecipient(callLinkRoomId: CallLinkRoomId): Observable<Recipient> {
    return MaybeCompat.fromCallable { SignalDatabase.recipients.getByCallLinkRoomId(callLinkRoomId).orNull() }
      .flatMapObservable { Recipient.observable(it) }
      .distinctUntilChanged { a, b -> a.hasSameContent(b) }
      .subscribeOn(Schedulers.io())
  }
}
