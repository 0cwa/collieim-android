# Project log captures

API 35 emulator (Pixel 9 profile, 1080 × 2424), synthetic local test account, no network. The app and test APKs were built together from this branch and run with:

```sh
./gradlew :app:assembleProdWebsiteDebug :app:assembleProdWebsiteDebugAndroidTest
adb install -r -t app/build/outputs/apk/prodWebsite/debug/app-prod-website-debug.apk
adb install -r -t app/build/outputs/apk/androidTest/prodWebsite/debug/app-prod-website-debug-androidTest.apk
adb shell am instrument -w -r -e class org.thoughtcrime.securesms.main.setup.ProjectLogScreenshotTest \
  im.collie.app.test/org.thoughtcrime.securesms.testing.SignalTestRunner
adb pull /sdcard/Android/data/im.collie.app/files/project-log-proof/.
```

Result: `OK (2 tests)`. The test turns screen security off only while it captures and restores every setting it changes.

| File | Shows |
| --- | --- |
| [01-light-this-update.png](01-light-this-update.png) | What's new opened from "Updated · See what's new": this update's highlights, then a button to the log |
| [02-light-project-log.png](02-light-project-log.png) | The Project log tab, with this build's changes open |
| [03-dark-help.png](03-dark-help.png) | Settings → Help with the new What's new row |
| [04-dark-project-log-from-help.png](04-dark-project-log-from-help.png) | The log opened from Help, every release closed except the oldest |
