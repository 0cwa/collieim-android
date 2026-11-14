/*
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.main.setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupRulesTest {

  /** A phone with everything set up through UnifiedPush and update checks on. */
  private val ready = SetupInputs(
    notificationsEnabled = true,
    delivery = Delivery.UNIFIEDPUSH,
    deliveryChosen = true,
    unifiedPushReady = true,
    batteryUnrestricted = false,
    updateChecksSupported = true,
    updateChecksEnabled = true,
    backupsEnabled = true
  )

  private fun statusOf(item: SetupItem, inputs: SetupInputs): ItemStatus? = SetupRules.rows(inputs).firstOrNull { it.item == item }?.status

  @Test
  fun `a fully set up phone shows nothing`() {
    val state = SetupRules.evaluate(ready)

    assertEquals(StatusLine.None, state.statusLine)
    assertNull(state.problem)
    assertTrue(state.remaining.isEmpty())
    assertEquals(0, state.visibleRemaining)
  }

  @Test
  fun `battery restrictions only matter while the app keeps its own connection`() {
    assertNull(statusOf(SetupItem.BATTERY, ready))
    assertEquals(ItemStatus.TODO, statusOf(SetupItem.BATTERY, ready.copy(delivery = Delivery.WEBSOCKET)))
    assertEquals(ItemStatus.TODO, statusOf(SetupItem.BATTERY, ready.copy(unifiedPushReady = false, pushFallback = true)))
    assertNull(statusOf(SetupItem.BATTERY, ready.copy(delivery = Delivery.GOOGLE)))
  }

  @Test
  fun `dismissing the old battery reminder counts as a decision`() {
    val inputs = ready.copy(delivery = Delivery.WEBSOCKET, deliveryChosen = true, batteryPromptDismissed = true)

    assertEquals(ItemStatus.SKIPPED, statusOf(SetupItem.BATTERY, inputs))
    assertEquals(StatusLine.None, SetupRules.evaluate(inputs).statusLine)
  }

  @Test
  fun `staying connected is done once chosen or once the app may run in the background`() {
    val websocket = ready.copy(delivery = Delivery.WEBSOCKET, deliveryChosen = false)

    assertEquals(ItemStatus.TODO, statusOf(SetupItem.DELIVERY, websocket))
    assertEquals(ItemStatus.DONE, statusOf(SetupItem.DELIVERY, websocket.copy(deliveryChosen = true)))
    assertEquals(ItemStatus.DONE, statusOf(SetupItem.DELIVERY, websocket.copy(batteryUnrestricted = true)))
  }

  @Test
  fun `update checks are listed only for builds that manage their own updates`() {
    assertNull(statusOf(SetupItem.UPDATE_CHECKS, ready.copy(updateChecksSupported = false)))
    assertEquals(ItemStatus.TODO, statusOf(SetupItem.UPDATE_CHECKS, ready.copy(updateChecksEnabled = false)))
  }

  @Test
  fun `the status line counts what is left`() {
    val inputs = ready.copy(backupsEnabled = false, updateChecksEnabled = false)

    assertEquals(StatusLine.Setup(2), SetupRules.evaluate(inputs).statusLine)
  }

  @Test
  fun `hiding setup lasts until a new item needs doing`() {
    val hidden = ready.copy(backupsEnabled = false, hiddenItems = setOf(SetupItem.BACKUPS))
    assertEquals(StatusLine.None, SetupRules.evaluate(hidden).statusLine)
    assertEquals(0, SetupRules.evaluate(hidden).visibleRemaining)

    val newItem = hidden.copy(updateChecksEnabled = false)
    assertEquals(StatusLine.Setup(2), SetupRules.evaluate(newItem).statusLine)
    assertEquals(1, SetupRules.evaluate(newItem).visibleRemaining)
  }

  @Test
  fun `release notes outrank setup but not problems`() {
    val unfinished = ready.copy(backupsEnabled = false, showUpdated = true)
    assertEquals(StatusLine.Updated, SetupRules.evaluate(unfinished).statusLine)

    val broken = unfinished.copy(notificationsEnabled = false)
    assertEquals(StatusLine.Issue(Problem.NOTIFICATIONS_OFF), SetupRules.evaluate(broken).statusLine)
  }

  @Test
  fun `problems are ranked by how badly they stop messages`() {
    val all = ready.copy(
      notificationsEnabled = false,
      distributorMissing = true,
      messagesDelayed = true,
      pushFallback = true,
      releaseActionPending = true
    )
    assertEquals(Problem.NOTIFICATIONS_OFF, SetupRules.problem(all))
    assertEquals(Problem.PUSH_APP_MISSING, SetupRules.problem(all.copy(notificationsEnabled = true)))
    assertEquals(Problem.MESSAGES_DELAYED, SetupRules.problem(all.copy(notificationsEnabled = true, distributorMissing = false)))
    assertEquals(Problem.PUSH_FAILING, SetupRules.problem(all.copy(notificationsEnabled = true, distributorMissing = false, messagesDelayed = false)))
    assertEquals(Problem.RELEASE_ACTION, SetupRules.problem(ready.copy(releaseActionPending = true)))
  }

  @Test
  fun `delayed messages are not a problem once the app may run in the background`() {
    assertNull(SetupRules.problem(ready.copy(messagesDelayed = true, batteryUnrestricted = true)))
  }

  @Test
  fun `push app problems only apply to UnifiedPush`() {
    assertNull(SetupRules.problem(ready.copy(delivery = Delivery.WEBSOCKET, distributorMissing = true, pushFallback = true)))
  }

  @Test
  fun `a release action shows even when setup is hidden`() {
    val inputs = ready.copy(backupsEnabled = false, hiddenItems = setOf(SetupItem.BACKUPS), releaseActionPending = true)

    assertEquals(StatusLine.Issue(Problem.RELEASE_ACTION), SetupRules.evaluate(inputs).statusLine)
    assertFalse(SetupRules.evaluate(inputs).remaining.isEmpty())
  }
}
