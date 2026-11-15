# API 35 Settings navigation and offline WebView proof

Six original, unmodified captures from [run 43724131037, attempt 1](https://github.com/0cwa/collieim-android/actions/runs/43724131037), tested source `3dcd5f09aea2d5cd09abe567f6da3ea8c8f7f522`, tree `7d36f0f0559a1f443aca09eae1855e2e23d2122f`.

The source-pinned checkers pass against both retained artifacts:

- Settings: eight host navigation cases and two API 35 device methods, zero failures/errors/skips. The device test enters the real Settings activity, opens Appearance, and verifies Back. The two screenshots show those actual screens.
- WebView: exactly four API 35 methods, zero failures/errors/skips. Wi-Fi is reported disabled before instrumentation; airplane mode and no-default-network checks remain, as do the tests' offline assertions. Four captures show local synthetic fixtures in the actual WebView components. They establish the scoped settings and bridge checks, not live CAPTCHA-provider page rendering or a solved challenge.
- Both jobs use matching app and normal-runner instrumentation APKs, with unchanged hashes before and after each run. The fixtures turn screen security off for capture and restore prior state afterward.

All six PNGs were decoded and visually inspected. Later CodeQL workflow/policy changes do not change these UI implementations. These images do not claim APK metadata or an exact-head runtime run for later commits, and they do not authorize merging PR118.

APK hashes by job:

## Settings

```text
6820160be485ad9d7c080a1fb6d68766d6c50e35adb00788b26039132d999a38  app/build/outputs/apk/prodWebsite/debug/app-prod-website-debug.apk
5f331bb631e9748c1efcdc5628f2b4eb133e7189f1f932b128d604bb732bc413  app/build/outputs/apk/androidTest/prodWebsite/debug/app-prod-website-debug-androidTest.apk
```

## WebView

```text
c2028f4d838e5dbc5da05b4999c217b4c2a591b4fefb9eb04448c82b7eeea11f  app/build/outputs/apk/prodWebsite/debug/app-prod-website-debug.apk
0709160d61c0753b5f65fda16b72789000574399176f206cb15a45c84d893eab  app/build/outputs/apk/androidTest/prodWebsite/debug/app-prod-website-debug-androidTest.apk
```

| Capture | Scope | SHA-256 |
| --- | --- | --- |
| [appearance-settings.png](appearance-settings.png) | settings | `35a2b730e777db932fbfe76e3b62dbd80c77c3c037f8ec0080be18828d9cb8b2` |
| [settings-home.png](settings-home.png) | settings | `c38c4cfc68e65949acdc0c3894d126a1733de79b1551fca2eeac8731b01daf02` |
| [compose-captcha.png](compose-captcha.png) | webview | `178b0f01df10ebf1f883fe78f545a1c336010f1ce58c3d2bdca4919416060bc7` |
| [debug-viewer.png](debug-viewer.png) | webview | `5675966daaf25494289e3cf844592186e07f7d0c5b58b37c1b17b2279cf75a95` |
| [legacy-captcha.png](legacy-captcha.png) | webview | `8308b174c34c9d36127faf32b65431cd82cae5575fb9ca59b897036a1f6544f5` |
| [recaptcha.png](recaptcha.png) | webview | `e679f2e923c47a446524772187a1d20b744b6b9e85f0cb8c8989e36f1e90639b` |
