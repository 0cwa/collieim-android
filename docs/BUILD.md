# Building Collie

Reconstruction and release engineering live in
[collie-release-engineering](https://github.com/0cwa/collie-release-engineering).
See [VERIFY.md](VERIFY.md) for Git-only source inspection.

This checkout contains the reconstructed application source; Gradle does not run
an assembler or inject source from another worktree. Its JDK is Eclipse Temurin
21.0.13+9: `.tool-versions` pins that release and `Dockerfile` pins the matching
official Linux/amd64 image by digest. Gradle, SDK, NDK, and strict dependency
inputs are also declared in this tree. Canonical identity is `im.collie.app`,
Collie backup names and optional FCM; legacy filename aliases are one product.

## Build and verification

Run the production build with strict dependency verification and the repository's
Gradle wrapper:

```sh
./gradlew --dependency-verification=strict :app:assembleProdWebsiteRelease
```

This uses the declared toolchain and checked-in dependency-verification
metadata. Native input status and hashes are recorded in the tracked
`build-inputs` manifests; their verification status does not by itself establish
release authorization.

This general build guide does not cover release acceptance, version reservation,
artifact retention, or operator procedures; those are maintained separately.
A successful source build alone does not establish independent native
reproduction or runtime, physical-device, account, attachment, database, or
upgrade acceptance. See [VERIFY.md](VERIFY.md) for evidence and stated limits.

## Published source dates

The [v8.35.1-2-rc1 release](https://github.com/0cwa/collieim-android/releases/tag/v8.35.1-2-rc1) was published on 2026-11-09 13:57:11 UTC. Its signed tag, `v8.35.1-2-rc1`, points to source G `72e1b84383d068c488e96ca9cd451e730e6e4e77` and is dated 2026-11-09 13:38:34 UTC. The release record establishes when this release became public; the tag object records its tagger date separately.

Generated publication commits in this source snapshot show `2000-01-01T00:00:00Z` because the historical publisher used a fixed fixture timestamp for reproducibility. That date describes generated commit metadata; it is not the age of the application or the original upstream work. Authentic upstream commits remain in the ancestry with their original metadata, and generated-layer messages retain source provenance.
