# Releasing Collie

1. **Prepare.** Merge a PR that sets `releaseVersionName` and `releaseVersionCode` in `gradle.properties` and adds `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` (plain ASCII, at most 500 bytes), and add `distribution/release-notes/<version>.md` to `collie-release-engineering` main.
2. **Build, sign, upload.** On the maintainer machine run `collie-private/release/release.sh v<version> [--build-on local|github]`. It builds `origin/main` with `tooling/reproducible-builds/build-release.sh` (here, or on GitHub via `build-release.yml`), signs `collie.apk` with the APK key, tags with the publication key (`.github/release-signers`), uploads the APK to a draft release and starts `release.yml`.
3. **Prerelease.** `release.yml` checks the tag signature, APK certificate, version and release notes, uploads the four profile copies (`collie-foss`, `molly-continued`, `molly-foss-continued`, `molly-minimal`) and publishes a GitHub prerelease.
4. **Full release.** An independent rebuild must match the published APK first:
   - built locally: `gh workflow run promote.yml -f tag=v<version>` rebuilds on GitHub and promotes on a match;
   - built on GitHub: `collie-private/release/verify-local.sh v<version>` rebuilds here and promotes on a match. Local builds use this machine's pinned JDK, SDK and NDK (`BUILD_MODE=host` in `build-release.sh`), so nothing is downloaded.

`collie-release-engineering` picks up full releases on its schedule and updates the F-Droid repo and Pages.
