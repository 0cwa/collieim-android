/*
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.main.setup

/** The steps that make Collie reliable, in the order the checklist shows them. */
enum class SetupItem {
  NOTIFICATIONS,
  DELIVERY,
  BATTERY,
  UPDATE_CHECKS,
  BACKUPS
}

enum class ItemStatus {
  DONE,
  TODO,

  /** The person said no somewhere else (for example the old battery reminder). Never counted as left. */
  SKIPPED
}

data class SetupRow(val item: SetupItem, val status: ItemStatus)

/** How new messages wake the app. [GOOGLE] only exists in builds that ship Firebase. */
enum class Delivery {
  GOOGLE,
  UNIFIEDPUSH,
  WEBSOCKET
}

/**
 * Something that stops messages from arriving on time, or a release that needs the person to act. Ordered from most
 * to least important: the first one that applies is the one shown.
 */
enum class Problem {
  NOTIFICATIONS_OFF,
  PUSH_APP_MISSING,
  MESSAGES_DELAYED,
  PUSH_FAILING,
  RELEASE_ACTION
}

/** The line under "Collie" in the Chats toolbar. */
sealed interface StatusLine {
  data object None : StatusLine
  data class Issue(val problem: Problem) : StatusLine
  data object Updated : StatusLine
  data class Setup(val remaining: Int) : StatusLine
}

/** Everything the rules read. Gathered by [SetupStatusRepository]; plain values so the rules run on the JVM. */
data class SetupInputs(
  val notificationsEnabled: Boolean = true,
  val delivery: Delivery = Delivery.WEBSOCKET,
  /** The stored delivery choice is the one in effect, rather than a default. */
  val deliveryChosen: Boolean = false,
  /** UnifiedPush is registered with a distributor app and push health trusts it. */
  val unifiedPushReady: Boolean = false,
  /** UnifiedPush is selected and enabled, but no distributor app acknowledges it. */
  val distributorMissing: Boolean = false,
  /** Push health gave up on UnifiedPush and fell back to the app's own connection. */
  val pushFallback: Boolean = false,
  val batteryUnrestricted: Boolean = false,
  /** The person dismissed the old battery-restriction reminder. */
  val batteryPromptDismissed: Boolean = false,
  /** [org.thoughtcrime.securesms.notifications.SlowNotificationHeuristics] sees late messages. */
  val messagesDelayed: Boolean = false,
  val updateChecksSupported: Boolean = false,
  val updateChecksEnabled: Boolean = false,
  val backupsEnabled: Boolean = false,
  /** The items that were left when the person chose "Hide for now". */
  val hiddenItems: Set<SetupItem> = emptySet(),
  /** The bundled release notes have not been opened and the update is recent. */
  val showUpdated: Boolean = false,
  /** The bundled release notes carry an action the person has not taken or dismissed. */
  val releaseActionPending: Boolean = false
)

data class SetupState(
  val rows: List<SetupRow> = emptyList(),
  val problem: Problem? = null,
  val statusLine: StatusLine = StatusLine.None,
  /** Items left that the person has not hidden. The checklist on an empty Chats list shows only while this is above 0. */
  val visibleRemaining: Int = 0
) {
  val remaining: List<SetupItem> get() = rows.filter { it.status == ItemStatus.TODO }.map { it.item }

  /** Items that need nothing more: done, or declined elsewhere. */
  val settledCount: Int get() = rows.count { it.status != ItemStatus.TODO }
}

object SetupRules {

  fun evaluate(inputs: SetupInputs): SetupState {
    val rows = rows(inputs)
    val problem = problem(inputs)
    val todo = rows.filter { it.status == ItemStatus.TODO }.map { it.item }
    val visible = todo.count { it !in inputs.hiddenItems }

    val statusLine = when {
      problem != null -> StatusLine.Issue(problem)
      inputs.showUpdated -> StatusLine.Updated
      visible > 0 -> StatusLine.Setup(todo.size)
      else -> StatusLine.None
    }
    return SetupState(rows, problem, statusLine, visible)
  }

  fun rows(inputs: SetupInputs): List<SetupRow> = buildList {
    add(SetupRow(SetupItem.NOTIFICATIONS, if (inputs.notificationsEnabled) ItemStatus.DONE else ItemStatus.TODO))
    add(SetupRow(SetupItem.DELIVERY, delivery(inputs)))
    if (usesOwnConnection(inputs)) {
      add(SetupRow(SetupItem.BATTERY, battery(inputs)))
    }
    if (inputs.updateChecksSupported) {
      add(SetupRow(SetupItem.UPDATE_CHECKS, if (inputs.updateChecksEnabled) ItemStatus.DONE else ItemStatus.TODO))
    }
    add(SetupRow(SetupItem.BACKUPS, if (inputs.backupsEnabled) ItemStatus.DONE else ItemStatus.TODO))
  }

  fun problem(inputs: SetupInputs): Problem? = when {
    !inputs.notificationsEnabled -> Problem.NOTIFICATIONS_OFF
    inputs.delivery == Delivery.UNIFIEDPUSH && inputs.distributorMissing -> Problem.PUSH_APP_MISSING
    inputs.messagesDelayed && !inputs.batteryUnrestricted -> Problem.MESSAGES_DELAYED
    inputs.delivery == Delivery.UNIFIEDPUSH && inputs.pushFallback -> Problem.PUSH_FAILING
    inputs.releaseActionPending -> Problem.RELEASE_ACTION
    else -> null
  }

  /** The app keeps its own connection open, so Android's battery limits decide whether messages arrive on time. */
  fun usesOwnConnection(inputs: SetupInputs): Boolean {
    return inputs.delivery == Delivery.WEBSOCKET || (inputs.delivery == Delivery.UNIFIEDPUSH && !inputs.unifiedPushReady)
  }

  private fun delivery(inputs: SetupInputs): ItemStatus = when (inputs.delivery) {
    Delivery.GOOGLE -> ItemStatus.DONE
    Delivery.UNIFIEDPUSH -> if (inputs.unifiedPushReady) ItemStatus.DONE else ItemStatus.TODO
    // Staying connected works once Android lets the app run, or once the person picked it on purpose.
    Delivery.WEBSOCKET -> if (inputs.deliveryChosen || inputs.batteryUnrestricted) ItemStatus.DONE else ItemStatus.TODO
  }

  private fun battery(inputs: SetupInputs): ItemStatus = when {
    inputs.batteryUnrestricted -> ItemStatus.DONE
    inputs.batteryPromptDismissed -> ItemStatus.SKIPPED
    else -> ItemStatus.TODO
  }
}
