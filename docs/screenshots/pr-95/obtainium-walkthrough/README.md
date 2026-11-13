# Account-free Obtainium first-install walkthrough

Observed on 2026-11-12 using an owned Pixel 6 API 35 Google APIs x86_64 emulator and Chrome 124.0.6367.219. This evidence-only update preserves the website implementation e532bde62ac1e4d4429b822055ee60ecc365c38a and the b696417ef3ec5058302c90f56c02cdf08d2c22e9 visual candidate. The walkthrough exercised its unchanged local handoff at http://10.0.2.2:8879/obtainium/. It does not claim a deployed-site test or coverage of every Android/browser/Obtainium version.

1. With Obtainium absent, the attempted app URI left Chrome on the handoff. Download/return guidance and the manual home-page route remained available.
2. Followed the official Obtainium site's x64 download, Android's browser APK warning/unknown-source permission and normal installer. Installed Obtainium 1.7.4, then returned to the handoff.
3. Accepted Chrome's “Continue to Obtainium?” prompt. The generated URI opened Obtainium import; the observed entry used GitHub source https://github.com/0cwa/collieim-android, package im.collie.app and release asset collie.apk. No account, login or token was required.
4. Obtainium downloaded public v8.35.1-3-rc2, then Android's normal package installer installed Collie. Package-manager verification reported versionName 8.35.1-3-rc2 and versionCode177705. Installed APK size was97,103,942 bytes, SHA256 0a7ad16221a421c6b78b4446f699227d12b3ad6988484577731e3ebc271b1de0.
5. The installer returned automatically to Obtainium. No Done tap occurred. Collie was not launched: top resumed activity stayed Obtainium and pidof im.collie.app returned no PID. An operator Cancel mis-tap was corrected using actual control bounds without another download; it was not a website defect.

Official Obtainium 1.7.4 x86_64 APK:36,936,351 bytes; SHA256 f4e0ba24f2ba7b49d7268a8f7b47b9c233afd864d1e5214f709e6e2ae30a7580. APK binaries are not retained in this repository proof. The scout removed only its owned emulator/AVD and disposable APK/hash copies; shared caches, user resources and requested localhost preview were retained.

## Selected unmodified native captures

All are1080×2400 native PNGs; no cropping, recoloring or UI reconstruction. The pictures establish the handoff, browser permission prompt and final installed-version list. Import identity and installed APK/version details above are runtime observations, not a claim that an import-dialog screenshot was preserved.

| View | Image | SHA256 |
| --- | --- | --- |
|Handoff with Obtainium absent|[02-handoff-no-app.png](02-handoff-no-app.png)|0666f7fe50e8448330b9e35bc0ec6171150aa743d8fdb4cabd2322418f1816b1|
|Chrome Continue to Obtainium prompt|[14-handoff-installed.png](14-handoff-installed.png)|8d73339db0a2e3a9d6cb9b79921d43fb67f9bc3a66785382fb9ac0605c574e35|
|Obtainium list after Collie installation|[16-installed-list.png](16-installed-list.png)|db99e19b1a67d1178c1aa9c28a8bec00dcfb246761a5fef45f29032b38e0a9dd|

[Chrome prompt UI hierarchy](14-open-prompt.xml). Original operator outcome record is preserved in [runtime-result.md](runtime-result.md). No frontend, import configuration or production Android code was changed for this evidence.
