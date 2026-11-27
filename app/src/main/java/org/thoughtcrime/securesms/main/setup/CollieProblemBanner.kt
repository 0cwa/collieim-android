/*
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.main.setup

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import org.thoughtcrime.securesms.banner.Banner

/**
 * The one banner above the chats for problems that stop messages arriving on time, and for release notices that need
 * the person to act. It replaces the battery reminder; setup steps never show here.
 */
class CollieProblemBanner(private val activity: FragmentActivity) : Banner<Problem>() {

  override val enabled: Boolean
    get() = SetupStatusRepository.snapshot.value.bannerProblem != null

  override val dataFlow: Flow<Problem>
    get() = SetupStatusRepository.snapshot.map { it.bannerProblem }.filterNotNull().distinctUntilChanged()

  override val stateUpdates: Flow<Unit>
    get() = SetupStatusRepository.snapshot.map { it.bannerProblem }.distinctUntilChanged().map { }

  @Composable
  override fun DisplayBanner(model: Problem, contentPadding: PaddingValues) {
    ProblemBanner(
      problem = model,
      onFix = { SetupActions.fixProblem(activity, model) },
      onDismiss = { SetupStatusRepository.snoozeBanner(model) },
      contentPadding = contentPadding
    )
  }
}
