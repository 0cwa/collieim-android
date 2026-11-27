package org.thoughtcrime.securesms.util;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StyleRes;
import androidx.annotation.VisibleForTesting;
import androidx.appcompat.app.AppCompatDelegate;

import com.google.android.material.color.DynamicColors;

import org.signal.core.ui.util.ThemeUtil;
import org.signal.core.util.logging.Log;
import org.signal.storage.keyvalue.PlainTextKeyValueStore;
import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.flags.CollieFlags;
import org.thoughtcrime.securesms.keyvalue.SettingsValues.Theme;
import org.signal.core.util.ConfigurationUtil;

import java.util.Objects;

public class DynamicTheme {

  private static final String TAG = Log.tag(DynamicTheme.class);

  /** How an Activity takes a built-in palette. Its own style keeps the window, inset and system bar policy. */
  public enum PalettePolicy {
    /** Palette roles at the Activity's effective brightness. */
    FOLLOW_ACTIVITY,
    /** Palette dark roles, for always-dark windows. */
    ALWAYS_DARK,
    /** Keeps the Collie roles. */
    NONE
  }

  private static int                globalNightModeConfiguration;
  private static @Nullable Identity globalIdentity;

  private int               onCreateNightModeConfiguration;
  private @Nullable Identity onCreateIdentity;

  private static final int regularTheme = R.style.Signal_DayNight;
  private static final int dynamicTheme = R.style.Theme_Molly_Dynamic;

  protected @StyleRes int getRegularTheme() {
    return regularTheme;
  }

  protected @StyleRes int getDynamicTheme() {
    return dynamicTheme;
  }

  protected @NonNull PalettePolicy getPalettePolicy() {
    return PalettePolicy.FOLLOW_ACTIVITY;
  }

  public void onCreate(@NonNull Activity activity) {
    int      previousGlobalConfiguration = globalNightModeConfiguration;
    Identity previousGlobalIdentity      = globalIdentity;

    onCreateNightModeConfiguration = ConfigurationUtil.getNightModeConfiguration(activity);
    onCreateIdentity               = resolveIdentity(activity);
    globalNightModeConfiguration   = onCreateNightModeConfiguration;
    globalIdentity                 = onCreateIdentity.withoutActivityStyle();

    activity.setTheme(onCreateIdentity.baseTheme);
    if (onCreateIdentity.paletteOverlay != 0) {
      activity.getTheme().applyStyle(onCreateIdentity.paletteOverlay, true);
    }

    if (previousGlobalConfiguration != globalNightModeConfiguration || !Objects.equals(previousGlobalIdentity, globalIdentity)) {
      Log.d(TAG, "Night mode or palette changed. Clearing cached layouts.");
      CachedInflater.from(activity).clear();
    }
  }

  public void onResume(@NonNull Activity activity) {
    if (onCreateIdentity != null && hasThemeChanged(activity)) {
      // Changing palettes does not change uiMode, so AppCompat will not recreate a retained activity. An activity that
      // handles uiMode itself would also keep a palette overlay of the old brightness. Rebuild its view and Compose
      // hierarchies with the selected theme when returning from Settings.
      Log.d(TAG, "Theme changed since creation. Recreating activity.");
      CachedInflater.from(activity).clear();
      activity.recreate();
    } else if (onCreateNightModeConfiguration != ConfigurationUtil.getNightModeConfiguration(activity)) {
      Log.d(TAG, "Create configuration different from current previous: " + onCreateNightModeConfiguration + " now: " + ConfigurationUtil.getNightModeConfiguration(activity));
      CachedInflater.from(activity).clear();
    }
  }

  private boolean hasThemeChanged(@NonNull Activity activity) {
    return CollieFlags.INSTANCE.getUserTheming()
           ? !onCreateIdentity.equals(resolveIdentity(activity))
           : onCreateIdentity.baseTheme != (useLegacyDynamicColors() ? getDynamicTheme() : getRegularTheme());
  }

  @VisibleForTesting
  final @StyleRes int getTheme(@NonNull Context context) {
    return usesSystemColors(ThemePalettes.selectedId(context)) ? getDynamicTheme() : getRegularTheme();
  }

  private @NonNull Identity resolveIdentity(@NonNull Activity activity) {
    boolean dark = ConfigurationUtil.getNightModeConfiguration(activity) == Configuration.UI_MODE_NIGHT_YES;
    return resolveIdentity(activity, getRegularTheme(), getDynamicTheme(), getPalettePolicy(), dark);
  }

  private static @NonNull Identity resolveIdentity(@NonNull Context context,
                                                   @StyleRes int regularStyle,
                                                   @StyleRes int dynamicStyle,
                                                   @NonNull PalettePolicy policy,
                                                   boolean effectiveDark)
  {
    String  selection = ThemePalettes.selectedId(context);
    boolean dynamic   = usesSystemColors(selection);
    String  paletteId = policy == PalettePolicy.NONE ? null : ThemePalettes.builtInPaletteId(selection);
    boolean dark      = policy == PalettePolicy.ALWAYS_DARK || effectiveDark;
    int     overlay   = paletteId != null ? GeneratedPaletteOverlays.overlay(paletteId, dark) : 0;

    return new Identity(dynamic ? dynamicStyle : regularStyle, dynamic, paletteId, overlay);
  }

  private static boolean usesSystemColors(@NonNull String selection) {
    return isDynamicColorsAvailable() && ThemePalettes.SYSTEM.equals(selection);
  }

  private static boolean useLegacyDynamicColors() {
    return isDynamicColorsAvailable() && PlainTextKeyValueStore.isDynamicColorsEnabled();
  }

  public static boolean isDynamicColorsAvailable() {
    return DynamicColors.isDynamicColorAvailable();
  }

  /**
   * Resolves a color of the app theme for contexts without an Activity theme, such as services. Uses the same
   * identity as activities: AppCompat's effective night mode, the System flag and the palette overlay.
   */
  public static @ColorInt int resolveColor(@NonNull Context context, int colorRef) {
    if (!CollieFlags.INSTANCE.getUserTheming()) {
      int style = useLegacyDynamicColors() ? dynamicTheme : regularTheme;
      ContextThemeWrapper themedContext = new ContextThemeWrapper(context, style);
      return ThemeUtil.getThemedColor(context, colorRef, themedContext.getTheme());
    }
    boolean  dark       = isEffectiveDark(context);
    Context  configured = withNightMode(context, dark);
    Identity identity   = resolveIdentity(context, regularTheme, dynamicTheme, PalettePolicy.FOLLOW_ACTIVITY, dark);

    ContextThemeWrapper themeWrapper = new ContextThemeWrapper(configured, identity.baseTheme);
    Resources.Theme     theme        = themeWrapper.getTheme();
    if (identity.paletteOverlay != 0) {
      theme.applyStyle(identity.paletteOverlay, true);
    }
    return ThemeUtil.getThemedColor(configured, colorRef, theme);
  }

  /**
   * A key for the theme a context inflates views with, read from its markers. Cached views are only reused for the
   * same key, so a palette or System change never hands out views inflated with the previous roles.
   */
  public static @NonNull String themeKey(@NonNull Context context) {
    if (!CollieFlags.INSTANCE.getUserTheming()) {
      return "";
    }
    Resources.Theme theme = context.getTheme();
    TypedValue      value = new TypedValue();

    boolean dynamic = theme.resolveAttribute(org.signal.core.ui.R.attr.dynamic_colors, value, true) && value.data != 0;
    CharSequence palette = theme.resolveAttribute(org.signal.core.ui.R.attr.collie_palette, value, true) ? value.string : null;

    return ConfigurationUtil.getNightModeConfiguration(context) + "|" + dynamic + "|" + (palette != null ? palette : "");
  }

  private static boolean isEffectiveDark(@NonNull Context context) {
    switch (AppCompatDelegate.getDefaultNightMode()) {
      case AppCompatDelegate.MODE_NIGHT_YES:
        return true;
      case AppCompatDelegate.MODE_NIGHT_NO:
        return false;
      default:
        return isSystemInDarkTheme(context);
    }
  }

  private static @NonNull Context withNightMode(@NonNull Context context, boolean dark) {
    Configuration current = context.getResources().getConfiguration();
    int           night   = dark ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO;

    if (ConfigurationUtil.getNightModeConfiguration(current) == night) {
      return context;
    }

    Configuration configuration = new Configuration(current);
    configuration.uiMode = (configuration.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | night;
    return context.createConfigurationContext(configuration);
  }

  public static boolean systemThemeAvailable() {
    return Build.VERSION.SDK_INT >= 29;
  }

  public static void setDefaultDayNightMode(@NonNull Context context) {
    Theme theme = Theme.deserialize(PlainTextKeyValueStore.getTheme());

    if (theme == Theme.SYSTEM) {
      Log.d(TAG, "Setting to follow system expecting: " + ConfigurationUtil.getNightModeConfiguration(context.getApplicationContext()));
      AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
    } else if (DynamicTheme.isDarkTheme(context)) {
      Log.d(TAG, "Setting to always night");
      AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
    } else {
      Log.d(TAG, "Setting to always day");
      AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
    }

    CachedInflater.from(context).clear();
  }

  /**
   * Takes the system theme into account.
   */
  public static boolean isDarkTheme(@NonNull Context context) {
    Theme theme = Theme.deserialize(PlainTextKeyValueStore.getTheme());

    if (theme == Theme.SYSTEM && systemThemeAvailable()) {
      return isSystemInDarkTheme(context);
    } else {
      return theme == Theme.DARK;
    }
  }

  private static boolean isSystemInDarkTheme(@NonNull Context context) {
    return (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
  }

  /**
   * The theme an Activity is created with: its base style, the System flag, the palette and its overlay, whose variant
   * follows the Activity's effective brightness and palette policy. Activities recreate when it changes. Cached layouts
   * are cleared when the night mode or the System and palette part changes.
   */
  @VisibleForTesting
  static final class Identity {
    final @StyleRes int     baseTheme;
    final boolean           dynamicColors;
    final @Nullable String  paletteId;
    final @StyleRes int     paletteOverlay;

    Identity(@StyleRes int baseTheme, boolean dynamicColors, @Nullable String paletteId, @StyleRes int paletteOverlay) {
      this.baseTheme      = baseTheme;
      this.dynamicColors  = dynamicColors;
      this.paletteId      = paletteId;
      this.paletteOverlay = paletteOverlay;
    }

    @NonNull Identity withoutActivityStyle() {
      return new Identity(0, dynamicColors, paletteId, 0);
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (!(o instanceof Identity)) return false;
      Identity other = (Identity) o;
      return baseTheme == other.baseTheme &&
             dynamicColors == other.dynamicColors &&
             paletteOverlay == other.paletteOverlay &&
             Objects.equals(paletteId, other.paletteId);
    }

    @Override
    public int hashCode() {
      return Objects.hash(baseTheme, dynamicColors, paletteId, paletteOverlay);
    }
  }
}
