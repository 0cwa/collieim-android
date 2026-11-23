# Theme propagation regression checks

These checks cover Appearance changes while Collie remains running, Dynamic
Colors, the Compose media viewer and message overlays. Use the toolchain in
[BUILD.md](../BUILD.md) and the screenshot requirements in
[AGENTS.md](../../AGENTS.md).

## JVM tests

```sh
./gradlew --no-daemon :app:testProdWebsiteDebugUnitTest \
  --tests org.thoughtcrime.securesms.util.DynamicThemeTest \
  --tests org.thoughtcrime.securesms.util.OverlayThemeTest \
  --tests org.thoughtcrime.securesms.conversation.v2.ChatReactionOverlayThemeTest \
  --tests org.thoughtcrime.securesms.components.settings.app.appearance.AppearanceSettingsViewModelTest
```

These cover retained activity themes, overlay resources, the Compose scrim and
Appearance settings. Robolectric results are host JVM results, including tests
configured with `sdk = [35]`; report them separately from device execution.
Reports are in `app/build/reports/tests/testProdWebsiteDebugUnitTest` and
`app/build/test-results/testProdWebsiteDebugUnitTest`.

## API 35 instrumentation

Use a disposable API 35 emulator with Dynamic Colors support. Check the target
with `adb devices` and `adb shell getprop ro.build.version.sdk`, then build both
APKs together:

```sh
./gradlew --no-daemon \
  :app:assembleProdWebsiteDebug \
  :app:assembleProdWebsiteDebugAndroidTest
```

Run each class separately, using its full package name from this table:

| Class | Package | Coverage |
| --- | --- | --- |
| `AppearanceThemePropagationTest` | `org.thoughtcrime.securesms.components.settings.app.appearance` | Dynamic Colors through both Settings hosts, and Dark/Light/System changes reaching the overview. |
| `MediaThemeRuntimeTest` | `org.thoughtcrime.securesms.mediapreview` | Compose image/video rendering and palette changes while retaining the application process. |
| `MessageOverlayThemeTest` | `org.thoughtcrime.securesms.conversation.v2` | Light/dark message menus, reaction colors and scrims with Dynamic Colors off/on. |

For example:

```sh
theme_class=org.thoughtcrime.securesms.components.settings.app.appearance.AppearanceThemePropagationTest
./gradlew --no-daemon :app:connectedProdWebsiteDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class="$theme_class" \
  -Pandroid.testInstrumentationRunnerArguments.clearPackageData=false
```

Use one class per invocation: the AGP/test-engine argument handling can truncate
a comma-separated class list. Gradle installs matching app/test APKs and the
configured Android Test Orchestrator. Require every selected class to execute
and pass; an APK build or a zero-test run does not establish runtime coverage.

## Capture and review

Before the next invocation replaces outputs, copy these generated directories
to a scratch evidence directory:

- `app/build/outputs/androidTest-results`
- `app/build/reports/androidTests`
- `app/build/outputs/connected_android_test_additional_output`

The fixtures temporarily disable screen security and restore the saved settings.
They write screenshots to `additionalTestOutputDir` when supplied, otherwise to
the app's external `theme-regression` directory. If that fallback was used, pull
it before resetting or uninstalling the app:

```sh
adb pull /sdcard/Android/data/im.collie.app/files/theme-regression ./theme-regression-screenshots
```

Retain the exact source SHA/tree, emulator fingerprint/API level, app/test APK
hashes, executed test counts, reports and screenshots together in the PR evidence.
Inspect palette changes, readable controls and the red/green/blue image/video
fixtures. The Compose media viewer follows the selected light/dark appearance
and retains its translucent window. Appearance changes must reach the overview
and overlays without restarting the application process.

The test sources define screenshot names, decoded-media readiness and color
checks. Keep generated screenshots and execution transcripts out of `docs/`.
Report focused regression results alongside the whole-suite result, and compare
unrelated suite failures against the same baseline before attributing them to a
change. Compiling the test APK does not execute these checks.
