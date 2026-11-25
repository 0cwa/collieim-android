/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.mediaoverview

import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.reactivex.rxjava3.core.Completable
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.attachments.AttachmentSaver
import org.thoughtcrime.securesms.database.AttachmentTable
import org.thoughtcrime.securesms.database.MediaTable
import org.thoughtcrime.securesms.flags.CollieFlags

internal object MediaActions {
  @JvmStatic
  fun canOfferAttachmentRemoval(record: MediaTable.MediaRecord): Boolean {
    val attachment = record.attachment ?: return false
    return CollieFlags.attachmentRemoval && record.linkPreviewJson == null &&
      attachment.hasData && attachment.transferState == AttachmentTable.TRANSFER_PROGRESS_DONE
  }

  @JvmStatic
  fun handleSaveMedia(fragment: Fragment, mediaRecords: Collection<MediaTable.MediaRecord>): Completable {
    return AttachmentSaver(fragment).saveAttachmentsRx(mediaRecords)
  }

  @JvmStatic
  fun handleDeleteMedia(fragment: Fragment, mediaRecords: Collection<MediaTable.MediaRecord>) {
    val recordCount = mediaRecords.size
    val res = fragment.resources
    val confirmTitle = res.getQuantityString(R.plurals.MediaOverviewActivity_Media_delete_confirm_title, recordCount, recordCount)
    val confirmMessage = res.getQuantityString(R.plurals.MediaOverviewActivity_Media_delete_confirm_message, recordCount, recordCount)

    MaterialAlertDialogBuilder(fragment.requireContext())
      .setTitle(confirmTitle)
      .setMessage(confirmMessage)
      .setCancelable(true)
      .setPositiveButton(R.string.delete) { _, _ ->
        val viewModel = ViewModelProvider(fragment)[MediaDeleteProgressViewModel::class.java]
        if (viewModel.start(mediaRecords)) {
          MediaDeleteProgressDialogFragment.show(fragment)
        }
      }
      .setNegativeButton(android.R.string.cancel, null)
      .show()
  }

  @JvmStatic
  @JvmOverloads
  fun handleRemoveAttachments(fragment: Fragment, mediaRecords: Collection<MediaTable.MediaRecord>, onStarted: Runnable? = null) {
    if (!CollieFlags.attachmentRemoval) return

    // Bind confirmation to this selection, even if the gallery refreshes while the dialog is open.
    val snapshot = mediaRecords.toList()
    if (snapshot.isEmpty()) return

    MaterialAlertDialogBuilder(fragment.requireContext())
      .setTitle(R.string.CollieAttachmentRemoval_confirm)
      .setMessage(fragment.resources.getQuantityString(R.plurals.CollieAttachmentRemoval_confirmation, snapshot.size, snapshot.size))
      .setPositiveButton(R.string.CollieAttachmentRemoval_remove) { _, _ ->
        val viewModel = ViewModelProvider(fragment)[MediaDeleteProgressViewModel::class.java]
        if (viewModel.start(snapshot, MediaDeleteProgressViewModel.Operation.REMOVE_ATTACHMENTS)) {
          onStarted?.run()
          MediaDeleteProgressDialogFragment.show(fragment)
        }
      }
      .setNegativeButton(android.R.string.cancel, null)
      .show()
  }
}
