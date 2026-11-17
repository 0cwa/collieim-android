# Development receipt pilot

This experimental version 1 record is editorial development metadata, not a release plan, source-build receipt, membership approval, or publishing authorization. It is deliberately not connected to the site build or a merge gate.

`pilot.json` pins a published tag, its preceding candidate, and selected editorial records. Each app-wide record names an immutable change, a path and blob at the release source, origin and evidence links, and limits. Release entries are checked against actual changed paths between the two pinned trees. This small pilot does not yet reconcile removal or replacement of editorial records automatically; the future membership interface remains an open design seam, not release approval.

Run `python3 tooling/site/receipt/render.py --layout app --out /path/app.html` and `python3 tooling/site/receipt/render.py --layout release --out /path/release.html`. Git must have the tags and objects locally. Rendering checks tag identity, ancestry, file blob identity and listed omissions. These checks do not establish behavior or attribution; editorial review remains necessary. The examples have not been runtime tested for this pilot.

`python3 tooling/site/receipt/metrics.py` recomputes individual change diffs directly from Git without a cache. Values are additions and removals, in that order. Paths are classified by conservative directory and filename rules into production, tests, translations, generated, and other. These heuristics cannot identify every generated file or translation and are not a semantic audit. Binary paths are listed separately. These per-change counts are historical churn, not the final release diff, and must not be summed across features.

Before public release: review the design, review the scope of the deliberate-removal example, review exact attribution and release scope, review path classification and implement record replacement reconciliation and connect to an approved membership interface, inspect accessibility and mobile screenshots, and integrate with the existing site build only after product approval. Existing release contracts remain authoritative.
