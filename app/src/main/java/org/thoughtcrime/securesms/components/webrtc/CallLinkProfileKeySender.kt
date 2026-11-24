/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.components.webrtc

import org.signal.core.models.recipients.RecipientId
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.calls.permissions.CallPermissions
import org.thoughtcrime.securesms.database.identity.IdentityRecordList
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.jobs.ProfileKeySendJob
import org.thoughtcrime.securesms.recipients.Recipient

/**
 * In-Memory Cache that keeps track of whom we've sent profile keys to. This is
 * something that really only needs to happen once so that profile information is
 * displayed correctly. The cache is cleared with the call because queued profile
 * work cannot carry consent into another session.
 */
object CallLinkProfileKeySender {
  private val TAG = Log.tag(CallLinkProfileKeySender::class.java)

  private val cache = hashSetOf<RecipientId>()

  @JvmStatic
  @Synchronized
  fun clear() {
    cache.clear()
  }

  /**
   * Invoked after pressing "Join" or "Continue" on the safety number change dialog before
   * or during a call.
   */
  @JvmStatic
  @Synchronized
  fun onSendAnyway(callLinkRecipientId: RecipientId, recipientIds: Set<RecipientId>) {
    if (!CallPermissions.isCallingAllowed()) return
    val toSendMessagesTo: Set<RecipientId> = recipientIds - cache

    if (toSendMessagesTo.isNotEmpty()) {
      Log.i(TAG, "Sending profile key to $toSendMessagesTo users.")
      val job = ProfileKeySendJob.createForCallLinks(callLinkRecipientId, toSendMessagesTo.toList()) ?: return
      AppDependencies.jobManager.add(job)
      cache += recipientIds
    } else {
      Log.i(TAG, "No users to send profile key to.")
    }
  }

  /**
   * Given the set of recipients, for each unblocked recipient we don't distrust, send a NullMessage
   */
  fun onRecipientsUpdated(callLinkRecipientId: RecipientId, recipients: Set<Recipient>) {
    if (!CallPermissions.isCallingAllowed()) return
    val nonBlockedNonSelfRecipients: List<Recipient> = recipients.filterNot { it.isBlocked || it.isSelf }

    val identityRecords: IdentityRecordList = AppDependencies
      .protocolStore
      .aci()
      .identities()
      .getIdentityRecords(nonBlockedNonSelfRecipients)

    val untrustedAndUnverifiedRecipients = if (identityRecords.isUntrusted(false) || identityRecords.isUnverified(false)) {
      (identityRecords.untrustedRecipients + identityRecords.unverifiedRecipients).toSet()
    } else {
      emptySet()
    }

    val trustedRecipients: Set<RecipientId> = (nonBlockedNonSelfRecipients - untrustedAndUnverifiedRecipients).map { it.id }.toSet()

    onSendAnyway(callLinkRecipientId, trustedRecipients)
  }
}
