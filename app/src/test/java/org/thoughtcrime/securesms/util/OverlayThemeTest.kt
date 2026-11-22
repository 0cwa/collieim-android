/*
 * Copyright 2026 Collie Messenger
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.util

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.ColorStateListDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import androidx.annotation.ColorRes
import androidx.annotation.StyleRes
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.shape.MaterialShapeDrawable
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.ui.util.ThemeUtil
import org.signal.core.util.logging.Log
import org.signal.storage.keyvalue.PlainTextKeyValueStore
import org.thoughtcrime.securesms.BuildConfig
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.conversation.ConversationContextMenu
import org.thoughtcrime.securesms.testutil.EmptyLogger
import org.thoughtcrime.securesms.testutil.MockSignalStoreRule
import org.signal.core.ui.R as CoreUiR

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [30, 31, 35])
class OverlayThemeTest {

  @get:Rule
  val signalStore = MockSignalStoreRule()

  @Before
  fun setUp() {
    Log.initialize(EmptyLogger())
    val application = ApplicationProvider.getApplicationContext<Application>()
    val preferences = application.getSharedPreferences("overlay-theme-test", Context.MODE_PRIVATE)
    preferences.edit().clear().commit()
    mockkStatic(SecurePreferenceManager::class)
    every { SecurePreferenceManager.getSecurePreferences(any()) } returns preferences
    PlainTextKeyValueStore.init(preferences, BuildConfig.VERSION_CODE)
  }

  @After
  fun tearDown() {
    unmockkStatic(SecurePreferenceManager::class)
  }

  @Test
  fun `default overlays retain the Collie palette in light and dark mode`() {
    for (dark in listOf(false, true)) {
      val context = themedContext(if (dark) R.style.TextSecure_DarkNoActionBar else R.style.TextSecure_LightNoActionBar, dark)

      assertEquals(
        Color.parseColor(if (dark) "#D924130D" else "#6676727F"),
        ThemeUtil.getThemedColor(context, R.attr.reactions_overlay_shade_color)
      )
      assertEquals(
        Color.parseColor(if (dark) "#544237" else "#DCCDBD"),
        ThemeUtil.getThemedColor(context, R.attr.reactions_overlay_selected_emoji_background)
      )
      assertEquals(
        Color.parseColor(if (dark) "#3D241A" else "#FFF0DE"),
        contextMenuColor(context)
      )
    }
  }

  @Test
  fun `default media preview theme retains its backdrop and chrome colors`() {
    val context = themedContext(R.style.TextSecure_MediaPreview, dark = true)
    assertMediaColors(context, Color.parseColor("#121212"), Color.parseColor("#DE121212"))
  }

  @Test
  @Config(sdk = [31, 35])
  fun `dynamic media preview theme uses the dark system palette`() {
    val context = themedContext(R.style.Theme_Molly_Dynamic_MediaPreview, dark = true)
    val surface = dynamicColor(context, android.R.color.system_surface_dark, CoreUiR.color.dynamic_surface_dark)

    assertMediaColors(context, surface, ColorUtils.setAlphaComponent(surface, 222))
    assertEquals(surface, ThemeUtil.getThemedColor(context, android.R.attr.windowBackground))
    assertEquals(surface, ThemeUtil.getThemedColor(context, android.R.attr.statusBarColor))
    assertEquals(surface, ThemeUtil.getThemedColor(context, android.R.attr.navigationBarColor))
  }

  @Test
  @Config(sdk = [31, 35])
  fun `dynamic message overlays use the system palette and keep the scrim opacity`() {
    for (dark in listOf(false, true)) {
      val context = themedContext(if (dark) R.style.Theme_Molly_Dynamic_Dark else R.style.Theme_Molly_Dynamic_Light, dark)
      val shade = if (dark) {
        dynamicColor(context, android.R.color.system_surface_dark, CoreUiR.color.dynamic_surface_dark)
      } else {
        dynamicColor(context, android.R.color.system_on_surface_variant_light, CoreUiR.color.dynamic_on_surface_variant_light)
      }
      val indicator = if (dark) {
        dynamicColor(context, android.R.color.system_surface_container_highest_dark, CoreUiR.color.dynamic_surface_container_highest_dark)
      } else {
        dynamicColor(context, android.R.color.system_surface_container_highest_light, CoreUiR.color.dynamic_surface_container_highest_light)
      }
      val menu = if (dark) {
        dynamicColor(context, android.R.color.system_surface_container_high_dark, CoreUiR.color.dynamic_surface_container_high_dark)
      } else {
        dynamicColor(context, android.R.color.system_surface_container_light, CoreUiR.color.dynamic_surface_container_light)
      }

      assertEquals(ColorUtils.setAlphaComponent(shade, if (dark) 217 else 102), ThemeUtil.getThemedColor(context, R.attr.reactions_overlay_shade_color))
      assertEquals(indicator, ThemeUtil.getThemedColor(context, R.attr.reactions_overlay_selected_emoji_background))
      assertEquals(menu, contextMenuColor(context))
    }
  }

  @Test
  @Config(sdk = [31, 35])
  fun `dynamic Compose viewer keeps the transparent window and themes its backdrop`() {
    for (dark in listOf(false, true)) {
      val context = themedContext(R.style.Theme_Molly_Dynamic_NoActionBar_MediaViewer, dark)
      val surface = if (dark) {
        dynamicColor(context, android.R.color.system_surface_dark, CoreUiR.color.dynamic_surface_dark)
      } else {
        dynamicColor(context, android.R.color.system_surface_light, CoreUiR.color.dynamic_surface_light)
      }
      val content = LayoutInflater.from(context).inflate(R.layout.media_viewer_activity, null)

      assertTrue(ThemeUtil.getThemedBoolean(context, CoreUiR.attr.dynamic_colors))
      assertTrue(ThemeUtil.getThemedBoolean(context, android.R.attr.windowIsTranslucent))
      assertEquals(Color.TRANSPARENT, ThemeUtil.getThemedColor(context, android.R.attr.windowBackground))
      assertEquals(surface, backgroundColor(content.findViewById(R.id.background_scrim)))
    }
  }

  @Test
  @Config(sdk = [30])
  fun `media viewers ignore a persisted dynamic selection on unsupported Android`() {
    val context = ApplicationProvider.getApplicationContext<Application>()
    val previous = PlainTextKeyValueStore.isDynamicColorsEnabled
    try {
      PlainTextKeyValueStore.isDynamicColorsEnabled = true

      assertEquals(R.style.TextSecure_MediaPreview, DynamicMediaPreviewTheme().getTheme())
      assertEquals(R.style.Signal_DayNight_NoActionBar_MediaViewer, DynamicMediaViewerTheme().getTheme())
    } finally {
      PlainTextKeyValueStore.isDynamicColorsEnabled = previous
    }
  }

  private fun assertMediaColors(context: Context, backdropColor: Int, chromeColor: Int) {
    assertEquals(backdropColor, ThemeUtil.getThemedColor(context, R.attr.media_preview_background))
    assertEquals(chromeColor, ThemeUtil.getThemedColor(context, R.attr.media_preview_bar_background))
  }

  private fun contextMenuColor(context: Context): Int {
    val menu = ConversationContextMenu(View(context), emptyList())
    return (menu.background as GradientDrawable).color!!.defaultColor
  }

  private fun backgroundColor(view: View): Int = when (val background = view.background) {
    is ColorDrawable -> background.color
    is ColorStateListDrawable -> background.colorStateList.defaultColor
    is MaterialShapeDrawable -> background.fillColor!!.defaultColor
    else -> error("Unexpected background $background")
  }

  private fun dynamicColor(context: Context, @ColorRes systemColor: Int, @ColorRes fallbackColor: Int): Int {
    return ContextCompat.getColor(context, if (Build.VERSION.SDK_INT >= 34) systemColor else fallbackColor)
  }

  private fun themedContext(@StyleRes theme: Int, dark: Boolean): Context {
    val application = ApplicationProvider.getApplicationContext<Application>()
    val configuration = Configuration(application.resources.configuration).apply {
      uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
    }
    return ContextThemeWrapper(application.createConfigurationContext(configuration), theme)
  }
}
