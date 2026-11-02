/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.components.settings.app.updates

import android.app.Application
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.appsettings.updates.AppUpdatesSettingsAction
import org.signal.appsettings.updates.AppUpdatesSettingsEvent
import org.signal.appsettings.updates.AppUpdatesSettingsState
import im.molly.app.base.ApkInfo
import org.thoughtcrime.securesms.apkupdate.ApkUpdateNotifications
import org.thoughtcrime.securesms.apkupdate.ApkUpdateRefreshListener
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.jobs.ApkUpdateJob
import org.thoughtcrime.securesms.keyvalue.ApkUpdateValues
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.testing.CoroutineDispatcherRule
import org.thoughtcrime.securesms.testutil.MockAppDependenciesRule
import org.thoughtcrime.securesms.util.TextSecurePreferences
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class AppUpdatesSettingsViewModelTest {

  companion object {
    private const val LAST_SUCCESSFUL_CHECK = 1_700_000_000_000L
  }

  private val testDispatcher = UnconfinedTestDispatcher()

  @get:Rule
  val dispatcherRule = CoroutineDispatcherRule(testDispatcher)

  @get:Rule
  val appDependencies = MockAppDependenciesRule()

  private val apkUpdateValues = mockk<ApkUpdateValues>(relaxUnitFun = true)
  private var updatesEnabled = false
  private var includeBeta = false

  @Before
  fun setUp() {
    Dispatchers.setMain(testDispatcher)

    mockkObject(SignalStore)
    every { SignalStore.apkUpdate } returns apkUpdateValues
    every { apkUpdateValues.lastSuccessfulCheck } returns LAST_SUCCESSFUL_CHECK
    mockkStatic(TextSecurePreferences::class, ApkUpdateRefreshListener::class)
    mockkObject(ApkUpdateNotifications)
    every { TextSecurePreferences.isUpdateApkEnabled(any()) } answers { updatesEnabled }
    every { TextSecurePreferences.setUpdateApkEnabled(any(), any()) } answers { updatesEnabled = secondArg() }
    every { TextSecurePreferences.isUpdateApkIncludeBetaEnabled(any()) } answers { includeBeta }
    every { TextSecurePreferences.setUpdateApkIncludeBetaEnabled(any(), any()) } answers { includeBeta = secondArg() }
    every { ApkUpdateRefreshListener.scheduleIfAllowed(any()) } just Runs
    every { ApkUpdateNotifications.dismissInstallPrompt(any()) } just Runs
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
    unmockkAll()
  }

  @Test
  fun `initial state reflects storage`() = runTest(testDispatcher) {
    updatesEnabled = true

    val viewModel = createViewModel()

    assertThat(viewModel.state.value).isEqualTo(
      AppUpdatesSettingsState(
        lastCheckedTime = LAST_SUCCESSFUL_CHECK.milliseconds,
        autoUpdateEnabled = true,
        isAutoUpdateSupported = true,
        versionName = ApkInfo.versionName
      )
    )
  }

  @Test
  fun `initial state reports an externally managed build`() = runTest(testDispatcher) {
    val viewModel = createViewModel(isAutoUpdateSupported = false)

    assertThat(viewModel.state.value.isAutoUpdateSupported).isFalse()
  }

  @Test
  fun `initial state reports never having checked`() = runTest(testDispatcher) {
    every { apkUpdateValues.lastSuccessfulCheck } returns 0L

    val viewModel = createViewModel()

    assertThat(viewModel.state.value.lastCheckedTime).isEqualTo(Duration.ZERO)
  }

  @Test
  fun `ScreenResumed picks up a check that finished while the screen was away`() = runTest(testDispatcher) {
    val viewModel = createViewModel()
    every { apkUpdateValues.lastSuccessfulCheck } returns LAST_SUCCESSFUL_CHECK + 1000

    viewModel.onEvent(AppUpdatesSettingsEvent.ScreenResumed)

    assertThat(viewModel.state.value.lastCheckedTime).isEqualTo((LAST_SUCCESSFUL_CHECK + 1000).milliseconds)
  }

  @Test
  fun `AutoUpdateToggled on saves and shows the new value`() = runTest(testDispatcher) {
    val viewModel = createViewModel()

    viewModel.onEvent(AppUpdatesSettingsEvent.AutoUpdateToggled(true))

    verify { TextSecurePreferences.setUpdateApkEnabled(any(), true) }
    assertThat(viewModel.state.value.autoUpdateEnabled).isTrue()
  }

  @Test
  fun `AutoUpdateToggled off saves and shows the new value`() = runTest(testDispatcher) {
    updatesEnabled = true
    val viewModel = createViewModel()

    viewModel.onEvent(AppUpdatesSettingsEvent.AutoUpdateToggled(false))

    verify { TextSecurePreferences.setUpdateApkEnabled(any(), false) }
    assertThat(viewModel.state.value.autoUpdateEnabled).isFalse()
  }

  @Test
  fun `CheckForUpdatesClicked enqueues an update check`() = runTest(testDispatcher) {
    updatesEnabled = true
    val viewModel = createViewModel()
    val actions = collectActions(viewModel.actions)

    viewModel.onEvent(AppUpdatesSettingsEvent.CheckForUpdatesClicked)

    verify(exactly = 1) { AppDependencies.jobManager.add(ofType<ApkUpdateJob>()) }
    assertThat(actions).isEmpty()
  }

  @Test
  fun `beta selection clears an old install prompt and checks the selected channel`() = runTest(testDispatcher) {
    val viewModel = createViewModel()

    viewModel.onEvent(AppUpdatesSettingsEvent.IncludeBetaToggled(true))

    verify { TextSecurePreferences.setUpdateApkIncludeBetaEnabled(any(), true) }
    verify { ApkUpdateNotifications.dismissInstallPrompt(any()) }
    verify(exactly = 1) { AppDependencies.jobManager.add(ofType<ApkUpdateJob>()) }
    assertThat(viewModel.state.value.includeBetaEnabled).isTrue()
  }

  @Test
  fun `externally managed builds ignore update actions`() = runTest(testDispatcher) {
    val viewModel = createViewModel(isAutoUpdateSupported = false)

    viewModel.onEvent(AppUpdatesSettingsEvent.AutoUpdateToggled(true))
    viewModel.onEvent(AppUpdatesSettingsEvent.IncludeBetaToggled(true))
    viewModel.onEvent(AppUpdatesSettingsEvent.CheckForUpdatesClicked)

    verify(exactly = 0) { TextSecurePreferences.setUpdateApkEnabled(any(), any()) }
    verify(exactly = 0) { TextSecurePreferences.setUpdateApkIncludeBetaEnabled(any(), any()) }
    verify(exactly = 0) { AppDependencies.jobManager.add(ofType<ApkUpdateJob>()) }
  }

  @Test
  fun `manual checks do not bypass disabled update checks`() = runTest(testDispatcher) {
    val viewModel = createViewModel()

    viewModel.onEvent(AppUpdatesSettingsEvent.CheckForUpdatesClicked)

    verify(exactly = 0) { AppDependencies.jobManager.add(ofType<ApkUpdateJob>()) }
  }

  @Test
  fun `NavigateBackClicked navigates back`() = runTest(testDispatcher) {
    val viewModel = createViewModel()
    val actions = collectActions(viewModel.actions)

    viewModel.onEvent(AppUpdatesSettingsEvent.NavigateBackClicked)

    assertThat(actions).containsExactly(AppUpdatesSettingsAction.NavigateBack)
  }

  private fun createViewModel(isAutoUpdateSupported: Boolean = true): AppUpdatesSettingsViewModel {
    return AppUpdatesSettingsViewModel(isAutoUpdateSupported = isAutoUpdateSupported)
  }

  private fun TestScope.collectActions(actions: Flow<AppUpdatesSettingsAction>): List<AppUpdatesSettingsAction> {
    val collected = mutableListOf<AppUpdatesSettingsAction>()
    backgroundScope.launch { actions.toList(collected) }
    return collected
  }
}
