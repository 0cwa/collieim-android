# Built-in palette checks

Built-in palettes for [discussion #97](https://github.com/0cwa/collieim-android/discussions/97)
are stored as a palette ID before unlock and applied as a roles-only theme overlay.
TrailBlazer Beta users pick them in Settings > Appearance > Color, beside the
existing theme mode, and turn on Pure black there. Stable keeps its existing
Dynamic Colors switch. The [theme propagation checks](theme-propagation-83.md)
still apply.

## Appearance

- **Color** lists Collie, System where Android supports Dynamic Colors, then Blue,
  Teal, Plum and Slate. Each option previews the palette's own primary, secondary
  container, tertiary and surface roles at the current brightness. The selected
  option has a ring, a check and a bold name, and its name is repeated under the
  title, so the choice does not depend on color. Options are radio buttons in a
  wrapping group, so they keep full names at large font sizes.
- **Pure black** turns the base surfaces black whenever the app is effectively
  dark, including Follow system switching to dark. It is independent of the mode
  and the palette.
- Either control commits the choice and emits the existing `THEME` event once.
  Both Settings hosts recreate on that event and stay on Appearance. The retained
  overview recreates when it resumes. A System flag restored onto a device
  without Dynamic Colors shows Collie and stays stored.

## Selection contract

- Stable uses `pref_dynamic_colors`. TrailBlazer Beta uses that preference as
  a fallback until a local color is chosen. The System choice still renders
  Collie on devices without Dynamic Colors.
- The local `pref_theme_palette` stores the current color as Collie, System or a
  built-in palette ID. An unknown ID renders Collie and stays stored until the
  next explicit choice.
- Mode changes and restore synchronization keep the color choice. An explicit
  color choice persists before the app emits the existing `THEME` event once.
- The palette and Pure black preferences are device-local. Select them again
  after restoring on a new device. Portable Dynamic Colors and theme settings
  still use their existing Molly-compatible restore paths.
- `pref_pure_black` is stored as a boolean beside the palette ID. A value of the
  wrong type reads as off. Both settings are enabled by `CollieFlags.userTheming`
  in TrailBlazer Beta.

## Generated palettes

`tooling/user-theming/palettes.json` defines each palette's stable ID, English
name, seed, scheme and contrast, in display order. The name must match the
translatable `user_palette_name_<id>` string in `core/ui`. Name a palette after
its rendered primary, not its seed: Expressive rotates the hue, so Plum comes from
a gold seed (`#B8860B`), while a plum seed would render teal. `tooling/user-theming/generate.py` writes:

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
below 4.5:1, or if a dark foreground drawn on base surfaces falls below 4.5:1
on Pure black. App builds only compile the checked-in output. Regenerate after
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

Pure black is a second overlay, `ThemeOverlay.Collie.PureBlack` in `core/ui`,
applied after the palette when an Activity follows the app's mode and is
effectively dark. It sets the background, surface, surface dim and lowest
container roles to black and the `collie_pure_black` marker. Windows and bars
colored with the surface follow. Elevated containers, accents and foregrounds
keep the palette's values. Media preview (always dark), wallpaper crop and
Collie's light windows keep their usual surfaces.

`SignalTheme` reads the host's `collie_palette` marker and picks the variant
from its own `isDarkMode`, so forced-dark content uses the dark roles. It applies
Pure black only when the host carries `collie_pure_black` and `isDarkMode` is set,
so forced-dark content in a light Activity keeps the palette's dark surfaces. Extended
surfaces and snackbar colors derive from the selected scheme. `ForceLightColors`
keeps Collie's light scheme for QR codes.

## JVM tests

```sh
./gradlew --no-daemon -PcollieBetaInstrumentation=true :app:testProdWebsiteBetaUnitTest \
  --tests org.thoughtcrime.securesms.util.UserPaletteThemeTest \
  --tests org.thoughtcrime.securesms.util.DynamicThemeTest \
  --tests org.thoughtcrime.securesms.components.settings.app.appearance.AppearanceSettingsViewModelTest \
  --tests org.thoughtcrime.securesms.keyvalue.SettingsValuesAppearanceTest \
  --tests org.thoughtcrime.securesms.keyvalue.MollyPreferenceCompatibilityTest \
  --tests org.thoughtcrime.securesms.backup.MollyBackupPreferencesTest \
  :feature:app-settings:testDebugUnitTest \
  --tests org.signal.appsettings.appearance.AppearanceSettingsScreenTest
```

`UserPaletteThemeTest` runs on Robolectric at API 27, 31 and 35. It covers View
and Compose role parity at each brightness, Collie, palette and System
switching on return, the API 27 return to Collie, forced dark, off-Activity
lookups, media and translucent windows, dialog and sheet inheritance, and the cache key. Report these as host
JVM results, separately from device runs.

## Device checks

`AppearancePaletteRuntimeTest` drives the real color picker and Pure black switch
in the fragment and navigation Settings hosts, light and dark. It switches from
Collie straight between two palettes, turns Pure black on, returns to the
overview and back to Collie, and keeps a screenshot of each state. One case
captures the picker at font scale 2.0. `UserPaletteRuntimeTest` stores a palette,
opens Settings and checks that View and Compose content agree, then restores the
saved choice. `AppearanceThemePropagationTest` picks System from the same picker.
Run it with the matching APKs as described in the theme propagation recipe:

```sh
./gradlew --no-daemon -PcollieBetaInstrumentation=true :app:connectedProdWebsiteBetaAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=org.thoughtcrime.securesms.components.settings.app.appearance.AppearancePaletteRuntimeTest \
  -Pandroid.testInstrumentationRunnerArguments.clearPackageData=false
```

For a cold start, run both stages on a disposable emulator. The write stage
creates a synthetic account. The verify stage checks that the app process
changed, opens Appearance and checks the selected preset, the Pure black
switch and the black surface. Run these stages with the instrumentation
runner directly so package data survives between stages. The Gradle connected
task uninstalls the app after completing its run.

```sh
adb install -r app/build/outputs/apk/prodWebsite/beta/app-prod-website-beta.apk
adb install -r -t app/build/outputs/apk/androidTest/prodWebsite/beta/app-prod-website-beta-androidTest.apk

run_cold_start_stage() {
  adb shell am instrument -w \
    -e class org.thoughtcrime.securesms.components.settings.app.appearance.AppearanceColdStartTest \
    -e clearPackageData false -e appearanceColdStartStage "$1" \
    im.collie.app.trailblazer.test/org.thoughtcrime.securesms.testing.SignalTestRunner
}

run_cold_start_stage write
# Once the write stage reports OK (1 test):
run_cold_start_stage verify
```
