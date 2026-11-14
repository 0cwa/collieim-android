/*
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.main.setup

import androidx.annotation.StringRes
import org.thoughtcrime.securesms.R

/**
 * The release notes bundled with this build. See docs/FINISH-SETUP.md for how to publish a new edition.
 *
 * [ID] names the content, not the APK: change it only when the highlights change, so a rebuild or version bump never
 * announces old notes again.
 */
object CollieReleaseNotes {
  const val ID = "2026-11-setup-and-push-health"
  const val RELEASES_URL = "https://github.com/0cwa/collieim-android/releases"

  data class Highlight(@StringRes val title: Int, @StringRes val body: Int)

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

  val highlights: List<Highlight> = listOf(
    Highlight(R.string.CollieWhatsNew__setup_title, R.string.CollieWhatsNew__setup_body),
    Highlight(R.string.CollieWhatsNew__push_health_title, R.string.CollieWhatsNew__push_health_body),
    Highlight(R.string.CollieWhatsNew__updates_title, R.string.CollieWhatsNew__updates_body),
    Highlight(R.string.CollieWhatsNew__backups_title, R.string.CollieWhatsNew__backups_body),
    Highlight(R.string.CollieWhatsNew__signal_title, R.string.CollieWhatsNew__signal_body)
  )

  /** This edition asks nothing of people. */
  val action: Action? = null
}
