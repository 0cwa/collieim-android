# Releasing Collie

This procedure publishes the Stable Collie product (`im.collie.app`, `v<version>`
tags). Its GitHub prerelease is a candidate for the same product. TrailBlazer
Beta has its own package, tag family and publisher; its APK cannot be promoted
into the Stable package. A version suffix such as `-rc1` does not determine the
GitHub prerelease flag or Latest designation.

Stable version names follow the updater and feed grammar: `X.Y.Z`, an optional
numeric revision such as `-4`, and an optional `-alphaN`, `-betaN` or `-rcN`
suffix. Development labels, custom suffixes and `+build` metadata cannot enter
this publication path.

1. **Prepare.** Merge a PR that sets `releaseVersionName` and `releaseVersionCode` in `gradle.properties` and adds `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` (plain ASCII, at most 500 bytes), and add `distribution/release-notes/<version>.md` to `collie-release-engineering` main. The exact source commit must pass `tooling/ci/verify_release_readiness.py` on `main` or the exact `release/stable/8.35` branch. The maintenance route requires the CI workflow and its verification inputs to match the trusted main revision. Select the source branch in prerelease publication. Promotion reads that same branch from the publication record. Both workflows run on `main`.
2. **Build, sign, upload.** On the maintainer machine run the release helper, `release/release.sh v<version> [--build-on local|github] [--source-branch <branch>]`. By default it builds `origin/main`; choose the maintenance branch explicitly when using that source. It builds with `tooling/reproducible-builds/build-release.sh` (here, or on GitHub via `build-release.yml`), signs `collie.apk` with the APK key, tags with the publication key (`.github/release-signers`), uploads the APK to a draft release and starts `release.yml` on main.
3. **Prerelease.** `release.yml` verifies the approved SSH-signed annotated tag, source version, production APK identity and certificate, exact-commit CI, release notes, and a byte-for-byte match between the draft APK and that approved CI build plus its signing records. It uploads the four identical profile copies (`collie-foss`, `molly-continued`, `molly-foss-continued`, `molly-minimal`) and `publication.json` while the release is still a draft, then explicitly publishes a prerelease without changing Latest. It never replaces an existing asset or demotes a full release on retry.
4. **Full release.** An independent rebuild must match the published APK first:
   - built locally: `gh workflow run promote.yml --ref main -f tag=v<version>` verifies the publication record, rebuilds its exact source commit on GitHub, compares APK payloads with `apkdiff.py`, and rechecks the signed tag and every asset identity before explicitly making that same release full and Latest;
   - built on GitHub: use the maintainer's independent local verification procedure, `release/verify-local.sh v<version>`. `promote.yml` refuses this build origin because a second GitHub build is not independent. The public workflow does not enforce or attest to the private verifier's implementation. Local builds use the machine's pinned JDK, SDK and NDK (`BUILD_MODE=host` in `build-release.sh`).

Use `--source-branch release/stable/8.35 --trusted-main <full-main-sha>` when
running the CI verifier locally for the maintenance branch. The verifier
accepts only the owning repository's PR checks workflow on a push or manual
run, and requires successful Unit tests, Unsigned production build and
Security readiness jobs for the same SHA and attempt. The existing historical
verify-only path still uses main.

`collie-release-engineering` picks up full releases on its schedule and updates the F-Droid repo and Pages.

## Publication identity and version codes

`tooling/release/publication.py` implements both public transitions. The draft's
`publication.json` records schema version 1, the repository and release ID, tag
name and signed tag object, source commit, package, version name and code, APK
certificate, declared build origin (`local` or `github`), and the ID, name, size
and SHA256 of each of the five identical APK assets. An optional `collie-beta.apk`
companion is verified against the Beta package and signer and recorded separately
as `beta_apk`; it does not enter the Stable feed. The build-origin declaration
selects the independent verification route. Maintenance publications also record
`source_branch`; historical records use `main`. Promotion binds the downloaded record's own asset identity
as well. Release notes cannot override it.

The final promotion job reruns the exact-source CI gate after the independent
build, so a newer failed or pending CI attempt cannot be hidden by the initial
successful preflight. It downloads the original identity artifact by the ID
returned by that preflight, including when only failed jobs are rerun. The
workflow does not reconstruct an artifact name from the retry's attempt number.

Before first publication, the candidate's versionCode must exceed every APK
versionCode already published for `im.collie.app`, including prereleases and
legacy profile APKs. Other Android packages have independent version histories.
Before promotion, its code must exceed every full Collie release, and no other
published Collie release may reuse its code. A newer prerelease can remain under
test while an earlier, independently verified candidate becomes full, provided
it is still newer than all full releases.

This is a public-history guard. The maintainer must still reserve codes across
unpublished drafts and privately signed builds; this repository cannot inspect
that signing ledger. Both workflows share the `release-im.collie.app`
concurrency group. They recheck remote identity before and after transitions,
but GitHub has no atomic transaction covering tags, assets, history and release
state. Other writers must use the same publication policy.

All assets are finalized before leaving draft. Promotion uploads no files to an
immutable release. The workflows retain publication, CI and rebuild results as
Actions artifacts; the release's publication record is permanent. Legacy full
releases need no new record to contribute to the version floor. A legacy
prerelease without this record is not eligible for this automated promotion
path, and is never rewritten to invent evidence after publication.

## Public source-to-APK verification

New prerelease notes link to the publication run. Before publishing, the workflow
binds the draft `collie.apk` to the approved exact-commit production CI artifact.
It recreates the signing-related ZIP formatting and copies the APK's existing
signature data onto that unsigned build. Publication requires the reconstructed
APK and the draft download to match byte for byte. The workflow retains this
result with its publication evidence; it needs no signing key.

Independent release promotion still follows step 4. To recheck a published
release without changing it, dispatch the read-only verification route:

```sh
gh workflow run release.yml --ref main -f verify_only=true -f built_on=github \
  -f tag=v<version>
```

For an older release built by the standalone unsigned build workflow, also pass
`-f legacy_build_run=<original-run-id>`. The verification job reports an expired
artifact as a failure.

Downloads verify the GitHub SHA256 and declared byte count, with a 2 GB maximum
per asset and an 8 GB fetch budget per helper invocation. Equal-digest profile
copies share a cache. If public history exceeds the budget, review a bounded
history-verification design before raising it.

Run the focused boundary tests with:

```sh
python3 -m unittest discover -s tooling/release -p 'test_*.py' -v
```
