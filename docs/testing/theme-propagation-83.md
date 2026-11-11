# Theme propagation regression checks (issue 83)

These checks cover changing Appearance settings while Collie remains running,
Dynamic Colors on supported Android versions, media-viewer surfaces, and message
overlays. Keep the default Collie palette intact. Additional selectable palettes
are outside this correction.

Record the exact source revision and tree for every run:

```sh
git rev-parse HEAD HEAD^{tree}
git status --short
```

## Focused JVM tests

Use JDK 21 and the Android SDK/build tools declared in
`gradle/libs.versions.toml`. Run:

```sh
./gradlew --no-daemon :app:testProdWebsiteDebugUnitTest \
  --tests org.thoughtcrime.securesms.util.DynamicThemeTest \
  --tests org.thoughtcrime.securesms.util.OverlayThemeTest \
  --tests org.thoughtcrime.securesms.conversation.v2.ChatReactionOverlayThemeTest \
  --tests org.thoughtcrime.securesms.components.settings.app.appearance.AppearanceSettingsViewModelTest
```

| Test class | Coverage |
| --- | --- |
| `DynamicThemeTest` | A retained activity adopts Dynamic Colors on return, switches back to Collie colors, retains saved activity state, and avoids unnecessary recreation when the palette is unchanged. Includes unsupported Android behavior and inflater-cache invalidation. |
| `OverlayThemeTest` | Resolves the actual media-viewer themes, inflates image/video chrome and context-menu resources, checks dynamic surface and scrim colors, and preserves the static palette and scrim opacity. Includes API 30, 31, and 35 configurations. |
| `ChatReactionOverlayThemeTest` | Renders the Compose message overlay and checks the themed scrim rather than a fixed fallback resource. |
| `AppearanceSettingsViewModelTest` | Existing coverage for theme persistence, Dynamic Colors selection, and the settings actions. |

Robolectric runs against simulated Android APIs in the host JVM. Report these
results separately from emulator or physical-device results, including when a
test declares `sdk = [35]`.

The report is under `app/build/reports/tests/testProdWebsiteDebugUnitTest` and
the machine-readable results are under
`app/build/test-results/testProdWebsiteDebugUnitTest`. Require a nonzero executed
count for every selected class; skipped tests do not establish coverage.

## Android 15 UI tests

Use a fresh API 35 emulator with Dynamic Colors support and a phone-sized
configuration. Check the connected target before running:

```sh
adb devices
adb shell getprop ro.build.version.sdk
adb shell getprop ro.build.fingerprint
```

The API level must report `35`. Build the app and instrumentation APKs from the
same source checkout and build invocation:

```sh
./gradlew --no-daemon \
  :app:assembleProdWebsiteDebug \
  :app:assembleProdWebsiteDebugAndroidTest
```

Run the focused instrumentation classes. Gradle installs the matching app and test
APKs and the configured Android Test Orchestrator:

```sh
theme_tests=org.thoughtcrime.securesms.components.settings.app.appearance.AppearanceThemePropagationTest
theme_tests="$theme_tests,org.thoughtcrime.securesms.mediapreview.MediaThemeRuntimeTest"
theme_tests="$theme_tests,org.thoughtcrime.securesms.conversation.v2.MessageOverlayThemeTest"
./gradlew --no-daemon :app:connectedProdWebsiteDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class="$theme_tests" \
  -Pandroid.testInstrumentationRunnerArguments.clearPackageData=false
```

The `clearPackageData=false` override preserves screenshots until collection on
this disposable emulator. The fixture restores the theme, Dynamic Colors,
screen-security, lock settings, and AppCompat night mode after each test.

`AppearanceThemePropagationTest` exercises real Appearance controls in both the
fragment-based Settings activity and the newer navigation host, returning to the
retained `MainActivity`. Its three tests check:

1. Dynamic Colors on and off in light mode through the fragment-based host.
2. Dynamic Colors on and off in dark mode through the navigation host.
3. Dark, Light, and System selections reaching the conversation overview.

Each theme change must become visible when returning from Settings, without
force-stopping or relaunching Collie and without waiting in the background. The
test checks the application identity and process ID, resolved theme attributes,
and rendered overview surface. Activity recreation is expected when required to
apply a new resource theme; it must preserve the application process.

`MediaThemeRuntimeTest` has four tests: image preview in light app mode and video
preview in dark app mode for each of the legacy and Compose media viewers. Each
test changes the palette off, on, and off again while retaining the application
process, checking actual activity surfaces and controls. The test assets include
an image and a small, generated H.264 video.

`MessageOverlayThemeTest` has two tests, for light and dark mode. Each long-presses
a real seeded conversation message with Dynamic Colors off and on, checking the
rendered scrim, context-menu surface, and selected reaction color.

Require all nine tests to execute and pass: three overview, four media, and two
message-overlay tests. A run that discovers zero tests, skips a selected class,
fails to install an APK, or only compiles the test APK does not establish Android
15 runtime coverage.

### Screenshot evidence

The tests temporarily disable screen security so `FLAG_SECURE` does not blank
the images, then restore the previous setting. They write PNGs to the runner's
`additionalTestOutputDir` when supplied, or to the app's external files directory
under `theme-regression`.

Expect 23 distinct screenshots: seven overview states, twelve media states
(legacy/Compose, image/video, and the off/on/off palette sequence), and four
message-overlay states (light/dark with Dynamic Colors off/on). The separate
validation workflow requires every expected filename, so duplicated copies in
different report directories cannot conceal a missing screen.

Collect Gradle's `app/build/outputs/connected_android_test_additional_output`
directory and the instrumentation reports. For the fallback location, pull the
files before resetting or uninstalling the app:

```sh
adb pull /sdcard/Android/data/im.collie.app/files/theme-regression ./theme-regression-screenshots
```

Retain the tested SHA/tree, emulator fingerprint, API level, app and test APK
SHA-256 digests, test counts, reports, and screenshots together. Inspect the
images for the selected palette and readable foreground colors. A nonempty PNG
alone does not prove the screen rendered correctly.

## Visual review matrix

Review the instrumentation screenshots against this matrix. Complete any
remaining combinations on API 35 using a test conversation containing an image,
a video, and ordinary messages. The automated media cases pair images with light
app mode and videos with dark app mode; the JVM resource tests cover both media
modes across the theme configurations. Do not restart the application between
Appearance changes and these checks.

The legacy `MediaPreviewActivity` deliberately uses a dark presentation in both
app modes: with Dynamic Colors off, its backdrop is `#121212` and its chrome is
`#DE121212`. The Compose `MediaViewerActivity` follows the selected light/dark
appearance and retains its translucent window. Check those distinct behaviors:

| Appearance state | Legacy media preview | Compose media viewer | Message long press |
| --- | --- | --- | --- |
| Light, Dynamic Colors off | Neutral dark image/video backdrop and chrome remain unchanged. | The regular light Collie surface remains unchanged. | Default light Collie menu and scrim colors and opacity remain unchanged. |
| Dark, Dynamic Colors off | Neutral dark image/video backdrop and chrome remain unchanged. | The regular dark Collie surface remains unchanged. | Default dark Collie menu and scrim colors and opacity remain unchanged. |
| Light, Dynamic Colors on | Backdrop and chrome use the dark system-derived palette, with readable foreground controls. | The viewer uses the light system-derived palette and keeps its translucent window. | Menu and scrim use the light system-derived palette. |
| Dark, Dynamic Colors on | Backdrop and chrome use the dark system-derived palette. | The viewer uses the dark system-derived palette and keeps its translucent window. | Menu and scrim use the dark system-derived palette. |
| Dynamic Colors turned off again | Reopen image and video details; the neutral default colors return immediately. | Reopen media; the selected light/dark Collie palette returns immediately. | Reopen the message menu; Collie colors return immediately. |

Check the legacy media viewer and the Compose media viewer when both routes are
available in the test configuration, recording which route was exercised.
Check the Compose message-reaction overlay and its context menu, including the
transparent scrim over the conversation. Capture each affected screen with the
screen-security setting temporarily disabled and restore that setting after
capture. Label emulator and physical-device evidence distinctly.

## CI evidence and interpretation

The normal app PR workflows run Layer trailers, Fastlane metadata, Fast lint,
unit tests, the optional debug APK build, direct source checks, and workflow
CodeQL. Workflow CodeQL analyzes GitHub Actions; it does not establish Kotlin or
Java correctness. The existing debug APK workflow builds the app APK without
running instrumentation.

For a separate validation workflow, pin checkout to the immutable app PR SHA,
build both APKs together, run the selected tests on API 35, and retain the evidence
above. Keep infrastructure changes separate from the app correction. Record the
workflow source revision as well as the app source it checked out.

Report focused regression results alongside the whole-suite result. If the full
suite fails, compare its failures with the same baseline revision before
attributing them to this correction. Do not treat a pre-existing suite failure
as a passing check, or a successful APK build as executed UI coverage.

During initial preparation, the available local workspace had a Java runtime
but no full JDK, Android SDK, emulator, or KVM device. Local source inspection and
non-Gradle checks therefore provided no Android 15 runtime evidence. Record any
subsequent CI or device execution separately with its exact revision and result.
