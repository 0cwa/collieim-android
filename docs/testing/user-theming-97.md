# Built-in palette checks

The fixed-seed palette pilot for [discussion #97](https://github.com/0cwa/collieim-android/discussions/97)
stores a palette ID before unlock and applies it as a roles-only theme overlay. It
has no Appearance control yet. Select it in tests through
`PlainTextKeyValueStore.setPalette` or `SettingsValues.setColorPalette`. The
[theme propagation checks](theme-propagation-83.md) still apply.

## Selection contract

- `pref_dynamic_colors` remains the System choice. With it set, System applies
  where supported and Collie elsewhere, whatever palette ID is stored.
- Otherwise `pref_theme_palette` names a built-in palette. A missing or unknown
  ID renders Collie and stays stored until the next explicit choice.
- Choosing System clears the ID. Choosing Collie or a palette clears the flag.
  Mode changes, the legacy Dynamic Colors writer turning the flag off and
  post-restore synchronization keep the ID. Each choice commits once, then
  emits the existing `THEME` event once.
- Older versions ignore the ID and show System or Collie. V1 backups carry it as
  a string `SharedPreference` record. Import accepts it from the current and the
  historical Molly and Collie filenames, and skips records of other types.

## Generated palettes

`tooling/user-theming/palettes.json` defines each palette's stable ID, seed,
scheme and contrast. `tooling/user-theming/generate.py` writes:

- `core/ui/src/main/res/values/user_theme_palettes.xml`: light and dark role
  colors and overlays, including the `collie_palette` marker;
- `app/src/main/res/values/user_theme_palettes.xml`: app overlays that inherit
  the shared roles and bind app-specific attributes to them;
- `app/src/main/java/org/thoughtcrime/securesms/util/GeneratedPaletteOverlays.kt`:
  the ID to overlay map;
- `core/ui/src/main/java/org/signal/core/ui/compose/theme/GeneratedUserPalettes.kt`:
  the Compose schemes and swatches.

The roles come from Material Color Utilities in the Material Components AAR
pinned in `gradle/libs.versions.toml`. The script checks the AAR against
`gradle/verification-metadata.xml`, then fails if a text/background pair falls
below 4.5:1. App builds only compile the checked-in output. Regenerate after
changing the spec or the Material pin, and check for drift before review:

```sh
python3 tooling/user-theming/generate.py                  # uses the Gradle cache
python3 tooling/user-theming/generate.py --aar PATH.aar   # or a verified copy
python3 tooling/user-theming/generate.py --download       # or the pinned Google Maven URL
python3 tooling/user-theming/generate.py --check          # exits 1 on drift
python3 -B tooling/user-theming/test_generate.py             # no AAR needed
```

Java 17 or newer must be on `PATH` or in `JAVA_HOME`.

## Theme application

`DynamicTheme` resolves one identity per Activity: its base style, the System
flag, the palette and the overlay variant. Activities recreate on return when
it changes. Cached layouts are keyed by night mode and the theme markers, and
`DynamicTheme.resolveColor` applies the same overlay at AppCompat's effective
night mode for service and application contexts. The overlay variant follows
the Activity's effective brightness. Media preview always takes the dark roles;
wallpaper crop keeps Collie. Activity styles keep their window, translucency
and system bar policy. The base styles color their bars with the surface role.

`SignalTheme` reads the host's `collie_palette` marker and picks the variant
from its own `isDarkMode`, so forced-dark content uses the dark roles. Extended
surfaces and snackbar colors derive from the selected scheme. `ForceLightColors`
keeps Collie's light scheme for QR codes.

## JVM tests

```sh
./gradlew --no-daemon :app:testProdWebsiteDebugUnitTest \
  --tests org.thoughtcrime.securesms.util.UserPaletteThemeTest \
  --tests org.thoughtcrime.securesms.util.DynamicThemeTest \
  --tests org.thoughtcrime.securesms.keyvalue.SettingsValuesAppearanceTest \
  --tests org.thoughtcrime.securesms.keyvalue.MollyPreferenceCompatibilityTest \
  --tests org.thoughtcrime.securesms.backup.MollyBackupPreferencesTest
```

`UserPaletteThemeTest` runs on Robolectric at API 27, 31 and 35. It covers View
and Compose role parity at each brightness, Collie, palette and System
switching on return, the API 27 return to Collie, forced dark, off-Activity
lookups, media and translucent windows, dialog and sheet inheritance, and the cache key. Report these as host
JVM results, separately from device runs.

## Device checks

`UserPaletteRuntimeTest` stores the pilot, opens the Appearance settings and
checks that View and Compose content agree, then restores the saved choice.
Run it with the matching APKs as described in the theme propagation recipe:

```sh
./gradlew --no-daemon :app:connectedProdWebsiteDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=org.thoughtcrime.securesms.util.UserPaletteRuntimeTest \
  -Pandroid.testInstrumentationRunnerArguments.clearPackageData=false
```
