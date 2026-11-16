/*
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.main.setup

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import androidx.fragment.app.FragmentActivity
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.components.settings.app.AppSettingsActivity
import org.thoughtcrime.securesms.notifications.TurnOnNotificationsBottomSheet
import org.thoughtcrime.securesms.util.CommunicationActions
import org.thoughtcrime.securesms.util.PowerManagerCompat

/** Opens the screen that settles a setup item or a problem. Every destination already exists elsewhere in the app. */
object SetupActions {

  private val TAG = Log.tag(SetupActions::class)

  fun openItem(activity: FragmentActivity, item: SetupItem) {
    when (item) {
      SetupItem.NOTIFICATIONS -> turnOnNotifications(activity)
      SetupItem.DELIVERY -> activity.startActivity(deliverySettings(activity))
      SetupItem.BATTERY -> allowBackgroundUse(activity)
      SetupItem.UPDATE_CHECKS -> activity.startActivity(AppSettingsActivity.appUpdates(activity))
      SetupItem.BACKUPS -> activity.startActivity(AppSettingsActivity.backups(activity))
    }
  }

  fun fixProblem(activity: FragmentActivity, problem: Problem) {
    when (problem) {
      Problem.NOTIFICATIONS_OFF -> turnOnNotifications(activity)
      Problem.PUSH_APP_MISSING, Problem.PUSH_FAILING -> activity.startActivity(AppSettingsActivity.unifiedPush(activity))
      Problem.MESSAGES_DELAYED -> allowBackgroundUse(activity)
      Problem.RELEASE_ACTION -> {
        CollieReleaseNotes.action?.let { openDestination(activity, it.destination) }
        SetupStatusRepository.markReleaseActionDone()
      }
    }
  }

  /** The status line names one thing; tapping it goes to the place that deals with that thing. */
  fun onStatusLineClick(activity: FragmentActivity, statusLine: StatusLine) {
    when (statusLine) {
      is StatusLine.Issue -> if (statusLine.problem == Problem.RELEASE_ACTION) showWhatsNew(activity) else fixProblem(activity, statusLine.problem)
      StatusLine.Updated -> showWhatsNew(activity)
      is StatusLine.Setup -> showSetup(activity)
      StatusLine.None -> Unit
    }
  }

  fun showSetup(activity: FragmentActivity) {
    FinishSetupBottomSheet.show(activity.supportFragmentManager)
  }

  fun showWhatsNew(activity: FragmentActivity) {
    WhatsNewDialogFragment.show(activity.supportFragmentManager)
  }

  fun openReleaseNotes(activity: FragmentActivity) {
    CommunicationActions.openBrowserLink(activity, CollieReleaseNotes.RELEASES_URL)
  }

  fun openSupport(activity: FragmentActivity) {
    CommunicationActions.openBrowserLink(activity, activity.getString(R.string.donate_collie_url))
  }

  private fun openDestination(activity: FragmentActivity, destination: CollieReleaseNotes.Destination) {
    val intent = when (destination) {
      CollieReleaseNotes.Destination.NOTIFICATION_SETTINGS -> AppSettingsActivity.notifications(activity)
      CollieReleaseNotes.Destination.APP_UPDATES -> AppSettingsActivity.appUpdates(activity)
      CollieReleaseNotes.Destination.BACKUPS -> AppSettingsActivity.backups(activity)
    }
    activity.startActivity(intent)
  }

  private fun deliverySettings(activity: FragmentActivity): Intent {
    return if (SetupStatusRepository.snapshot.value.details.delivery == Delivery.UNIFIEDPUSH) {
      AppSettingsActivity.unifiedPush(activity)
    } else {
      AppSettingsActivity.notifications(activity)
    }
  }

  private fun turnOnNotifications(activity: FragmentActivity) {
    TurnOnNotificationsBottomSheet.turnOnSystemNotificationsFragment(activity).show(activity.supportFragmentManager, null)
  }

  private fun allowBackgroundUse(activity: FragmentActivity) {
    try {
      activity.startActivity(PowerManagerCompat.buildRequestIgnoreBatteryOptimizationsIntent(activity))
    } catch (e: ActivityNotFoundException) {
      Log.w(TAG, "No battery optimization prompt on this device; opening the list instead", e)
      try {
        activity.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
      } catch (e2: ActivityNotFoundException) {
        Log.w(TAG, "No battery optimization settings on this device", e2)
      }
    }
  }
}
