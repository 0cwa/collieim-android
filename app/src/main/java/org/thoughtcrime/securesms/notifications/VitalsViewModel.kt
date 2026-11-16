/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.notifications

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.schedulers.Schedulers
import io.reactivex.rxjava3.subjects.BehaviorSubject
import org.thoughtcrime.securesms.notifications.DeviceSpecificNotificationConfig.ShowCondition
import java.util.concurrent.TimeUnit

/**
 * View model for checking for various app vitals, like slow notifications and crashes.
 */
class VitalsViewModel(private val context: Application) : AndroidViewModel(context) {

  private val checkSubject = BehaviorSubject.create<Unit>()

  val vitalsState: Observable<State>

  init {
    vitalsState = checkSubject
      .subscribeOn(Schedulers.io())
      .observeOn(Schedulers.io())
      .throttleFirst(5, TimeUnit.MINUTES)
      .switchMapSingle {
        checkHeuristics()
      }
      .distinctUntilChanged()
      .observeOn(AndroidSchedulers.mainThread())
  }

  fun checkSlowNotificationHeuristics() {
    checkSubject.onNext(Unit)
  }

  private fun checkHeuristics(): Single<State> {
    return Single.fromCallable {
      // COLLIE: a missing push app, and late messages on a restricted battery, are shown by the status line and the
      // problem banner on Chats (main/setup). Only the phone-specific guide below still opens on its own.

      val deviceSpecificCondition = SlowNotificationHeuristics.getDeviceSpecificShowCondition()

      val shouldShowDeviceSpecificDialog = SlowNotificationHeuristics.shouldShowDeviceSpecificDialog()
      val havingDelayedNotifications = SlowNotificationHeuristics.isHavingDelayedNotifications()

      if (deviceSpecificCondition == ShowCondition.ALWAYS && shouldShowDeviceSpecificDialog) {
        return@fromCallable State.PROMPT_SPECIFIC_BATTERY_SAVER_DIALOG
      }

      if (deviceSpecificCondition == ShowCondition.HAS_BATTERY_OPTIMIZATION_ON && shouldShowDeviceSpecificDialog && SlowNotificationHeuristics.isBatteryOptimizationsOn()) {
        return@fromCallable State.PROMPT_SPECIFIC_BATTERY_SAVER_DIALOG
      }

      if (deviceSpecificCondition == ShowCondition.HAS_SLOW_NOTIFICATIONS && shouldShowDeviceSpecificDialog && havingDelayedNotifications) {
        return@fromCallable State.PROMPT_SPECIFIC_BATTERY_SAVER_DIALOG
      }

      return@fromCallable State.NONE
    }.subscribeOn(Schedulers.io())
  }

  enum class State {
    NONE,
    PROMPT_UNIFIEDPUSH_SELECT_DISTRIBUTOR_DIALOG,
    PROMPT_SPECIFIC_BATTERY_SAVER_DIALOG,
    PROMPT_GENERAL_BATTERY_SAVER_DIALOG,
  }
}
