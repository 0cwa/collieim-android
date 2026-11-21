# Collie release notifications

This document describes the implementation in current source. The published `v8.35.1-3-rc2` APK predates it and has built-in update checks disabled; Obtainium and F-Droid provide updates for that release.

Direct APK (`website`) builds check their product's signed F-Droid repository for newer compatible releases. **Settings → App updates** controls these checks and shows an available release even when Android notifications are disabled. Checks are enabled when there is no saved preference; an explicit choice to disable them is preserved. Store builds continue to rely on their store.

The existing periodic schedule runs about every 22 hours, with an initial check when first scheduled. **Check for updates** queues a check while checks are enabled. A failed check remains visible alongside the time of the last successful check. Turning checks off dismisses the notification, and a request already in progress cannot publish its result after checks are disabled.

A notification or the available-release row opens the exact version's official GitHub release page. Collie does not download or install an APK in the background. The browser uses its own connection settings, and Android enforces the installed app's signing identity when the user installs the downloaded update. The old DownloadManager receiver, installer path, and related permissions have been removed.

## Authentication and connection policy

1. Fetch `/entry.jar` only from the installed product's fixed repository address below, using Collie's proxy-aware socket factory, DNS, and proxy selector. Redirects are disabled and the call has a timeout.
2. Bound the compressed archive to 2 MiB. Save it in the app's private cache and preflight its central-directory entries with `ZipFile`: at most 16 entries, no duplicate names (case-insensitively), known entry names, and at most 4 MiB of total actual expansion. Delete the temporary archive afterward.
3. Use `JarFile` with verification enabled, consume all of `entry.json`, and require exactly one `CodeSigner`. Pin the SHA-256 fingerprint of its leaf certificate. A matching certificate elsewhere in an untrusted claimed chain does not authenticate the signer. Require a SHA-256 content digest, without changing platform security policies.
4. Require index format `20002` and the fixed index path `/index-v2.json`. The signed descriptor binds the exact size and SHA-256 of the downloaded index, bounded to 4 MiB. The index's repository URL must match the selected profile, and its timestamp must match the signed descriptor.
5. Reject duplicate JSON properties, malformed identity fields, malformed release versions, duplicate version codes, and repository timestamps older than the highest accepted timestamp. Require the profile's exact package, its pinned APK signing certificate, matching APK hash/key, and expected repository filename.
6. Select the highest newer version compatible with the device SDK and ABI in that product's default release channel, excluding versions marked `KnownVuln`. A version suffix does not select a product or feed: a Collie release's name may contain `-rc` after promotion into Collie's default repository channel, and TrailBlazer Beta follows only its separate repository. The updater does not query arbitrary GitHub prereleases.
7. Persist only authenticated results. Notifications point to a fixed GitHub repository and a strictly validated release tag. Disabled app/channel notifications do not mark a version as already notified, so a later successful check can announce it after permission is enabled.

The timestamp high-water mark detects rollback below an already seen repository generation. It does not guarantee freshness: withholding newer signed metadata remains possible. An unchanged signed index is allowed, because the publication process does not promise an expiry interval. Failed authentication never advances the last-successful-check time or replaces the previously verified candidate.

## Product identities

The exact compiled application ID selects a fixed `CollieUpdateProfile`. Each product follows the default channel of its own repository:

| Product | Application ID | Repository | GitHub tag prefix |
| --- | --- | --- | --- |
| Collie | `im.collie.app` | `https://repo.collie.im/fdroid/repo` | `v` |
| Collie TrailBlazer Beta | `im.collie.app.trailblazer` | `https://repo.collie.im/fdroid/beta/repo` | `trailblazer-v` |

The Beta profile prepares the shared updater for the separate Beta application. The existing Gradle flavors still build the Collie identity; Beta source, build activation, and publication are separate release work. There is no channel-switching preference. Staging, IMO test packages, and unrecognized application IDs do not schedule or perform production update checks.

Profiles pin the repository address, package, APK signer, repository signer, and release tag prefix together. Neither a downloaded index nor a preference can change that selection. Both profiles reject nondefault F-Droid `releaseChannels`; Beta's separate repository uses its own default channel. Version codes are compared with the installed version of the same package. A Beta APK cannot update a Collie installation, or the reverse.

The identities match [`distribution/channel_profile.py`](https://github.com/0cwa/collie-release-engineering/blob/ee8dd193a430402aa6f964ff8a496958ec58fa73/distribution/channel_profile.py), and Beta version validation follows [`tools/patches/release_artifacts.py`](https://github.com/0cwa/collie-release-engineering/blob/ee8dd193a430402aa6f964ff8a496958ec58fa73/tools/patches/release_artifacts.py) at the same reviewed engineering revision. `app/src/test/resources/apkupdate/update-profiles.json` retains the identity contract snapshot for comparison tests. This is a source-controlled reference, not a remotely fetched policy. Changes to these identities require a reviewed app change.

## Trust pins and fixtures

Production repository certificate SHA-256:

```
B0C5DCBAC2B65ADA4E8F641910097FB0BF86A5F7255319584D453C2932F5233C
```

This is the fingerprint published on [Collie's repository page](https://repo.collie.im/) and independently read from its signed `entry.jar` on 2026-11-13. The older device-acceptance fixture in the engineering repository contains a different staging certificate and is not used as a production trust root.

Collie APK signing certificate SHA-256:

```
F8CC0C0C052E5635C26FCD293C2B0AF3E87D61FF7F2DF3602A9AF6E91F73C826
```

This matches the existing [release engineering policy](https://github.com/0cwa/collie-release-engineering/blob/ee8dd193a430402aa6f964ff8a496958ec58fa73/distribution/channel_profile.py) and the production repository's signer metadata.

TrailBlazer Beta APK signing certificate SHA-256, pinned by that same engineering policy:

```
A515C03498BCE8852B835544910B09CCC11A3ABBED23E826E9C62C002680FE98
```

The profiles share the repository signing certificate but have separate APK signing certificates. A valid repository signature alone does not authorize another product's package or repository address.

The public production test fixtures are retained under `app/src/test/resources/apkupdate/` and, for native verification, `app/src/androidTest/assets/apkupdate/`:

| Fixture | SHA-256 |
| --- | --- |
| `collie-entry.jar` | `bf3f07b34b188807a63f1e0ca61d1ae0a0e66cbfdd1e1dbe8f58a738e6bf17de` |
| `collie-index-v2.json` | `9bb43e329041aea313127de92905496183f895a0ce8a5aa18235f1085661376b` |

The additional JVM fixture, `untrusted-signer-with-repository-certificate.jar`, is signed with a disposable test key. Its claimed certificate chain includes the public production certificate, but its leaf is untrusted. The test checks that the chain really contains the pinned certificate and that verification still rejects it. No production private key or test private key is stored.

The legacy `index-v1.jar` is deliberately unused: the production v1 format uses SHA-1 signing and is treated as unsigned by current JDK policy. The v2 entry uses SHA-256 and places its manifest last, which is why verification uses `JarFile`, not `JarInputStream`.

## Regression checks

Focused JVM classes:

- `org.thoughtcrime.securesms.apkupdate.CollieUpdateIndexTest`
- `org.thoughtcrime.securesms.apkupdate.CollieUpdateProfileTest`
- `org.thoughtcrime.securesms.jobs.ApkUpdateJobTest`
- `org.thoughtcrime.securesms.components.settings.app.updates.AppUpdatesSettingsViewModelTest`
- `org.signal.appsettings.updates.AppUpdatesSettingsScreenTest` in `:feature:app-settings`

The API 35 class `org.thoughtcrime.securesms.apkupdate.CollieUpdateSettingsTest` verifies the real signed v2 fixtures on Android and opens the actual Updates screen. It intercepts the browser intent, checks the exact official destination, and captures available-release and failure states. It does not start a network check. The screenshot state uses a synthetic future release and restores preferences and screen security afterward.

Profile tests compare the compiled identities with the reviewed contract snapshot. Parser tests authenticate the real Collie fixture and reject it under the Beta profile despite the shared repository signer. Synthetic Beta metadata tests cover its package, signer, address, filenames, version policy, default channel, and release links; they do not claim that a production Beta feed or APK has been published. Job and settings tests cover package-specific routing and reject unsupported identities before network access or release-page navigation.

Native captures are written as `collie-updates-available.png` and `collie-updates-failed.png` both to the app's external-files directory and, when supplied, `additionalTestOutputDir`. The latter lets Gradle preserve captures before uninstalling the test APKs.
