package org.signal.core.ui.compose.theme

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.util.TypedValue
import androidx.annotation.AttrRes
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.sp
import org.signal.core.ui.CoreUiDependencies
import org.signal.core.ui.R
import org.signal.core.ui.compose.ProvideIncognitoKeyboard

/**
 * Headings and labels hyphenate a word too long for its line, rather than breaking it at an arbitrary letter. This also
 * lets lines break at a hyphen already in the text, which they otherwise don't. Body text is left alone.
 */
private val typography = Typography().run {
  copy(
    displayLarge = displayLarge.copy(hyphens = Hyphens.Auto),
    displayMedium = displayMedium.copy(hyphens = Hyphens.Auto),
    displaySmall = displaySmall.copy(hyphens = Hyphens.Auto),
    headlineLarge = headlineLarge.copy(
      fontSize = 32.sp,
      lineHeight = 40.sp,
      letterSpacing = 0.sp,
      hyphens = Hyphens.Auto
    ),
    headlineMedium = headlineMedium.copy(
      fontSize = 28.sp,
      lineHeight = 36.sp,
      letterSpacing = 0.sp,
      hyphens = Hyphens.Auto
    ),
    headlineSmall = headlineSmall.copy(hyphens = Hyphens.Auto),
    titleLarge = titleLarge.copy(
      fontSize = 22.sp,
      lineHeight = 28.sp,
      letterSpacing = 0.sp,
      hyphens = Hyphens.Auto
    ),
    titleMedium = titleMedium.copy(
      fontSize = 18.sp,
      lineHeight = 24.sp,
      letterSpacing = 0.0125.sp,
      fontFamily = FontFamily.SansSerif,
      fontStyle = FontStyle.Normal,
      hyphens = Hyphens.Auto
    ),
    titleSmall = titleSmall.copy(
      fontSize = 16.sp,
      lineHeight = 22.sp,
      letterSpacing = 0.0125.sp,
      hyphens = Hyphens.Auto
    ),
    bodyLarge = bodyLarge.copy(
      fontSize = 16.sp,
      lineHeight = 22.sp,
      letterSpacing = 0.0125.sp
    ),
    bodyMedium = bodyMedium.copy(
      fontSize = 14.sp,
      lineHeight = 20.sp,
      letterSpacing = 0.0107.sp
    ),
    bodySmall = bodySmall.copy(
      fontSize = 13.sp,
      lineHeight = 16.sp,
      letterSpacing = 0.0192.sp
    ),
    labelLarge = labelLarge.copy(
      fontSize = 14.sp,
      lineHeight = 20.sp,
      letterSpacing = 0.0107.sp,
      hyphens = Hyphens.Auto
    ),
    labelMedium = labelMedium.copy(
      fontSize = 13.sp,
      lineHeight = 16.sp,
      letterSpacing = 0.0192.sp,
      hyphens = Hyphens.Auto
    ),
    labelSmall = labelSmall.copy(
      fontSize = 12.sp,
      lineHeight = 16.sp,
      letterSpacing = 0.025.sp,
      hyphens = Hyphens.Auto
    )
  )
}

private val lightColorScheme = lightColorScheme(
  inversePrimary = Color(0xFFF2982B),
  surfaceDim = Color(0xFFEBD1B4),
  inverseSurface = Color(0xFF4C1A0B),
  surfaceBright = Color(0xFFFFF8F0),
  surfaceContainerLowest = Color(0xFFFFFBF7),
  surfaceContainerLow = Color(0xFFFFF4E5),
  surfaceContainer = Color(0xFFFFF0DE),
  surfaceContainerHigh = Color(0xFFFCE9D0),
  surfaceContainerHighest = Color(0xFFF8E2C0),
  inverseOnSurface = Color(0xFFFFF8F0),
  outlineVariant = Color(0xFFB49D89),
  onError = Color(0xFFFFFFFF),
  onErrorContainer = Color(0xFF6D0028),
  tertiary = Color(0xFF891E07),
  onTertiary = Color(0xFFFFFFFF),
  tertiaryContainer = Color(0xFFEE9024),
  onTertiaryContainer = Color(0xFF3C1207),
  primary = Color(0xFF96390C),
  primaryContainer = Color(0xFFF2982B),
  secondary = Color(0xFF2F4F3A),
  secondaryContainer = Color(0xFFB7C597),
  surface = Color(0xFFFFF8F0),
  surfaceVariant = Color(0xFFF8E2C0),
  background = Color(0xFFFFF8F0),
  error = Color(0xFFE00052),
  errorContainer = Color(0xFFFDC7DB),
  onPrimary = Color(0xFFFFFFFF),
  onPrimaryContainer = Color(0xFF24130D),
  onSecondary = Color(0xFFFFFFFF),
  onSecondaryContainer = Color(0xFF203A29),
  onSurface = Color(0xFF24130D),
  onSurfaceVariant = Color(0xFF5A4033),
  onBackground = Color(0xFF24130D),
  outline = Color(0xFF687E59)
)

private val lightExtendedColors = ExtendedColors(
  neutralSurface = Color(0x99FFFFFF),
  neutralFill = Color(0x1A000000),
  colorOnCustom = Color(0xFFFFFFFF),
  colorOnCustomVariant = Color(0xB3FFFFFF),
  colorSurface1 = Color(0xFFFFF4E5),
  colorSurface2 = Color(0xFFFFF0DE),
  colorSurface3 = Color(0xFFFCE9D0),
  colorSurface4 = Color(0xFFF8E2C0),
  colorSurface5 = Color(0xFFEBD1B4),
  colorSurfaceVariantFill = Color(0xCCFFFFFF),
  colorTransparent1 = Color(0x14FFFFFF),
  colorTransparent2 = Color(0x29FFFFFF),
  colorTransparent3 = Color(0x8FFFFFFF),
  colorTransparent4 = Color(0xB8FFFFFF),
  colorTransparent5 = Color(0xF5FFFFFF),
  colorNeutral = Color(0xFFFFFFFF),
  colorNeutralVariant = Color(0xB8FFFFFF),
  colorTransparentInverse1 = Color(0x0A000000),
  colorTransparentInverse2 = Color(0x14000000),
  colorTransparentInverse3 = Color(0x66000000),
  colorTransparentInverse4 = Color(0xB8000000),
  colorTransparentInverse5 = Color(0xE0000000),
  colorNeutralInverse = Color(0xFF121212),
  colorNeutralVariantInverse = Color(0xFF5C5C5C),
  colorWarning = Color(0x1FB44828),
  colorOnWarning = Color(0xFFB44828),
  colorAlert = Color(0xFFEF5350),
  colorAlertDisabled = Color(0x80EF5350)
)

private val darkExtendedColors = ExtendedColors(
  neutralSurface = Color(0x14FFFFFF),
  neutralFill = Color(0x33FFFFFF),
  colorOnCustom = Color(0xFFFFFFFF),
  colorOnCustomVariant = Color(0x18FFFFFF),
  colorSurface1 = Color(0xFF2C1911),
  colorSurface2 = Color(0xFF342017),
  colorSurface3 = Color(0xFF3D241A),
  colorSurface4 = Color(0xFF48291C),
  colorSurface5 = Color(0xFF4C2A1B),
  colorSurfaceVariantFill = Color(0x33FFFFFF),
  colorTransparent1 = Color(0x0AFFFFFF),
  colorTransparent2 = Color(0x1FFFFFFF),
  colorTransparent3 = Color(0x29FFFFFF),
  colorTransparent4 = Color(0x7AFFFFFF),
  colorTransparent5 = Color(0xB8FFFFFF),
  colorNeutral = Color(0xFF121212),
  colorNeutralVariant = Color(0xFF5C5C5C),
  colorTransparentInverse1 = Color(0x0A000000),
  colorTransparentInverse2 = Color(0x14000000),
  colorTransparentInverse3 = Color(0x29000000),
  colorTransparentInverse4 = Color(0xB8000000),
  colorTransparentInverse5 = Color(0xF5000000),
  colorNeutralInverse = Color(0xE0FFFFFF),
  colorNeutralVariantInverse = Color(0xA3FFFFFF),
  colorWarning = Color(0x1FEB977D),
  colorOnWarning = Color(0xFFEB977D),
  colorAlert = Color(0xFFF44336),
  colorAlertDisabled = Color(0x80F44336)
)

private val darkColorScheme = darkColorScheme(
  inversePrimary = Color(0xFF96390C),
  surfaceBright = Color(0xFF4C2A1B),
  inverseSurface = Color(0xFFF8E2C0),
  surfaceDim = Color(0xFF24130D),
  surfaceContainerLowest = Color(0xFF1D100B),
  surfaceContainerLow = Color(0xFF2C1911),
  surfaceContainer = Color(0xFF342017),
  surfaceContainerHigh = Color(0xFF3D241A),
  surfaceContainerHighest = Color(0xFF48291C),
  inverseOnSurface = Color(0xFF3C1207),
  outlineVariant = Color(0xFF705849),
  onError = Color(0xFF63012C),
  onErrorContainer = Color(0xFFFFDAD6),
  tertiary = Color(0xFFEE9024),
  onTertiary = Color(0xFF24130D),
  tertiaryContainer = Color(0xFFEE9024),
  onTertiaryContainer = Color(0xFF24130D),
  primary = Color(0xFFF2982B),
  primaryContainer = Color(0xFF6F270F),
  secondary = Color(0xFFB7C597),
  secondaryContainer = Color(0xFF2F4F3A),
  surface = Color(0xFF24130D),
  surfaceVariant = Color(0xFF49372F),
  background = Color(0xFF24130D),
  error = Color(0xFFFE006E),
  errorContainer = Color(0xFFA40449),
  onPrimary = Color(0xFF24130D),
  onPrimaryContainer = Color(0xFFFFF3DF),
  onSecondary = Color(0xFF203A29),
  onSecondaryContainer = Color(0xFFE2EBCF),
  onSurface = Color(0xFFFFF3DF),
  onSurfaceVariant = Color(0xFFD8C3B2),
  onBackground = Color(0xFFFFF3DF),
  outline = Color(0xFFA9917F)
)

// MOLLY: Replaced by snackbarColors()

@Composable
fun SignalTheme(
  isDarkMode: Boolean = LocalConfiguration.current.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES,
  incognitoKeyboardEnabled: Boolean = CoreUiDependencies.isIncognitoKeyboardEnabled,
  useDynamicColors: Boolean? = null,
  content: @Composable () -> Unit
) {
  val context = LocalContext.current
  val maySupportDynamicColor = Build.VERSION.SDK_INT >= 31
  val dynamicColors = maySupportDynamicColor && (useDynamicColors ?: isThemeUsingDynamicColors(context))

  // MOLLY: Apply dynamic color if supported and enabled:
  // - API 34+: Use Compose's built-in dynamic scheme (matches system Material You).
  // - API 31–33: Use mapped color resources to approximate system dynamic palette, avoiding Compose's more saturated fallback.
  // - Otherwise: Use app light/dark color schemes.
  val colorScheme = when {
    dynamicColors -> {
      if (Build.VERSION.SDK_INT >= 34) {
        if (isDarkMode) dynamicDarkColorScheme(context)
        else dynamicLightColorScheme(context)
      } else {
        if (isDarkMode) systemAlignedDarkColorScheme()
        else systemAlignedLightColorScheme()
      }
    }
    isDarkMode -> darkColorScheme
    else -> lightColorScheme
  }

  val extendedColors = extendedColors(colorScheme, isDarkMode = isDarkMode, isDynamic = dynamicColors)
  val snackbarColors = snackbarColors(colorScheme, isDarkMode = isDarkMode, isDynamic = dynamicColors)

  ProvideIncognitoKeyboard(enabled = incognitoKeyboardEnabled) {
    CompositionLocalProvider(LocalExtendedColors provides extendedColors, LocalSnackbarColors provides snackbarColors) {
      MaterialTheme(
        colorScheme = colorScheme,
        typography = typography,
        content = content
      )
    }
  }
}

@Composable
private fun systemAlignedLightColorScheme() = lightColorScheme(
  primary = colorResource(R.color.dynamic_primary_light),
  onPrimary = colorResource(R.color.dynamic_on_primary_light),
  primaryContainer = colorResource(R.color.dynamic_primary_container_light),
  onPrimaryContainer = colorResource(R.color.dynamic_on_primary_container_light),
  inversePrimary = colorResource(R.color.dynamic_primary_inverse_light),
  secondary = colorResource(R.color.dynamic_secondary_light),
  onSecondary = colorResource(R.color.dynamic_on_secondary_light),
  secondaryContainer = colorResource(R.color.dynamic_secondary_container_light),
  onSecondaryContainer = colorResource(R.color.dynamic_on_secondary_container_light),
  tertiary = colorResource(R.color.dynamic_tertiary_light),
  onTertiary = colorResource(R.color.dynamic_on_tertiary_light),
  tertiaryContainer = colorResource(R.color.dynamic_tertiary_container_light),
  onTertiaryContainer = colorResource(R.color.dynamic_on_tertiary_container_light),
  background = colorResource(R.color.dynamic_background_light),
  onBackground = colorResource(R.color.dynamic_on_background_light),
  surface = colorResource(R.color.dynamic_surface_light),
  onSurface = colorResource(R.color.dynamic_on_surface_light),
  surfaceVariant = colorResource(R.color.dynamic_surface_variant_light),
  onSurfaceVariant = colorResource(R.color.dynamic_on_surface_variant_light),
  inverseSurface = colorResource(R.color.dynamic_surface_inverse_light),
  inverseOnSurface = colorResource(R.color.dynamic_on_surface_inverse_light),
  error = colorResource(R.color.dynamic_error_light),
  onError = colorResource(R.color.dynamic_on_error_light),
  errorContainer = colorResource(R.color.dynamic_error_container_light),
  onErrorContainer = colorResource(R.color.dynamic_on_error_container_light),
  outline = colorResource(R.color.dynamic_outline_light),
  outlineVariant = colorResource(R.color.dynamic_outline_variant_light),
  surfaceBright = colorResource(R.color.dynamic_surface_bright_light),
  surfaceContainer = colorResource(R.color.dynamic_surface_container_light),
  surfaceContainerHigh = colorResource(R.color.dynamic_surface_container_high_light),
  surfaceContainerHighest = colorResource(R.color.dynamic_surface_container_highest_light),
  surfaceContainerLow = colorResource(R.color.dynamic_surface_container_low_light),
  surfaceContainerLowest = colorResource(R.color.dynamic_surface_container_lowest_light),
  surfaceDim = colorResource(R.color.dynamic_surface_dim_light),
)

@Composable
private fun systemAlignedDarkColorScheme() = darkColorScheme(
  primary = colorResource(R.color.dynamic_primary_dark),
  onPrimary = colorResource(R.color.dynamic_on_primary_dark),
  primaryContainer = colorResource(R.color.dynamic_primary_container_dark),
  onPrimaryContainer = colorResource(R.color.dynamic_on_primary_container_dark),
  inversePrimary = colorResource(R.color.dynamic_primary_inverse_dark),
  secondary = colorResource(R.color.dynamic_secondary_dark),
  onSecondary = colorResource(R.color.dynamic_on_secondary_dark),
  secondaryContainer = colorResource(R.color.dynamic_secondary_container_dark),
  onSecondaryContainer = colorResource(R.color.dynamic_on_secondary_container_dark),
  tertiary = colorResource(R.color.dynamic_tertiary_dark),
  onTertiary = colorResource(R.color.dynamic_on_tertiary_dark),
  tertiaryContainer = colorResource(R.color.dynamic_tertiary_container_dark),
  onTertiaryContainer = colorResource(R.color.dynamic_on_tertiary_container_dark),
  background = colorResource(R.color.dynamic_background_dark),
  onBackground = colorResource(R.color.dynamic_on_background_dark),
  surface = colorResource(R.color.dynamic_surface_dark),
  onSurface = colorResource(R.color.dynamic_on_surface_dark),
  surfaceVariant = colorResource(R.color.dynamic_surface_variant_dark),
  onSurfaceVariant = colorResource(R.color.dynamic_on_surface_variant_dark),
  inverseSurface = colorResource(R.color.dynamic_surface_inverse_dark),
  inverseOnSurface = colorResource(R.color.dynamic_on_surface_inverse_dark),
  error = colorResource(R.color.dynamic_error_dark),
  onError = colorResource(R.color.dynamic_on_error_dark),
  errorContainer = colorResource(R.color.dynamic_error_container_dark),
  onErrorContainer = colorResource(R.color.dynamic_on_error_container_dark),
  outline = colorResource(R.color.dynamic_outline_dark),
  outlineVariant = colorResource(R.color.dynamic_outline_variant_dark),
  surfaceBright = colorResource(R.color.dynamic_surface_bright_dark),
  surfaceContainer = colorResource(R.color.dynamic_surface_container_dark),
  surfaceContainerHigh = colorResource(R.color.dynamic_surface_container_high_dark),
  surfaceContainerHighest = colorResource(R.color.dynamic_surface_container_highest_dark),
  surfaceContainerLow = colorResource(R.color.dynamic_surface_container_low_dark),
  surfaceContainerLowest = colorResource(R.color.dynamic_surface_container_lowest_dark),
  surfaceDim = colorResource(R.color.dynamic_surface_dim_dark),
)

private fun isThemeUsingDynamicColors(context: Context): Boolean {
  val theme = context.theme
  val typedValue = TypedValue()
  return theme.resolveAttribute(R.attr.dynamic_colors, typedValue, false)
    && typedValue.data != 0
}

private fun extendedColors(colorScheme: ColorScheme, isDarkMode: Boolean, isDynamic: Boolean): ExtendedColors {
  return when {
    isDynamic -> (if (isDarkMode) darkExtendedColors else lightExtendedColors).copy(
      colorSurface1 = colorScheme.surfaceContainerLow,
      colorSurface2 = colorScheme.surfaceContainer,
      colorSurface3 = colorScheme.surfaceContainerHigh,
      colorSurface4 = colorScheme.surfaceContainerHighest,
      colorSurface5 = if (isDarkMode) colorScheme.surfaceBright else colorScheme.surfaceDim
    )

    isDarkMode -> darkExtendedColors
    else -> lightExtendedColors
  }
}

private fun snackbarColors(colorScheme: ColorScheme, isDarkMode: Boolean, isDynamic: Boolean): SnackbarColors {
  // val surface = if (!isDynamic) colorScheme.surfaceVariant else colorScheme.surface
  // val onSurface = if (!isDynamic) colorScheme.onSurfaceVariant else colorScheme.onSurface

  return SnackbarColors(
    color = colorScheme.inverseSurface,
    contentColor = colorScheme.inverseOnSurface,
    actionColor = colorScheme.inversePrimary,
    actionContentColor = colorScheme.inversePrimary,
    dismissActionContentColor = colorScheme.inverseOnSurface
  )
}

@Composable
fun colorAttribute(@AttrRes id: Int): Color {
  val theme = LocalContext.current.theme
  val typedValue = TypedValue()
  return if (theme.resolveAttribute(id, typedValue, true) && typedValue.resourceId != 0) {
    colorResource(typedValue.resourceId)
  } else {
    Color.Unspecified
  }
}

/**
 * Applies the light color scheme to [content] regardless of the ambient theme, leaving typography and shapes untouched.
 */
@Composable
fun ForceLightColors(content: @Composable () -> Unit) {
  CompositionLocalProvider(LocalExtendedColors provides lightExtendedColors) {
    MaterialTheme(
      colorScheme = lightColorScheme,
      content = content
    )
  }
}

@Preview(showBackground = true)
@Composable
private fun TypographyPreview() {
  SignalTheme(
    isDarkMode = false,
    incognitoKeyboardEnabled = false
  ) {
    Column {
      Text(
        text = "Headline Small",
        style = MaterialTheme.typography.headlineLarge
      )
      Text(
        text = "Headline Small",
        style = MaterialTheme.typography.headlineMedium
      )
      Text(
        text = "Headline Small",
        style = MaterialTheme.typography.headlineSmall
      )
      Text(
        text = "Title Large",
        style = MaterialTheme.typography.titleLarge
      )
      Text(
        text = "Title Medium",
        style = MaterialTheme.typography.titleMedium
      )
      Text(
        text = "Title Small",
        style = MaterialTheme.typography.titleSmall
      )
      Text(
        text = "Body Large",
        style = MaterialTheme.typography.bodyLarge
      )
      Text(
        text = "Body Medium",
        style = MaterialTheme.typography.bodyMedium
      )
      Text(
        text = "Body Small",
        style = MaterialTheme.typography.bodySmall
      )
      Text(
        text = "Label Large",
        style = MaterialTheme.typography.labelLarge
      )
      Text(
        text = "Label Medium",
        style = MaterialTheme.typography.labelMedium
      )
      Text(
        text = "Label Small",
        style = MaterialTheme.typography.labelSmall
      )
    }
  }
}

object SignalTheme {
  val colors: ExtendedColors
    @Composable
    get() = LocalExtendedColors.current
}
