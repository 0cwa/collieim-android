# Local group invitation controls

Group invitation controls belong under **Settings → Collie → Privacy**. They
control this installation's automatic acceptance and subsequent sharing. Signal
may already have recorded a membership before delivering its update; these
controls cannot prevent that server-side addition or undo information already
shared by another device.

## Policy

The default preserves existing Signal behavior. Restrictive modes allow
automatic admission from contacts on this device, from explicitly selected
people, or require approval every time. Selected people are identified by ACI,
not a display name or phone number. Blocked and hidden inviters never gain
automatic permission through a contact or allowlist match. Unattributed
additions require review in restrictive modes.

An actual pending invitation still requires acceptance. A verified full
membership can remain locally held for review without fabricating a different
server membership. Existing admitted memberships remain admitted when the
policy changes. A new invitation or re-add is a new decision. Changing contacts
or the allowlist does not automatically release a waiting request.

## Durable decisions and explicit actions

Admission is separate from the legacy profile-sharing flag and message-count
heuristics. Decisions and explicit join intents are account-bound and tied to
the relevant membership transition. Full membership uses its joined-at revision;
pending membership and join attempts retain their own transition evidence.
Ordinary group updates must not reuse old consent for a removal and re-add.

Join, request-to-join and accept actions record intent before their network
mutation and reconcile its result. Opening a conversation, viewing a group link,
receiving an ordinary sync record or having sent messages in an earlier
membership does not authorize a new addition. A stale screen or queued job must
recheck the current admission before performing its action.

The policy evaluator has no network, database or job-manager effects. Durable
storage and application effects are separate from its decision. Failed writes
must not publish an optimistic permission change. Invalid stored policy must not
grant broader automatic admission.

## Enforcement

All permission consumers use the same effective admission state, including:

- Group processing and message-request presentation.
- Profile sharing and self-profile-key publication.
- User-message submission, uploads and final transport dispatch.
- Receipt, avatar, automatic-media and group-call trust checks.
- Storage-service and linked-device imports that change legacy sharing state.

Protocol maintenance, verified membership updates, termination, expiration and
deletion handling continue normally. Explicit leave keeps its existing behavior.
Restrictive modes do not enqueue new automatic leave operations.

The existing linked-device acceptance message has no membership episode. It and
storage-service whitelist flags cannot silently release a restrictive local hold.
Another client may still behave differently, and this device may require its own
confirmation after acceptance elsewhere. This is not an account-wide policy
protocol.

## Restore and account changes

Do not use a policy or consent record belonging to another account. Local restore
must not turn unfinished intent into proof of a completed join, or allow legacy
imported sharing flags to override an existing hold. Standard Signal sync and v2
backup records have no group-admission policy field; no unrelated wire field is
repurposed to carry it. Installation and restore behavior must remain explicit
in the storage implementation and user-facing descriptions.

## Source and maintenance

This is an independent implementation inspired by Johan's group-addition
controls in [v8.36.0.0-JW](https://github.com/johanw666/Signal-Android/commit/8f4ad8577344e844945ee8e73d95fd1fcc7208bc).
The broad fork customization commit is not an application dependency. App
commits, tests and this contract use `Layer: group-admission-policy`.

Changes to Signal's group-state, request, sharing or send paths must preserve
the distinction between verified membership and local admission. The decisive
regressions are re-adds, missing attribution, stale consent, imported sharing,
queued sends and delayed profile updates.
