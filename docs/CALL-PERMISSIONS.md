# Local call permissions

This is the calling implementation of the local permission foundation for
TrailBlazer Beta. It belongs to the existing `call-safety` feature layer.
Voice-message and media permissions are separate later work. This feature does
not implement an approval prompt or promise that the RingRTC library remains
unloaded.

## User choices

Device controls live under **Settings → Collie → Hardening → Calls**. Contact
and v2-group conversation settings expose their own incoming-call override.

| Choice | Meaning |
| --- | --- |
| Device incoming-call default | Allow or block incoming direct calls without an override. |
| Device incoming-group-call default | Allow or block incoming group rings and passive group peeks without a group override. |
| Use device default | A genuine `INHERIT`: absence of an override, so later default changes apply. |
| Allow incoming calls | An explicit grant for that contact or group context, subject to blocking, identity and membership checks. |
| Block incoming calls | Deny incoming calls in that context. An explicit outgoing action can still create its own bounded session. |
| Disable all calling on this device | An absolute prohibition on incoming, outgoing, group and call-link activity. An override cannot bypass it. |

A fresh installation starts with both incoming defaults allowed and the absolute
prohibition off. Beta membership does not change these defaults. Inherited
direct permission retains the existing call-request trust behavior. An explicit
contact grant can permit an ACI call without accepting a message request or
sharing a profile. Existing identity, block and destination-device checks still
apply. PNI profile handshakes retain their existing trust requirements.

Group permission never grants private-call permission to the group's members.
A contact grant never grants permission for an arbitrary group. Call links are
entered through an explicit action, with their own exact session context.

Changing a permission ends the current call and invalidates queued calling work.
The settings UI explains this and does not write when the selected value is
unchanged. A failed save is reported; the UI does not optimistically present an
unpersisted permission as saved.

## Persistence and identity

`CallPermissionValues` stores one versioned record under
`collie.local_call_permissions.`. `CallPolicyCodec` provides bounded strict
decoding, and `CallPolicyState` resolves defaults and explicit overrides. This
is a small calling record, not a general profile system. Mute state,
notification profiles, address-book membership and messaging activity do not
form its policy inputs.

The record is installation-local. It has no backup or sync keys, the legacy
backup importer excludes its entire prefix, and a random installation anchor
lives in Android's no-backup directory. A durable pending-write marker prevents
an interrupted restrictive write from falling back to an older permissive
record. Record/anchor mismatches, an imported unbound record, a missing anchored
record or an interrupted write recover to **all calling disabled**, with both
defaults blocked and overrides cleared. An unreadable or malformed store denies
calling and is shown as unavailable.

Overrides use canonical local recipient IDs. A settings write resolves the
authoritative recipient record inside the same database writer transaction used
by recipient merges. It does not rely on a potentially stale live-recipient
cache. Merge resolution is conservative: `BLOCK` wins over `INHERIT`, and
`INHERIT` wins over `ALLOW`. The result is retained for both IDs, so a rolled-back
database transaction cannot expose an older, broader override. A merge involving
an active session invalidates that session.

Account identity changes and restore invalidate all session grants and discard
recipient-specific overrides. Both backup import paths reset overrides under
the recipient database writer before replacing recipient tables, including
imports that later roll back. Device restrictions survive. If a discarded
override contained a block, the corresponding device default becomes blocked,
preventing loss of that restriction when the old recipient IDs no longer have
meaning. Registration and authorization transitions for the same account revoke
session grants while preserving overrides for its unchanged recipient database.
Unregistered, unauthorized or unavailable account state cannot authorize calling.

## Admission and session authorization

The message path validates whole-message shape, authenticated envelope sender,
destination identity/device, message age and existing trust checks before
obtaining the native call manager. An ambiguous message with multiple direct
categories or inconsistent ICE call IDs is rejected as a whole.

`CallAuthorization` is an immutable process-local ticket. It binds policy
generation, session serial, context, canonical recipient, authenticated sender,
source device, direction and available call/ring identifier. Signaling tickets
and unaccepted direct/ringing sessions expire within two minutes; a message's
existing age and time waiting in dispatch consume that same freshness budget. Accepted calls
and explicitly entered group/link contexts have a finite 24-hour session lease.
The call manager checks the ticket on serialized dispatch and immediately before
each native receive method. A policy snapshot is paired with its generation;
an older decision cannot acquire a newer generation during a concurrent write.

Calling messages deferred behind ordinary message jobs carry a
`CallMessageQueueReceipt` stamped before deferral. It binds the originating
process, policy generation and monotonic arrival time. Time in that local queue
consumes the same message-freshness budget; job rehydration never restarts it.
Unstamped legacy calling jobs, receipts from another process, expired receipts
and work revoked before admission are dropped. Ordinary data and sync message
persistence keeps its existing behavior. Admission checks the expected generation
again under the policy lock, so a change after dequeue validation cannot grant
old bytes the new policy. Transport-only refusals retain that generation too;
new arrivals can still be refused while the absolute calling prohibition is on.

Offers, answers, ICE updates, hangups, busy messages and opaque messages all use
this boundary. A different active caller does not authorize a denied peer. A
direct outgoing offer can receive continuation from the peer's authenticated
linked devices for its exact call ID. Incoming continuation stays bound to the
offering device. Simultaneous incoming/outgoing calls from the same permitted
peer retain native glare resolution, without extending the outgoing exception
to an otherwise blocked incoming offer.

Opaque classification uses generated managed Wire decoding of the pinned
RingRTC schema, with a 64 KiB input limit. Multiple populated top-level
categories, unsupported fields and malformed data are rejected deliberately.
Accepted messages are canonically re-encoded before dispatch, avoiding
differences in duplicate-field handling between the managed and native decoders.
Group data has no fabricated universal call ID: it requires the precise active
context and current membership. Rings and cancellations also bind the actual
ring identifier, ringer and source device.

Call-link media keys additionally bind the authenticated sender to the joined
SFU participant and demux identifier for the exact current session. An early
key may wait up to 30 seconds in a managed queue bounded to 32 messages, 256 KiB
total and four messages per sender, for that roster update. It cannot reach
RingRTC while membership is unknown. Revocation, session replacement, expiry or
member departure invalidates old work; a later rejoin does not revive an old
admitted ticket. Pending or rejected join requests are not joined members.

Signaling and `GroupCall` callbacks retain their originating generation and
peer/group object. Asynchronous network completions retain their request's
generation. Remote-triggered group peeks carry a process-, generation-, sender-
and group-bound permit from message admission through batching, persisted jobs
and their asynchronous completion. Unstamped or restored jobs have no live
permit. An old callback cannot attach itself to a new call to the same
contact. Group and call-link credentials are rechecked after
blocking retrieval and before native create/connect/join operations.

Rejections use the existing Signal call-message transport without constructing
RingRTC. They do not send explanatory chat messages or accept a message request.
For the pinned protocol, a normal/busy rejection can terminate other receiving
devices too, so a policy-only refusal on a linked-device account is silently
dropped rather than cancelling an otherwise authorized call on another device.

Call-specific profile jobs carry an ephemeral exact-context permit and recheck
it before transport. Call-link targets must still be joined participants.
Ordinary messaging profile jobs retain their existing behavior. A trusted PNI
handshake or a final group-presence update may finish after normal teardown with
its original bounded permit, but cannot survive revocation, account change or
process restart.

## Interfaces for dependent work

| Surface | Purpose |
| --- | --- |
| `CallPermission`, `CallContext`, `CallPermissionSettings` | Calling choices and the distinction between a default and an absolute restriction. |
| `CallPermissions.settings`, `setDefault`, `getOverride`, `setOverride`, `setAllCallingDisabled`, `changes` | Settings reads, durable writes and observable invalidation. |
| `canStartCall`, `canReceiveGroup`, `canUseGroup`, `canPeekGroup`, `canUseCallLinks` | Narrow preflight checks before call-specific work or manager acquisition. |
| `beginOutgoing`, `bindOutgoing`, `activateCall`, `endCall`, `endAllSessions` | Explicit session lifecycle. A preflight boolean is not a dispatch grant. |
| `admitDirect`, `authorizeGroup`, `isAuthorized`, `runAuthorized`, `isDispatchAuthorized` | Managed admission and mandatory serialized/native-adjacent validation. |
| `CallMessageQueueReceipt`, `admitDirect(expectedGeneration)`, `authorizeGroup(expectedGeneration)` | Preserve arrival generation and remaining freshness when calling work is deferred; a receipt is not an admission grant. |
| `generation`, `isCurrent`, `runIfCurrent` | Keep asynchronous work bound to the policy generation that initiated it. |
| `createGroupPeekPermit`, `isGroupPeekPermitValid` | Bind a remote-triggered group peek before batching or job persistence. |
| `CallSessionKey`, `currentCallLinkSession`, `updateCallLinkMembers` | Bind SFU roster updates to the exact current link session. |
| `createSharingPermit`, `isSharingPermitValid`, `createGroupCleanupPermit`, `isGroupCleanupPermitValid` | Bounded transport-only sharing/cleanup work; these do not authorize native dispatch. |
| `AppSettingsRoute.CollieCalls`, `CallsSettingsEntry`, `CallsSettingsScreen`, `CallPermissionOverrideRow` | Existing Calls destination and narrow UI/state integration points. |

Later voice or media controls should reuse the installation-local persistence
and true-inheritance pattern while defining their own capability semantics. They
must not interpret a calling grant as a media grant, copy defaults into
overrides, or bypass the absolute calling prohibition. Extend the relevant
settings/state surface without redesigning the main Collie screen or exposing
controls for unfinished capabilities.

Dependent feature branches need this complete `call-safety` layer, including
its account, restore, recipient-merge and queued-job hooks. The settings facade
alone does not establish those lifecycle guarantees. While this PR is open,
dependent work can build on its feature branch; after review and merge, use the
updated `trailblazer-beta` baseline.

Managed link-key parsing, formatting, room-ID derivation and era-ID conversion
track the exact RingRTC source selected in `build-inputs/native-lock.json`.
`tooling/ci/test_call_admission_schema.py` checks the source pins for the schema
and metadata helpers. A RingRTC upgrade requires reviewing these managed
projections and running the native parity instrumentation tests against the new
packaged library.

## Validation entry points

The feature's unit tests cover policy resolution, local storage/restore,
recipient merges and concurrent writes, every denied receive category, queued
revocation, multi-device continuation, group/link separation, bounded opaque
decoding, callback lifetime, and transport-only sharing. UI tests cover defaults,
inheritance, the absolute restriction, error state and unchanged selections.

The feature workflow also has a focused **Call permission admission and settings
host tests** job. Its maintained inventory selects 42 app classes (292 methods)
and two settings classes (17 methods), including the ordinary call processors
and shared-fixture regressions. It reuses the repository's pinned desktop JNI
cache, source builder and receipt verifier; the library remains a host-test
input only. The job downloads Robolectric 4.16's exact API 28, 35 and 36 jars
from official Maven Central with bounded transfers and published SHA-512
verification, then uses its local-only resolver. API 36 also supplies the
sandbox compile SDK. Normal Gradle dependencies keep their existing pins.
The scoped init script applies only to those two test tasks. The evidence gate
requires every inventoried method exactly once, no failures or skips, unchanged
source and native inputs, and verified SDK bytes. XML, source/method inventory,
SDK/native receipts and logs are retained; older or cached test results cannot
satisfy this job. The general PR shard workflow and its cache policy are separate.

`CallPermissionsScreenshotTest` captures the real device, contact and group
settings states. `CallLinkKeyNativeParityTest` and `CallEraIdNativeParityTest`
compare managed helpers to packaged RingRTC on Android. App and instrumentation
APKs must come from the same build; a zero-test run is a failure. Follow
`TRAILBLAZER-DEVELOPMENT.md` for the `prodWebsiteBeta` build and account-free smoke
test. Retain actual runtime evidence outside Git and link it in the PR.

The **Call permissions API 35** workflow builds the Beta variant directly and
launches a separately signed disposable test copy on API 35. It then runs the
three instrumentation classes above using matching isolated IMO app/test APKs.
`CallPermissionsApi35Suite` selects exactly those classes with one runner argument,
avoiding the pinned AGP's comma-delimited argument serialization. Orchestrator
still reports the original four methods under their individual test classes.
Captures wait for the saving indicator to disappear and a new window frame to
commit; a missing frame callback fails the capture after five seconds.
They use AndroidX test storage (`useTestStorageService=true`), which preserves
the PNGs while Orchestrator clears app and test data between methods. AGP
collects the service output after the suite, independently of test order.
Its evidence gate requires all four methods to pass, all nine settings captures
to decode, and source, APK and device identity to match. The workflow retains
the results as an Actions artifact; it does not prepare or publish a release.
This account-free check covers startup, settings and native metadata parity.
Ordinary calls and notification/acceptance latency still require two enrolled
accounts and comparison with the unchanged Beta baseline.

Test source alone is not execution evidence. The PR records the exact tests and
builds that ran, plus any remaining API 35 or two-account ordinary-call timing
checks against the unchanged Beta baseline.
