# Development receipt pilot

This experimental version 1 record is editorial development metadata, not a release plan, source-build receipt, membership approval, or publishing authorization. It is deliberately not connected to the site build or a merge gate.

`pilot.json` pins a published tag and commit, a reviewed ordered feature ID list, and a small set of records. Each record names an immutable change, a path and blob at the release source, origin and evidence links, and limits. IDs are editorial identifiers, independent of Layer trailers. A change can support several IDs; do not sum overlapping change statistics. The membership list is a reconciliation aid for this pilot, not an approved release membership manifest.

Run `python3 tooling/site/receipt/render.py --layout story --out /path/to/receipt.html` (or `--layout atlas`). Git must have the tag and objects locally. Rendering checks tag identity, ancestry, membership and file blob identity. These checks detect missing records and mismatched source evidence; they cannot establish that a feature's behavior or its attribution is correct, and editorial review remains necessary. The examples have not been runtime tested for this pilot.

`python3 tooling/site/receipt/metrics.py` recomputes individual change diffs directly from Git without a cache. Values are additions and removals, in that order. Tests and translations are separated; everything else is explicitly unclassified, including production and generated files. Binary paths are listed separately. These per-change counts are historical churn, not the final release diff, and must not be summed across features.

Before public release: choose and review a design, verify a deliberate-removal example, review exact attribution and release scope, extend classification for generated and production files and reconcile against an approved membership interface, inspect accessibility and mobile screenshots, and integrate with the existing site build only after product approval. Existing release contracts remain authoritative.
