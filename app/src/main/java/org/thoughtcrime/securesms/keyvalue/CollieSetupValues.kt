/*
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.keyvalue

import org.signal.storage.keyvalue.KeyValueStore
import org.signal.storage.keyvalue.SignalStoreValues
import org.signal.storage.keyvalue.longValue
import org.signal.storage.keyvalue.stringValue
import org.thoughtcrime.securesms.main.setup.CollieReleaseNotes
import org.thoughtcrime.securesms.main.setup.Problem
import org.thoughtcrime.securesms.main.setup.SetupItem

/**
 * What the person did with the setup prompts and release notes on this phone. Kept out of backups: a restored account
 * gets fresh prompts for the phone it lands on.
 */
class CollieSetupValues(store: KeyValueStore) : SignalStoreValues(store) {

  companion object {
    private const val HIDDEN_ITEMS = "collie.setup.hidden_items"
    private const val NOTES_SEEN_ID = "collie.whats_new.seen_id"
    private const val NOTES_PENDING_ID = "collie.whats_new.pending_id"
    private const val NOTES_PENDING_SINCE = "collie.whats_new.pending_since"
    private const val RELEASE_ACTION_DONE_ID = "collie.whats_new.action_done_id"
    private const val SNOOZED_PREFIX = "collie.setup.banner_snoozed_until."
  }

  /** A new install has nothing to catch up on: its first notes are the ones already bundled. */
  override fun onFirstEverAppLaunch() {
    notesSeenId = CollieReleaseNotes.ID
    releaseActionDoneId = CollieReleaseNotes.ID
  }

  override val keysToIncludeInBackup: List<String> = emptyList()

  /** The items left when the person chose "Hide for now". A new item reaching the list shows the prompts again. */
  var hiddenItems: Set<SetupItem>
    get() = getString(HIDDEN_ITEMS, "")
      .orEmpty()
      .split(',')
      .mapNotNull { name -> SetupItem.entries.firstOrNull { it.name == name } }
      .toSet()
    set(value) = putString(HIDDEN_ITEMS, value.sortedBy { it.ordinal }.joinToString(",") { it.name })

  /** The ID of the release notes the person last opened. */
  var notesSeenId: String? by stringValue(NOTES_SEEN_ID, null)

  /** The unseen notes ID that [notesPendingSince] belongs to. */
  var notesPendingId: String? by stringValue(NOTES_PENDING_ID, null)

  /** When the unseen notes were first announced, so the announcement can retire on its own. */
  var notesPendingSince: Long by longValue(NOTES_PENDING_SINCE, 0)

  /** The ID of the release notes whose action the person took or dismissed. */
  var releaseActionDoneId: String? by stringValue(RELEASE_ACTION_DONE_ID, null)

  /** Until when the banner for [problem] stays hidden. The status line keeps showing the problem meanwhile. */
  fun bannerSnoozedUntil(problem: Problem): Long = getLong(SNOOZED_PREFIX + problem.name, 0)

  fun snoozeBanner(problem: Problem, untilMs: Long) {
    putLong(SNOOZED_PREFIX + problem.name, untilMs)
  }
}
