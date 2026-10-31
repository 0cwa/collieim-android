# Molly releases as patches on a Signal base

The tracked application source on these branches is the exact Signal release.
The build reconstructs Molly in a separate worktree. Review the pipeline,
the original Molly snapshot, and any compatibility changes as separate commits.

## Source and review boundaries

| Input | Immutable source |
| --- | --- |
| Signal 8.19.2 | `c47a74ba0961903eeaeda6d22347a990056c3e83` |
| Official Molly 8.19.2-4 | `0e202dda90785771ff8c87526eaaf30c655f54f3` |
| Official Molly source tree | `3cd0bb01cf840e4b702187085432020d163d80ce` |
| Signal 8.33.2 | `6e9dddc01f1b26e608ac683807c51e139c141ed2` |
| Pipeline and historical export origin | [PR #7 at cef8e04](https://github.com/0cwa/mollyim-android/tree/cef8e044f55d1a47c5634f500e01b3956ee31f4f) |

`molly-build.json` selects the release. Its Signal commit must be an ancestor
of the branch. The assembler rejects changes to the tracked Signal source
outside the explicitly named pipeline, documentation, patch, and CI paths.
An application edit cannot disappear behind a broad source projection.

`patches/molly` contains the original 65 mailbox patches in 16 review groups,
covering 3,178 changed paths. Their bytes, source pins, ordering, binary
payloads, executable modes, and original whitespace are retained from PR #7.
They represent the released Molly snapshot, including maintained merge
resolutions and third-party attribution already present in that snapshot.
The patch grouping was organized by AI; it adds no application logic to this
historical source. Grouping does not make features independently buildable.

The README inside that unchanged snapshot retains its original PR #7 paths.
Use the commands in this guide for this branch; the old `tools/827` pilot,
pilot-specific source adaptations, reports, and CI jobs are not imported.

The pipeline and its tests are AI-assisted infrastructure. Compatibility
mailboxes under `patches/ports` are explicitly identified as AI-generated
application adaptations. They never replace or silently regenerate the
official Molly mailboxes.

## Reconstruct and prove the source

Use Git with `merge-tree --write-tree --merge-base` support and Python 3.10+.
The port was generated with Git 2.51.1; its exact mechanical-tree gate detects
changes in Git's merge behavior. Git and Python alone are sufficient for
source verification. A complete, non-shallow history is required for ancestry
checks.

```sh
git fetch --no-tags https://github.com/mollyim/mollyim-android.git \
  0e202dda90785771ff8c87526eaaf30c655f54f3
python3 -m unittest discover -s tools/patches -p 'test_*.py' -v
python3 tools/patches/release.py --repo . --config molly-build.json \
  --worktree /tmp/molly-release --report /tmp/molly-source-proof.json
```

The destination must be new and outside the source checkout. A failed
explicit destination is retained for inspection. The assembler does not
rewrite the checkout, move its branch, or modify patch files.

Every build first reconstructs the **official historical Molly tree** from
the pinned old Signal commit. The complete verifier replays both legal
ready-node orders, checks provenance and unique path ownership, and requires
exact whole-tree equality. It also checks the materialized index and files;
ignored leftovers and sparse omissions are not accepted as a clean build.
The source-whitespace exception is conditional on this exact tree equality.

For Signal 8.19.2 this verified source is the Android build input. A configured
native hotfix is a separate, mandatory native-source build input, described
below. The historical Android tree proof alone does not establish that an APK
contains the fixed native library.

## Line movement is mechanical

Ordinary mailboxes are applied with `git am --no-3way --whitespace=nowarn`.
Git locates hunks using their context, so adding or deleting unrelated lines
earlier in a file needs no patch edit. The runner explicitly disables ambient
whitespace-ignore matching, automatic whitespace correction, hooks, signing,
and rerere. Changed context stops the replay; it is not discarded as fuzz.

Regression tests cover movement in both directions, different offsets for
multiple hunks, unchanged mailbox bytes, changed context, and ambient Git
settings that would otherwise hide a conflict. Do not refresh hunk headers
or regenerate every mailbox just because an upstream line number changes.

For a new Signal release, the assembler uses a separate deterministic
three-way integration step. It first proves the original Molly patches,
then merges that exact snapshot with the new Signal base using the old
Signal commit as the explicit merge base. This runs in an isolated Git
directory with fixed merge options. Stable `MOLLY` and `SIGNAL` labels prevent
commit-ID changes from introducing needless conflict-marker edits.

Clean automatic merges, context relocation, and Git's configured rename
detection happen in that mechanical step. No AI runs during reconstruction.
The pinned attribute files must be present before merging; missing partial-clone
objects cannot silently disable an attribute driver. The complete resulting tree is pinned. A different result fails with a
request to inspect the inputs and Git behavior, rather than silently updating
the expected hash.

## Review the Signal 8.33.2 adaptations

The first two commits on the upgrade branch contain the pipeline and original
Molly snapshot with **no AI compatibility patches**. This is an intentionally
unadapted review checkpoint: source assembly reports the conflicts and refuses
to label the result buildable.

Subsequent commits add ordinary mailbox resolutions, grouped by application
feature. Their parent source is Git's mechanical merge, so they show changes
that remain after deterministic integration. The initial groups reproduce
the existing PR #7 source merge
`90a0bb917ecc49a62236d539f4e7faae01b1d9fe`; eight subsequent layers retain
the preference-import, NetCipher SDK DSL, broader integration/regression, CDN
test-fixture, linked-device reminder, backup preference precedence, donation
completion, and legacy backup-notification cleanup corrections as individual
review steps. Each mailbox
records its source commit and AI-generation status.
Previous mailboxes do not receive new provenance headers when a later fix is
added.

The port has 764 conflict paths. Initial resolution patches change 763 of
them; the retained `CorrectedPreferenceFragment.java` modify/delete conflict
is recorded as an explicit keep-Molly decision in the configuration. A
content diff is unnecessary for that choice because Git already leaves
Molly's version in the conflicted tree. The choice is still reviewed as an
AI compatibility decision. Every conflict must be covered by a resolution
or an explicit unchanged-content decision.

The final source must equal tree
`59fa2b2af25d82ffd11d0198d5e9e8f5b5b3eadf`, the tree of synthetic source
commit `a069fdf625d924e517eb6f2aeb0cbd607db3fcdb`. It extends donation
checkpoint `0e7a018870f034d547db10e8e4856c515bbe2b80` (tree
`c9e7ee6396f5109c8d29e5e87f551cd4e4b9c68d`) with channel-checked cleanup of
Molly's old backup alerts at IDs `120500` and `120501`; new backup alerts keep
Signal's current IDs. The donation checkpoint extends backup checkpoint
`266d0185d021b2b6a98882205f6b6242152d146e` (tree
`febafe3aa084d0ca6219e4f10a27ee66fbecfe4e`) with the restored donation badge
acknowledgement, eligible monthly-sustainer link, and a completion-sheet
loading-state fix plus Compose coverage. The backup checkpoint extends
re-registration checkpoint `d3c917a5be7db6116c83b7e29e09f145e7f38825` (tree
`0e5c581b7db3f4429256630011790f3ed4ad141f`). That checkpoint extends
`cb8173a9ed5a4965d433e10e1a0a64e241b2ce4c` (tree
`2533afec2fe2d7f9fc682f699fc21d7624521889`) with the explicit
re-registration marker, linked-device reminder assignment after successful
primary-device multi-device registration, and marker test. These settings
restore Molly's prior reminder behavior while leaving new registrations
unchanged. This remains a development port, not an official Molly release.

Each synthetic source commit is stored in an incremental bundle. The reminder
bundle requires `cb8173a9ed5a4965d433e10e1a0a64e241b2ce4c`; the backup bundle
requires `d3c917a5be7db6116c83b7e29e09f145e7f38825`; the donation bundle
requires `266d0185d021b2b6a98882205f6b6242152d146e`; and the backup-notification
bundle requires `0e7a018870f034d547db10e8e4856c515bbe2b80`. Each prerequisite must
already be present in the object database. For a fresh local repository,
materialize and verify the chain in order:

```sh
git cat-file -e cb8173a9ed5a4965d433e10e1a0a64e241b2ce4c^{commit}
git rev-parse cb8173a9ed5a4965d433e10e1a0a64e241b2ce4c^{tree}
git bundle verify patches/ports/signal-8.33.2/followups/reregistration-linked-devices-reminder/source-checkpoint.bundle
git fetch patches/ports/signal-8.33.2/followups/reregistration-linked-devices-reminder/source-checkpoint.bundle \
  refs/heads/source-checkpoint/reregistration-linked-devices-reminder:refs/port-source/reregistration-linked-devices-reminder
git rev-parse refs/port-source/reregistration-linked-devices-reminder^{tree}

git bundle verify patches/ports/signal-8.33.2/followups/legacy-backup-precedence/source-checkpoint.bundle
git fetch patches/ports/signal-8.33.2/followups/legacy-backup-precedence/source-checkpoint.bundle \
  refs/heads/source-checkpoint/legacy-backup-precedence:refs/port-source/legacy-backup-precedence
git rev-parse refs/port-source/legacy-backup-precedence^{tree}

git bundle verify patches/ports/signal-8.33.2/followups/donation-completion/source-checkpoint.bundle
git fetch patches/ports/signal-8.33.2/followups/donation-completion/source-checkpoint.bundle \
  refs/heads/source-checkpoint/donation-completion:refs/port-source/donation-completion
git rev-parse refs/port-source/donation-completion^{tree}

git bundle verify patches/ports/signal-8.33.2/followups/backup-notification-id-upgrade/source-checkpoint.bundle
git fetch patches/ports/signal-8.33.2/followups/backup-notification-id-upgrade/source-checkpoint.bundle \
  refs/heads/source-checkpoint/backup-notification-id-upgrade:refs/port-source/backup-notification-id-upgrade
git rev-parse refs/port-source/backup-notification-id-upgrade^{tree}
```

The tree commands must print, in order, `2533afec2fe2d7f9fc682f699fc21d7624521889`,
`0e5c581b7db3f4429256630011790f3ed4ad141f`,
`febafe3aa084d0ca6219e4f10a27ee66fbecfe4e`,
`c9e7ee6396f5109c8d29e5e87f551cd4e4b9c68d`, and
`59fa2b2af25d82ffd11d0198d5e9e8f5b5b3eadf`. The bundles do not carry their
prerequisites; a fresh clone cannot verify or fetch a later bundle until the
earlier checkpoints are materialized.

When changing a resolution, update only the affected mailbox and its reviewed
target-tree pin. Leave unrelated mailbox envelopes and the historical forest
alone. `export_complete.py` remains available for exporting a new official
source snapshot; it is not a command to refresh unchanged patches for line
offsets. An upstream release with genuinely changed APIs still requires
explicit compatibility work and review.

## The microphone fix is a native source cherry-pick

The post-hangup media teardown fix is Signal RingRTC commit
[`4b1b6fa53c9f30c87c8ea78e44675d4a13e1e4d3`](https://github.com/signalapp/ringrtc/commit/4b1b6fa53c9f30c87c8ea78e44675d4a13e1e4d3),
"Ensure calls terminate completely in corner cases," discussed in
[Molly issue #843](https://github.com/mollyim/mollyim-android/issues/843).
It belongs to RingRTC's native Rust source, which Android consumes as an AAR.

On the historical branch, the final commit adds `native-ringrtc.json` and
the mandatory native build integration. The native helper checks out Molly
RingRTC v2.69.5-1 (`8d81a87adcb08b77446b691be3692ff7ed53d580`) and **directly
cherry-picks the Signal commit**, preserving its author and upstream trailer.
There is no microphone hotfix `.patch` in either Molly forest.

All production hunks apply unchanged. The only backport adjustments merge two
test import blocks while preserving the older test infrastructure. The helper
checks exact preconditions, the whole resulting native tree
`f310c13efdb82ff6123f05df6e232a74babb7d33`, and equality of the production
patch ID with Signal. Native production behavior and the six regression tests
come from Signal; no replacement native implementation is generated.

This preserves Molly's native API, proxy support, and WebRTC fork. Jumping the
old Android app straight to RingRTC 2.72/2.73 would require unrelated calling
API migrations. Keeping the old published AAR would omit the fix. The build
therefore produces a distinct local version and substitutes that exact
artifact through an exclusive repository. Missing native output is fatal;
the original Molly dependency pin cannot downgrade the resulting build.

The native helper exposes separate source, build, and integration stages:

```sh
python3 tools/native/ringrtc.py prepare --lock native-ringrtc.json \
  --source-dir /tmp/ringrtc-source --proof-dir /tmp/ringrtc-proof
python3 tools/native/ringrtc.py build --lock native-ringrtc.json \
  --source-dir /tmp/ringrtc-source --proof-dir /tmp/ringrtc-proof \
  --maven-dir /tmp/ringrtc-maven
python3 tools/native/ringrtc.py integrate --lock native-ringrtc.json \
  --app-dir /tmp/molly-release --proof-dir /tmp/ringrtc-proof \
  --maven-dir /tmp/ringrtc-maven
```

Use the generated `/tmp/ringrtc-proof/ringrtc.init.gradle` for every Android
Gradle invocation on this branch. The checked-in build driver enforces that
requirement. Only the exact locally produced component receives new artifact
hashes in the generated worktree's verification metadata; existing dependency
verification remains strict. The native source/build proof and artifact hashes
travel with the CI result. A hash calculated from a local build records that
build's output; it is not an independent reproducible-build attestation.

Signal 8.33.2 already contains the fix through its RingRTC update. The port's
Molly RingRTC 2.73.0-1 dependency includes the same upstream native commit, so
the older-API native backport is confined to the historical branch.

## Build gates and limits

`.github/workflows/molly-patch-release.yml` runs a source-proof job and a
dependent Android job on standard Ubuntu runners. It replaces the inherited
Signal APK and APK-diff PR jobs, which would otherwise build the unpatched
Signal checkout and request unrelated premium runners. Repository permissions
are read-only; the workflow does not publish releases or sign production APKs.

The Android job reconstructs the verified source again, prepares the native
library when configured, installs the exact source toolchain, assembles the
Website debug APK, and runs the configured test tasks. The 8.33.2 profile
retains the complete network suite and focused integration regression gate
from PR #7. The 8.19.2 profile uses tests present in the official old source;
it does not import AI-authored port tests. Native source reconstruction and
native compilation/test results are reported separately.

Source equality, successful compilation, unit tests, reproducible binaries,
production signing, and device/live-service behavior are distinct claims.
Keep a PR in draft until its required native and Android gates have actually
passed. Neither a source-tree proof nor an earlier candidate's CI result is
a fresh APK validation. No native binary checksum, build success, or device
acceptance is assumed by these patches.
