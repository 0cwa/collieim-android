# Building Collie

The application source is maintained directly in this repository. Follow the
[development cycle](https://github.com/0cwa/collie-release-engineering/blob/main/docs/DEVELOPMENT-CYCLE.md)
for contributor workflow, [VERIFY.md](VERIFY.md) for source verification, and
[RELEASING.md](RELEASING.md) for the release build and signing process.
The release build uses `tooling/reproducible-builds/build-release.sh` and the
native inputs recorded in `build-inputs`.


## Beta release preparation

Prepare a Beta source commit on a review branch with a version code above the last Beta hold. For example, from a clean source commit after the release notes are ready:

```sh
python3 tooling/collie-log/stamp.py 8.36.3-3-beta1 --edition beta --version-code 178302 --date 2026-11-24
```

Review and commit the generated Beta release record, changelog, and version properties. The normal build keeps preparation optional. To require it locally, run `COLLIE_BUILD_EDITION=beta COLLIE_REQUIRE_RELEASE_PREP=1 tooling/reproducible-builds/build-release.sh <output-dir>`; the verifier must report a prepared Beta build before Gradle receives `-PcollieReleasePrepared=true`.

The workflow enables that strict check only for a direct `workflow_dispatch` on `0cwa/collieim-android` main, with edition `beta`, the caller workflow matching the running workflow, and the requested `ref` exactly equal to the dispatch SHA. Dispatch `Build unsigned release APK` on `main`, choose `beta`, and enter the full SHA of the prepared commit. Other workflow calls and ordinary Stable builds do not opt into strict preparation.
