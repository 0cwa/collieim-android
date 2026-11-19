# Patch and release pages

The normal site build generates `/patches/` and `/releases/` without adding homepage or navigation links. The reviewed edition remains pinned in `pilot.json`; a newly published release does not change its patch descriptions or source identity. Membership remains unknown. These pages do not approve an APK or release.

## Authoring

Add a short reviewed JSON record under `subjects/` (continuing app patches) or `changes/` (release differences). Include the benefit, original/adaptation credits, change commit, file path and meaningful limitations. Human-readable filenames become anchors. Add the record to `pilot.json` when the reviewed source tag contains it. Selecting a new edition requires reviewing that tag, baseline and every continuing claim; the builder never treats `main` as shipped source.

To describe a newer release, set `release`, `release_base_tag`, `release_status`, `release_headline` and `release_history` in `pilot.json`, and edit or add records. Leave the source and baseline commits and the file IDs to the tool; each record's `change` commit is still written by hand. In a checkout containing the named tags and change ancestry, run:

```sh
python3 tooling/site/receipt/freeze.py --update-pins --previous tooling/site/receipt/source-receipt.json --out tooling/site/receipt/source-receipt.json
python3 tooling/site/receipt/freeze.py --check
```

`--update-pins` fills `source` and `release_base` from the tags and each record's `source_blob`/`blob` from the source tree, rewriting only values that differ. It prints one line per change. A line ending in `review: git diff <old> <new>` means that file changed since the record was reviewed: read that diff and correct the record's claims before committing. A path missing from the release fails, so the record must be updated or removed. Without `--update-pins`, freezing still fails on any mismatched ID.

Review the reconciliation report in the receipt and commit the records and receipt together. Added, removed, replaced, changed and reordered records are reported. The Git validator checks exact source blobs, absent paths, tag identities and ancestry; release entries must cite a single-parent commit touching a file that differs across the pinned range. Missing objects fail with instructions; freezing never fetches. Benefits, selection, attribution and behavior claims require editorial review.

## Source and presentation contracts

`source-receipt.json` is a committed **v2 source-only receipt** produced by the Git validator. It preserves the reviewed records, canonical hashes, pinned identities, deterministic tree-diff metrics, classification/method and Git version. Normal builds compare the records and manifest against this snapshot and fail with the changed paths/fields if they differ. This committed, reviewed evidence is the shallow/offline build's trust boundary; CI does not re-prove Android ancestry or download Android history. Source changes require the re-freeze above. A deliberate edit to both evidence and its hashes still requires source review; hashes are not signatures or semantic proof.

Presentation changes need no source re-freeze. The renderer consumes the frozen source data without Git, network or inference. Each build writes `patches/build.json` with the source receipt hash, release-index input, presentation/asset hashes, route configuration and HTML hashes. The same source data, release index and presentation inputs produce identical HTML.

Legacy **v1 receipts remain asset-bound**: rendering still rejects changed asset hashes. They are not silently migrated. Re-freeze reviewed source records to create v2; unsupported schema or metric-method versions fail explicitly. `--editorial-revision <commit>` still reads records from immutable Git objects when producing a receipt.

## Automatic updates

The existing website CI builds these routes on website changes and its daily schedule. Live builds fetch up to five GitHub release pages (500 entries), accept only non-draft releases with a publication timestamp, and sort notes links by publication time. Published prereleases are included. API failures, invalid identities and the pagination bound fail the build. Future releases appear as **notes links only**; patch copy, credits, source pins and metrics remain editor-owned. When a published release is newer than the reviewed one, the release log leads with that release and a link to its notes, followed by the reviewed release's changes; the patch explorer names it as not yet checked. The build manifest lists these as `newer_releases`, and the build prints a GitHub Actions notice (a plain stderr note locally) asking for a re-review. If the reviewed release is not published yet, only releases listed before its baseline or history count as newer, and a separate notice says it is unpublished. The build does not fail. The homepage's existing latest-release/discussion behavior is unchanged. Deployment conditions and permissions are unchanged; PR builds do not deploy.

Offline full build, using the reviewed release-note list:

```sh
python3 tooling/site/build.py --out /path/site --tag v8.35.1-3-rc2 --roadmap-json /path/discussions.json
```

Add `--releases-json /path/releases.json` for a captured GitHub release array (`tag_name`, `draft`, `published_at`). `fixtures/releases.json` exercises a future published prerelease, a draft and an unpublished record; its versions are test data.

For the old local draft routes, add `--receipt-preview /path/receipt.json`; this also writes `pilot/app.html` and `pilot/release.html` with explicit links for that route set. No generated HTML or screenshots belong in the repository. Screenshot evidence belongs in PR attachments.

Production line counts remain inside closed Changes details. They compare exact pinned trees with `path-rules-v1`, exclude tests and are not summed across patches. The diff isolates Git config/attributes and uses `isolated-myers-source-attributes-v1`. No Signal/Molly baseline counts are inferred. Runtime behavior, keyboard, assistive technology and forced-color validation remain separate evidence.
