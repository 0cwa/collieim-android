# Building Collie

Reconstruction and release engineering live in [collie-release-engineering](https://github.com/0cwa/collie-release-engineering). See [VERIFY.md](VERIFY.md) for Git-only source inspection.

This checkout contains the reconstructed application source; Gradle does not run
an assembler or inject source from another worktree. JDK/Gradle/SDK/NDK and strict
dependency inputs are declared in this tree. Canonical identity is im.collie.app,
Collie backup names and optional FCM; legacy filename aliases are one product.

**Direct build readiness: BLOCKED.** `build-inputs/native-lock.json` identifies
accepted native bytes. Their public immutable repository has not been supplied
or independently downloaded. `native-publication.json` records this gap. Do not
substitute upstream native libraries or disable strict dependency verification.
Every direct Android Gradle configuration deliberately fails until the required publication layer
and clean-checkout build acceptance are reviewed. This draft is not releasable.

The retained local operator route is in collie-release-engineering and still
requires its explicit trusted-local native acceptance. That is not independent
native reproduction. Obtain the three locked artifacts and POMs from approved
public immutable URLs, verify all hashes, materialize their exclusive repository
and verification entries in source, then run a fresh controlled Gradle build.
After reviewed gate removal, releaseVersionCode and releaseVersionName are normal source-defined Gradle properties. Final values must come from serialized external reservation and be recorded in the build receipt. The expected canonical task is `./gradlew --dependency-verification=strict
:app:assembleProdWebsiteRelease`; it is currently BLOCKED as described above.

Release versions must be externally reserved above 177702 and all later published
or reserved codes using engineering's serialized version policy. RC1 ingestion
reuses its existing signed bytes/code. No build has been performed from this G.
**DRAFT candidate proposal:** 8.35.1-2-rc1 / 177703. This value is not allocated
or reserved. The direct-build gate remains closed until the owner refreshes the
inventory and confirms this proposal through the serialized reservation policy.
No parity with historical APK bytes is claimed after changing build metadata.
Instrumentation remains opt-in. Physical-device/account/attachment/database
upgrade acceptance and independently reproduced native outputs are NOT RUN.
