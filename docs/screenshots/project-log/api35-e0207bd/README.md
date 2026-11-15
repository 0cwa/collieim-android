# API 35 Project log and browser proof

These are the seven original, unmodified PNGs from [hosted run 43725612346, attempt 1](https://github.com/0cwa/collieim-android/actions/runs/43725612346).

- Tested source: `e0207bd89722ccb403965a7652c1fc773a799b6e`.
- Tested tree: `1c8ac784033bc9d7041df5e0fd545be1e0fd3b0e`.
- API 35, normal `SignalTestRunner`, matching app and instrumentation APKs before and after the run.
- Exactly three test methods passed, with zero failures, errors or skips. The checker from the tested commit passed against the retained artifact.
- The fixture disables screen security for its captures and restores its prior settings in `finally`.

The captures cover both tabs, Help, an expanded older release, the external-browser disclosure, the real PR156 row, and its browser destination. The last capture visibly shows the GitHub PR title and number. The observed address was `github.com/0cwa/collieim-android/pull/156`, in `com.android.chrome`. The title-area bounds recorded by the test were `56,959,1340,1376`; the saved raw bitmap passed the stable, nonblank title-area check. All seven PNGs were decoded and visually inspected.

APK SHA-256 values:

- App: `de131da0447f904069b543aac0048fbd876c5818665245b36aea2cbe848e1a18`.
- Instrumentation: `71b701e932cd2c131fdd0fe982a37e49ade6417e68d5c2431962048f38298741`.

This documentation commit adds only the retained evidence. It does not claim APK BuildConfig metadata for the documentation commit, and it does not authorize merging this feature PR.

| Capture | SHA-256 |
| --- | --- |
| [01-light-this-update.png](01-light-this-update.png) | `008c1e8fe763c7304343b6d8f28b40d9a3ec3858ad9bb36278a8408986d9c69d` |
| [02-light-project-log.png](02-light-project-log.png) | `dfd3d0734247e9d04b2ddc2d5da581d19d57bfaff8b28303b5efd1ef44f75a73` |
| [03-dark-help.png](03-dark-help.png) | `c1bb2bafa4aee5a5b1fe62cc9ea5ae720c55caa621270b1508bd8245e6fd4837` |
| [04-dark-project-log-from-help.png](04-dark-project-log-from-help.png) | `0d4926ebf35d6ffc828f217ae225b9e9d8ccbe2578aa506ea0d4ee4da5bf4fb6` |
| [05-light-pr156-disclosure.png](05-light-pr156-disclosure.png) | `41a5ed1ea264bbbeaacc6a66c780a0bd5dabf08684e7a75edbe09877d27aa4d6` |
| [06-light-pr156-entry.png](06-light-pr156-entry.png) | `be3a7b94b211b41b90263864cbdb5dcea973f211b2e1ffaf70cbc5fae6c0e48d` |
| [07-light-pr156-browser.png](07-light-pr156-browser.png) | `ad9627a559b105f139c2c4cf6cbc1d326dc54f19ecde43a55f7a44f1bec5a217` |
