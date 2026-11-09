/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.appsettings.appicon

import android.content.ComponentName
import android.content.Context
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import org.signal.appsettings.R

<<<<<<< MOLLY:app/src/main/java/org/thoughtcrime/securesms/components/settings/app/appearance/appicon/util/AppIconPreset.kt
enum class AppIconPreset(private val componentName: String, @DrawableRes val iconPreviewResId: Int, @StringRes val labelResId: Int) {
  DEFAULT(".RoutingActivity", R.mipmap.ic_launcher, R.string.app_name),
  LIGHT(".RoutingActivityAltWhite", R.mipmap.ic_launcher_alt_light, R.string.app_name),
  SIGNAL(".RoutingActivityAltColor", R.mipmap.ic_launcher_alt_signal, R.string.app_name),
  COLORFUL(".RoutingActivityAltDark", R.mipmap.ic_launcher_alt_colorful, R.string.app_name),
  GOLD(".RoutingActivityAltDarkVariant", R.mipmap.ic_launcher_alt_gold, R.string.app_name),
  NEON(".RoutingActivityAltChat", R.mipmap.ic_launcher_alt_neon, R.string.app_name),
  CHRISTMAS(".RoutingActivityAltBubbles", R.mipmap.ic_launcher_alt_xmas, R.string.app_name),
  HEART(".RoutingActivityAltYellow", R.mipmap.ic_launcher_alt_heart, R.string.app_name),
  ZEN(".RoutingActivityAltNews", R.mipmap.ic_launcher_alt_zen, R.string.app_icon_label_zen),
  NOTES(".RoutingActivityAltNotes", R.mipmap.ic_launcher_alt_notes, R.string.app_icon_label_notes),
  MOON(".RoutingActivityAltWeather", R.mipmap.ic_launcher_alt_moon, R.string.app_icon_label_moon),
  MUSIC(".RoutingActivityAltWaves", R.mipmap.ic_launcher_alt_music, R.string.app_icon_label_music);
=======
/**
 * @param labelResId The launcher label shown with this icon, or null to use the app's own name.
 */
enum class AppIconPreset(private val componentName: String, @DrawableRes val iconPreviewResId: Int, @StringRes val labelResId: Int?) {
  DEFAULT(".RoutingActivity", R.drawable.ic_app_icon_default_top_preview, null),
  WHITE(".RoutingActivityAltWhite", R.drawable.ic_app_icon_signal_white_top_preview, null),
  COLOR(".RoutingActivityAltColor", R.drawable.ic_app_icon_signal_color_top_preview, null),
  DARK(".RoutingActivityAltDark", R.drawable.ic_app_icon_signal_dark_top_preview, null),
  DARK_VARIANT(".RoutingActivityAltDarkVariant", R.drawable.ic_app_icon_signal_dark_variant_top_preview, null),
  CHAT(".RoutingActivityAltChat", R.drawable.ic_app_icon_chat_top_preview, null),
  BUBBLES(".RoutingActivityAltBubbles", R.drawable.ic_app_icon_bubbles_top_preview, null),
  YELLOW(".RoutingActivityAltYellow", R.drawable.ic_app_icon_yellow_top_preview, null),
  NEWS(".RoutingActivityAltNews", R.drawable.ic_app_icon_news_top_preview, R.string.app_icon_label_news),
  NOTES(".RoutingActivityAltNotes", R.drawable.ic_app_icon_notes_top_preview, R.string.app_icon_label_notes),
  WEATHER(".RoutingActivityAltWeather", R.drawable.ic_app_icon_weather_top_preview, R.string.app_icon_label_weather),
  WAVES(".RoutingActivityAltWaves", R.drawable.ic_app_icon_waves_top_preview, R.string.app_icon_label_waves);
>>>>>>> SIGNAL:feature/app-settings/src/main/java/org/signal/appsettings/appicon/AppIconPreset.kt

  fun getComponentName(context: Context): ComponentName {
    val applicationContext = context.applicationContext
    return ComponentName(applicationContext, "org.thoughtcrime.securesms" + componentName)
  }
}
