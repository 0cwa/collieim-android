/*
 * Copyright 2026 Collie Messenger
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.util

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.view.ContextThemeWrapper
import androidx.core.graphics.ColorUtils
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import com.google.android.material.R as MaterialR
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.signal.core.ui.R as CoreUiR
import org.thoughtcrime.securesms.R
import kotlin.math.roundToInt

/** Verifies theme attributes inside a color state list on the app's API 27 floor. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 27, maxSdkVersion = 27)
class ThemeStateListApi27Test {

  @Test
  fun themedDismissShapeAndDisabledSelectorsResolveDistinctRoles() {
    val baseContext = ApplicationProvider.getApplicationContext<Context>()
    verifyTheme(baseContext, R.style.TextSecure_LightTheme)
    verifyTheme(baseContext, R.style.TextSecure_DarkTheme)
  }

  private fun verifyTheme(baseContext: Context, theme: Int) {
    val context = ContextThemeWrapper(baseContext, theme)

    val surface = resolveColor(context, MaterialR.attr.colorSurface)
    val onSurface = resolveColor(context, MaterialR.attr.colorOnSurface)
    val onSurfaceVariant = resolveColor(context, MaterialR.attr.colorOnSurfaceVariant)
    val onBackground = resolveColor(context, MaterialR.attr.colorOnBackground)
    val onPrimary = resolveColor(context, MaterialR.attr.colorOnPrimary)
    val surfaceVariant = resolveColor(context, MaterialR.attr.colorSurfaceVariant)
    val outline = resolveColor(context, MaterialR.attr.colorOutline)
    assertNotEquals("The proof requires distinct foreground and surface roles", surface, onSurface)
    assertNotEquals("The proof requires distinct disabled text and surface roles", surface, onSurfaceVariant)

    val background = context.resources.getDrawable(R.drawable.dismiss_background, context.theme) as InsetDrawable
    val shape = background.drawable as GradientDrawable
    assertEquals(withAlpha(surface, 0.6f), shape.color!!.defaultColor)

    assertSelector(context, CoreUiR.color.signal_color_on_surface_8, onSurface, 0.08f)
    assertSelector(context, CoreUiR.color.signal_color_on_surface_12, onSurface, 0.12f)
    assertSelector(context, CoreUiR.color.signal_color_on_surface_38, onSurface, 0.38f)
    assertSelector(context, CoreUiR.color.signal_color_on_surface_50, onSurface, 0.5f)
    assertSelector(context, CoreUiR.color.signal_color_on_surface_variant_60, onSurfaceVariant, 0.6f)
    assertSelector(context, CoreUiR.color.signal_color_on_background_60, onBackground, 0.6f)
    assertSelector(context, CoreUiR.color.signal_color_outline_38, outline, 0.38f)
    assertSelector(context, CoreUiR.color.signal_color_surface_variant_64, surfaceVariant, 0.64f)
    assertSelector(context, CoreUiR.color.signal_color_on_primary_12, onPrimary, 0.12f)
    assertSelector(context, CoreUiR.color.signal_color_on_primary_20, onPrimary, 0.2f)

    assertDisabledColor(context, R.color.text_color_primary_enabled_selector, withAlpha(onSurfaceVariant, 0.6f))
    assertDisabledColor(context, R.color.text_color_secondary_enabled_selector, withAlpha(onSurfaceVariant, 0.6f))
    assertDisabledColor(context, R.color.text_color_red_enabled_selector, withAlpha(onSurfaceVariant, 0.6f))
    assertDisabledColor(context, R.color.icon_tint_color_primary_enabled_selector, withAlpha(onSurface, 0.5f))
    assertDisabledColor(context, R.color.button_outline_color_selector, withAlpha(onSurface, 0.12f))
    assertDisabledColor(context, R.color.signal_button_secondary_text_selector, withAlpha(onSurface, 0.12f))
    assertEquals(withAlpha(outline, 1f), context.resources
      .getColorStateList(R.color.button_outline_color_selector, context.theme)
      .getColorForState(intArrayOf(android.R.attr.state_enabled), Color.TRANSPARENT))
  }

  private fun assertSelector(context: Context, resource: Int, role: Int, alpha: Float) {
    assertEquals(
      withAlpha(role, alpha),
      context.resources.getColorStateList(resource, context.theme).defaultColor
    )
  }

  private fun assertDisabledColor(context: Context, resource: Int, expected: Int) {
    assertEquals(
      expected,
      context.resources.getColorStateList(resource, context.theme)
        .getColorForState(intArrayOf(-android.R.attr.state_enabled), Color.TRANSPARENT)
    )
  }

  private fun resolveColor(context: Context, attribute: Int): Int {
    val value = android.util.TypedValue()
    assertTrue(context.theme.resolveAttribute(attribute, value, true))
    return if (value.resourceId != 0) context.resources.getColor(value.resourceId, context.theme) else value.data
  }

  private fun withAlpha(color: Int, alpha: Float): Int =
    ColorUtils.setAlphaComponent(color, (Color.alpha(color) * alpha).roundToInt())
}
