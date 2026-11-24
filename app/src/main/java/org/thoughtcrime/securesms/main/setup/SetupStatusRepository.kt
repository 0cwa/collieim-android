/*
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.main.setup

import android.os.Build
import androidx.annotation.WorkerThread
import im.molly.unifiedpush.UnifiedPushDistributor
import im.molly.unifiedpush.health.PushHealth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.signal.core.util.concurrent.SignalDispatchers
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.BuildConfig
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.keyvalue.SettingsValues.NotificationDeliveryMethod
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.notifications.NotificationChannels
import org.thoughtcrime.securesms.notifications.SlowNotificationHeuristics
import org.thoughtcrime.securesms.util.PowerManagerCompat
import kotlin.time.Duration.Companion.days

/**
 * Reads the phone's state for [SetupRules] and shares the result with the Chats toolbar, banner, empty list and setup
 * sheet. Reads happen off the main thread; call [refresh] when the app returns to the foreground or after the person
 * changes something.
 */
object SetupStatusRepository {

  private val TAG = Log.tag(SetupStatusRepository::class)

  /** How long "Updated" stays in the status line when the notes are never opened. */
  val UPDATED_WINDOW_MS = 3.days.inWholeMilliseconds

  /** How long a dismissed problem banner stays away. The status line keeps naming the problem. */
  val BANNER_SNOOZE_MS = 3.days.inWholeMilliseconds

  /** Facts the checklist rows show next to their titles. */
  data class Details(
    val delivery: Delivery = Delivery.WEBSOCKET,
    val pushTestResult: PushHealth.Hop? = null,
    val pushTestAtSeconds: Long = 0,
    val lastUpdateCheckMs: Long = 0,
    val availableUpdateName: String? = null
  )

  data class Snapshot(
    val state: SetupState = SetupState(),
    val details: Details = Details(),
    /** The problem whose banner shows now, or null when there is none or it was dismissed recently. */
    val bannerProblem: Problem? = null,
    /** The highlight edition selected by this installed release, if any. */
    val releaseNotes: CollieReleaseNotes.Edition? = null
  )

  private val scope = CoroutineScope(SupervisorJob() + SignalDispatchers.IO)
  private val internalSnapshot = MutableStateFlow(Snapshot())

  val snapshot: StateFlow<Snapshot> = internalSnapshot.asStateFlow()

  fun refresh() {
    scope.launch {
      try {
        internalSnapshot.value = read(System.currentTimeMillis())
      } catch (e: Exception) {
        Log.w(TAG, "Could not read the setup state", e)
      }
    }
  }

  /** The person opened the release notes. */
  fun markNotesSeen(editionId: String) {
    SignalStore.collieSetup.notesSeenId = editionId
    refresh()
  }

  /** The person acted on, or dismissed, the release notice. */
  fun markReleaseActionDone(editionId: String) {
    SignalStore.collieSetup.releaseActionDoneId = editionId
    refresh()
  }

  /** Hides the setup prompts until an item that is not left now appears. */
  fun hideRemaining() {
    SignalStore.collieSetup.hiddenItems = internalSnapshot.value.state.remaining.toSet()
    refresh()
  }

  fun snoozeBanner(problem: Problem) {
    if (problem == Problem.RELEASE_ACTION) {
      internalSnapshot.value.releaseNotes?.let { markReleaseActionDone(it.id) }
    } else {
      SignalStore.collieSetup.snoozeBanner(problem, System.currentTimeMillis() + BANNER_SNOOZE_MS)
      refresh()
    }
  }

  @WorkerThread
  private fun read(nowMs: Long): Snapshot {
    val context = AppDependencies.application
    val values = SignalStore.collieSetup
    val releaseNotes = CollieReleaseNotes.edition(ProjectLogRepository.load(context).current.highlightsId)

    val method = SignalStore.settings.preferredNotificationMethod
    val delivery = when (method) {
      NotificationDeliveryMethod.FCM -> Delivery.GOOGLE
      NotificationDeliveryMethod.UNIFIEDPUSH -> Delivery.UNIFIEDPUSH
      NotificationDeliveryMethod.WEBSOCKET -> Delivery.WEBSOCKET
    }

    val unifiedPushEnabled = SignalStore.unifiedpush.enabled
    val health = SignalStore.unifiedpush.health
    val distributorActive = delivery == Delivery.UNIFIEDPUSH && UnifiedPushDistributor.checkIfActive()

    val channels = NotificationChannels.getInstance()
    val systemAllowsNotifications = channels.areNotificationsEnabled() &&
      (Build.VERSION.SDK_INT < 26 || (channels.isMessageChannelEnabled && channels.isMessagesChannelGroupEnabled))
    // Turning message notifications off inside Collie is a choice, not a fault.
    val notificationsEnabled = !SignalStore.settings.isMessageNotificationsEnabled || systemAllowsNotifications

    val inputs = SetupInputs(
      notificationsEnabled = notificationsEnabled,
      delivery = delivery,
      deliveryChosen = SignalStore.settings.hasChosenNotificationMethod,
      unifiedPushReady = unifiedPushEnabled && distributorActive && !health.fallback,
      distributorMissing = delivery == Delivery.UNIFIEDPUSH && unifiedPushEnabled && !distributorActive,
      pushFallback = delivery == Delivery.UNIFIEDPUSH && health.fallback,
      batteryUnrestricted = PowerManagerCompat.isIgnoringBatteryOptimizations(context),
      batteryPromptDismissed = SignalStore.uiHints.hasPromptedOptimizeDoze(),
      messagesDelayed = SlowNotificationHeuristics.isHavingDelayedNotifications(),
      updateChecksSupported = BuildConfig.MANAGE_COLLIE_UPDATES,
      updateChecksEnabled = SignalStore.molly.updateApkEnabled,
      backupsEnabled = SignalStore.backup.newLocalBackupsEnabled || SignalStore.settings.isBackupEnabled,
      hiddenItems = values.hiddenItems,
      showUpdated = showUpdated(nowMs, releaseNotes),
      releaseActionPending = releaseNotes?.action != null && values.releaseActionDoneId != releaseNotes.id
    )

    val state = SetupRules.evaluate(inputs)
    val details = Details(
      delivery = delivery,
      pushTestResult = PushHealth.Hop.fromCode(health.lastResult),
      pushTestAtSeconds = health.lastResultAt,
      lastUpdateCheckMs = SignalStore.apkUpdate.lastSuccessfulCheck,
      availableUpdateName = SignalStore.apkUpdate.availableVersionName.takeIf { SignalStore.apkUpdate.availableVersionCode > BuildConfig.VERSION_CODE }
    )
    val banner = state.problem?.takeIf { it == Problem.RELEASE_ACTION || values.bannerSnoozedUntil(it) <= nowMs }

    return Snapshot(state, details, banner, releaseNotes)
  }

  /** Unseen notes are announced for [UPDATED_WINDOW_MS] from the first time this build looked. */
  private fun showUpdated(nowMs: Long, edition: CollieReleaseNotes.Edition?): Boolean {
    if (edition == null) return false
    val values = SignalStore.collieSetup
    if (values.notesSeenId == edition.id) return false
    if (values.notesPendingId != edition.id) {
      values.notesPendingId = edition.id
      values.notesPendingSince = nowMs
    }
    return nowMs - values.notesPendingSince < UPDATED_WINDOW_MS
  }
}
