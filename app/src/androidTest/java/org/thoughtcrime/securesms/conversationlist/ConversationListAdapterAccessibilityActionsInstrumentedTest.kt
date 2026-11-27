/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversationlist

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.thoughtcrime.securesms.MainActivity
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.database.MessageType
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.database.model.StoryType
import org.thoughtcrime.securesms.mms.IncomingMessage
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.testing.SignalActivityRule
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class ConversationListAdapterAccessibilityActionsInstrumentedTest {

  @get:Rule
  val harness = SignalActivityRule(othersCount = 1, networkEnabled = false)

  @Test
  fun liveRowExposesActionsAndSelectEntersSelectionMode() {
    val other = Recipient.resolved(harness.others.first())
    insertIncomingText(other, "conversation list accessibility test")

    ActivityScenario.launch<MainActivity>(Intent(harness.context, MainActivity::class.java)).use { scenario ->
      assertTrue("Conversation row did not expose accessibility actions", waitForAction(scenario, R.id.conversation_list_accessibility_select_action, 15_000))

      assertEquals(
        harness.context.resources.getQuantityString(R.plurals.ConversationListFragment_read_plural, 1),
        getActionLabel(scenario, R.id.conversation_list_accessibility_read_action)
      )
      assertEquals(harness.context.getString(R.string.ConversationListFragment_pin), getActionLabel(scenario, R.id.conversation_list_accessibility_pin_action))
      assertEquals(harness.context.getString(R.string.ConversationListFragment_mute), getActionLabel(scenario, R.id.conversation_list_accessibility_mute_action))
      assertEquals(harness.context.getString(R.string.ConversationListFragment_select), getActionLabel(scenario, R.id.conversation_list_accessibility_select_action))
      assertEquals(harness.context.getString(R.string.ConversationListFragment_archive), getActionLabel(scenario, R.id.conversation_list_accessibility_archive_action))
      assertEquals(harness.context.getString(R.string.ConversationListFragment_delete), getActionLabel(scenario, R.id.conversation_list_accessibility_delete_action))

      assertTrue("Select accessibility action was not handled", performAction(scenario, R.id.conversation_list_accessibility_select_action))
      assertTrue("Selection action did not enter selection mode", waitForViewVisible(scenario, R.id.conversation_list_bottom_action_bar, 5_000))
      assertFalse("A selected row still exposes its select action", hasAction(scenario, R.id.conversation_list_accessibility_select_action))
    }
  }

  private fun insertIncomingText(other: Recipient, body: String): Long {
    val now = System.currentTimeMillis()
    val message = IncomingMessage(
      MessageType.NORMAL, other.id, now, now, now, null, null, body, StoryType.NONE, null,
      false, -1, 0, null, false, false, null, null,
      Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
      null, null, false, null
    )
    val threadId = SignalDatabase.threads.getOrCreateThreadIdFor(other)
    try {
      SignalDatabase.messages.insertMessageInbox(message, threadId).get()
    } catch (e: Exception) {
      throw AssertionError("Failed to insert incoming message for test setup", e)
    }
    return threadId
  }

  private fun waitForAction(scenario: ActivityScenario<MainActivity>, actionId: Int, timeoutMs: Long): Boolean {
    val deadline = SystemClock.uptimeMillis() + timeoutMs
    while (SystemClock.uptimeMillis() < deadline) {
      if (hasAction(scenario, actionId)) return true
      InstrumentationRegistry.getInstrumentation().waitForIdleSync()
      SystemClock.sleep(100)
    }
    return false
  }

  private fun hasAction(scenario: ActivityScenario<MainActivity>, actionId: Int): Boolean {
    val found = AtomicBoolean(false)
    scenario.onActivity { activity ->
      val recycler = activity.findViewById<RecyclerView>(R.id.list)
      found.set(findChildWithAction(recycler, actionId) != null)
    }
    return found.get()
  }

  private fun getActionLabel(scenario: ActivityScenario<MainActivity>, actionId: Int): String? {
    val label = AtomicReference<String?>()
    scenario.onActivity { activity ->
      val child = findChildWithAction(activity.findViewById(R.id.list), actionId) ?: return@onActivity
      val nodeInfo = child.createAccessibilityNodeInfo()
      try {
        label.set(findActionById(nodeInfo, actionId)?.label?.toString())
      } finally {
        nodeInfo.recycle()
      }
    }
    return label.get()
  }

  private fun performAction(scenario: ActivityScenario<MainActivity>, actionId: Int): Boolean {
    val performed = AtomicBoolean(false)
    scenario.onActivity { activity ->
      findChildWithAction(activity.findViewById(R.id.list), actionId)?.let {
        performed.set(it.performAccessibilityAction(actionId, Bundle.EMPTY))
      }
    }
    return performed.get()
  }

  private fun waitForViewVisible(scenario: ActivityScenario<MainActivity>, viewId: Int, timeoutMs: Long): Boolean {
    val deadline = SystemClock.uptimeMillis() + timeoutMs
    while (SystemClock.uptimeMillis() < deadline) {
      val visible = AtomicBoolean(false)
      scenario.onActivity { activity ->
        val view = activity.findViewById<View>(viewId)
        visible.set(view != null && view.isShown)
      }
      if (visible.get()) return true
      InstrumentationRegistry.getInstrumentation().waitForIdleSync()
      SystemClock.sleep(100)
    }
    return false
  }

  private fun findChildWithAction(recycler: RecyclerView?, actionId: Int): View? {
    if (recycler == null) return null
    for (index in 0 until recycler.childCount) {
      val child = recycler.getChildAt(index)
      val nodeInfo = child.createAccessibilityNodeInfo()
      try {
        if (findActionById(nodeInfo, actionId) != null) return child
      } finally {
        nodeInfo.recycle()
      }
    }
    return null
  }

  private fun findActionById(info: AccessibilityNodeInfo, actionId: Int): AccessibilityNodeInfo.AccessibilityAction? {
    return info.actionList?.firstOrNull { it.id == actionId }
  }
}
