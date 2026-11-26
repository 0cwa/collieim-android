/*
 * SPDX-License-Identifier: AGPL-3.0-only
 */

import com.google.android.material.color.utilities.DynamicColor;
import com.google.android.material.color.utilities.DynamicScheme;
import com.google.android.material.color.utilities.Hct;
import com.google.android.material.color.utilities.MaterialDynamicColors;
import com.google.android.material.color.utilities.SchemeExpressive;
import com.google.android.material.color.utilities.SchemeNeutral;
import com.google.android.material.color.utilities.SchemeTonalSpot;
import com.google.android.material.color.utilities.SchemeVibrant;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * Prints the Material 3 color roles of one fixed seed and style, as {@code role=#AARRGGBB} lines.
 *
 * <p>Usage: {@code java -cp material-classes.jar PaletteRoles.java <#RRGGBB> <tonal_spot|vibrant|expressive|neutral> <light|dark> <contrast>}
 *
 * <p>Only tooling/user-theming/generate.py runs this, with the classes.jar of the pinned Material Components AAR.
 * The color utilities are restricted to Material's library group, so app code must use the generated output.
 */
public final class PaletteRoles {

  public static void main(String[] args) {
    if (args.length != 4) {
      throw new IllegalArgumentException("Expected: <#RRGGBB> <style> <light|dark> <contrast>");
    }

    int     seed     = 0xFF000000 | Integer.parseInt(args[0].replace("#", ""), 16);
    boolean dark     = switch (args[2]) {
      case "light" -> false;
      case "dark" -> true;
      default -> throw new IllegalArgumentException("Unknown brightness: " + args[2]);
    };
    double  contrast = Double.parseDouble(args[3]);
    Hct     hct      = Hct.fromInt(seed);

    DynamicScheme scheme = switch (args[1]) {
      case "tonal_spot" -> new SchemeTonalSpot(hct, dark, contrast);
      case "vibrant" -> new SchemeVibrant(hct, dark, contrast);
      case "expressive" -> new SchemeExpressive(hct, dark, contrast);
      case "neutral" -> new SchemeNeutral(hct, dark, contrast);
      default -> throw new IllegalArgumentException("Unknown style: " + args[1]);
    };

    MaterialDynamicColors colors = new MaterialDynamicColors();
    Map<String, Function<MaterialDynamicColors, DynamicColor>> roles = new LinkedHashMap<>();
    roles.put("primary", MaterialDynamicColors::primary);
    roles.put("onPrimary", MaterialDynamicColors::onPrimary);
    roles.put("primaryContainer", MaterialDynamicColors::primaryContainer);
    roles.put("onPrimaryContainer", MaterialDynamicColors::onPrimaryContainer);
    roles.put("inversePrimary", MaterialDynamicColors::inversePrimary);
    roles.put("primaryFixed", MaterialDynamicColors::primaryFixed);
    roles.put("primaryFixedDim", MaterialDynamicColors::primaryFixedDim);
    roles.put("onPrimaryFixed", MaterialDynamicColors::onPrimaryFixed);
    roles.put("onPrimaryFixedVariant", MaterialDynamicColors::onPrimaryFixedVariant);
    roles.put("secondary", MaterialDynamicColors::secondary);
    roles.put("onSecondary", MaterialDynamicColors::onSecondary);
    roles.put("secondaryContainer", MaterialDynamicColors::secondaryContainer);
    roles.put("onSecondaryContainer", MaterialDynamicColors::onSecondaryContainer);
    roles.put("secondaryFixed", MaterialDynamicColors::secondaryFixed);
    roles.put("secondaryFixedDim", MaterialDynamicColors::secondaryFixedDim);
    roles.put("onSecondaryFixed", MaterialDynamicColors::onSecondaryFixed);
    roles.put("onSecondaryFixedVariant", MaterialDynamicColors::onSecondaryFixedVariant);
    roles.put("tertiary", MaterialDynamicColors::tertiary);
    roles.put("onTertiary", MaterialDynamicColors::onTertiary);
    roles.put("tertiaryContainer", MaterialDynamicColors::tertiaryContainer);
    roles.put("onTertiaryContainer", MaterialDynamicColors::onTertiaryContainer);
    roles.put("tertiaryFixed", MaterialDynamicColors::tertiaryFixed);
    roles.put("tertiaryFixedDim", MaterialDynamicColors::tertiaryFixedDim);
    roles.put("onTertiaryFixed", MaterialDynamicColors::onTertiaryFixed);
    roles.put("onTertiaryFixedVariant", MaterialDynamicColors::onTertiaryFixedVariant);
    roles.put("error", MaterialDynamicColors::error);
    roles.put("onError", MaterialDynamicColors::onError);
    roles.put("errorContainer", MaterialDynamicColors::errorContainer);
    roles.put("onErrorContainer", MaterialDynamicColors::onErrorContainer);
    roles.put("background", MaterialDynamicColors::background);
    roles.put("onBackground", MaterialDynamicColors::onBackground);
    roles.put("surface", MaterialDynamicColors::surface);
    roles.put("onSurface", MaterialDynamicColors::onSurface);
    roles.put("surfaceVariant", MaterialDynamicColors::surfaceVariant);
    roles.put("onSurfaceVariant", MaterialDynamicColors::onSurfaceVariant);
    roles.put("inverseSurface", MaterialDynamicColors::inverseSurface);
    roles.put("inverseOnSurface", MaterialDynamicColors::inverseOnSurface);
    roles.put("surfaceTint", MaterialDynamicColors::surfaceTint);
    roles.put("surfaceDim", MaterialDynamicColors::surfaceDim);
    roles.put("surfaceBright", MaterialDynamicColors::surfaceBright);
    roles.put("surfaceContainerLowest", MaterialDynamicColors::surfaceContainerLowest);
    roles.put("surfaceContainerLow", MaterialDynamicColors::surfaceContainerLow);
    roles.put("surfaceContainer", MaterialDynamicColors::surfaceContainer);
    roles.put("surfaceContainerHigh", MaterialDynamicColors::surfaceContainerHigh);
    roles.put("surfaceContainerHighest", MaterialDynamicColors::surfaceContainerHighest);
    roles.put("outline", MaterialDynamicColors::outline);
    roles.put("outlineVariant", MaterialDynamicColors::outlineVariant);

    StringBuilder out = new StringBuilder();
    for (Map.Entry<String, Function<MaterialDynamicColors, DynamicColor>> role : roles.entrySet()) {
      int argb = role.getValue().apply(colors).getArgb(scheme);
      out.append(role.getKey()).append('=').append(String.format(Locale.ROOT, "#%08X", argb)).append('\n');
    }
    System.out.print(out);
  }
}
