# Current encryption assets: native viewport follow-up

The website retains the original corrected 540×1200 WebP described below. Fastlane 3.png now uses a **new, unmodified 1080×2160 native API 35 capture**, because the original 1080×2400 PNG exceeded the documented 2:1 distribution limit. Its hash is b85f15db028af21b96692d4baa431496e19ccc7304e11cd4612046ee0245f5da. Title 451, bottom button 1760 and system insets 115/63 give 336/337px clearance; the offline centering method passed without skip. See [conversation-capture.md](conversation-capture.md) for matching APKs, exact helper sources, actual UI bounds and durable security restoration.

The original 1080×2400 capture remains preserved in signed 555fff83a6b7e8f5b8de89f522859f250a166b5f, linked at the end of this historical record, and as the website derivative's source. These are two real captures at different viewport sizes; they are not claimed to have identical pixels/hashes. The original record below describes the first correction, not the current Fastlane dimensions.

# Fresh database-encryption capture

The native Android app sources are unchanged from `02c414c19ae686451de85bd4f46cc9d3209f4c5c`, which contains PR 86's `85779e109803` system-bar and centering fix. Website-only changes do not alter that native source. Both app and instrumentation APKs were assembled together from this worktree using the cached SDK, JDK 21 and Gradle dependencies:

```sh
agent-heavy --mem 16G -- env JAVA_HOME=/var/home/x/.local/opt/jdk-21.0.13+9 ANDROID_HOME=/var/home/x/Android/Sdk ANDROID_SDK_ROOT=/var/home/x/Android/Sdk GRADLE_USER_HOME=/var/home/x/.gradle ./gradlew --offline --no-daemon --build-cache --console=plain --max-workers=6 -Dorg.gradle.jvmargs="-Xmx6g -Xms256m -XX:MaxMetaspaceSize=1g" -Pkotlin.daemon.jvmargs=-Xmx2g :app:assembleProdWebsiteDebug :app:assembleProdWebsiteDebugAndroidTest
```

Execution used the host bridge with the explicit host-mapped worktree directory. The dedicated Pixel 6 API 35 emulator ran headlessly on `emulator-5562` under a 6G resource job; `emulator-5554` belonged to another task and was untouched.

APK SHA-256 values:

- App: `c4521417b26b1d9b50dd3acd809283a12d06ecc6cf30fc54ffe8ad9f93bf0045`
- Matching test APK: `9752825848e7ab43c554820d914bb96f42221da5e89483b66842ef37296ae80e`

The following method ran on the matching pair and returned `OK (1 test)`, with no assumption skip:

```sh
adb -s emulator-5562 shell am instrument -w -r -e class org.thoughtcrime.securesms.SystemBarClearanceTest#databaseEncryptionScreenClearsStatusBarAndCentersContent im.collie.app.test/org.thoughtcrime.securesms.testing.SignalTestRunner
```

Direct instrumentation launches initially left an alpha-zero starting-reveal layer despite WMS focus. Black captures and unrelated emulator launcher/SystemUI/Messages ANR overlays were rejected. A SurfaceFlinger inspection identified the presentation problem; it was not treated as proof of a secure-capture API defect. The successful capture opened the normal production activity outside instrumentation, after dismissing unrelated emulator ANRs. The API 35 debug emulator's root shell launched the non-exported activity; no host privilege or production account was used. The emulator was offline during this path.

A temporary instrumentation fixture set screen security false before normal launch and true afterward. Its exact fixture body was:

```kotlin
package org.thoughtcrime.securesms

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.thoughtcrime.securesms.keyvalue.SignalStore

class FreshScreenSecurityTest {
  @Test fun disableOnlyForCapture() {
    SignalStore.settings.isScreenSecurityEnabled = false
    assertFalse(SignalStore.settings.isScreenSecurityEnabled)
  }
  @Test fun restoreAfterCapture() {
    SignalStore.settings.isScreenSecurityEnabled = true
    assertTrue(SignalStore.settings.isScreenSecurityEnabled)
  }
}
```

The fixture has no account-registration rule. Its exact source SHA-256 is `44a28477312d6449e1e5d2daa69c8ae65b0a875c3b6b6d8a8c6efa2c2f3b1346`. Both methods returned `OK (1 test)`. Capture and restoration used:

```sh
adb -s emulator-5562 shell am instrument -w -r -e class org.thoughtcrime.securesms.FreshScreenSecurityTest#disableOnlyForCapture im.collie.app.test/org.thoughtcrime.securesms.testing.SignalTestRunner
adb -s emulator-5562 root
adb -s emulator-5562 shell am start -W -n im.collie.app/org.thoughtcrime.securesms.PassphraseCreateActivity
adb -s emulator-5562 exec-out screencap -p > fresh-encryption-api35.png
adb -s emulator-5562 shell am instrument -w -r -e class org.thoughtcrime.securesms.FreshScreenSecurityTest#restoreAfterCapture im.collie.app.test/org.thoughtcrime.securesms.testing.SignalTestRunner
```

The actual focused setup window was visible and ready for display without SECURE during capture. A subsequent normal launch after restoration reported SECURE in the window flags. A native UI dump showed title `[0,578][1080,697]`, body `[0,1249][1080,1477]`, primary button `[234,1624][845,1740]` and No thanks `[391,1782][689,1887]`. With top/bottom system insets 128/63, free space is 450px on each side of the content. The PNG was visually inspected before replacing either asset.

Both assets were derived from that same fresh 06:53 capture:

```sh
cp fresh-encryption-api35.png fastlane/metadata/android/en-US/images/phoneScreenshots/3.png
cwebp -q 82 -resize 540 0 fresh-encryption-api35.png -o docs/site/assets/screens/encryption.webp
```

- PNG, 1080×2400: `54184ad85cb143558aa7b858e7620cb379c1fbc0aa27b10e68f7f02504af9cb4`
- WebP, 540×1200: `6862fbc60d5eb744ec9ba441bdec5ba4e064f3674343d5909760a19180bb0848`

The immutable asset commit is signed `555fff83a6b7e8f5b8de89f522859f250a166b5f`, pushed without force to [PR 95](https://github.com/0cwa/collieim-android/pull/95). Browser `currentSrc`, loaded 540×1200 dimensions, no-store fetched bytes and actual hero rendering were checked. Local main separately received exactly these two blobs in signed `bf7e386100905d5c5c03bb2f22cff480c647ebe4`; main was not pushed, and unrelated `.local/` and `tools/` were preserved. This supersedes the earlier local-main images from `6d02965ccb86`.

Temporary app test sources were removed after saving exact text evidence in the task scratch directory. The emulator and its AVD, generated Android build/cache directories, and unusable diagnostic images were removed. SDK, JDK and shared dependency caches were reused and retained. No native app-code change is included in this website PR.
