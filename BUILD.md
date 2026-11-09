# Building Collie

Reconstruction and release engineering live in [collie-release-engineering](https://github.com/0cwa/collie-release-engineering). See [VERIFY.md](VERIFY.md) for Git-only source inspection.

This checkout contains the reconstructed application source; Gradle does not run
an assembler or inject source from another worktree. Its JDK is Eclipse Temurin
21.0.13+9: `.tool-versions` pins that release and `Dockerfile` pins the matching
official Linux/amd64 image by digest. Gradle/SDK/NDK and strict dependency inputs
are also declared in this tree. Canonical identity is im.collie.app,
Collie backup names and optional FCM; legacy filename aliases are one product.

**Direct build readiness: ACCEPTED for the exact native publication evidence recorded in `build-inputs/native-publication.json`.** `build-inputs/native-lock.json` identifies
accepted native bytes. Policy run 42569065555 attempt 1 completed; both readback phases verified all 15 canonical HTTPS paths by response body size and SHA-256. The retained pre-dispatch snapshot and receipt are bound by the evidence digest below. `previous_verified=false` records the authorized first-publication recovery classification; this does not claim origin verification or cryptographic GitHub attestation. Do not
substitute upstream native libraries or disable strict dependency verification.
Native evidence SHA-256: `c250fabb3deaf9b1266b23bba60f9a094636b5359c1efd3a4520114eb5a11948`.
Direct Android Gradle configuration proceeds only when its committed acceptance digest matches the
validated Pages plan, journal, six pinned native files, and exact successful policy run. Native input
acceptance does not by itself establish a releasable build.

The six exact pinned native files were published to the immutable repository and independently read back in both completed phases. The retained local operator route is separate and was not used to establish this acceptance. The source binds the receipt's run, policy, asset, manifest, journal, dispatch and plan digests as trusted local consistency evidence; it is not a cryptographic GitHub attestation or an origin-verification claim. No native init script, local Maven lookup or external native source includeBuild is part of this direct-source recipe.
After reviewed gate removal, releaseVersionCode and releaseVersionName are normal source-defined Gradle properties. Final values must come from serialized external reservation and be recorded in the build receipt. The expected canonical task is `./gradlew --dependency-verification=strict
:app:assembleProdWebsiteRelease`; native input acceptance is recorded above; source build and runtime acceptance remain required.

Release versions must be externally reserved above 177702 and all later published
or reserved codes using engineering's serialized version policy. RC1 ingestion
reuses its existing signed bytes/code. No build has been performed from this G.
**Release candidate identity:** 8.35.1-2-rc1 / 177703. Version code 177703 is held; its
hold must be bound to the actual unsigned manifest before signing. The direct-build gate remains
bound to the recorded native acceptance digest. Build and runtime acceptance remain required.
No parity with historical APK bytes is claimed after changing build metadata.
Instrumentation remains opt-in. Physical-device/account/attachment/database
upgrade acceptance and independently reproduced native outputs are NOT RUN.

Native JVM regression readiness is separate: build-inputs/host-test-inputs.json records the historical Linux testing-JNI candidate and immutable staging-guard recipe. No public host-test input or complete host build recipe is supplied; those tests are NOT RUN, not skipped/passed. Do not package test JNI in a release or treat the six production Maven files as all-test readiness.


### Preserve build outputs before source receipt collection

Build only from the registered agent-owned G checkout after recording its clean HEAD, index tree, and worktree tree. Before Gradle runs, expand and verify this exact generated-path set is absent: `.gradle/`, `.kotlin/`, `.cxx/`, root `build/`, `app/build/`, and each included module's `build/` under `core/*`, `core-gms/*`, `lib/*`, and `feature/*`, plus `screener/build/`, `lintchecks/build/`, and `fast-lint/build/`. Record the expanded paths. After the unsigned APK and required reports are complete, copy the APK, reports, build log, exact command, and tool-version record to the separate receipt directory outside the G checkout. Then remove only the recorded paths that were absent before the build and were created by that build. Do not remove `local.properties`, source files, pre-existing paths, or anything with `git clean`. Finally verify HEAD, index tree, and tracked worktree tree still equal the recorded G commit/tree, and verify there are no untracked or modified source paths before running the strict source receipt collector. Keep the copied APK and reports outside the checkout.

## Published source dates

The [v8.35.1-2-rc1 release](https://github.com/0cwa/collieim-android/releases/tag/v8.35.1-2-rc1) was published on 2026-11-09 13:57:11 UTC. Its signed tag, `v8.35.1-2-rc1`, points to source G `72e1b84383d068c488e96ca9cd451e730e6e4e77` and is dated 2026-11-09 13:38:34 UTC. The release record establishes when this release became public; the tag object records its tagger date separately.

Generated publication commits in this source snapshot show `2000-01-01T00:00:00Z` because the historical publisher used a fixed fixture timestamp for reproducibility. That date describes generated commit metadata; it is not the age of the application or the original upstream work. Authentic upstream commits remain in the ancestry with their original metadata, and generated-layer messages retain source provenance.
