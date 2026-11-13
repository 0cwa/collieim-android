# Release notes captures

## Capture status after the 2026-11-13 follow-up

The images below are historical captures from before the long-edition layout and support-button changes. They do not show the current action group, reopening hint, fixed close control, or temporary battery-banner suppression.

Fresh API 35 screenshots and native execution remain pending. The follow-up environment had no emulator acceleration, and neither software-emulator configuration completed Android boot. Run the matching app and test APKs on a ready API 35 device using the command below before merging, and replace these captures after reviewing the current layouts.

## Historical API 35 run

API 35, Pixel 6 emulator, 1080 × 2400 portrait and 2400 × 1080 landscape.
App and instrumentation APKs were assembled together from `5660b3934dea8855530fb32ab6e10f12754a54c5`:

```sh
./gradlew --no-daemon :app:assembleProdWebsiteDebug :app:assembleProdWebsiteDebugAndroidTest
adb -s emulator-5562 shell am instrument -w -r \
  -e class org.thoughtcrime.securesms.main.updates.ReleaseNotesUiTest,org.thoughtcrime.securesms.main.updates.ReleaseNotesLinksTest \
  im.collie.app.test/org.thoughtcrime.securesms.testing.SignalTestRunner
```

Result: `OK (6 tests)` — five native UI methods and one link-destination method, no skipped methods. The fixture disables screen security for capture and restores its previous setting in `finally`. It also restores theme, font scale, orientation, network and dismissal settings.

The captures use a synthetic local test account. The battery-restriction prompt remains visible alongside the release notes in these captures; passing instrumentation does not establish that this interaction is visually correct. Landscape captures also show the very short reading area at large text sizes. These are the implementation's actual rendered states, with no retouching.

- [Minimized](01-light-minimized.png)
- [Expanded](02-light-expanded.png)
- [Support footer](03-light-support.png)
- [Dark expanded](04-dark-expanded.png)
- [Large text](05-large-text-expanded.png)
- [Large-text support footer](06-large-text-support.png)
- [Landscape large text](07-landscape-large-text.png)
- [Landscape actions](08-landscape-large-text-actions.png)
