package org.thoughtcrime.securesms.components.settings.app.appearance

import androidx.compose.runtime.Composable
import androidx.navigation.fragment.findNavController
import org.signal.appsettings.routes.AppSettingsRoute
import org.signal.core.ui.compose.ComposeFragment
<<<<<<< MOLLY
import org.signal.core.ui.compose.DayNightPreviews
import org.signal.core.ui.compose.Previews
import org.signal.core.ui.compose.Rows
import org.signal.core.ui.compose.Scaffolds
import org.signal.core.ui.compose.SignalIcons
import org.signal.core.ui.compose.Texts
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.components.settings.app.appearance.navbar.ChooseNavigationBarStyleFragment
import org.thoughtcrime.securesms.compose.rememberStatusBarColorNestedScrollModifier
import org.thoughtcrime.securesms.keyvalue.SettingsValues
import org.thoughtcrime.securesms.util.DynamicTheme
=======
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.components.settings.app.appSettingsRouter
>>>>>>> SIGNAL
import org.thoughtcrime.securesms.util.navigation.safeNavigate

/**
 * Fragment host for [AppearanceSettingsEntry] until app settings moves to Nav3. Only translates routes into the nav graph.
 */
class AppearanceSettingsFragment : ComposeFragment() {

  private val appSettingsRouter by appSettingsRouter(onNavigateToScreen = ::navigateToScreen)

  private fun navigateToScreen(route: AppSettingsRoute) {
    when (route) {
      AppSettingsRoute.AppearanceRoute.Wallpaper -> findNavController().safeNavigate(R.id.action_appearanceSettings_to_wallpaperActivity)
      AppSettingsRoute.AppearanceRoute.AppIconSelection -> findNavController().safeNavigate(R.id.action_appearanceSettings_to_appIconActivity)
      else -> error("Unexpected route: ${route.javaClass.name}")
    }
  }

  @Composable
  override fun FragmentContent() {
<<<<<<< MOLLY
    val callbacks = remember { Callbacks() }
    val state by viewModel.state.collectAsStateWithLifecycle()

    AppearanceSettingsScreen(
      state = state,
      callbacks = callbacks
    )
  }

  private inner class Callbacks : AppearanceSettingsCallbacks {
    override fun onNavigationClick() {
      requireActivity().onBackPressedDispatcher.onBackPressed()
    }

    override fun onLanguageSelected(selection: String) {
      MaterialAlertDialogBuilder(requireContext())
        .setMessage(R.string.preferences_language_change_confirmation_message)
        .setPositiveButton(android.R.string.ok) { _, _ -> viewModel.setLanguage(selection) }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
    }

    override fun onThemeSelected(selection: String, useDynamicColors: Boolean) {
      viewModel.setTheme(activity, SettingsValues.Theme.deserialize(selection), useDynamicColors)
    }

    override fun onChatColorAndWallpaperClick() {
      findNavController().safeNavigate(R.id.action_appearanceSettings_to_wallpaperActivity)
    }

    override fun onAppIconClick() {
      findNavController().safeNavigate(R.id.action_appearanceSettings_to_appIconActivity)
    }

    override fun onMessageFontSizeSelected(selection: String) {
      viewModel.setMessageFontSize(selection.toInt())
    }

    override fun onNavigationBarSizeClick() {
      ChooseNavigationBarStyleFragment().show(childFragmentManager, null)
    }
  }
}

interface AppearanceSettingsCallbacks {
  fun onNavigationClick() = Unit
  fun onLanguageSelected(selection: String) = Unit
  fun onThemeSelected(selection: String, useDynamicColors: Boolean) = Unit
  fun onChatColorAndWallpaperClick() = Unit
  fun onAppIconClick() = Unit
  fun onMessageFontSizeSelected(selection: String) = Unit
  fun onNavigationBarSizeClick() = Unit

  object Empty : AppearanceSettingsCallbacks
}

@Composable
private fun AppearanceSettingsScreen(
  state: AppearanceSettingsState,
  callbacks: AppearanceSettingsCallbacks
) {
  Scaffolds.Settings(
    title = stringResource(R.string.preferences__appearance),
    onNavigationClick = callbacks::onNavigationClick,
    navigationIcon = SignalIcons.ArrowStart.imageVector
  ) { paddingValues ->
    LazyColumn(
      modifier = Modifier
        .padding(paddingValues)
        .then(rememberStatusBarColorNestedScrollModifier())
    ) {
      item {
        Rows.RadioListRow(
          text = stringResource(R.string.preferences__language),
          labels = stringArrayResource(R.array.language_entries),
          values = stringArrayResource(R.array.language_values),
          selectedValue = state.language,
          onSelected = callbacks::onLanguageSelected
        )
      }

      item {
        Rows.RadioListRow(
          text = stringResource(R.string.preferences__theme),
          labels = stringArrayResource(R.array.pref_theme_entries),
          values = stringArrayResource(R.array.pref_theme_values),
          selectedValue = state.theme.serialize(),
          onSelected = { callbacks.onThemeSelected(it, state.dynamicColors) },
        )
      }

      if (DynamicTheme.isDynamicColorsAvailable()) {
        item {
          Rows.ToggleRow(
            text = stringResource(R.string.preferences_appearance__dynamic_colors),
            label = stringResource(R.string.preferences_appearance__use_system_colors_for_the_app_theme),
            checked = state.dynamicColors,
            onCheckChanged = { callbacks.onThemeSelected(state.theme.serialize(), it) },
          )
        }
      }

      item {
        Rows.TextRow(
          text = stringResource(R.string.preferences__chat_color_and_wallpaper),
          onClick = callbacks::onChatColorAndWallpaperClick
        )
      }

      if (Build.VERSION.SDK_INT >= 26) {
        item {
          Rows.TextRow(
            text = stringResource(R.string.preferences__app_icon),
            onClick = callbacks::onAppIconClick
          )
        }
      }

      item {
        Rows.RadioListRow(
          text = stringResource(R.string.preferences_chats__message_text_size),
          labels = stringArrayResource(R.array.pref_message_font_size_entries),
          values = integerArrayResource(R.array.pref_message_font_size_values).map { it.toString() }.toTypedArray(),
          selectedValue = state.messageFontSize.toString(),
          onSelected = callbacks::onMessageFontSizeSelected
        )
      }

      item {
        Texts.SectionHeader(stringResource(R.string.preferences_appearance__navigation_bar))
      }

      item {
        val label = if (state.isCompactNavigationBar) {
          R.string.preferences_compact
        } else {
          R.string.preferences_normal
        }

        Rows.TextRow(
          text = stringResource(R.string.preferences_navigation_bar_size),
          label = stringResource(label),
          onClick = callbacks::onNavigationBarSizeClick
        )
      }
    }
  }
}

@DayNightPreviews
@Composable
private fun AppearanceSettingsScreenPreview() {
  Previews.Preview {
    AppearanceSettingsScreen(
      state = AppearanceSettingsState(
        theme = SettingsValues.Theme.SYSTEM,
        dynamicColors = true,
        messageFontSize = 0,
        language = "en-US",
        isCompactNavigationBar = false
      ),
      callbacks = AppearanceSettingsCallbacks.Empty
    )
=======
    AppearanceSettingsEntry(parentEventEmitter = appSettingsRouter::onEvent)
>>>>>>> SIGNAL
  }
}
