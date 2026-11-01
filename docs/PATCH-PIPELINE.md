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

`molly-build.json` selects the release. The pinned Signal commit supplies the source projection for the branch. The assembler rejects changes to the tracked Signal source
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
mailboxes under `patches/ports` are described as AI-assisted
compatibility changes. They never replace or silently regenerate the
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

An ordered series can also contain checked JSON records for whole-file
deletions and translation generation. The assembler checks record and input
hashes, generated output hashes, and the before and after trees. Translation
generation follows Molly's transform and matches all 816 pinned native witness
outputs; the pipeline itself does not execute Gradle. The `please_wait`
relocation is validated separately.

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

The original Molly snapshot remains unchanged. AI-assisted compatibility
changes are grouped into initial resolution mailboxes and follow-up mailboxes.
The initial source commit has the pinned Molly and Signal commits as parents.
Each follow-up records its immediate source parent and resulting tree.

Initial source: `7761e6fdb7170378374221bee386f3a9f58900e5`. Final source tree: `ba7294b3687ac88aea08f0e5a6439355927c60c8`.

| Follow-up | Source commit | Resulting tree |
| --- | --- | --- |
| `followups/restore-preference-import` | `288cdc7bfab7d54968007b9545c851eebbc0d362` | `f929420687a8bff839543cf402cb7ee9864bcfee` |
| `followups/netcipher-sdk-dsl` | `3e1cd5b3485924ccfe6f8f20624b9288e10061da` | `d3ac1d9662d7072e55082483669a0f2dc2cbd13f` |
| `followups/integration-and-regression-tests` | `e1ef1d6a93b238c8369620a010e756b3c3f12e27` | `1e01a679249562e550e26f76191725e9d8caf395` |
| `followups/cdn-transport-test-fixture` | `4e2a3cdb5884437745613e1e17b0511f6c465dff` | `4863181c619a00b732137504d1fdfc2dbe2bf353` |
| `followups/reregistration-linked-devices-reminder` | `f7e4313a85910cba87682054af1a7565e018a1fa` | `64ad2e081bc82908546ae3136a6a7b5bad551e94` |
| `followups/legacy-backup-precedence` | `715764a69ba80b809bfdbcd8e607e018ec57f281` | `32c9da7764c17bd50da0863a362b30eb2d242d59` |
| `followups/donation-completion` | `8336ae483ceb7e3a8ead460b2087c0f282d3be12` | `fcd083c18d7a51fb4c328b6bb8a8ee7b665bc223` |
| `followups/backup-notification-id-upgrade` | `a715cb9771ff8e74dea7ad8d07238739b42f5b53` | `ba7294b3687ac88aea08f0e5a6439355927c60c8` |

The `CorrectedPreferenceFragment.java` modify/delete conflict retains Molly's
file unchanged in the mechanical merge and is an explicit configuration choice.
The port report records unmeasured hunk evidence without treating it as proof.

The source checkpoint bundle at `patches/ports/signal-8.33.2/source-checkpoint.bundle`
contains the initial commit and four core follow-ups. Its prerequisites are the
pinned official Molly and Signal commits. The bundle has
6891099 bytes and SHA-256 `4eb35c9720d10122ec711a47d6fad464fe8304545d78724799aeb4fd2b922949`; its advertised ref is
`refs/heads/source-checkpoint/core`, pointing to `4e2a3cdb5884437745613e1e17b0511f6c465dff` (tree
`4863181c619a00b732137504d1fdfc2dbe2bf353`).

Prepare the pinned source objects and verify the complete checkpoint chain:

```sh
python3 tools/patches/prepare_port_report.py
python3 tools/patches/verify_source_bundles.py
```

The verifier checks bundle bytes, hashes, advertised refs, prerequisites, source
parents and trees. Mailbox reconstruction separately proves the selected final
tree. These checks do not establish that every compatibility hunk is minimal.

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

## Local stack scope and port report

For a stack boundary, pass its explicit base to the preflight, for example
`tools/patches/preflight.sh --scope pipeline --base stack/0-molly-8.19.2-4`.
Use `switch`, `release`, `followups`, or `additions` for the subsequent boundaries.
For the fifth branch, use `--scope additions --base stack/4-behavior-followups`. The
scope check validates the base ancestry, source projection, approved workflow
paths, Molly forest identity, and profile pins. The pipeline branch has no
Android profile, so its preflight runs the tool tests only.

`port_report.py` measures physical Git additions and deletions for the Molly
patchset, the mechanical merge, and AI-assisted compatibility changes. Its
preview marks missing hunk evidence as unmeasured. `--check` verifies current
output; `--ready` additionally requires measured evidence for the final gate.
These counts alone do not establish authorship or necessity.

Report metadata may share descriptions through `groups`: each group has an
explicit `hunks` array of current IDs and a `description` containing only
`type`, `reason`, `molly_equivalent`, and `searched_branches`. Groups may not
claim gate results or overlap. Individual `hunks` entries record `revert_gate`,
`gate_input`, `outcome`, and `evidence` for the exact measured check. Missing
fields remain unmeasured; readiness requires every field, exclusion evidence,
and completed minimization.

For removed changes, `action_chains` records a `start_tree` and ordered
`actions`. Each action names a `path`, tracked one-file `patch`, `input_blob`,
`output_blob`, `gate_input`, `gate`, `outcome`, and `evidence`. The renderer
replays the patches in a private index, checks the blob IDs and single changed
path, and displays the reported gate separately from current-hunk evidence.
The input description must identify the actual tested state; a passing build
cannot establish an individual revert failure. Historical patches must be
committed so replay works in a fresh checkout. The starting tree must be
available from the report preparation step. The clean-merge measurement omits
whole conflicted files, including any clean portions within those files.
