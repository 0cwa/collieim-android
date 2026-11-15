> Current chat selection: [ongoing-conversation-capture.md](ongoing-conversation-capture.md) supersedes this six-message conversation image with a genuine fourteen-message latest-thread capture. This earlier record remains the valid encryption provenance and historical six-message/chat build record. Its original helper/UI dump are preserved at immutable ae8af5670c7a03dd77041a3dfba54a0085e01c0c; the current helper and chat-native-ui.xml now represent the fuller thread. No original encryption/lock asset changed.

# Offline native capture provenance

- Dedicated AVD: collieim-offline-api35, emulator-5562, API 35 Pixel 6; guest remained offline.
- Display: wm size 1080x2160 override; screenshots are unmodified native adb exec-out screencap -p PNGs at 1080x2160. No crop, padding, compositing, or recoloring.
- Offline checks before seed and after: airplane_mode_on=1, mobile_data=0, Wi-Fi disabled, and dumpsys connectivity reported Active default network: none.

## Captures

- Chat: /home/x/agent-scratch/collieim-android/pr-screenshots/demo-conversation-api35-2to1.png, SHA256 28ade62dd49c45047c283ce232f14b7dedf0e36d788e85825dfa6d1fea4a2688. UI dump: chat-2to1-ui.xml, SHA256 dc082991c4365ce99b3bfb810c8738644f9d429f54767d1aa1ec35c635f2ad65. Shows fictional local Alex and six alternating messages, header and composer.
- Database encryption: /home/x/agent-scratch/collieim-android/pr-screenshots/encryption-api35-2to1.png, SHA256 b85f15db028af21b96692d4baa431496e19ccc7304e11cd4612046ee0245f5da. UI dump: encryption-2to1-ui.xml, SHA256 18f6717eef2beaf88525773203c001e1f8b4f39bf9c83c672c973597536a4c54.
- Both captures visually accepted by site_author. Earlier 1080x2400 PNGs were preserved separately and not overwritten.

## Tests and production window security

- Seed: OfflineConversationCaptureTest#seedOfflineDemo, 1 collected, 1 passed; app APK SHA256 c4521417b26b1d9b50dd3acd809283a12d06ecc6cf30fc54ffe8ad9f93bf0045, test APK SHA256 927889ca6fd0c5a9b5a796aa6a7588ffc2ba3cba92b8c566cd6324ce45563cb8. Fixture hash 15e8ee1637bf9193e34c27c3184ed7c36019061ebfff430074addd1a72c2290d.
- Centering: no-registration offline method OfflineDemoSecurityRestoreTest#databaseEncryptionScreenClearsStatusBarAndCentersContent, 1 collected, 1 passed, networking asserted disabled. Test APK SHA256 b8abffff275d4534703a78bf4c2a1183983e464f1c6cf41c8772e673a0f37890; helper hash 22c07379c31d03b1dc020f3386490035427b4d927be6fa0214888a136d3dc40d.
- Geometry: root/decor 1080x2160; title [0,451][1080,570]; primary [234,1497][845,1613]; No thanks [391,1655][689,1760]; system-bar insets top 115/bottom 63; free space above 336/below 337 pixels. Captured output: OFFLINE_ENCRYPTION_GEOMETRY width=1080 height=2160 titleTop=451 buttonBottom=1760 barsTop=115 barsBottom=63 above=336 below=337. Recorded in encryption-geometry.log from the observed test output.
- Restore: OfflineDemoSecurityRestoreTest#restoreAfterCapture, 1 collected, 1 passed; final test APK SHA256 b493a6b0bddc9badafd8492b3166337c8ed34178fb6d726b38f7e3c567b4f5eb, durable-write helper hash 3ae03f6f2ac55846d5726ac0df8b7701c542463a90a5de81a1ec10c69c3b660d.
- After restore, force-stopped only im.collie.app, then cold-launched exported RoutingActivity. Actual production PID 6443, task 18 top-resumed/focused. WMS attrs contained fl=LAYOUT_IN_SCREEN SECURE LAYOUT_INSET_DECOR SPLIT_TOUCH.

## Source, format and reproduction

The application sources are unchanged from `02c414c19ae686451de85bd4f46cc9d3209f4c5c`, including the PR86 fix 85779e109803. Native build checkout was signed website 4a114b087497e41f98662c453a8976bb9896d827 plus temporary instrumentation fixtures only. All phases used the same bit-identical app APK listed above; each app/test pair was assembled together with cached SDK/JDK 21/Gradle dependencies. The seed, centering and durable-security helper snapshots are preserved here as `.kt.txt`, alongside the unchanged offline registration rule. No production Android source change is included in this PR. Those temporary androidTest files were removed after capture.

Build command (run through host-exec from /home/x with explicit host-mapped worktree):

```sh
agent-heavy --mem 16G -- env JAVA_HOME=/var/home/x/.local/opt/jdk-21.0.13+9 ANDROID_HOME=/var/home/x/Android/Sdk ANDROID_SDK_ROOT=/var/home/x/Android/Sdk GRADLE_USER_HOME=/var/home/x/.gradle ./gradlew --offline --no-daemon --build-cache --console=plain --max-workers=6 -Dorg.gradle.jvmargs="-Xmx6g -Xms256m -XX:MaxMetaspaceSize=1g" -Pkotlin.daemon.jvmargs=-Xmx2g :app:assembleProdWebsiteDebug :app:assembleProdWebsiteDebugAndroidTest
```

Each method was run with `adb -s emulator-5562 shell am instrument -w -r -e class org.thoughtcrime.securesms.CLASS#METHOD im.collie.app.test/org.thoughtcrime.securesms.testing.SignalTestRunner`. Normal UI launch used the exported RoutingActivity launcher alias, then actual Alex chat navigation. PassphraseCreateActivity was opened normally outside instrumentation using the owned debug emulator's root shell. Native files came directly from `adb exec-out screencap -p`.

The first temporary restoration method only asserted its in-memory setter. A fresh production window showed that restoration was not durable. Adding the existing SignalStore.blockUntilAllWritesFinished() API fixed the fixture: the subsequent cold production window had SECURE as recorded above. No app code or security policy was weakened to obtain the capture. The no-registration centering method retains the original title/system-bar/last-button assertions without the original registration harness's networking side effect. It has no assumption skip.

Alex, local nickname and all six text messages are fictional fixture data inserted offline into the real app database. Outbox receipt timestamps were aligned with staged timestamps so actual native UI orders replies correctly. The local nickname is the app's own feature; no identity key was marked verified. No personal account or remote delivery was involved; the bubbles, header, avatar and composer are genuine app-rendered UI, not a generated/composited mockup.

[IzzyOnDroid's documented 2:1 limit](https://izzyondroid.org/docs/general/Fastlane/) is enforced by the existing unchanged Fastlane validator. Rather than crop/pad the original 1080×2400 image or weaken that check, this capture used a real 1080×2160 Android viewport. Both selected Fastlane PNGs therefore satisfy the 2:1 format. The original corrected 1080×2400 encryption PNG is preserved at immutable 555fff83a6b7e8f5b8de89f522859f250a166b5f and in the original capture provenance. The website encryption WebP remains its original 540×1200 derivative, SHA256 6862fbc60d5eb744ec9ba441bdec5ba4e064f3674343d5909760a19180bb0848. The two native viewport captures are distinct and do not have the same hash.

## Selected assets and order

- Fastlane 1.png: this native 1080×2160 chat, hash 28ade62d… above. Website conversation.webp is its 540×1080 q82 resize, SHA256 b94444eeab129ef45a453dd19c73b7ad181bed0d9d636623cdb30c3a81ea5b54, with no crop/padding or color edits.
- Fastlane 2.png: existing locked screen, formerly 4.png, unchanged pixels.
- Fastlane 3.png: new native 1080×2160 encryption, hash b85f15db… above.
- Fastlane 4.png: existing privacy settings, formerly 2.png; 5–9 unchanged. Existing settings overview formerly 1.png is retained byte-for-byte as settings-overview.png, after numbered images. No other existing screenshot was discarded.
- Website montage: conversation front, original corrected encryption left, locked screen right. The more technical proxy image remains available in source/Fastlane but is no longer a hero phone. Native 2:1 conversation fits existing natural-height framing at 390/1280 without CSS redesign or image stretching. Foreground overlap covers parts of the rear encryption title/controls; the full native setup image remains available separately. No claim is made that every rear control is unobscured.

Fourteen website tests and the Fastlane metadata validator pass. The requested live server and shared caches are retained. Only the owned emulator/AVD, temporary helper sources and generated build outputs are removed after evidence is saved; unrelated emulators are untouched.

## Reproduction safety

Use **offline-demo-security.kt.txt** as the current complete capture/restore fixture together with offline-demo-rule.kt.txt. It includes the corrected message ordering, local nickname, offline centering check and durable write flush. The files named historical-seed-fixture.kt.txt and historical-centering-fixture.kt.txt preserve exact earlier APK provenance only; their older restoration setters are superseded and must not be reused. No current reproduction should use those pre-flush restoration methods. The scratch native-demo-resume.md is likewise replaced with this completed, durable-source runbook.

User continuation authority was “Don't worry about disk free warning until it's below 30G, continue”. Disk checks remained above that threshold. No repeated confirmation was requested for the approved build/capture job.
