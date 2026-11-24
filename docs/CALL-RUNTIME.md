# On-demand calling runtime

This extends the [local call permission contract](CALL-PERMISSIONS.md) in the
`call-safety` layer. Calls settings under **Collie → Hardening → Calls** offer
an installation-local, off-by-default on-demand startup preference in Beta.
It reduces background calling activity and can increase call connection time.

## Boundary

`CallRuntime` is the managed boundary between already-authorized work and the
calling backend. Reading its state is passive. It carries no RingRTC or WebRTC
types, and does not implement a permission policy. Its authorization predicates
delegate to Part 1's `CallPermissions`, retaining each operation's original
generation, session and message freshness where applicable.

`START_IF_AUTHORIZED` can initialize for an admitted incoming call or an explicit
local calling operation. `IF_RUNNING` defers background work while cold; it does
not queue it indefinitely or grant it permission to initialize later. Native
dispatch retains the existing policy checks next to each native operation.

`SerializedCallRuntime` serializes acquisition and dispatch. It checks the
request before initialization, then checks cancellation and current authorization
again after initialization. Initialization failure is sticky for the process:
there is no retry loop, permissive fallback, or automatic restart. Request
cancellation prevents dispatch if startup is already underway; it cannot undo
native code already loaded by that startup. Asynchronous network completions
must retain the originating authorization and cancellation too.

The production `CallPermissionRuntime` adapter binds every request, including
direct users of `AppDependencies.callRuntime`, to the existing policy generation
and absolute prohibition. A caller-supplied predicate cannot override that gate.
`SignalCallManager` owns the native backend privately; its constructor, state
streams, cache getters and call-active query are managed-only. Its native getter
requires prior acquisition and never creates a manager itself.

## Entry-point inventory

| Entry point | Cold on-demand behavior | Authorized activation |
| --- | --- | --- |
| `ApplicationContext.initializeRingRtc` | Skips the library when opted in, disabled, or policy/preference is unreadable. | Default/off uses the same logger and field trials through once-only `RingRtcLibrary`. |
| `AppDependencies.signalCallManager`, `callRuntime` | Construct only the managed facade/state. | Requests pass the Part 1 adapter and serialized backend. |
| `SignalCallManager` incoming offer/answer/ICE/hangup/busy/opaque methods | Rejected or expired tickets cannot acquire. | Original `CallAuthorization` is rechecked after initialization and next to native dispatch. |
| Local prejoin/audio/video actions | Cancellation invalidates the pending local attempt immediately. | Explicit action checks the recipient and original generation before and after acquisition. |
| `isCallActive`, cached peeks, ephemeral state, settings data-mode update | Queries use managed state; idle updates do not initialize. | Active-call query result codes retain the current action processor's behavior. |
| `CallLogPeekHelper`, `CallPeekState`, `CallLinkTable`, conversation group join indicators | History/cache remain readable. Background peeks defer while cold. | Existing peeks may run when warm and still authorized. |
| `GroupCallPeekJob`, worker jobs, `GroupRingCleanupJob`, `CallLinkPeekJob` | Do not cold-start. Cleanup still marks stale rings as missed. | Ringing checks retain the incoming ring/sender permit; background peeks keep their original permit. |
| Call-link previews, detail refresh, `RefreshCallLinkDetailsJob` | Keep saved metadata and omit native refresh, before fetching native-operation credentials. | Read operations can refresh when warm; create/edit/delete are explicit operations. |
| `SignalCallLinkManager` | Obtaining the facade and constructing an Rx request are inert. | Subscription prepares credentials on IO, then enters the runtime. Disposal, revocation and failed acquisition terminate/defer the request. |
| `CallLinkCredentials.generate` | Generates the pinned pending V1 key format in managed code using `SecureRandom`, with finite rejection sampling and no fallback key. | Actual server creation still requires runtime admission. |
| `CallingAssets`, `CallingAssetsDownloadJob` | Do not register or download calling assets for a cold opted-in engine. No deferred asset is marked as registered. | First acquisition registers cached assets and schedules missing assets using the existing job. |
| Call history, linked-device call events and call-link sync | Preserve the message/history/metadata updates. | Only the optional native refresh is deferred. |
| `CallLinkRecordProcessor`, `CallLinkTable`, backup import/export and metadata helpers | Reuse Part 1's managed key parser, room derivation and era conversion. | Native metadata parity stays tied to `build-inputs/native-lock.json`. |
| WebRTC/EGL state and class initialization | Idle state contains managed values/null native resources. Nested enums and `CallId(long)` do not invoke native code. | EGL/video creation remains inside acquired call action processors. |

`CallManager` has a native-loading static initializer in the pinned version.
`CallLinkRootKey` constructors and `CallId.fromEra` call JNI. Passive code must
not substitute these for the managed metadata helpers. `RingRtcLibrary` is the
only production `CallManager.initialize` route; initialization failures, including
the pinned API's `AssertionError`, remain terminal for this process.

## Verification contract

Host tests exercise the runtime independently of Android, plus the real policy
adapter, settings persistence and native-adjacent dispatch. The API 35 suite uses
fresh Orchestrator processes with preferences persisted before the production
startup decision. It checks `/proc/self/maps` throughout ordinary messaging,
settings/group browsing, history, link/sync/storage, refresh/cleanup and rejected
traffic, then has a separate positive control for authorized native acquisition,
warm reuse and revocation. The suite never resets a native-loaded process to
pretend that it is cold.

These local-account probes do not establish network call interoperability or
call latency. Comparisons with the unchanged Beta baseline must separately
measure incoming request visibility, user acceptance to connection, and outgoing
connection time with controlled enrolled devices, recording cold/warm state and
sample counts. Runtime evidence, timing results, APK hashes and screenshots belong
in the draft PR's retained evidence, not in this maintained contract.

## Process lifetime

The absolute **Disable all calling on this device** policy takes precedence over
startup preferences. On a clean process, it prevents initialization through
startup and calling entry points. Changing policy revokes admission immediately
and cleans up existing call resources. Closing or resetting a manager does not
unload its shared library. Once calling has initialized, its library can remain
mapped until the process ends normally.

The ordinary startup preference preserves eager initialization when on-demand
startup is off, subject to independently enabled calling restrictions. Passive
history, cached status, metadata, settings and sync data remain usable when the
engine is cold. Managed metadata projections remain tied to the exact native pin
and its parity tests; on-demand startup does not change the wire protocol.

## Backend evolution

Later admission UI and process-isolation work should consume `CallRuntime` and
the existing permission contract. A separate backend must keep authorization
bound to its original operation, recheck after asynchronous startup, distinguish
deferred work from failure, and honor cancellation without granting a replacement
session. This part adds neither another calling process nor automatic restarts.
