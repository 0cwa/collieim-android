# Fuller offline Alex conversation capture

Date: 2026-11-12. Dedicated Pixel 6 API 35 Google APIs x86_64 AVD `collie-alex-thread-api35`, serial `emulator-5562`; cached SDK, no user devices touched. Display override was `1080x2160` (physical emulated size before override `1080x2400`).

## Build and fixture

- App APK: `app/build/outputs/apk/prodWebsite/debug/app-prod-website-debug.apk`, SHA-256 `c4521417b26b1d9b50dd3acd809283a12d06ecc6cf30fc54ffe8ad9f93bf0045`.
- Test APK: `app/build/outputs/apk/androidTest/prodWebsite/debug/app-prod-website-debug-androidTest.apk`, SHA-256 `c5b08e79524ccda629f6ab94186ef3967df8e6ff9dac701e6525c987c9b81189`.
- Temporary fixture SHA-256 `53f22a394119c10dfe433521c366fa90485766f18980b7e63ebb02c2d93a73b9`; offline rule SHA-256 `8bcc74b872d5a373458fb1cdf3efa149dcfd54e11d0d28ca820edea12cd5a1b1`.
- Installed matching pair with `adb -s emulator-5562 install -r <app.apk>` and `adb -s emulator-5562 install -r <test.apk>`.
- Before install/seed and after restore: `cmd connectivity airplane-mode` = `enabled`; `settings get global mobile_data` = `0`; `cmd wifi status` = `Wifi is disabled`; `dumpsys connectivity` = `Active default network: none`. The fixture rule disabled app networking before local registration and asserted it remained disabled.
- Seed command: `adb -s emulator-5562 shell am instrument -w -r -e class org.thoughtcrime.securesms.OfflineConversationCaptureTest#seedOfflineDemo im.collie.app.test/org.thoughtcrime.securesms.testing.SignalTestRunner`. Result: `OK (1 test)`. Seeded 14 alternating fictional local messages for Alex.

## Capture

- Normal production launch: `adb -s emulator-5562 shell am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n im.collie.app/org.thoughtcrime.securesms.RoutingActivity`; opened the Alex row through the real UI. The conversation was already at its newest message; no scrolling or UI alteration was needed.
- Native screenshot command: `adb -s emulator-5562 exec-out screencap -p > ongoing-conversation-api35.png`.
- PNG: `/home/x/agent-scratch/collieim-android/pr-screenshots/ongoing-conversation-api35.png`, 1080×2160, SHA-256 `c70ebe40ab720f40c93d87254c3f906776b7678bfe011a4153b8c6edcc9f9f11`.
- Matching UI dump: `/home/x/agent-scratch/collieim-android/pr-screenshots/ongoing-conversation-ui.xml`, SHA-256 `fffa1529e2f04be53624f7dd51c951d096922c572f2d7876a24b62e15165cd03`.
- UI dump includes 14 message-body nodes. `Alex` title bounds `[284,168][376,231]`; conversation app bar `[147,115][731,283]`; composer panel `[0,1939][1080,2097]`, editor `[159,1960][706,2076]`. First visible message-text bound (“Are we still meeting by the river?”) `[74,250][802,307]`, naturally partly under the app bar. First fully readable message-text bound (“Yes! I'll be there at 10.”) `[414,373][1006,430]`.
- At capture, WMS `mCurrentFocus`, `mFocusedApp`, and `mFocusedWindow` named `im.collie.app/org.thoughtcrime.securesms.RoutingActivity`; its attributes had `LAYOUT_IN_SCREEN LAYOUT_INSET_DECOR SPLIT_TOUCH` with no `SECURE`; surface was ready, shown, and on-screen. The saved native PNG shows actual app-rendered Alex bubbles, app bar, and composer.

## Durable security restore

- Ran `OfflineDemoSecurityRestoreTest#restoreAfterCapture`: `OK (1 test)`. This no-registration helper calls `SignalStore.blockUntilAllWritesFinished()` after restoring security.
- Force-stopped only `im.collie.app`, then cold-launched the exported `RoutingActivity` normally. Production PID `4149`; top-resumed and focused window was `im.collie.app/org.thoughtcrime.securesms.RoutingActivity`. Cold WMS attributes included `LAYOUT_IN_SCREEN SECURE LAYOUT_INSET_DECOR SPLIT_TOUCH`. Guest still had airplane enabled, Wi-Fi disabled, mobile data `0`, and no active default network.
- The capture was visually accepted by site_author. No app source was changed by this runtime lane.

## Selected website and Fastlane assets

Fastlane1.png is the unmodified native PNG above. Website conversation.webp is its540×1080 derivative, SHA256230620ee9b676c145f4f729fd356258921826732fcad4050b889a072326edd62, generated with `cwebp -quiet -q 82 -resize 540 1080 ongoing-conversation-api35.png -o conversation.webp`. No crop, recoloring or compositing. The first fully readable bubble container begins355px down; its text begins373px down, versus985/1003px in the prior six-message image. The partly masked historical message at250px is not counted as fully readable.

Build checkout was ae8af5670c7a03dd77041a3dfba54a0085e01c0c plus the two temporary test fixtures only. Production app sources remain unchanged from02c414c19ae686451de85bd4f46cc9d3209f4c5c, including PR86. The app APK is bit-identical to the original accepted capture. The exact current fourteen-message/durable-write fixture is offline-demo-security.kt.txt; unchanged rule is offline-demo-rule.kt.txt. Build uses the same cached JDK21/SDK/Gradle command recorded in conversation-capture.md; it succeeded in1m8s (776tasks:323executed,453from cache). Seed and durable restore each collected/passed1test; no new encryption test is claimed because those assets were untouched.

Encryption/lock asset guards are unchanged: website encryption6862fbc60d5eb744ec9ba441bdec5ba4e064f3674343d5909760a19180bb0848; website lock8777f78f07d1c78f9305d0bac3107046ad1351b2bc65c3d7403c1aeacff561c6; Fastlane2 lock4536a908d8410fa9a03bc1640e8500ecf0873eafca9fe7deb82d06daa6401a38; Fastlane3 encryptionb85f15db028af21b96692d4baa431496e19ccc7304e11cd4612046ee0245f5da. No HTML/CSS/copy/layout change accompanies this selection.

User disk continuation authority: “Don't worry about disk free warning until it's below 30G, continue”. Before heavy writes agent-disk-check reported OK free106G/unallocated7G/metadata74%. Only the owned AVD/emulator, temporary app-test fixtures and generated worktree build directories are removed; shared caches and requested localhost8879 server remain.

The selected native PNG is committed as Fastlane1.png and matching current UI dump as chat-native-ui.xml in this proof directory. Temporary helper app sources and47 generated build/cache directories were removed after verification. The dedicated AVD/emulator was removed; the live server/shared caches remain.
