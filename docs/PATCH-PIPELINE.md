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
`90a0bb917ecc49a62236d539f4e7faae01b1d9fe`; twenty-seven subsequent layers retain
the preference-import, NetCipher SDK DSL, broader integration/regression, CDN
test-fixture, linked-device reminder, backup preference precedence, donation
completion, legacy backup-notification cleanup, recipient badge list, localized
resource notices, locked system information, remaining resource notices, dynamic
appearance, blocked-network priority, recipient avatar badge, version preference
write, Giphy client formatting, always-relay accessor, logcat collection, three retained Molly import corrections, source import formatting, direct backup cancellation, pre-27 selector cleanup, registration layout and timing log, and unavailable-contact and retained legacy code as individual review steps. Each mailbox
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
`41a6f4dd9731829110773b23e7739c5b5fd65444`, the tree of synthetic source
commit `a3a9eaabe66d42fdbf8807158eca7de8c9755b33`. The unavailable-contact message no longer distinguishes whether
the person previously used Signal. This checkpoint also restores the currently unused
legacy PlainTextSharedPrefsDataStore API and imports, and removes one unused import.
It extends registration and timing checkpoint `1f0af82f4ab6a510afe506d0efd33d8f09ecf24e` (tree `ad924cb75d22db46a530c9257fe331bc1b6e218a`).
The registration and timing checkpoint keeps a longer one-line
registration signature and removes the shared-contacts duration from a debug timing log.
It extends pre-27 selector checkpoint `bda4a65056b18ae1b9aa31aefa8a737342967b64` (tree `c23d85ed3707b8c153c65e1a91dcca30dd51ee7c`).
The pre-27 selector checkpoint extends direct backup cancellation
checkpoint `7ee4f371851ac9f2baae2915b209eaf2a664e92f` (tree `76df44b49dccd2ea946719764aaa23fd8f9e9208`). The direct backup cancellation checkpoint extends media and preference imports
checkpoint `98f6a7e996abf915eecee06ece4ea901a34ef64b` (tree `ebc09c526aeab140c8c51ebc898d08f32c0f28c6`). The media and preference imports checkpoint extends source import formatting
checkpoint `5bd3bcd52a7f42ace4a303a070b5a6d3f1d0f1c9` (tree `46d52ca93e698991994f668d7d649f61ec6cd503`). The source import formatting checkpoint retains Molly screen-lock punctuation and
the mechanical-merge reaction overlay import order; it extends activity and push imports
checkpoint `ad4e4df669330f034242afe4d19f29bb1c6f3995` (tree `09b480ee848a0c390779e27ca166fbbe0bf13f3a`). The activity and push imports checkpoint extends retained imports
checkpoint `6a08b686d0f4159a8b801420d601ce55b5195b66` (tree `ed84aae0b10c71574fb91a124202015f298a2226`). The retained imports checkpoint extends logcat collection
checkpoint `9fd7b272e2b546c5fc706315df09c5778590f1fb` (tree `2f2df9763230c11a9a0eac423b0fec52ef0b7fe5`). The logcat and always-relay checkpoints extend Giphy formatting
checkpoint `ae5099dd637e08095c995464ec22118866296e3a` (tree `ff319e31f48739facb1b430102ce61351114ebb8`). The Giphy formatting checkpoint extends version preference
checkpoint `b0e9ca51c613c1b88ada51a5552b0b7131d1621e` (tree `190ad231ab86fd7fbe7c6cb137a3bf0680a13d24`). The version preference checkpoint extends recipient avatar badge
checkpoint `09d0c9e17576de7090da7a9e6c84080c618f817e` (tree `67761e2dc6664979273d89f2a438fa7be060f65a`). The recipient avatar badge checkpoint extends blocked-network priority
checkpoint `7a2ec75db0b56f45310d09716a3559e5dd503b19` (tree `936c1c8ccf52a6450b4d6b9fb216ae1dfdff102a`). The blocked-network priority checkpoint extends dynamic appearance
checkpoint `325a19e470349aa88232eaa6fd68d7e064a3da24` (tree `229e4bd926c1080318cf80ee6633178e42eeb6dd`). Five prior checkpoints extend the backup-notification
checkpoint `ae2672a773d15d1b22186bd8acbec2221760f049` (tree `70f2eba69967c4a3ae82dd9204999feb1bd70f9b`): recipient badge list, localized
resource notices, locked system information, remaining resource notices, and Molly dynamic appearance.
The backup-notification checkpoint extends donation
checkpoint `34100dfa15eb70256d948f96b14abe4b7c7920d6` (tree
`33540be50355fd43b776249136554d167625462a`) with channel-checked cleanup of
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
bundle requires `34100dfa15eb70256d948f96b14abe4b7c7920d6`. Each prerequisite must
already be present in the object database. The nineteen later bundles each require
the source commit from the immediately preceding checkpoint. For a fresh local repository,
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

git bundle verify patches/ports/signal-8.33.2/followups/recipient-badge-list/source-checkpoint.bundle
git fetch patches/ports/signal-8.33.2/followups/recipient-badge-list/source-checkpoint.bundle \
  refs/heads/source-checkpoint/recipient-badge-list:refs/port-source/recipient-badge-list
git rev-parse refs/port-source/recipient-badge-list^{tree}

git bundle verify patches/ports/signal-8.33.2/followups/localized-resource-notices/source-checkpoint.bundle
git fetch patches/ports/signal-8.33.2/followups/localized-resource-notices/source-checkpoint.bundle \
  refs/heads/source-checkpoint/localized-resource-notices:refs/port-source/localized-resource-notices
git rev-parse refs/port-source/localized-resource-notices^{tree}

git bundle verify patches/ports/signal-8.33.2/followups/locked-system-info/source-checkpoint.bundle
git fetch patches/ports/signal-8.33.2/followups/locked-system-info/source-checkpoint.bundle \
  refs/heads/source-checkpoint/locked-system-info:refs/port-source/locked-system-info
git rev-parse refs/port-source/locked-system-info^{tree}

git bundle verify patches/ports/signal-8.33.2/followups/remaining-resource-notices/source-checkpoint.bundle
git fetch patches/ports/signal-8.33.2/followups/remaining-resource-notices/source-checkpoint.bundle \
  refs/heads/source-checkpoint/remaining-resource-notices:refs/port-source/remaining-resource-notices
git rev-parse refs/port-source/remaining-resource-notices^{tree}

git bundle verify patches/ports/signal-8.33.2/followups/dynamic-appearance-colors/source-checkpoint.bundle
git fetch patches/ports/signal-8.33.2/followups/dynamic-appearance-colors/source-checkpoint.bundle \
  refs/heads/source-checkpoint/dynamic-appearance-colors:refs/port-source/dynamic-appearance-colors
git rev-parse refs/port-source/dynamic-appearance-colors^{tree}

git bundle verify patches/ports/signal-8.33.2/followups/blocked-network-priority/source-checkpoint.bundle
git fetch patches/ports/signal-8.33.2/followups/blocked-network-priority/source-checkpoint.bundle \
  refs/heads/source-checkpoint/blocked-network-priority:refs/port-source/blocked-network-priority
git rev-parse refs/port-source/blocked-network-priority^{tree}

git bundle verify patches/ports/signal-8.33.2/followups/recipient-avatar-badge/source-checkpoint.bundle
git fetch patches/ports/signal-8.33.2/followups/recipient-avatar-badge/source-checkpoint.bundle \
  refs/heads/source-checkpoint/recipient-avatar-badge:refs/port-source/recipient-avatar-badge
git rev-parse refs/port-source/recipient-avatar-badge^{tree}

git bundle verify patches/ports/signal-8.33.2/followups/version-preference-write/source-checkpoint.bundle
git fetch patches/ports/signal-8.33.2/followups/version-preference-write/source-checkpoint.bundle \
  refs/heads/source-checkpoint/version-preference-write:refs/port-source/version-preference-write
git rev-parse refs/port-source/version-preference-write^{tree}

git bundle verify patches/ports/signal-8.33.2/followups/giphy-client-formatting/source-checkpoint.bundle
git fetch patches/ports/signal-8.33.2/followups/giphy-client-formatting/source-checkpoint.bundle \
  refs/heads/source-checkpoint/giphy-client-formatting:refs/port-source/giphy-client-formatting
git rev-parse refs/port-source/giphy-client-formatting^{tree}

git bundle verify patches/ports/signal-8.33.2/followups/always-relay-accessor/source-checkpoint.bundle
git fetch patches/ports/signal-8.33.2/followups/always-relay-accessor/source-checkpoint.bundle \
  refs/heads/source-checkpoint/always-relay-accessor:refs/port-source/always-relay-accessor
git rev-parse refs/port-source/always-relay-accessor^{tree}

git bundle verify patches/ports/signal-8.33.2/followups/logcat-collection/source-checkpoint.bundle
git fetch patches/ports/signal-8.33.2/followups/logcat-collection/source-checkpoint.bundle \
  refs/heads/source-checkpoint/logcat-collection:refs/port-source/logcat-collection
git rev-parse refs/port-source/logcat-collection^{tree}

git bundle verify patches/ports/signal-8.33.2/followups/retained-molly-imports/source-checkpoint.bundle
git fetch patches/ports/signal-8.33.2/followups/retained-molly-imports/source-checkpoint.bundle \
  refs/heads/source-checkpoint/retained-molly-imports:refs/port-source/retained-molly-imports
git rev-parse refs/port-source/retained-molly-imports^{tree}

git bundle verify patches/ports/signal-8.33.2/followups/retained-activity-push-imports/source-checkpoint.bundle
git fetch patches/ports/signal-8.33.2/followups/retained-activity-push-imports/source-checkpoint.bundle \
  refs/heads/source-checkpoint/retained-activity-push-imports:refs/port-source/retained-activity-push-imports
git rev-parse refs/port-source/retained-activity-push-imports^{tree}

git bundle verify patches/ports/signal-8.33.2/followups/retained-import-formatting/source-checkpoint.bundle
git fetch patches/ports/signal-8.33.2/followups/retained-import-formatting/source-checkpoint.bundle \
  refs/heads/source-checkpoint/retained-import-formatting:refs/port-source/retained-import-formatting
git rev-parse refs/port-source/retained-import-formatting^{tree}

git bundle verify patches/ports/signal-8.33.2/followups/retained-media-preference-imports/source-checkpoint.bundle
git fetch patches/ports/signal-8.33.2/followups/retained-media-preference-imports/source-checkpoint.bundle \
  refs/heads/source-checkpoint/retained-media-preference-imports:refs/port-source/retained-media-preference-imports
git rev-parse refs/port-source/retained-media-preference-imports^{tree}

git bundle verify patches/ports/signal-8.33.2/followups/backup-direct-cancellation/source-checkpoint.bundle
git fetch patches/ports/signal-8.33.2/followups/backup-direct-cancellation/source-checkpoint.bundle \
  refs/heads/source-checkpoint/backup-direct-cancellation:refs/port-source/backup-direct-cancellation
git rev-parse refs/port-source/backup-direct-cancellation^{tree}

git bundle verify patches/ports/signal-8.33.2/followups/remove-pre27-sheet-selectors/source-checkpoint.bundle
git fetch patches/ports/signal-8.33.2/followups/remove-pre27-sheet-selectors/source-checkpoint.bundle \
  refs/heads/source-checkpoint/remove-pre27-sheet-selectors:refs/port-source/remove-pre27-sheet-selectors
git rev-parse refs/port-source/remove-pre27-sheet-selectors^{tree}

git bundle verify patches/ports/signal-8.33.2/followups/registration-and-timing-format/source-checkpoint.bundle
git fetch patches/ports/signal-8.33.2/followups/registration-and-timing-format/source-checkpoint.bundle \
  refs/heads/source-checkpoint/registration-and-timing-format:refs/port-source/registration-and-timing-format
git rev-parse refs/port-source/registration-and-timing-format^{tree}

git bundle verify patches/ports/signal-8.33.2/followups/unavailable-contact-and-legacy-preferences/source-checkpoint.bundle
git fetch patches/ports/signal-8.33.2/followups/unavailable-contact-and-legacy-preferences/source-checkpoint.bundle \
  refs/heads/source-checkpoint/unavailable-contact-and-legacy-preferences:refs/port-source/unavailable-contact-and-legacy-preferences
git rev-parse refs/port-source/unavailable-contact-and-legacy-preferences^{tree}
```

The tree commands must print, in order, `2533afec2fe2d7f9fc682f699fc21d7624521889`,
`0e5c581b7db3f4429256630011790f3ed4ad141f`,
`febafe3aa084d0ca6219e4f10a27ee66fbecfe4e`,
`33540be50355fd43b776249136554d167625462a`, and
`70f2eba69967c4a3ae82dd9204999feb1bd70f9b`, `09c0192152398a4969178704894cfcb1c0552513`, `f3b957dcbd02f828be8bccd29770fa080b68b55a`, `e71a4e56e8f5ebb9229ff4c2cf1dc3972b444655`, `2e5e16d1d08625df6ae12ab911eb180d44ed581a`, `229e4bd926c1080318cf80ee6633178e42eeb6dd`, `936c1c8ccf52a6450b4d6b9fb216ae1dfdff102a`, `67761e2dc6664979273d89f2a438fa7be060f65a`, `190ad231ab86fd7fbe7c6cb137a3bf0680a13d24`, `ff319e31f48739facb1b430102ce61351114ebb8`, `0965228d1febaa1b9be4d228208f3ffcf3b7c595`, `2f2df9763230c11a9a0eac423b0fec52ef0b7fe5`, `ed84aae0b10c71574fb91a124202015f298a2226`, `09b480ee848a0c390779e27ca166fbbe0bf13f3a`, `46d52ca93e698991994f668d7d649f61ec6cd503`, `ebc09c526aeab140c8c51ebc898d08f32c0f28c6`, `76df44b49dccd2ea946719764aaa23fd8f9e9208`, `c23d85ed3707b8c153c65e1a91dcca30dd51ee7c`, `ad924cb75d22db46a530c9257fe331bc1b6e218a`, `41a6f4dd9731829110773b23e7739c5b5fd65444`. The bundles do not carry their
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
