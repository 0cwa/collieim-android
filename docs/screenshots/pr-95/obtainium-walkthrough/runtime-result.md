# Collie first-install walkthrough

Date: 2026-11-12. Device: owned Pixel 6 API 35 Google APIs x86_64 AVD `collie-obtainium-api35`, emulator-5562, Chrome 124.0.6367.219. No user devices or app data were used.

## Outcome

- Opened `http://10.0.2.2:8879/obtainium/` in Chrome. With Obtainium absent, the handoff remained visible and included “Download Obtainium, then return here to add Collie” and the manual home-page route (`02-handoff-no-app.png`).
- Followed the official Obtainium site’s x64 download. Chrome required the standard APK warning confirmation and “Allow from this source” setting. Installed and opened Obtainium 1.7.4.
- The page’s `obtainium://app/...` handoff showed Chrome’s “Continue to Obtainium?” prompt; accepting it launched Obtainium’s import dialog. Imported Collie without a login, token, or account prompt. Entry showed `https://github.com/0cwa/collieim-android`, package `im.collie.app`, and selected release asset `collie.apk`.
- Before the Collie download, searched reasonable download/cache locations for a 97,103,942-byte `collie.apk`; no match existed. Obtainium fetched release `v8.35.1-3-rc2` and its 92.6 MiB / 97,103,942-byte asset.
- Granted Obtainium Android’s “Allow from this source” permission, then accepted the normal package installer prompt. `pm list packages` showed `im.collie.app`; `dumpsys package` reported versionName `8.35.1-3-rc2`, versionCode `177705`, firstInstallTime `2026-11-12 18:24:41`.
- Did not launch Collie. After install, top resumed activity remained Obtainium and `pidof im.collie.app` returned no PID. The installer returned to Obtainium automatically; no Done/Open screen was captured or tapped.

## Hashes

- Obtainium official x86_64 APK, 36,936,351 bytes: `f4e0ba24f2ba7b49d7268a8f7b47b9c233afd864d1e5214f709e6e2ae30a7580`.
- Installed Collie `base.apk`, 97,103,942 bytes: `0a7ad16221a421c6b78b4446f699227d12b3ad6988484577731e3ebc271b1de0`.

## Evidence

Screenshots and UI dumps are in this directory. Key screens: `02-handoff-no-app.png`, `14-handoff-installed.png`, `14-open-prompt.xml`, `16-installed-list.png`. Earlier step captures include Obtainium download, unknown-source permission, and install confirmation. Collie is visibly installed in the final Obtainium list; it was not opened.
