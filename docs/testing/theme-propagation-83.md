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

Run each focused instrumentation class separately. The current AGP 9.4.0 and
Android test engine 1.0.1 serialize runner arguments using commas, then split
on those commas when reading them. A comma-separated `class` argument therefore
reaches the runner as only its first class. This was observed in the
[initial validation run](https://github.com/0cwa/collieim-android/actions/runs/43127416339)
and confirmed in the published
[AGP sources](https://dl.google.com/dl/android/maven2/com/android/tools/build/gradle/9.4.0/gradle-9.4.0-sources.jar)
and [engine sources](https://dl.google.com/dl/android/maven2/com/android/tools/androidtest/android-test-engine/1.0.1/android-test-engine-1.0.1-sources.jar).

Gradle installs the matching app and test APKs and the configured Android Test
Orchestrator. Capture each class's reports, screenshots, and APK hashes before
the next invocation replaces the generated outputs. This Bash loop attempts all
three classes even if one fails, then returns a failure if any invocation failed:

```bash
set -o pipefail
theme_evidence="$(mktemp -d "${TMPDIR:-/tmp}/collie-theme-83.XXXXXX")"
git rev-parse HEAD HEAD^{tree} > "$theme_evidence/source.txt"
sha256sum app/build/outputs/apk/prodWebsite/debug/*.apk \
  app/build/outputs/apk/androidTest/prodWebsite/debug/*.apk \
  > "$theme_evidence/apk-sha256.txt"
theme_status=0
for theme_class in \
  org.thoughtcrime.securesms.components.settings.app.appearance.AppearanceThemePropagationTest \
  org.thoughtcrime.securesms.mediapreview.MediaThemeRuntimeTest \
  org.thoughtcrime.securesms.conversation.v2.MessageOverlayThemeTest; do
  theme_output="$theme_evidence/$theme_class"
  mkdir -p "$theme_output"
  # These generated directories were saved after the previous invocation.
  rm -rf app/build/outputs/androidTest-results \
    app/build/reports/androidTests \
    app/build/outputs/connected_android_test_additional_output
  if ./gradlew --no-daemon :app:connectedProdWebsiteDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class="$theme_class" \
    -Pandroid.testInstrumentationRunnerArguments.clearPackageData=false \
    | tee "$theme_output/connected-tests.log"; then
    theme_class_status=0
  else
    theme_class_status=$?
    theme_status=1
  fi
  printf '%s\n' "$theme_class_status" > "$theme_output/exit-code.txt"
  sha256sum app/build/outputs/apk/prodWebsite/debug/*.apk \
    app/build/outputs/apk/androidTest/prodWebsite/debug/*.apk \
    > "$theme_output/apk-sha256.txt" || theme_status=1
  for theme_report in androidTest-results connected_android_test_additional_output; do
    if [ -d "app/build/outputs/$theme_report" ]; then
      cp -a "app/build/outputs/$theme_report" "$theme_output/$theme_report" || theme_status=1
    fi
  done
  if [ -d app/build/reports/androidTests ]; then
    cp -a app/build/reports/androidTests "$theme_output/androidTests" || theme_status=1
  fi
done
printf 'Evidence: %s\n' "$theme_evidence"
test "$theme_status" -eq 0
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

Before each overview capture, the test waits for the Settings window to detach,
Main to regain window focus with its search control displayed, and a rendered
Main frame to be committed. This prevents a last Settings frame from being
misidentified as the overview merely because both surfaces share a palette.

`MediaThemeRuntimeTest` has four tests: image preview in light app mode and video
preview in dark app mode for each of the legacy and Compose media viewers. Each
test changes the palette off, on, and off again while retaining the application
process, checking actual activity surfaces and controls. The test assets include
a generated PNG and a matching H.264 video. Every capture must contain their
red, green, and blue bands; decoded/load-state flags alone do not establish that
the image or video has appeared on screen.

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
directory and the instrumentation reports after each class, as in the loop above.
Gradle collects additional output before uninstalling the APKs. When the app is
still installed and the fallback location was used, pull its files before
resetting or uninstalling it:

```sh
adb pull /sdcard/Android/data/im.collie.app/files/theme-regression ./theme-regression-screenshots
```

Retain the tested SHA/tree, emulator fingerprint, API level, app and test APK
SHA-256 digests, test counts, reports, and screenshots together. Inspect the
images for the selected palette and readable foreground colors. A nonempty PNG
alone does not prove the screen rendered correctly.

### Media fixtures and capture readiness

The two media fixtures are 160×90 with red, green, and blue vertical bands of
54, 52, and 54 pixels. The video lasts one second at 10 frames per second and
declares limited-range SDR BT.709 matrix, transfer, and primaries. Generate them
from the repository root with FFmpeg:

```sh
ffmpeg -y -v error -f lavfi \
  -i 'color=c=red:s=160x90:r=10:d=1,format=rgb24,drawbox=x=54:y=0:w=52:h=90:color=0x00FF00:t=fill,drawbox=x=106:y=0:w=54:h=90:color=blue:t=fill' \
  -frames:v 1 -update 1 app/src/androidTest/assets/images/theme_sample.png

ffmpeg -y -v error -loop 1 \
  -i app/src/androidTest/assets/images/theme_sample.png -t 1 -r 10 \
  -vf 'scale=out_color_matrix=bt709:out_range=tv,format=yuv420p,setparams=range=limited:color_primaries=bt709:color_trc=bt709:colorspace=bt709' \
  -c:v libx264 -profile:v baseline -level 1.0 -preset veryslow -crf 18 \
  -color_range tv -colorspace bt709 -color_primaries bt709 -color_trc bt709 \
  -movflags +faststart -an app/src/androidTest/assets/videos/theme_sample.mp4
```

Check the resulting stream metadata with:

```sh
ffprobe -v error -select_streams v:0 \
  -show_entries stream=codec_name,profile,width,height,pix_fmt,color_range,color_space,color_transfer,color_primaries,duration,nb_frames \
  -of json app/src/androidTest/assets/videos/theme_sample.mp4
```

`MediaThemeRuntimeTest` samples two interior rows of all three bands using the
image's source-to-view coordinates or the video's content-frame bounds. Each
sample must have its expected channel at least 192 and the other channels at
most 64. This verifies the decoded content and its arrangement without equating
encoder RGB values with the output of a color-managed video decoder. A blank
frame, uniform placeholder, or covering dialog cannot satisfy this pattern.
The separate theme/chrome checks still use their exact resource colors and
alpha values, with the existing two-level screenshot rounding tolerance.

Capture waits continue synchronizing the Compose test clock while the real
Android view draws. A timeout preserves a `-frame-failure.png` and records the
sample coordinates and observed ARGB colors in the failure message.

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
