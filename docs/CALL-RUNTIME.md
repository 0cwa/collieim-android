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
