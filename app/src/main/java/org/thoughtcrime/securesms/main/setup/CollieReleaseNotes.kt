/*
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.main.setup

import androidx.annotation.StringRes
import org.thoughtcrime.securesms.R

/**
 * Localized highlight editions selected by a bundled release record. An edition's ID names its content, not an APK,
 * so rebuilding a release never announces the same highlights again.
 */
object CollieReleaseNotes {
  const val SETUP_AND_PUSH_HEALTH = "2026-11-setup-and-push-health"
  const val STABLE_8_36_3_3 = "stable-8.36.3-3"

  data class Highlight(@StringRes val title: Int, @StringRes val body: Int, val titleIncludesSignalVersion: Boolean = false)

  data class Edition(val id: String, val highlights: List<Highlight>, val action: Action? = null)

  /** Where the action of an important release notice leads. */
  enum class Destination {
    NOTIFICATION_SETTINGS,
    APP_UPDATES,
    BACKUPS
  }

  /**
   * Something people must do after updating, shown as a banner until they act or dismiss it. Use it rarely: a change
   * that breaks delivery or needs a decision, never a feature announcement.
   */
  data class Action(
    @StringRes val title: Int,
    @StringRes val body: Int,
    @StringRes val button: Int,
    val destination: Destination
  )

  private val setupAndPushHealth = Edition(
    id = SETUP_AND_PUSH_HEALTH,
    highlights = listOf(
      Highlight(R.string.CollieWhatsNew__setup_title, R.string.CollieWhatsNew__setup_body),
      Highlight(R.string.CollieWhatsNew__push_health_title, R.string.CollieWhatsNew__push_health_body),
      Highlight(R.string.CollieWhatsNew__updates_title, R.string.CollieWhatsNew__updates_body),
      Highlight(R.string.CollieWhatsNew__backups_title, R.string.CollieWhatsNew__backups_body),
      Highlight(R.string.CollieWhatsNew__signal_title, R.string.CollieWhatsNew__signal_body, titleIncludesSignalVersion = true)
    )
  )

  private val stable83633 = Edition(
    id = STABLE_8_36_3_3,
    highlights = listOf(
      Highlight(R.string.CollieWhatsNew__stable_setup_title, R.string.CollieWhatsNew__stable_setup_body),
      Highlight(R.string.CollieWhatsNew__stable_updates_title, R.string.CollieWhatsNew__stable_updates_body),
      Highlight(R.string.CollieWhatsNew__stable_controls_title, R.string.CollieWhatsNew__stable_controls_body),
      Highlight(R.string.CollieWhatsNew__stable_unifiedpush_title, R.string.CollieWhatsNew__stable_unifiedpush_body),
      Highlight(R.string.CollieWhatsNew__stable_signal_title, R.string.CollieWhatsNew__stable_signal_body, titleIncludesSignalVersion = true)
    ),
    action = Action(
      R.string.CollieReleaseAction__stable_apk_title,
      R.string.CollieReleaseAction__stable_apk_body,
      R.string.CollieReleaseAction__stable_apk_button,
      Destination.APP_UPDATES
    )
  )

  /** Missing or unknown editions never fall back to another release's highlights. */
  fun edition(id: String?): Edition? = when (id) {
    SETUP_AND_PUSH_HEALTH -> setupAndPushHealth
    STABLE_8_36_3_3 -> stable83633
    else -> null
  }
}
