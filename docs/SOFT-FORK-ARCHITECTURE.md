# Soft-fork architecture

Collie tracks Signal and Molly while keeping a small set of product, release, and policy differences. Keep those differences legible at the next upstream merge. A short hook at a stable boundary is easier to review than a second implementation of a recurring upstream flow.

## Keep recurring upstream edits small

When changing a recurring upstream monolith, aim for a one to three line hook: a narrow call, guard, or provider choice at an existing boundary. Keep new behavior in a Collie-owned class or module and make the upstream call site delegate to it. Avoid copy-and-paste forks, broad rewrites, and unrelated formatting in the same change. If a small hook cannot express the behavior, document why and isolate the larger seam.

Recurring hotspots include `ApplicationContext.java`, `AndroidManifest.xml`, `app/build.gradle.kts`, `AppSettingsActivity.kt`, `AttachmentTable.kt`, `MediaTable.kt`, `ContactsSyncAdapter.java`, `IncomingMessageObserver.kt`, and `LinkPreviewUtil.java`. A multi-hunk change in one of these files deserves an explicit review of each hunk and its upstream context. Preflight reports this as a nonblocking advisory.

## Isolate product data

Keep Collie-only strings in a dedicated resource file such as `strings2.xml`; avoid inserting them into upstream `values*/strings.xml` unless an upstream resource must be changed in place. Keep Collie-only manifest declarations in the appropriate Collie flavor or a small manifest overlay. This makes upstream resource and manifest edits easier to merge independently.

## Model Signal and Molly separately

Signal is the primary upstream lineage. Molly is another public fork with its own changes and may already contain behavior that Collie carries or adapts. Do not assume that a Signal merge describes the complete Molly state, or that matching names imply matching semantics. For each upgrade, identify the exact upstream commit, inspect both lineage-specific changes, and record adaptations at the upgrade seam. Use the merge helper's expected commit pin when planning a merge; resolve conflicts against the intended upstream revision.

## Track deliberate removals

`tooling/upstream/signal-donation-removals.json` records donation-related removals. The optional schema-1 `tooling/upstream/persistent-removals.json` records other deliberate removals that should stay removed when an upstream edit conflicts with them. Each entry has a repository-relative `path` and a concise `reason`. Add an entry only when the removal is intentional and verified against the relevant upstream history. The merge helper validates paths and reasons, deduplicates overlap between manifests, preserves those deletions during an edit/edit conflict, and reports if upstream reintroduces a listed path. Renames and unrelated conflicts remain for human review.

These lists are narrowly scoped merge guidance, not a substitute for reviewing every merge. Remove stale entries when the corresponding upstream path no longer exists.

## Review and verify meaning

Review semantic behavior, not just clean merges or matching text. Trace callers, persisted data, permission and manifest effects, and security boundaries affected by a hook. For upgrade work, verify the feature across the intended Signal and Molly lineage and check that a removal did not leave an active caller or an alternate path. See [the security review methodology](security/REVIEW-METHODOLOGY.md) for provenance, trust-domain, and falsification prompts.
