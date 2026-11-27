/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversationlist

import android.content.Context
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.conversationlist.model.Conversation

object ConversationListAccessibilityHelper {
  @JvmStatic
  @JvmOverloads
  fun addConversationActions(
    info: AccessibilityNodeInfoCompat,
    context: Context,
    conversation: Conversation,
    isInSelectionMode: Boolean,
    includeSelect: Boolean = true
  ) {
    if (isInSelectionMode) return

    val thread = conversation.threadRecord
    val archived = thread.isArchived
    if (!archived) {
      val readLabel = context.resources.getQuantityString(
        if (thread.isRead) R.plurals.ConversationListFragment_unread_plural else R.plurals.ConversationListFragment_read_plural,
        1
      )
      addAction(info, R.id.conversation_list_accessibility_read_action, readLabel)
      addAction(info, R.id.conversation_list_accessibility_pin_action, context.getString(if (thread.isPinned) R.string.ConversationListFragment_unpin else R.string.ConversationListFragment_pin))
      addAction(info, R.id.conversation_list_accessibility_mute_action, context.getString(if (thread.recipient.live().get().isMuted) R.string.ConversationListFragment_unmute else R.string.ConversationListFragment_mute))
    }
    if (includeSelect) addAction(info, R.id.conversation_list_accessibility_select_action, context.getString(R.string.ConversationListFragment_select))
    addAction(info, R.id.conversation_list_accessibility_archive_action, context.getString(if (archived) R.string.ConversationListFragment_unarchive else R.string.ConversationListFragment_archive))
    addAction(info, R.id.conversation_list_accessibility_delete_action, context.getString(R.string.ConversationListFragment_delete))
  }

  @JvmStatic
  fun dispatchConversationAction(actionId: Int, conversation: Conversation, listener: OnAccessibilityActionListener): Boolean {
    val thread = conversation.threadRecord
    val action = when (actionId) {
      R.id.conversation_list_accessibility_read_action -> if (thread.isRead) ThreadAccessibilityAction.MARK_AS_UNREAD else ThreadAccessibilityAction.MARK_AS_READ
      R.id.conversation_list_accessibility_pin_action -> if (thread.isPinned) ThreadAccessibilityAction.UNPIN else ThreadAccessibilityAction.PIN
      R.id.conversation_list_accessibility_mute_action -> if (thread.recipient.live().get().isMuted) ThreadAccessibilityAction.UNMUTE else ThreadAccessibilityAction.MUTE
      R.id.conversation_list_accessibility_select_action -> ThreadAccessibilityAction.SELECT
      R.id.conversation_list_accessibility_archive_action -> if (thread.isArchived) ThreadAccessibilityAction.UNARCHIVE else ThreadAccessibilityAction.ARCHIVE
      R.id.conversation_list_accessibility_delete_action -> ThreadAccessibilityAction.DELETE
      else -> return false
    }
    listener.onAccessibilityAction(conversation, action)
    return true
  }

  private fun addAction(info: AccessibilityNodeInfoCompat, id: Int, label: String) {
    info.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat(id, label))
  }

  fun interface OnAccessibilityActionListener {
    fun onAccessibilityAction(conversation: Conversation, action: ThreadAccessibilityAction)
  }
}
