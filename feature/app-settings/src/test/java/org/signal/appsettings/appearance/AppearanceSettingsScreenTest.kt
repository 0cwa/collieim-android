/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.appsettings.appearance

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import assertk.assertions.isLessThanOrEqualTo
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.ui.compose.Dialogs

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AppearanceSettingsScreenTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  private val events = mutableListOf<AppearanceSettingsEvent>()

  @Test
  fun `clicking the navigation icon emits NavigateBackClicked`() {
    setContent(AppearanceSettingsState())

    composeTestRule.onNodeWithContentDescription("Navigate up").performClick()

    assertThat(events).containsExactly(AppearanceSettingsEvent.NavigateBackClicked)
  }

  @Test
  fun `picking a language emits LanguageSelected`() {
    setContent(AppearanceSettingsState(language = "zz"))

    composeTestRule.onNodeWithTag(AppearanceSettingsTestTags.ROW_LANGUAGE).performClick()
    composeTestRule.onNodeWithText("English").performClick()

    assertThat(events).contains(AppearanceSettingsEvent.LanguageSelected("en"))
  }

  @Test
  fun `picking a theme emits ThemeSelected`() {
    setContent(AppearanceSettingsState(theme = AppearanceSettingsState.Theme.SYSTEM))

    composeTestRule.onNodeWithTag(AppearanceSettingsTestTags.ROW_THEME).performClick()
    composeTestRule.onNodeWithText("Dark").performClick()

    assertThat(events).contains(AppearanceSettingsEvent.ThemeSelected(AppearanceSettingsState.Theme.DARK))
  }

  @Test
  fun `the system theme is not offered when the device does not support it`() {
    setContent(AppearanceSettingsState(language = "en", theme = AppearanceSettingsState.Theme.LIGHT, isSystemThemeAvailable = false))

    composeTestRule.onNodeWithTag(AppearanceSettingsTestTags.ROW_THEME).performClick()

    composeTestRule.onNodeWithText("Dark").assertIsDisplayed()
    composeTestRule.onNodeWithText("System default").assertDoesNotExist()
  }

  @Test
  fun `the color picker names every choice and marks only the selected one`() {
    setContent(AppearanceSettingsState(palette = "plum_expressive", availablePalettes = ALL_PALETTES))

    scrollTo(AppearanceSettingsTestTags.ROW_COLOR)
    for ((id, name) in ALL_PALETTES.zip(listOf("Collie", "System", "Blue", "Teal", "Plum", "Slate"))) {
      val option = composeTestRule.onNodeWithTag(AppearanceSettingsTestTags.paletteOption(id))
      option.assertTextEquals(name)
      option.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
      if (id == "plum_expressive") option.assertIsSelected() else option.assertIsNotSelected()
    }
    // The selected name is also spelled out under the title, so the choice never depends on color alone.
    composeTestRule.onAllNodesWithText("Plum").assertCountEquals(2)
  }

  @Test
  fun `picking a color emits PaletteSelected`() {
    setContent(AppearanceSettingsState(availablePalettes = ALL_PALETTES))

    scrollTo(AppearanceSettingsTestTags.ROW_COLOR)
    composeTestRule.onNodeWithTag(AppearanceSettingsTestTags.paletteOption("teal_vibrant")).performClick()
    composeTestRule.onNodeWithTag(AppearanceSettingsTestTags.paletteOption("system")).performClick()

    assertThat(events).containsExactly(
      AppearanceSettingsEvent.PaletteSelected("teal_vibrant"),
      AppearanceSettingsEvent.PaletteSelected("system")
    )
  }

  @Test
  fun `the System color is not offered when the device does not support it`() {
    setContent(AppearanceSettingsState(availablePalettes = ALL_PALETTES - "system"))

    scrollTo(AppearanceSettingsTestTags.ROW_COLOR)
    composeTestRule.onNodeWithTag(AppearanceSettingsTestTags.paletteOption("collie")).assertIsSelected()
    composeTestRule.onNodeWithTag(AppearanceSettingsTestTags.paletteOption("system")).assertDoesNotExist()
  }

  @Test
  fun `the color picker wraps its full names at a large font size`() {
    setContent(AppearanceSettingsState(availablePalettes = ALL_PALETTES), fontScale = 2f)

    val picker = composeTestRule.onNodeWithTag(AppearanceSettingsTestTags.ROW_COLOR).fetchSemanticsNode()
    val root = composeTestRule.onRoot().fetchSemanticsNode()
    assertThat(picker.boundsInRoot.right).isLessThanOrEqualTo(root.boundsInRoot.right)
    for ((id, name) in ALL_PALETTES.zip(listOf("Collie", "System", "Blue", "Teal", "Plum", "Slate"))) {
      scrollTo(AppearanceSettingsTestTags.paletteOption(id))
      composeTestRule.onNodeWithTag(AppearanceSettingsTestTags.paletteOption(id)).assertTextEquals(name).assertIsDisplayed()
      val option = composeTestRule.onNodeWithTag(AppearanceSettingsTestTags.paletteOption(id)).fetchSemanticsNode()
      assertThat(option.boundsInRoot.right).isLessThanOrEqualTo(root.boundsInRoot.right)
    }
  }

  @Test
  fun `toggling pure black emits PureBlackChanged`() {
    setContent(AppearanceSettingsState(pureBlack = false))

    scrollTo(AppearanceSettingsTestTags.ROW_PURE_BLACK)
    composeTestRule.onNodeWithTag(AppearanceSettingsTestTags.ROW_PURE_BLACK).performClick()

    assertThat(events).containsExactly(AppearanceSettingsEvent.PureBlackChanged(enabled = true))
  }

  @Test
  fun `clicking chat color and wallpaper emits ChatColorAndWallpaperClicked`() {
    setContent(AppearanceSettingsState())

    scrollTo(AppearanceSettingsTestTags.ROW_CHAT_COLOR_AND_WALLPAPER)
    composeTestRule.onNodeWithTag(AppearanceSettingsTestTags.ROW_CHAT_COLOR_AND_WALLPAPER).performClick()

    assertThat(events).containsExactly(AppearanceSettingsEvent.ChatColorAndWallpaperClicked)
  }

  @Test
  fun `clicking app icon emits AppIconClicked`() {
    setContent(AppearanceSettingsState(isAppIconSupported = true))

    scrollTo(AppearanceSettingsTestTags.ROW_APP_ICON)
    composeTestRule.onNodeWithTag(AppearanceSettingsTestTags.ROW_APP_ICON).performClick()

    assertThat(events).containsExactly(AppearanceSettingsEvent.AppIconClicked)
  }

  @Test
  fun `app icon row is hidden when the device does not support it`() {
    setContent(AppearanceSettingsState(isAppIconSupported = false))

    composeTestRule.onNodeWithTag(AppearanceSettingsTestTags.ROW_APP_ICON).assertDoesNotExist()
  }

  @Test
  fun `picking a message font size emits MessageFontSizeSelected`() {
    setContent(AppearanceSettingsState(messageFontSize = 16))

    scrollTo(AppearanceSettingsTestTags.ROW_MESSAGE_FONT_SIZE)
    composeTestRule.onNodeWithTag(AppearanceSettingsTestTags.ROW_MESSAGE_FONT_SIZE).performClick()
    composeTestRule.onNodeWithText("Extra large").performClick()

    assertThat(events).contains(AppearanceSettingsEvent.MessageFontSizeSelected(28))
  }

  @Test
  fun `navigation bar size row shows the current size`() {
    setContent(AppearanceSettingsState(isCompactNavigationBar = true))

    scrollTo(AppearanceSettingsTestTags.ROW_NAVIGATION_BAR_SIZE)
    composeTestRule.onNodeWithText("Compact").assertIsDisplayed()
  }

  @Test
  fun `clicking navigation bar size emits NavigationBarSizeClicked`() {
    setContent(AppearanceSettingsState())

    scrollTo(AppearanceSettingsTestTags.ROW_NAVIGATION_BAR_SIZE)
    composeTestRule.onNodeWithTag(AppearanceSettingsTestTags.ROW_NAVIGATION_BAR_SIZE).performClick()

    assertThat(events).containsExactly(AppearanceSettingsEvent.NavigationBarSizeClicked)
  }

  @Test
  fun `confirming the language change dialog emits LanguageChangeConfirmed`() {
    setContent(AppearanceSettingsState(dialog = AppearanceSettingsState.Dialog.ConfirmLanguageChange("de")))

    composeTestRule.onNodeWithTag(Dialogs.TEST_TAG_ALERT_DIALOG_CONFIRM_BUTTON).performClick()

    assertThat(events).containsExactly(AppearanceSettingsEvent.LanguageChangeConfirmed)
  }

  @Test
  fun `cancelling the language change dialog emits DialogDismissed`() {
    setContent(AppearanceSettingsState(dialog = AppearanceSettingsState.Dialog.ConfirmLanguageChange("de")))

    composeTestRule.onNodeWithTag(Dialogs.TEST_TAG_ALERT_DIALOG_DISMISS_BUTTON).performClick()

    assertThat(events).containsExactly(AppearanceSettingsEvent.DialogDismissed)
  }

  @Test
  fun `picking a size in the navigation bar size dialog emits NavigationBarSizeSelected`() {
    setContent(AppearanceSettingsState(dialog = AppearanceSettingsState.Dialog.NavigationBarSize(isCompact = false)))

    composeTestRule.onNodeWithTag(AppearanceSettingsTestTags.BUTTON_NAVIGATION_BAR_COMPACT).performClick()
    composeTestRule.onNodeWithTag(AppearanceSettingsTestTags.BUTTON_NAVIGATION_BAR_NORMAL).performClick()

    assertThat(events).containsExactly(
      AppearanceSettingsEvent.NavigationBarSizeSelected(isCompact = true),
      AppearanceSettingsEvent.NavigationBarSizeSelected(isCompact = false)
    )
  }

  @Test
  fun `the navigation bar size dialog marks the current choice`() {
    setContent(AppearanceSettingsState(dialog = AppearanceSettingsState.Dialog.NavigationBarSize(isCompact = true)))

    composeTestRule.onNodeWithTag(AppearanceSettingsTestTags.BUTTON_NAVIGATION_BAR_COMPACT).assertIsSelected()
    composeTestRule.onNodeWithTag(AppearanceSettingsTestTags.BUTTON_NAVIGATION_BAR_NORMAL).assertIsNotSelected()
  }

  @Test
  fun `confirming the navigation bar size dialog emits NavigationBarSizeConfirmed`() {
    setContent(AppearanceSettingsState(dialog = AppearanceSettingsState.Dialog.NavigationBarSize(isCompact = true)))

    composeTestRule.onNodeWithTag(AppearanceSettingsTestTags.BUTTON_NAVIGATION_BAR_CONFIRM).performClick()

    assertThat(events).containsExactly(AppearanceSettingsEvent.NavigationBarSizeConfirmed)
  }

  private fun setContent(state: AppearanceSettingsState, fontScale: Float? = null) {
    composeTestRule.setContent {
      val density = LocalDensity.current
      CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale ?: density.fontScale)) {
        AppearanceSettingsScreen(
          state = state,
          onEvent = { events += it }
        )
      }
    }
  }

  companion object {
    private val ALL_PALETTES = listOf("collie", "system", "blue_tonal_spot", "teal_vibrant", "plum_expressive", "slate_neutral")
  }

  private fun scrollTo(testTag: String) {
    composeTestRule.onNodeWithTag(AppearanceSettingsTestTags.SCROLLER)
      .performScrollToNode(hasTestTag(testTag))
  }
}
