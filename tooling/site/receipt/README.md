# Development receipt pilot

This is an experimental, versioned development receipt. It is not a release plan, source-build receipt, membership approval, or publishing authorization. Ordinary website builds exclude the pilot; an explicit nonpublished preview option is documented below. There is no merge gate.

`pilot.json` names a source tag and baseline, reviewed subject records, and release change records. The human-readable filenames are internal references, not public labels. Subject records describe selected capabilities at the chosen source; change records describe selected release outcomes. Records carry reviewed text and source evidence. Editorial review remains responsible for benefits, attribution, selection and limitations.

The same manifest contains an ordered `release_history` of published release tags. The renderer links these to their release notes; it does not fetch or infer a release list. For a later edition, review the selected release and its evidence, update the ordered history, and freeze again. An empty history renders an empty state. This list is an editorial archive, not an approved membership manifest.

Freeze the current working records and render both views:

```sh
python3 tooling/site/receipt/freeze.py --out /path/receipt.json
python3 tooling/site/receipt/render.py --receipt /path/receipt.json --layout app --out /path/app.html
python3 tooling/site/receipt/render.py --receipt /path/receipt.json --layout release --out /path/release.html
```

For a reproducible committed editorial snapshot, pass `--editorial-revision <commit>` to `freeze.py`. The manifest and records are read from that Git commit, and the resolved commit is recorded in the receipt. Without that option the records come from the working tree and the receipt says so with a null editorial revision. The receipt records hashes of the renderer’s fonts and artwork; rendering rejects mismatched assets. Rebuilding from the same frozen receipt, matching assets and renderer revision produces identical HTML; rendering itself does not query Git or the network.

Freezing verifies the release and baseline tags, recorded change ancestry, selected source blobs and listed absent paths. A release entry must cite a single-parent commit in the comparison range that touched its path, and that path must also differ in the final tree. Merge-resolution-only changes require separately reviewed evidence; the pilot does not infer them. It does not prove behavior, attribution or approved membership. The pilot always records membership as unknown. A future adapter requires the release owner's approved interface; this development receipt does not replace release approvals.

Pass `--previous /path/previous-receipt.json` to compare record paths and canonical content hashes. Added, removed and changed records, manifest changes and editorial order changes are reported for review, including a rename as a removal plus an addition. The report does not infer whether a capability disappeared or approve its replacement. Source-bound file mismatches fail validation. Recorded paths, hashes and source revisions are evidence of what was read, not proof of semantic claims.

`python3 tooling/site/receipt/metrics.py` recomputes historical per-change churn from Git. The frozen receipt separately stores the final baseline-to-release tree difference. Counts are additions and removals, not effort or quality; shared commits must not be summed across subjects. Path rules classify production, tests, translations, generated and other, with binary paths listed separately. The diff uses an isolated temporary bare repository sharing only source objects, a pinned Myers algorithm, and attributes from the selected source tree; local Git configuration, info attributes and working-tree attributes are excluded. The Git version and method are recorded. Missing objects fail rather than triggering a fetch. Path heuristics are versioned but cannot identify every generated file or translation.

Before public release, review the design and source claims, establish exact Signal and Molly baselines, connect to an approved membership interface, and verify keyboard and screen-reader behavior. No current receipt grants publication approval.

## Website preview

The renderer shares the site's actual palette/theme foundations and self-hosted fonts. Story-specific composition lives in `stories.css`; both stylesheets and the used artwork are pinned by the frozen asset hashes. Re-freeze after an intentional style change.

For a **nonpublished draft build only**, add `--receipt-preview /path/receipt.json` to the existing `tooling/site/build.py` command. This adds `pilot/release.html` and `pilot/app.html` to the disposable output. Ordinary builds have no pilot routes. It never selects a current release, resolves membership, or updates release approval. Do not deploy this output. The six older entries remain explicit GitHub release-note links, not receipt stories.

Release stories explain selected changes; the app-wide view explains selected continuing capabilities. Both prioritize current users and retain unknown membership and evidence limits. Screenshot evidence belongs in PR user-attachments, never repository proof directories.
