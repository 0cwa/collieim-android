# What's new and the Project log

Both tabs read one selected release record. **This update** shows that record's
curated highlights; **Project log** shows its full changes, followed by earlier
releases of the same product. Only the current section has **Your version**.
Settings → Help → What's new opens the log without marking the highlights seen.

The notes are bundled and work offline. Current app and Signal versions come
from `ApkInfo`. The Beta build reads `collie-log/releases/beta/`; other builds
read `collie-log/releases/stable/`. Version suffixes and GitHub publication flags
do not select the product.

## Authoring changes

Add `app/src/main/assets/collie-log/unreleased/<PR number>.txt`:

```text
topic: push
pr: 96
Collie notices when push stops working. Settings has a Test my push button.
```

Use one file per PR. Each entry is a plain sentence of at most 120 characters,
with a benefit or behavior that is true for the selected source. Describe
security protections with their conditions, without attack instructions.
`pr` is optional; named fragments are valid when there is no PR number.

Topics are setup, push, notifications, backups, privacy, security, updates,
settings, devices, look, signal and fixes. Entries are English; topic labels
and highlights use translated Android resources. A PR with no noticeable
app change uses the **no log entry** label.

Pending files are preparation inputs. A development preview may show them in
its one current-build section; they never form a second release. A prepared
release cannot have unassigned fragments.

## Release records

Each product's directory contains `<versionName>.txt` records:

```text
version: 8.36.3-1-beta1
date: 2026-11-21
tag: trailblazer-v8.36.3-1-beta1
highlights: 2026-11-setup-and-push-health

topic: settings
pr: 156
What's new includes a history of changes in Collie.
```

`version` is the exact version name, and `date` is an explicit release date.
`tag` optionally selects an exact release page in the Collie GitHub repository.
`highlights` optionally selects a known content edition in `CollieReleaseNotes`;
missing highlights means no feature announcement. An empty maintenance record
is valid. Keep highlight wording in Android string resources and retain its
content ID when the news has not changed.

The release recipe supplies `collieReleasePrepared=true` only after the existing
preparation verifier accepts the source. Direct Gradle builds default to a
**Development build**, even when they retain an old release version. Both tabs
use the same selection; missing or inconsistent prepared metadata shows a
neutral unavailable state instead of another release's highlights.

Prepare notes after the intended feature changes have merged. Correct old
history in an ordinary PR before the final source pin; final preparation only
creates the new release record. See [RELEASING.md](RELEASING.md) for the existing
source, build and publication boundaries.

## Promoting features to Stable

A feature's Stable promotion PR adds a Stable-facing pending note, retaining
its original PR reference. Stable preparation assigns it to the first Stable
release that contains it. Its Beta history remains in the Beta directory.
Main-to-Beta integrations similarly include notes for the changes newly reaching
Beta. Review the final feature set instead of copying all of another product's
history or inferring promotion from a version suffix.

## Verification

`ProjectLogTest` covers one current release, product-scoped inputs, missing and
duplicate records, development previews, empty maintenance releases, safe links
and deterministic history order. The existing API 35 screenshot fixture covers
the rendered tabs and external-browser handoff. Release notes are shown from
local assets; opening a PR or release page uses the external browser, outside
Collie's proxy.
