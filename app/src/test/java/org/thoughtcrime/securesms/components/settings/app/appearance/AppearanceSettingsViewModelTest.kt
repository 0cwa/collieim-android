/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.components.settings.app.appearance

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockkObject
import io.mockk.unmockkObject
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
import org.signal.appsettings.appearance.AppearanceSettingsAction
import org.signal.appsettings.appearance.AppearanceSettingsEvent
import org.signal.appsettings.appearance.AppearanceSettingsState
import org.signal.appsettings.appearance.AppearanceSettingsState.Dialog
import org.signal.appsettings.appearance.AppearanceSettingsState.Theme
import org.signal.appsettings.routes.AppSettingsNavigationEvent
import org.signal.appsettings.routes.AppSettingsRoute
import org.thoughtcrime.securesms.testing.CoroutineDispatcherRule

@OptIn(ExperimentalCoroutinesApi::class)
class AppearanceSettingsViewModelTest {

  private val testDispatcher = UnconfinedTestDispatcher()

  @get:Rule
  val dispatcherRule = CoroutineDispatcherRule(testDispatcher)

  private val navigationEvents = mutableListOf<AppSettingsNavigationEvent>()

  @Before
  fun setUp() {
    Dispatchers.setMain(testDispatcher)
    mockkObject(AppearanceSettingsRepository)

    every { AppearanceSettingsRepository.getLanguage() } returns "zz"
    every { AppearanceSettingsRepository.getTheme() } returns Theme.SYSTEM
    every { AppearanceSettingsRepository.isDynamicColorsEnabled() } returns false
    every { AppearanceSettingsRepository.getMessageFontSize() } returns 16
    every { AppearanceSettingsRepository.isCompactNavigationBar() } returns false
    every { AppearanceSettingsRepository.isSystemThemeAvailable() } returns true
    every { AppearanceSettingsRepository.isDynamicColorsAvailable() } returns true
    every { AppearanceSettingsRepository.isAppIconSupported() } returns true
    every { AppearanceSettingsRepository.getPalette() } returns "collie"
    every { AppearanceSettingsRepository.getAvailablePalettes() } returns ALL_PALETTES
    every { AppearanceSettingsRepository.isPureBlackEnabled() } returns false
    every { AppearanceSettingsRepository.setPalette(any()) } just Runs
    every { AppearanceSettingsRepository.setPureBlack(any()) } just Runs
    every { AppearanceSettingsRepository.applyLanguage(any()) } just Runs
    every { AppearanceSettingsRepository.setAppearance(any(), any()) } just Runs
    every { AppearanceSettingsRepository.setMessageFontSize(any()) } just Runs
    every { AppearanceSettingsRepository.setCompactNavigationBar(any()) } just Runs
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
    unmockkObject(AppearanceSettingsRepository)
  }

  @Test
  fun `initial state is read out of the repository`() = runTest(testDispatcher) {
    every { AppearanceSettingsRepository.getLanguage() } returns "de"
    every { AppearanceSettingsRepository.getTheme() } returns Theme.DARK
    every { AppearanceSettingsRepository.isDynamicColorsEnabled() } returns true
    every { AppearanceSettingsRepository.getMessageFontSize() } returns 22
    every { AppearanceSettingsRepository.isCompactNavigationBar() } returns true
    every { AppearanceSettingsRepository.isSystemThemeAvailable() } returns false
    every { AppearanceSettingsRepository.isDynamicColorsAvailable() } returns false
    every { AppearanceSettingsRepository.isAppIconSupported() } returns false
    every { AppearanceSettingsRepository.getPalette() } returns "slate_neutral"
    every { AppearanceSettingsRepository.getAvailablePalettes() } returns ALL_PALETTES - "system"
    every { AppearanceSettingsRepository.isPureBlackEnabled() } returns true

    val viewModel = createViewModel()

    assertThat(viewModel.state.value).isEqualTo(
      AppearanceSettingsState(
        language = "de",
        theme = Theme.DARK,
        palette = "slate_neutral",
        availablePalettes = ALL_PALETTES - "system",
        pureBlack = true,
        messageFontSize = 22,
        isCompactNavigationBar = true,
        isSystemThemeAvailable = false,
        isAppIconSupported = false
      )
    )
  }

  @Test
  fun `NavigateBackClicked navigates back`() = runTest(testDispatcher) {
    val viewModel = createViewModel()

    viewModel.onEvent(AppearanceSettingsEvent.NavigateBackClicked)

    assertThat(navigationEvents).containsExactly(AppSettingsNavigationEvent.NavigateBack())
  }

  @Test
  fun `LanguageSelected asks for confirmation before applying it`() = runTest(testDispatcher) {
    val viewModel = createViewModel()

    viewModel.onEvent(AppearanceSettingsEvent.LanguageSelected("de"))

    assertThat(viewModel.state.value.dialog).isEqualTo(Dialog.ConfirmLanguageChange("de"))
    assertThat(viewModel.state.value.language).isEqualTo("zz")
    verify(exactly = 0) { AppearanceSettingsRepository.applyLanguage(any()) }
  }

  @Test
  fun `LanguageChangeConfirmed applies the selected language`() = runTest(testDispatcher) {
    val viewModel = createViewModel()

    viewModel.onEvent(AppearanceSettingsEvent.LanguageSelected("de"))
    viewModel.onEvent(AppearanceSettingsEvent.LanguageChangeConfirmed)

    verify { AppearanceSettingsRepository.applyLanguage("de") }
    assertThat(viewModel.state.value.language).isEqualTo("de")
    assertThat(viewModel.state.value.dialog).isEqualTo(Dialog.None)
  }

  @Test
  fun `LanguageChangeConfirmed does nothing without a pending language change`() = runTest(testDispatcher) {
    val viewModel = createViewModel()

    viewModel.onEvent(AppearanceSettingsEvent.LanguageChangeConfirmed)

    verify(exactly = 0) { AppearanceSettingsRepository.applyLanguage(any()) }
  }

  @Test
  fun `DialogDismissed cancels the language change`() = runTest(testDispatcher) {
    val viewModel = createViewModel()

    viewModel.onEvent(AppearanceSettingsEvent.LanguageSelected("de"))
    viewModel.onEvent(AppearanceSettingsEvent.DialogDismissed)

    assertThat(viewModel.state.value.dialog).isEqualTo(Dialog.None)
    verify(exactly = 0) { AppearanceSettingsRepository.applyLanguage(any()) }
  }

  @Test
  fun `ThemeSelected stores the theme and updates the splash screen`() = runTest(testDispatcher) {
    val viewModel = createViewModel()
    val actions = collectActions(viewModel.actions)

    viewModel.onEvent(AppearanceSettingsEvent.ThemeSelected(Theme.DARK))

    verify { AppearanceSettingsRepository.setAppearance(Theme.DARK, false) }
    assertThat(viewModel.state.value.theme).isEqualTo(Theme.DARK)
    assertThat(actions).isEqualTo(listOf(AppearanceSettingsAction.ApplySplashScreenTheme(Theme.DARK, false)))
  }

  @Test
  fun `ThemeSelected preserves dynamic colors`() = runTest(testDispatcher) {
    every { AppearanceSettingsRepository.isDynamicColorsEnabled() } returns true
    every { AppearanceSettingsRepository.getPalette() } returns "system"
    val viewModel = createViewModel()

    viewModel.onEvent(AppearanceSettingsEvent.ThemeSelected(Theme.DARK))

    verify { AppearanceSettingsRepository.setAppearance(Theme.DARK, true) }
    assertThat(viewModel.state.value.theme).isEqualTo(Theme.DARK)
    assertThat(viewModel.state.value.dynamicColors).isTrue()
  }

  @Test
  fun `ThemeSelected keeps a System flag that this device cannot show`() = runTest(testDispatcher) {
    every { AppearanceSettingsRepository.isDynamicColorsEnabled() } returns true
    every { AppearanceSettingsRepository.getAvailablePalettes() } returns ALL_PALETTES - "system"
    val viewModel = createViewModel()

    viewModel.onEvent(AppearanceSettingsEvent.ThemeSelected(Theme.LIGHT))

    verify { AppearanceSettingsRepository.setAppearance(Theme.LIGHT, true) }
    verify(exactly = 0) { AppearanceSettingsRepository.setPalette(any()) }
    assertThat(viewModel.state.value.palette).isEqualTo("collie")
  }

  @Test
  fun `PaletteSelected stores a built-in palette and updates the splash screen`() = runTest(testDispatcher) {
    every { AppearanceSettingsRepository.getTheme() } returns Theme.DARK
    val viewModel = createViewModel()
    val actions = collectActions(viewModel.actions)

    viewModel.onEvent(AppearanceSettingsEvent.PaletteSelected("plum_expressive"))

    verify(exactly = 1) { AppearanceSettingsRepository.setPalette("plum_expressive") }
    verify(exactly = 0) { AppearanceSettingsRepository.setAppearance(any(), any()) }
    assertThat(viewModel.state.value.palette).isEqualTo("plum_expressive")
    assertThat(viewModel.state.value.theme).isEqualTo(Theme.DARK)
    assertThat(actions).isEqualTo(listOf(AppearanceSettingsAction.ApplySplashScreenTheme(Theme.DARK, false)))
  }

  @Test
  fun `PaletteSelected switches between every offered choice in turn`() = runTest(testDispatcher) {
    val viewModel = createViewModel()
    val actions = collectActions(viewModel.actions)
    val order = listOf("blue_tonal_spot", "teal_vibrant", "system", "plum_expressive", "slate_neutral", "collie")

    order.forEach { viewModel.onEvent(AppearanceSettingsEvent.PaletteSelected(it)) }

    order.forEach { verify(exactly = 1) { AppearanceSettingsRepository.setPalette(it) } }
    assertThat(viewModel.state.value.palette).isEqualTo("collie")
    assertThat(actions.map { (it as AppearanceSettingsAction.ApplySplashScreenTheme).dynamicColors })
      .isEqualTo(listOf(false, false, true, false, false, false))
  }

  @Test
  fun `PaletteSelected ignores the current choice and choices this device does not offer`() = runTest(testDispatcher) {
    every { AppearanceSettingsRepository.getAvailablePalettes() } returns ALL_PALETTES - "system"
    val viewModel = createViewModel()
    val actions = collectActions(viewModel.actions)

    viewModel.onEvent(AppearanceSettingsEvent.PaletteSelected("collie"))
    viewModel.onEvent(AppearanceSettingsEvent.PaletteSelected("system"))
    viewModel.onEvent(AppearanceSettingsEvent.PaletteSelected("unknown"))

    verify(exactly = 0) { AppearanceSettingsRepository.setPalette(any()) }
    assertThat(viewModel.state.value.palette).isEqualTo("collie")
    assertThat(actions).isEmpty()
  }

  @Test
  fun `PureBlackChanged stores the flag without touching the theme or palette`() = runTest(testDispatcher) {
    every { AppearanceSettingsRepository.getPalette() } returns "teal_vibrant"
    val viewModel = createViewModel()
    val actions = collectActions(viewModel.actions)

    viewModel.onEvent(AppearanceSettingsEvent.PureBlackChanged(true))
    viewModel.onEvent(AppearanceSettingsEvent.PureBlackChanged(true))

    verify(exactly = 1) { AppearanceSettingsRepository.setPureBlack(true) }
    verify(exactly = 0) { AppearanceSettingsRepository.setPalette(any()) }
    verify(exactly = 0) { AppearanceSettingsRepository.setAppearance(any(), any()) }
    assertThat(viewModel.state.value.pureBlack).isTrue()
    assertThat(viewModel.state.value.palette).isEqualTo("teal_vibrant")
    assertThat(actions).isEmpty()
  }

  @Test
  fun `MessageFontSizeSelected stores the font size`() = runTest(testDispatcher) {
    val viewModel = createViewModel()
    val actions = collectActions(viewModel.actions)

    viewModel.onEvent(AppearanceSettingsEvent.MessageFontSizeSelected(28))

    verify { AppearanceSettingsRepository.setMessageFontSize(28) }
    assertThat(viewModel.state.value.messageFontSize).isEqualTo(28)
    assertThat(actions).isEmpty()
  }

  @Test
  fun `ChatColorAndWallpaperClicked opens chat color and wallpaper`() = runTest(testDispatcher) {
    val viewModel = createViewModel()

    viewModel.onEvent(AppearanceSettingsEvent.ChatColorAndWallpaperClicked)

    assertThat(navigationEvents).containsExactly(AppSettingsNavigationEvent.NavigateToScreen(AppSettingsRoute.AppearanceRoute.Wallpaper))
  }

  @Test
  fun `AppIconClicked opens the app icon picker`() = runTest(testDispatcher) {
    val viewModel = createViewModel()

    viewModel.onEvent(AppearanceSettingsEvent.AppIconClicked)

    assertThat(navigationEvents).containsExactly(AppSettingsNavigationEvent.NavigateToScreen(AppSettingsRoute.AppearanceRoute.AppIconSelection))
  }

  @Test
  fun `NavigationBarSizeClicked opens the picker on the current size`() = runTest(testDispatcher) {
    every { AppearanceSettingsRepository.isCompactNavigationBar() } returns true
    val viewModel = createViewModel()

    viewModel.onEvent(AppearanceSettingsEvent.NavigationBarSizeClicked)

    assertThat(viewModel.state.value.dialog).isEqualTo(Dialog.NavigationBarSize(isCompact = true))
  }

  @Test
  fun `NavigationBarSizeSelected changes the choice without saving it`() = runTest(testDispatcher) {
    val viewModel = createViewModel()

    viewModel.onEvent(AppearanceSettingsEvent.NavigationBarSizeClicked)
    viewModel.onEvent(AppearanceSettingsEvent.NavigationBarSizeSelected(isCompact = true))

    assertThat(viewModel.state.value.dialog).isEqualTo(Dialog.NavigationBarSize(isCompact = true))
    assertThat(viewModel.state.value.isCompactNavigationBar).isFalse()
    verify(exactly = 0) { AppearanceSettingsRepository.setCompactNavigationBar(any()) }
  }

  @Test
  fun `NavigationBarSizeConfirmed saves the choice and closes the picker`() = runTest(testDispatcher) {
    val viewModel = createViewModel()

    viewModel.onEvent(AppearanceSettingsEvent.NavigationBarSizeClicked)
    viewModel.onEvent(AppearanceSettingsEvent.NavigationBarSizeSelected(isCompact = true))
    viewModel.onEvent(AppearanceSettingsEvent.NavigationBarSizeConfirmed)

    verify { AppearanceSettingsRepository.setCompactNavigationBar(true) }
    assertThat(viewModel.state.value.isCompactNavigationBar).isTrue()
    assertThat(viewModel.state.value.dialog).isEqualTo(Dialog.None)
  }

  @Test
  fun `NavigationBarSizeConfirmed is ignored without the picker`() = runTest(testDispatcher) {
    val viewModel = createViewModel()

    viewModel.onEvent(AppearanceSettingsEvent.NavigationBarSizeConfirmed)

    verify(exactly = 0) { AppearanceSettingsRepository.setCompactNavigationBar(any()) }
  }

  companion object {
    private val ALL_PALETTES = listOf("collie", "system", "blue_tonal_spot", "teal_vibrant", "plum_expressive", "slate_neutral")
  }

  private fun createViewModel(): AppearanceSettingsViewModel {
    return AppearanceSettingsViewModel(parentEventEmitter = { navigationEvents += it })
  }

  private fun TestScope.collectActions(actions: Flow<AppearanceSettingsAction>): List<AppearanceSettingsAction> {
    val collected = mutableListOf<AppearanceSettingsAction>()
    backgroundScope.launch { actions.toList(collected) }
    return collected
  }
}
