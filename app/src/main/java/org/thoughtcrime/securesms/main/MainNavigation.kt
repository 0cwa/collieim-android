/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.main

<<<<<<< MOLLY
import androidx.annotation.RawRes
import androidx.annotation.StringRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Text
=======
import androidx.compose.foundation.layout.fillMaxSize
>>>>>>> SIGNAL
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
<<<<<<< MOLLY
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.graphics.BlendModeColorFilterCompat
import androidx.core.graphics.BlendModeCompat
import com.airbnb.lottie.LottieProperty
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.rememberLottieComposition
import com.airbnb.lottie.compose.rememberLottieDynamicProperties
import com.airbnb.lottie.compose.rememberLottieDynamicProperty
import org.signal.core.ui.compose.DayNightPreviews
import org.signal.core.ui.compose.Previews
import org.signal.core.ui.compose.theme.colorAttribute
import org.thoughtcrime.securesms.R

private val LOTTIE_SIZE = 28.dp

enum class MainNavigationListLocation(
  @StringRes val label: Int,
  @RawRes val icon: Int,
  @StringRes val contentDescription: Int = label
) {
  CHATS(
    label = R.string.ConversationListTabs__chats,
    icon = R.raw.chats_28
  ),
  ARCHIVE(
    label = R.string.ConversationListTabs__chats,
    icon = R.raw.chats_28
  ),
  CALLS(
    label = R.string.ConversationListTabs__calls,
    icon = R.raw.calls_28
  ),
  STORIES(
    label = R.string.ConversationListTabs__stories,
    icon = R.raw.stories_28
  );

  val isChatsTab: Boolean
    get() = this == CHATS || this == ARCHIVE
}

data class MainNavigationState(
  val chatsCount: Int = 0,
  val callsCount: Int = 0,
  val storiesCount: Int = 0,
  val storyFailure: Boolean = false,
  val isStoriesFeatureEnabled: Boolean = true,
  val currentListLocation: MainNavigationListLocation = MainNavigationListLocation.CHATS,
  val compact: Boolean = false
)
=======
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import org.signal.core.ui.compose.split.listEntry
import org.signal.core.ui.compose.split.rememberCurrentDecoratedNavEntries
import org.thoughtcrime.securesms.calls.CallsListPane
import org.thoughtcrime.securesms.calls.registerCallsTabDetailRoutes
import org.thoughtcrime.securesms.chats.ArchiveListPane
import org.thoughtcrime.securesms.chats.ChatsListPane
import org.thoughtcrime.securesms.chats.registerChatsTabDetailRoutes
import org.thoughtcrime.securesms.stories.StoriesListPane
import org.thoughtcrime.securesms.stories.registerStoriesTabDetailRoutes
>>>>>>> SIGNAL

/**
 * Builds our nav entries and decorates them so they can save state, handing back the entries of whichever
 * tab is displayed.
 *
 * A new *backstack* on [MainNavigationViewModel] is picked up here for free; a new *screen* needs a route
 * registering in [rememberMainNavEntryProvider].
 */
@Composable
<<<<<<< MOLLY
fun MainNavigationBar(
  state: MainNavigationState,
  onDestinationSelected: (MainNavigationListLocation) -> Unit
) {
  NavigationBar(
    containerColor = colorAttribute(R.attr.navbar_container_color),
    contentColor = MaterialTheme.colorScheme.onSurface,
    modifier = Modifier.height(if (state.compact) 48.dp else 80.dp),
    windowInsets = WindowInsets(0, 0, 0, 0)
  ) {
    val entries = remember(state.isStoriesFeatureEnabled) {
      if (state.isStoriesFeatureEnabled) {
        MainNavigationListLocation.entries.filterNot { it == MainNavigationListLocation.ARCHIVE }
      } else {
        MainNavigationListLocation.entries.filterNot { it == MainNavigationListLocation.STORIES || it == MainNavigationListLocation.ARCHIVE }
      }
    }

    entries.forEach { destination ->

      val badgeCount = when (destination) {
        MainNavigationListLocation.ARCHIVE -> error("Not supported")
        MainNavigationListLocation.CHATS -> state.chatsCount
        MainNavigationListLocation.CALLS -> state.callsCount
        MainNavigationListLocation.STORIES -> state.storiesCount
      }

      val selected = state.currentListLocation == destination
      NavigationBarItem(
        colors = NavigationBarItemDefaults.colors(
          indicatorColor = colorAttribute(R.attr.navbar_active_indicator_color),
          selectedTextColor = MaterialTheme.colorScheme.onSurface,
        ),
        selected = selected,
        icon = {
          NavigationDestinationIcon(
            destination = destination,
            selected = selected
          )
        },
        label = if (state.compact) null else {
          { NavigationDestinationLabel(destination) }
        },
        onClick = {
          onDestinationSelected(destination)
        },
        modifier = Modifier.drawNavigationBarBadge(count = badgeCount, compact = state.compact)
      )
    }
  }
}

/**
 * Draws badge over navigation bar item. We do this since they're required to be inside a row,
 * and things get really funky or clip weird if we try to use a normal composable.
 */
@Composable
private fun Modifier.drawNavigationBarBadge(count: Int, compact: Boolean): Modifier {
  return if (count <= 0) {
    this
  } else {
    val formatted = formatCount(count)
    val textMeasurer = rememberTextMeasurer()
    val color = colorResource(R.color.ConversationListTabs__unread)
    val textStyle = MaterialTheme.typography.labelMedium
    val textLayoutResult = remember(formatted) {
      textMeasurer.measure(formatted, textStyle)
    }

    var size by remember { mutableStateOf(IntSize.Zero) }

    val padding = with(LocalDensity.current) {
      4.dp.toPx()
    }

    val xOffsetExtra = with(LocalDensity.current) {
      4.dp.toPx()
    }

    val yOffset = with(LocalDensity.current) {
      if (compact) 6.dp.toPx() else 10.dp.toPx()
    }

    this
      .onSizeChanged {
        size = it
      }
      .drawWithContent {
        drawContent()

        val xOffset = size.width.toFloat() / 2f + xOffsetExtra
        val yRadius = size.height.toFloat() / 2f

        if (size != IntSize.Zero) {
          drawRoundRect(
            color = color,
            topLeft = Offset(xOffset, yOffset),
            size = Size(textLayoutResult.size.width.toFloat() + padding * 2, textLayoutResult.size.height.toFloat()),
            cornerRadius = CornerRadius(yRadius, yRadius)
          )

          drawText(
            textLayoutResult = textLayoutResult,
            color = Color.White,
            topLeft = Offset(xOffset + padding, yOffset)
          )
        }
      }
  }
}

/**
 * Navigation Rail for medium and large form factor devices.
 */
@Composable
fun MainNavigationRail(
  state: MainNavigationState,
  mainFloatingActionButtonsCallback: MainFloatingActionButtonsCallback,
  onDestinationSelected: (MainNavigationListLocation) -> Unit
) {
  NavigationRail(
    containerColor = colorAttribute(R.attr.navbar_container_color),
  ) {
    Spacer(modifier = Modifier.height(40.dp).weight(1f, fill = false))

    MainFloatingActionButtons(
      destination = state.currentListLocation,
      callback = mainFloatingActionButtonsCallback,
      modifier = Modifier.padding(vertical = 8.dp)
    )

    Spacer(modifier = Modifier.height(40.dp).weight(1f, fill = false))

    val entries = remember(state.isStoriesFeatureEnabled) {
      if (state.isStoriesFeatureEnabled) {
        MainNavigationListLocation.entries.filterNot { it == MainNavigationListLocation.ARCHIVE }
      } else {
        MainNavigationListLocation.entries.filterNot { it == MainNavigationListLocation.STORIES || it == MainNavigationListLocation.ARCHIVE }
      }
    }

    val selectedDestination = if (state.currentListLocation == MainNavigationListLocation.ARCHIVE) {
      MainNavigationListLocation.CHATS
    } else {
      state.currentListLocation
    }

    entries.forEachIndexed { idx, destination ->
      val selected = selectedDestination == destination

      Box {
        NavigationRailItem(
          colors = NavigationRailItemDefaults.colors(
            indicatorColor = colorAttribute(R.attr.navbar_active_indicator_color)
          ),
          modifier = Modifier.padding(bottom = if (MainNavigationListLocation.entries.lastIndex == idx) 0.dp else 16.dp),
          icon = {
            NavigationDestinationIcon(
              destination = destination,
              selected = selected
            )
          },
          label = {
            NavigationDestinationLabel(destination)
          },
          selected = selected,
          onClick = {
            onDestinationSelected(destination)
          }
        )

        NavigationRailCountIndicator(
          state = state,
          destination = destination
        )
      }
    }
  }
}

@Composable
private fun BoxScope.NavigationRailCountIndicator(
  state: MainNavigationState,
  destination: MainNavigationListLocation
) {
  val count = remember(state, destination) {
    when (destination) {
      MainNavigationListLocation.ARCHIVE -> error("Not supported")
      MainNavigationListLocation.CHATS -> state.chatsCount
      MainNavigationListLocation.CALLS -> state.callsCount
      MainNavigationListLocation.STORIES -> state.storiesCount
    }
  }

  if (count > 0) {
    Box(
      modifier = Modifier
        .padding(start = 42.dp)
        .height(16.dp)
        .defaultMinSize(minWidth = 16.dp)
        .background(color = colorResource(R.color.ConversationListTabs__unread), shape = RoundedCornerShape(percent = 50))
        .align(Alignment.TopStart)
    ) {
      Text(
        text = formatCount(count),
        style = MaterialTheme.typography.labelMedium,
        color = Color.White,
        modifier = Modifier
          .align(Alignment.Center)
          .padding(horizontal = 4.dp)
      )
    }
  }
}

@Composable
private fun NavigationDestinationIcon(
  destination: MainNavigationListLocation,
  selected: Boolean
) {
  val dynamicProperties = rememberLottieDynamicProperties(
    rememberLottieDynamicProperty(
      property = LottieProperty.COLOR_FILTER,
      value = BlendModeColorFilterCompat.createBlendModeColorFilterCompat(
        MaterialTheme.colorScheme.onSurface.hashCode(),
        BlendModeCompat.SRC_ATOP
      ),
      keyPath = arrayOf("**")
    )
  )

  val composition by rememberLottieComposition(LottieCompositionSpec.RawRes(destination.icon))
  val progress by animateFloatAsState(targetValue = if (selected) 1f else 0f, animationSpec = tween(durationMillis = composition?.duration?.toInt() ?: 0))

  LottieAnimation(
    composition = composition,
    progress = { if (selected) progress else 0f },
    dynamicProperties = dynamicProperties,
    modifier = Modifier.size(LOTTIE_SIZE)
=======
fun rememberDecoratedDetailEntries(
  mainNavigationViewModel: MainNavigationViewModel,
  isSplitPane: Boolean
): List<NavEntry<NavKey>> {
  return rememberCurrentDecoratedNavEntries(
    navigator = mainNavigationViewModel.navigator,
    entryProvider = rememberMainNavEntryProvider(isSplitPane)
>>>>>>> SIGNAL
  )
}

/**
 * The entry-provider for main screen. The methods which are called are where you'd add additional
 * screens for different tabs.
 */
@Composable
private fun rememberMainNavEntryProvider(isSplitPane: Boolean): (NavKey) -> NavEntry<NavKey> {
  return remember(isSplitPane) {
    entryProvider {
      registerMainScreenRoutes()
      registerChatsTabDetailRoutes()
      registerCallsTabDetailRoutes(isSplitPane)
      registerStoriesTabDetailRoutes()
    }
  }
}

/**
 * Registers the main list of tab routes (list locations)
 */
private fun EntryProviderScope<NavKey>.registerMainScreenRoutes() {
  listEntry<MainListRoute> { location ->
    when (location) {
      MainListRoute.Chats -> ChatsListPane(modifier = Modifier.fillMaxSize())
      MainListRoute.Archive -> ArchiveListPane(modifier = Modifier.fillMaxSize())
      MainListRoute.Calls -> CallsListPane(modifier = Modifier.fillMaxSize())
      MainListRoute.Stories -> StoriesListPane(modifier = Modifier.fillMaxSize())
    }
  }
}
