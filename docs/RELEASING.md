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

1. **Prepare.** After the intended changes have merged, prepare the clean source with the command below and merge its metadata in a PR. Add `distribution/release-notes/<version>.md` to `collie-release-engineering` main. The exact source commit must pass `tooling/ci/verify_release_readiness.py` on `main` or the exact `release/stable/8.35` branch. The maintenance route requires the CI workflow and its verification inputs to match the trusted main revision. Select the source branch in prerelease publication. Promotion reads that same branch from the publication record. Both workflows run on `main`.
2. **Build, sign, upload.** On the maintainer machine run the release helper, `release/release.sh v<version> [--build-on local|github] [--source-branch <branch>]`. By default it builds `origin/main`; choose the maintenance branch explicitly when using that source. It builds with `tooling/reproducible-builds/build-release.sh` (here, or on GitHub via `build-release.yml`), signs `collie.apk` with the APK key, tags with the publication key (`.github/release-signers`), uploads the APK to a draft release and starts `release.yml` on main.
3. **Prerelease.** `release.yml` verifies the approved SSH-signed annotated tag, source version, production APK identity and certificate, exact-commit CI, and release notes. It uploads the four identical profile copies (`collie-foss`, `molly-continued`, `molly-foss-continued`, `molly-minimal`) and `publication.json` while the release is still a draft, then explicitly publishes a prerelease without changing Latest. It never replaces an existing asset or demotes a full release on retry.
4. **Full release.** An independent rebuild must match the published APK first:
   - built locally: `gh workflow run promote.yml -f tag=v<version>` rebuilds on GitHub and promotes on a match;
   - built on GitHub: `collie-private/release/verify-local.sh v<version>` rebuilds here and promotes on a match. Local builds use this machine's pinned JDK, SDK and NDK (`BUILD_MODE=host` in `build-release.sh`), so nothing is downloaded.

Use `--source-branch release/stable/8.35 --trusted-main <full-main-sha>` when
running the CI verifier locally for the maintenance branch. The verifier
accepts only the owning repository's PR checks workflow on a push or manual
run, and requires successful Unit tests, Unsigned production build and
Security readiness jobs for the same SHA and attempt. The existing historical
verify-only path still uses main.

`collie-release-engineering` picks up full releases on its schedule and updates the F-Droid repo and Pages.

## Preparing the source and notes

From the clean source commit, with the selected version, reserved code and release date:

```sh
python3 tooling/collie-log/stamp.py "$VERSION" --edition stable \
  --version-code "$VERSION_CODE" --date "$RELEASE_DATE" --tag "v$VERSION"
```

The command sets the four release properties in `gradle.properties`, pinning the
current source commit and its commit time. It consumes every tracked fragment in
`tooling/collie-log/unreleased/`, writes the complete record under
`app/src/main/assets/collie-log/releases/stable/`, and drafts the matching
Fastlane changelog. Named fragments and an empty maintenance release are valid.
All inputs and destination collisions are checked before files change. Existing
release records and changelogs are never overwritten.

Review the complete notes, then trim only the Fastlane draft if needed; it must
remain ASCII and at most 500 bytes. To change a complete entry, correct its source
fragment before preparing. Optional `--highlights <content-id>` selects a known
content edition; omit it when there is no feature announcement. The optional
`--tag` must match the product and version. See [PROJECT-LOG.md](PROJECT-LOG.md)
for authoring and promotion between products.

For TrailBlazer, use `--edition beta` and `--tag "trailblazer-v$VERSION"`; the same
operation writes the Beta record and `fastlane/metadata/trailblazer-beta/` draft.
Its release recipe uses `COLLIE_BUILD_EDITION=beta`. This selects preparation and
build inputs; Beta still follows its separate signing and publication route.

Commit preparation before building. The existing release verifier requires the
matching product record and changelog, no unassigned fragments, preservation of
every source entry, and only the permitted metadata changes after the source pin.
The recipe supplies `collieReleasePrepared=true` only after those checks pass,
identically for host and container builds. Direct Gradle builds and ordinary PR
builds that retain old release properties remain development builds. Do not set
the prepared flag in tracked properties or local overrides. If more source
changes land, prepare again from that source instead of relabelling the old build.

## Publication identity and version codes

`tooling/release/publication.py` implements both public transitions. The draft's
`publication.json` records schema version 1, the repository and release ID, tag
name and signed tag object, source commit, package, version name and code, APK
certificate, declared build origin (`local` or `github`), and the ID, name, size
and SHA256 of each of the five identical APK assets. The build-origin declaration
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

New prerelease notes link to the verification summary in the publication run.
After publication, a read-only job takes the unsigned APK from the existing
exact-commit production CI run. It downloads the published `collie.apk`,
recreates the signing-related ZIP formatting and copies the existing APK
signature data onto the CI build. The check succeeds only if the reconstructed
APK and the public download match byte for byte. The summary shows the exact
source commit, the original build run and both signed SHA-256 hashes.

The check uses an existing artifact and needs no signing key. Independent
release promotion still follows step 4. To check an older release whose
original GitHub build used the standalone unsigned build workflow, run:

```sh
gh workflow run release.yml --ref main -f verify_only=true -f built_on=github \
  -f tag=v8.35.1-3-rc2 -f legacy_build_run=42893881886
```

The verification job reports an expired artifact as a failure.

Downloads verify the GitHub SHA256 and declared byte count, with a 2 GB maximum
per asset and an 8 GB fetch budget per helper invocation. Equal-digest profile
copies share a cache. If public history exceeds the budget, review a bounded
history-verification design before raising it.

Run the focused boundary tests with:

```sh
python3 -m unittest discover -s tooling/release -p 'test_*.py' -v
```
