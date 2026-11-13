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
  :app:testProdWebsiteDebugUnitTest testDebugUnitTest \
  :core:util-jvm:test :lib:libsignal-service:test
```

The script reads the exact Rust version from the native lock and verifies it
against the pinned source. The first build downloads Cargo dependencies and
compiles libsignal. Later builds reuse the generated checkout and Cargo
outputs under `build/host-tests/libsignal`. Use `--work-dir <directory>` to
select another location, and pass its `resources` subdirectory to Gradle.
Memory-constrained local builds can set `CARGO_BUILD_JOBS=1`.
The two explicit `:test` tasks cover JVM-only modules, including resettable-state
concurrency and per-device control-message routing. They are not selected by
Android's `testDebugUnitTest` task name.
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

With `collieHostJniDir` set, Gradle runs the test tasks again instead of reusing
cached or up-to-date test results. Compilation can still use its normal caches.
This keeps each candidate's CI evidence tied to actual test execution.

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
