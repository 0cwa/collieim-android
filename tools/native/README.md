# Pinned native artifact staging (incremental)

This is a pinned source-preparation and fail-closed artifact-staging component, not yet a native source builder. The lock records exact source commits, attributed patch bytes, and hashes of the three locally built candidate binaries and their original POMs. It does not prove those binaries were produced from those sources, that the build is reproducible, or that real proxy and call behavior works. The complete RingRTC/WebRTC builder, independently pinned output trees/toolchain verification, and Android runtime gates must be completed before release. The accompanying build-driver integration permits provisional debug/test runs only and explicitly blocks release assembly.

Given local Git repositories containing the three exact pinned commits, prepare fresh source checkouts without network access:

```sh
python3 tools/native/prepare_sources.py --lock tools/native/native-lock.json \
  --libsignal "$LIBSIGNAL_REPO" --ringrtc "$RINGRTC_REPO" --webrtc "$WEBRTC_REPO" \
  --output "$NATIVE_SOURCES"
```

The helper verifies input commits and patch hashes, preserves authors in the libsignal/RingRTC mailboxes, and records the resulting source trees. It has been tested with synthetic Git fixtures and the three exact pinned source repositories. `ATTRIBUTION.md` records the composite WebRTC origin. Source preparation alone does not prove the staged binaries came from those trees. `prepare_sources.py --verify-prepared` independently replays the pinned patch bytes into a temporary index and checks each prepared tree and working source.

Given a local Maven repository containing exactly the locked candidate inputs and a fresh reconstructed app checkout, stage them using paths supplied by the caller:

```sh
python3 tools/native/stage_artifacts.py --lock tools/native/native-lock.json \
  --input-maven "$NATIVE_INPUT_MAVEN" --output "$NATIVE_STAGE" \
  --verification "$APP_SOURCE/gradle/verification-metadata.xml"
python3 tools/native/stage_artifacts.py --verify-stage --lock tools/native/native-lock.json \
  --output "$NATIVE_STAGE" --verification "$APP_SOURCE/gradle/verification-metadata.xml"
```

Output contains a local Maven repository at distinct `im.collie.native` coordinates, a Gradle init script, generated strict verification metadata and a digest receipt. The input AAR/JAR bytes are unchanged. The generated POMs keep upstream dependencies and redirect libsignal's matching client dependency to the Collie coordinate. The generated metadata narrows the existing blanket POM trust rule so it cannot bypass the new native POM hashes; trust for other groups is preserved.

The companion catalog patch switches the application to those distinct coordinates. It is mandatory: without the generated exclusive repository, resolution must fail instead of falling back to published Molly or Signal binaries. The accompanying build-driver integration verifies the stage immediately before every Gradle invocation, permits only the exact recomputed verification-metadata transformation in its disposable checkout, and passes the generated init script with strict verification. It refuses release assembly and reports that the complete RingRTC/WebRTC native source build is not implemented. A separate `native-build-libsignal` diagnostic phase validates prepared source, checks the recorded Rust/NDK versions and four proxy-mode tests, then invokes the documented libsignal Android build; its receipt is explicitly partial and cannot authorize release. On the real pinned source, its four proxy-only unit tests passed and Gradle built the AAR and client JAR; the diagnostic correctly rejected their hashes because they differ from the locked candidate bytes. The `native-prepare` phase accepts three caller-supplied local repositories; `native-integrate` stages the pinned provisional artifacts from the supplied `--maven-dir`. Do not add private or absolute machine paths to tracked configuration. The generated init script necessarily contains the caller's local Maven URI and must remain untracked.

The current three artifact hashes identify one local native build. A fresh build can legitimately differ until reproducibility is demonstrated, and should not be accepted by weakening verification or overwriting the lock automatically. Additional source-based build, source provenance, four-ABI output and no-direct-network runtime tests remain required.

`setup_webrtc.py` is a standalone diagnostic for the pinned WebRTC gclient setup. It omits only the source test-media hook and restores DEPS afterward, but it does not attest the downloaded dependency closure. `build_ringrtc.py` runs the pinned RingRTC builder with a narrowly checked in-memory adaptation that avoids copying WebRTC build trees, using a temporary link in the prepared scratch checkout. It validates the tracked WebRTC tree and disables the upstream wrapper's cloud upload trigger. A matching candidate hash produces only a partial receipt; neither script is integrated into release assembly or has yet proved the complete source-to-artifact chain.

For an explicitly non-release RingRTC diagnostic after source preparation and gclient setup:

```sh
python3 tools/native/build_ringrtc.py --lock tools/native/native-lock.json \
  --sources "$NATIVE_SOURCES" --workspace "$WEBRTC_WORKSPACE" --sdk "$ANDROID_HOME" \
  --java-home "$WEBRTC_WORKSPACE/src/third_party/jdk/current" \
  --rust-bin "$RUST_BIN" --output "$NEW_DIAGNOSTIC_DIR" \
  --gradle-zip "$GRADLE_931_ZIP" --gradle-distribution "$GRADLE_931_EXTRACTED"
```

Provide pinned depot_tools on `PATH` and Rust 1.98.1 with all four Android targets. The host must have a working C and C++ toolchain; an Android NDK alone is insufficient. The build script refuses a pre-existing output directory and never enables upstream cloud upload. Its exact-byte comparison may fail because reproducibility is not established; never update the hash automatically to make it pass.

The RingRTC diagnostic checks that the supplied 9.3.1 all-distribution ZIP matches Gradle's official SHA-256 (`17f277867f6914d61b1aa02efab1ba7bb439ad652ca485cd8ca6842fccec6e43`), checks the extracted files against that ZIP, and executes the verified distribution directly with the RingRTC project directory explicitly selected. It rejects a missing ZIP, modified extraction, symlinks, extra files and group/world-writable distribution paths. Source ZIP: https://services.gradle.org/distributions/gradle-9.3.1-all.zip.sha256. This is not an independent source provenance or runtime proof; the running user's ability to modify files concurrently remains outside the check.

Native diagnostic Gradle invocations reject inherited Gradle/JVM options, user-home init scripts and user-home `gradle.properties`. To retain caches, use a clean `GRADLE_USER_HOME` whose `caches` and `wrapper` directories refer to the desired caches. Dependency cache contents are not authenticated by these environment checks; the native release gate remains closed. The libsignal and native app diagnostics also require an official 9.6.0 ZIP and an extracted distribution that matches it; the real 9.6.0 ZIP and extraction passed the verifier, while the diagnostics have not yet executed under that requirement.


The `native-build-libsignal` and native app diagnostic phases require `--native-gradle-zip` and `--native-gradle-distribution` pointing to the Gradle 9.6.0 all ZIP and its extracted directory. The ZIP must match the checksum already pinned by the 8.35.1 source, `87a2216cc1f9122192d4e0fe905ffdf1b4c72cff797e9f733b174e157cadd396`; the directory is checked against the ZIP before and after execution. The build runs the verified distribution directly with the intended project directory, leaving unrelated legacy build flows unchanged. The cached 9.6.0 ZIP and extracted distribution passed this verifier.

An offline strict-verification fixture for direct and transitive upstream-coordinate substitution is available via `COLLIE_NATIVE_TEST_GRADLE=/path/to/verified/gradle python3 tools/native/native_fallback_smoke.py`. It creates and removes a small temporary Maven repository and checks all three native modules resolve only to Collie coordinates.

The partial RingRTC diagnostic also checks 70 nested Git checkouts against the
pinned retained gclient snapshot before and after the build. It hashes tracked
working files, checks file modes and symlinks, permits pinned Git CRLF checkout
conversion, and checks Signal's exact upstream Java-release hook output. The
27 recorded Gitlinks must be absent or empty. This is a consistency check, not
independent authentication of the snapshot against DEPS; ignored and untracked
files, CIPD package contents, compiler provenance and concurrent mutations are
not attested. It cannot authorize a release.

The partial RingRTC diagnostic verifies the recorded 59 Linux/Android CIPD
package manifests and their installed payloads before and after execution. The
reference hashes were derived by checking the packages' content-addressed CIPD
archive IDs against the upstream DEPS selections; generated version files and
four separately pinned upstream Git files sharing a package root are checked as
well. The check rejects undeclared files and directories under those package
roots and writable package files. It does not authenticate its reference by
itself, attest symlink target contents, other build inputs or concurrent
changes, or establish source-to-binary provenance. Release remains blocked.

[CIPD-PROVENANCE.md](CIPD-PROVENANCE.md) records how the package reference was derived and its remaining trust boundaries.

[RUST-PROVENANCE.md](RUST-PROVENANCE.md) describes the partial Rust payload check, how its reference was derived, and what remains unverified.
