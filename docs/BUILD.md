# Building Collie

The application source is maintained directly in this repository. Follow the
[development cycle](https://github.com/0cwa/collie-release-engineering/blob/main/docs/DEVELOPMENT-CYCLE.md)
for contributor workflow, [VERIFY.md](VERIFY.md) for source verification, and
[RELEASING.md](RELEASING.md) for the release build and signing process.
The release build uses `tooling/reproducible-builds/build-release.sh` and the
native inputs recorded in `build-inputs`.

## Historical published source dates

The [v8.35.1-2-rc1 release](https://github.com/0cwa/collieim-android/releases/tag/v8.35.1-2-rc1) was published on 2026-11-09 13:57:11 UTC. Its signed tag, `v8.35.1-2-rc1`, points to source G `72e1b84383d068c488e96ca9cd451e730e6e4e77` and is dated 2026-11-09 13:38:34 UTC. The release record establishes when this release became public; the tag object records its tagger date separately.

Generated source commits G and I for `8.35.1-3-rc1` use the canonical publication timestamp `2026-11-10T03:20:12Z`. This is commit metadata; authentic Signal and Molly history retains its original dates.

The 62 Collie-created commits in RC2 were corrected to integration time `2026-11-09T12:43:27Z`, with each source tree preserved. The RC2 tag and assets remain unchanged. Authentic upstream commits retain their original metadata; new generated RC3 commits use the publication timestamp above.
