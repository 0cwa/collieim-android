# Host JVM tests

The production `im.collie.native:libsignal-client` JAR contains Java classes but
no desktop native library. Android gets its native implementation from the
separate Android AAR. JVM unit tests need a Linux library instead.

`build_libsignal.py` builds the testing JNI library from the same libsignal
revision and proxy patches pinned in `build-inputs/native-lock.json`. It fetches
the patch from the revision recorded in `build-inputs/engineering.json`, checks
its SHA-256, and checks the complete patched source tree against
`build-inputs/host-test-inputs.json`. It uses upstream's desktop build script,
including its required Linux thin LTO settings.

## Run locally

Use Linux x86-64, Python 3.11 or newer, Git, Rust via rustup, Clang/libclang,
CMake, Make, protoc, and binutils (`nm` and `strip`). Android unit tests also need the JDK
and Android SDK described in the app's build documentation. On Debian/Ubuntu,
the native prerequisites are `clang libclang-dev cmake make protobuf-compiler
libprotobuf-dev python3 git binutils`.

From the repository root:

```sh
rustup toolchain install 1.98.1 --profile minimal
python3 tooling/host-tests/build_libsignal.py
./gradlew --no-daemon --continue \
  -PcollieHostJniDir=build/host-tests/resources \
  :app:testProdWebsiteDebugUnitTest testDebugUnitTest
```

The script reads the exact Rust version from the native lock and verifies it
against the pinned source. The first build downloads Cargo dependencies and
compiles libsignal. Later builds reuse the generated checkout and Cargo
outputs under `build/host-tests/libsignal`. Use `--work-dir <directory>` to
select another location, and pass its `resources` subdirectory to Gradle.
Memory-constrained local builds can set `CARGO_BUILD_JOBS=1`.
`--prepare-only` verifies the source and patches without compiling Rust.
`--verify-only` checks cached resources and their receipt against the current
source pins without Rust or network access; it still needs `ldd` and `nm`.

Only `libsignal_jni_testing_amd64.so` is staged in the resource directory.
Libsignal loads this testing library first; it includes the production JNI
functions. The script checks its ELF architecture, shared-library dependencies,
and both production and testing JNI exports. It strips debug symbols only from
the staging copy, preserving the original build output, then validates the
stripped copy before replacing the resource. This reduces disk use when
Robolectric extracts native resources for separate classloaders. The receipt
at `build/host-tests/receipt.json` records the staged bytes' SHA-256 and source
pins. Gradle adds the directory only to host
`Test` tasks. No Android dependency, APK resource, or production native artifact
is replaced.

Source verification runs before and after compilation. If the source pins
change or a generated checkout is modified, remove the generated
`build/host-tests/libsignal` directory and rebuild. If the pinned patch SHA-256
changes, also remove `build/host-tests/libsignal.mbox` before rebuilding. Cache
keys in CI must cover the native lock, engineering revision, host-test manifest,
and this builder.
CI may cache just `build/host-tests/resources` and `build/host-tests/receipt.json`
and run `--verify-only` on an exact cache hit.
Do not reuse a testing binary solely because it has the expected filename.

## Test the tooling

These tests need no network access, Rust compiler, or Android SDK:

```sh
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover \
  -s tooling/host-tests -p 'test_*.py'
```

They exercise real local Git source preparation and reject altered patches,
changed source trees, missing or incompatible native outputs, and competing
native resources. They also check that stripping preserves the original output
and rejects an invalid stripped library before replacing a staged resource.
Passing these checks does not replace running the JVM suite
with a non-zero test count.

The required module and security-suite inventory and the repository activation
steps are in [Security CI readiness](../../docs/SECURITY-CI-READINESS.md).

## Temporary security-stack validation

This support branch validates a pinned app commit without changing its production
sources. It is separate from the app changes and does not establish a full-suite
or release result. The full unit and production readiness jobs run on the pinned app branch.

The `Security stack JVM validation` job runs the backup-preference and relay
revocation classes, three updater classes, the update-settings Compose screen
class, and the upstream `FiatMoneyTest`. Every expected class must be present with
actual passing testcases; a missing class, empty run, failure, error, or skip fails
the gate. The Gradle tasks are explicitly rerun, so cached test results do not
substitute for execution.

Before publishing this validation branch, set `FINAL_APP_SHA` in `pr.yml` to the
full final app commit and put these support changes on top of that commit. The
source check rejects every difference outside its explicit support-file list.
The root Gradle file must match the app exactly, or match after removing the two
reviewed test-only JNI additions. It checks tracked and untracked working files before and
after tests. The resulting receipt, native build receipt, Gradle log, and JUnit
reports are retained on both success and failure.

The separate API 35 job builds the app and its instrumentation APK together,
checks that both APK hashes remain identical after execution, and requires both
named `CollieUpdateSettingsTest` methods and both screenshots. It keeps the
device API/fingerprint, reports, screenshots, and logs for inspection. Set its
`FINAL_APP_SHA` in `debug-apk.yml` to the same app revision before publishing.
Passing this job does not substitute for visually reviewing its screenshots.

The device-evidence gate fully decodes both PNG captures with Pillow 13.0.0.
Install that version before running the offline validation tests; CI pins the
Python 3.12 wheel hash and rejects incomplete captures.
