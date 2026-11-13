package org.thoughtcrime.securesms.util;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Build;
import android.view.ContextThemeWrapper;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.StyleRes;
import androidx.appcompat.app.AppCompatDelegate;

import com.google.android.material.color.DynamicColors;

import org.signal.core.ui.util.ThemeUtil;
import org.signal.core.util.logging.Log;
import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.keyvalue.SettingsValues.Theme;
import org.signal.core.util.ConfigurationUtil;

public class DynamicTheme {

  private static final String TAG = Log.tag(DynamicTheme.class);

  private static int globalNightModeConfiguration;
  private static boolean globalDynamicColors;

  private int onCreateNightModeConfiguration;
  private @StyleRes int onCreateTheme;

  private static final int regularTheme = R.style.Signal_DayNight;
  private static final int dynamicTheme = R.style.Theme_Molly_Dynamic;

  public void onCreate(@NonNull Activity activity) {
    int     previousGlobalConfiguration = globalNightModeConfiguration;
    boolean previousGlobalDynamicColors = globalDynamicColors;

    onCreateNightModeConfiguration = ConfigurationUtil.getNightModeConfiguration(activity);
    onCreateTheme                  = getTheme(activity);
    globalNightModeConfiguration   = onCreateNightModeConfiguration;
    globalDynamicColors            = useDynamicColors(activity);

    activity.setTheme(onCreateTheme);

    if (previousGlobalConfiguration != globalNightModeConfiguration || previousGlobalDynamicColors != globalDynamicColors) {
      Log.d(TAG, "Night mode or dynamic colors changed. Clearing cached layouts.");
      CachedInflater.from(activity).clear();
    }
  }

  public void onResume(@NonNull Activity activity) {
    if (onCreateTheme != getTheme(activity)) {
      // Changing palettes does not change uiMode, so AppCompat will not recreate a retained activity.
      // Rebuild its view and Compose hierarchies with the selected theme when returning from Settings.
      Log.d(TAG, "Theme changed since creation. Recreating activity.");
      CachedInflater.from(activity).clear();
      activity.recreate();
    } else if (onCreateNightModeConfiguration != ConfigurationUtil.getNightModeConfiguration(activity)) {
      Log.d(TAG, "Create configuration different from current previous: " + onCreateNightModeConfiguration + " now: " + ConfigurationUtil.getNightModeConfiguration(activity));
      CachedInflater.from(activity).clear();
    }
  }

  protected @StyleRes int getRegularTheme() {
    return regularTheme;
  }

  protected @StyleRes int getDynamicTheme() {
    return dynamicTheme;
  }

  public final @StyleRes int getTheme(@NonNull Context context) {
    return useDynamicColors(context) ? getDynamicTheme() : getRegularTheme();
  }

  public static boolean useDynamicColors(@NonNull Context context) {
    return isDynamicColorsAvailable() && TextSecurePreferences.isDynamicColorsEnabled(context);
  }

  public static boolean isDynamicColorsAvailable() {
    return DynamicColors.isDynamicColorAvailable();
  }

  public static @ColorInt int resolveColor(@NonNull Context context, int colorRef) {
    int resId = useDynamicColors(context) ? dynamicTheme : regularTheme;
    ContextThemeWrapper themeWrapper = new ContextThemeWrapper(context, resId);
    return ThemeUtil.getThemedColor(context, colorRef, themeWrapper.getTheme());
  }

  public static boolean systemThemeAvailable() {
    return Build.VERSION.SDK_INT >= 29;
  }

  public static void setDefaultDayNightMode(@NonNull Context context) {
    Theme theme = Theme.deserialize(TextSecurePreferences.getTheme(context));

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
    Theme theme = Theme.deserialize(TextSecurePreferences.getTheme(context));

    if (theme == Theme.SYSTEM && systemThemeAvailable()) {
      return isSystemInDarkTheme(context);
    } else {
      return theme == Theme.DARK;
    }
  }

  private static boolean isSystemInDarkTheme(@NonNull Context context) {
    return (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
  }
}
