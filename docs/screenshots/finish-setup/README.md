# Finish setup captures

API 35 emulator (Pixel 9 profile, 1080 × 2424), synthetic local test account, no network. The app and test APKs were built together from this branch and run with:

```sh
./gradlew :app:assembleProdWebsiteDebug :app:assembleProdWebsiteDebugAndroidTest
adb install -r -t app/build/outputs/apk/prodWebsite/debug/app-prod-website-debug.apk
adb install -r -t app/build/outputs/apk/androidTest/prodWebsite/debug/app-prod-website-debug-androidTest.apk
adb shell am instrument -w -r -e class org.thoughtcrime.securesms.main.setup.FinishSetupScreenshotTest \
  im.collie.app.test/org.thoughtcrime.securesms.testing.SignalTestRunner
adb pull /sdcard/Android/data/im.collie.app/files/finish-setup-proof/.
```

Result: `OK (4 tests)`. The test turns screen security off only while it captures and restores every setting it changes. The bottom prompts are stubbed out so they don't cover the screens.

| File | Shows |
| --- | --- |
| [01-light-setup-empty-list.png](01-light-setup-empty-list.png) | Status line "Finish setup · 2 left" and the checklist on an empty chat list |
| [02-light-setup-sheet.png](02-light-setup-sheet.png) | The setup sheet opened from the status line |
| [03-light-updated.png](03-light-updated.png) | "Updated · See what's new" outranking setup |
| [04-light-whats-new.png](04-light-whats-new.png) | What's new, opened from the status line |
| [05-dark-push-app-missing.png](05-dark-push-app-missing.png) | Dark theme: UnifiedPush on with no push app installed, so a red status line and the problem banner |
| [06-large-text-updated.png](06-large-text-updated.png) | Font scale 2.0: the status line switches to the shorter "See what's new" instead of being cut off |

The test account keeps message notifications off inside Collie, which counts as the person's choice, so Notifications shows as allowed.
