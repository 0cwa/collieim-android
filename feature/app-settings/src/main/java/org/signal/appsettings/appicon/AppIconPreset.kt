/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.appsettings.appicon

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import androidx.annotation.StringRes
import org.signal.appsettings.R

/** Launcher aliases are stable; their artwork and labels come from Molly's application manifest. */
enum class AppIconPreset(private val componentName: String, @StringRes val labelResId: Int?) {
  DEFAULT(".RoutingActivity", null),
  WHITE(".RoutingActivityAltWhite", null),
  COLOR(".RoutingActivityAltColor", null),
  DARK(".RoutingActivityAltDark", null),
  DARK_VARIANT(".RoutingActivityAltDarkVariant", null),
  CHAT(".RoutingActivityAltChat", null),
  BUBBLES(".RoutingActivityAltBubbles", null),
  YELLOW(".RoutingActivityAltYellow", null),
  NEWS(".RoutingActivityAltNews", R.string.app_icon_label_zen),
  NOTES(".RoutingActivityAltNotes", R.string.app_icon_label_notes),
  WEATHER(".RoutingActivityAltWeather", R.string.app_icon_label_moon),
  WAVES(".RoutingActivityAltWaves", R.string.app_icon_label_music);

  fun getComponentName(context: Context): ComponentName {
    return ComponentName(context.applicationContext, "org.thoughtcrime.securesms" + componentName)
  }

  fun loadIcon(context: Context): Drawable {
    val packageManager = context.packageManager
    return try {
      packageManager.getActivityInfo(getComponentName(context), PackageManager.MATCH_DISABLED_COMPONENTS).loadIcon(packageManager)
    } catch (_: PackageManager.NameNotFoundException) {
      packageManager.defaultActivityIcon
    }
  }
}
