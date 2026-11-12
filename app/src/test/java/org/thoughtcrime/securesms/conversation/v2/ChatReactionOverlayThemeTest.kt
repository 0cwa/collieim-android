/*
 * Copyright 2026 Collie Messenger
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversation.v2

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.ContextThemeWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.signal.core.ui.compose.theme.SignalTheme
import org.signal.core.ui.util.ThemeUtil
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.conversation.ReactionScrubber
import org.thoughtcrime.securesms.testutil.EmptyLogger
import android.graphics.Color as AndroidColor

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [31, 35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatReactionOverlayThemeTest {

  @get:Rule
  val compose = createComposeRule()

  @Before
  fun setUp() {
    Log.initialize(EmptyLogger())
  }

  @Test
  fun `light message scrim follows palette changes and preserves wallpaper translucency`() {
    assertPaletteSwitch(dark = false)
  }

  @Test
  fun `dark message scrim follows palette changes and preserves wallpaper translucency`() {
    assertPaletteSwitch(dark = true)
  }

  private fun assertPaletteSwitch(dark: Boolean) {
    val application = ApplicationProvider.getApplicationContext<Application>()
    val configuration = Configuration(application.resources.configuration).apply {
      uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
    }
    val base = application.createConfigurationContext(configuration)
    val regularContext = ContextThemeWrapper(base, if (dark) R.style.TextSecure_DarkNoActionBar else R.style.TextSecure_LightNoActionBar)
    val dynamicContext = ContextThemeWrapper(base, if (dark) R.style.Theme_Molly_Dynamic_Dark else R.style.Theme_Molly_Dynamic_Light)
    var currentContext: Context by mutableStateOf(regularContext)

    val selectedMessage = ReactionOverlaySelection(
      snapshot = ImageBitmap(20, 20),
      bubbleX = 100f,
      bubbleY = 100f,
      bubbleWidth = 20,
      contextMenuX = 100f,
      isMessageOnLeft = true,
      lastSeenDownY = 110f,
      emoji = emptyList(),
      appliedEmojiIndex = ReactionScrubber.NO_SELECTION,
      canReact = false,
      returnPosition = { null }
    )
    val controller = mockk<ChatReactionOverlayController>(relaxUnitFun = true) {
      every { selection } returns selectedMessage
      every { isRevealed } returns true
      every { scrubber } returns ReactionScrubber(REACTION_EMOJI_COUNT, 0f)
      every { menu } returns null
    }

    compose.setContent {
      CompositionLocalProvider(
        LocalContext provides currentContext,
        LocalConfiguration provides configuration
      ) {
        SignalTheme(isDarkMode = dark, incognitoKeyboardEnabled = false) {
          Box(Modifier.size(320.dp, 480.dp).background(WALLPAPER)) {
            ChatReactionOverlay(controller, Modifier.testTag(OVERLAY))
          }
        }
      }
    }

    assertScrim(regularContext)
    compose.runOnIdle { currentContext = dynamicContext }
    assertScrim(dynamicContext)
    compose.runOnIdle { currentContext = regularContext }
    assertScrim(regularContext)
  }

  private fun assertScrim(context: Context) {
    val pixels = compose.onNodeWithTag(OVERLAY).captureToImage().toPixelMap()
    // This corner is clear of the message snapshot and controls, so it contains both scrim layers.
    val actual = pixels[pixels.width - 1, pixels.height - 1].toArgb()
    val expected = renderExpectedScrim(context)

    // Native raster compositing can round a channel by one at the alpha boundary.
    assertEquals(AndroidColor.red(expected).toDouble(), AndroidColor.red(actual).toDouble(), 1.0)
    assertEquals(AndroidColor.green(expected).toDouble(), AndroidColor.green(actual).toDouble(), 1.0)
    assertEquals(AndroidColor.blue(expected).toDouble(), AndroidColor.blue(actual).toDouble(), 1.0)
    assertEquals(255, AndroidColor.alpha(actual))
  }

  private fun renderExpectedScrim(context: Context): Int {
    // Use the native rasterizer for the independent reference pixel. Integer color arithmetic
    // truncates after each translucent layer, unlike the renderer used by captureToImage on API 31.
    val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(WALLPAPER.toArgb())
    canvas.drawColor(ContextCompat.getColor(context, R.color.reactions_screen_light_shade_color))
    canvas.drawColor(ThemeUtil.getThemedColor(context, R.attr.reactions_overlay_shade_color))
    val pixel = bitmap.getPixel(0, 0)
    bitmap.recycle()
    return pixel
  }

  companion object {
    private const val OVERLAY = "theme-reaction-overlay"
    private val WALLPAPER = Color(0xFF204060)
  }
}
