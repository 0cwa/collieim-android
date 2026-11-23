# Source verification

`0cwa/collieim-android` `main` is the application source. App changes land directly through ordinary pull requests; generated source commits and engineering-layer receipts are not part of the current source path. Follow the [release engineering development cycle](https://github.com/0cwa/collie-release-engineering/blob/main/docs/DEVELOPMENT-CYCLE.md) for the full policy.

## Verify an app source commit

Fetch the current app source and identify the exact full commit SHA used by the build or release:

```sh
git fetch origin main --tags
SOURCE=<full-app-commit-sha>
git show -s --format=fuller "$SOURCE"
git verify-commit "$SOURCE"
git merge-base --is-ancestor "$SOURCE" origin/main
```

The source commit must be signed and reachable from `origin/main`. For an open pull request, identify its exact base and head and inspect the diff. For a range **without upstream merges**, verify every non-merge commit:

```sh
BASE=<full-pr-base-sha>
HEAD=<full-pr-head-sha>
for commit in $(git rev-list --no-merges "$BASE..$HEAD"); do
  git verify-commit "$commit"
done
git diff --stat "$BASE...$HEAD"
```

For a range containing upstream merges, first verify each declared merge's signature and upstream parent as described under [Verify an upstream merge](#verify-an-upstream-merge). Exclude only the history reached through those verified Signal or Molly parents from the non-merge signature loop, and verify the remaining Collie commits. A selective fork's provenance table never grants this exemption.

The PR check requires one `Layer:` trailer on every non-merge commit, naming an entry in [`tooling/layers.toml`](../tooling/layers.toml); from PR #202 on, a PR may change only one `feature` layer, and PRs land with a merge commit so each commit keeps its layer; fast lint and that trailer check are required, while unit tests and a debug APK build run but are not required checks.

## Catch trailer mistakes locally

Install the local checks once per clone, and rerun the installer after hook updates:

```sh
python3 tooling/git-hooks/install.py
```

The installer copies `commit-msg`, `pre-push` and their validator into Git's active hooks directory. The default directory is shared by this clone's worktrees, so the checks stay active after switching to branches without the tooling. Existing unrelated hooks are preserved: installation stops before writing anything if a destination is already in use. A configured `core.hooksPath` is respected; if it points to a shared directory, these checks also apply to other repositories using that directory.

`commit-msg` rejects a missing, duplicate or malformed layer before creating a commit. Keep all trailers in one final paragraph:

```text
Explain what changed and why.

Assisted by: ChatGPT

Layer: website
Co-authored-by: Name <name@example.com>
```

A blank line between `Layer:` and `Co-authored-by:` makes Git ignore the layer. The check uses Git's trailer parser, including its handling of editor comments. Ordinary merge commits are exempt, as in CI. An upstream merge must carry one `Layer:` of kind `upstream` and one `Upstream:` trailer; the commits it brings in are skipped.

`pre-push` checks every non-merge commit in `origin/main..HEAD` for each branch being pushed, including commits already published on that branch. Fetch `origin/main` before pushing; a missing base blocks the push with a fetch instruction. Branch deletions and tags are left to their existing workflows. Like CI, it checks each upstream merge against the upstream repository over the network; offline, `COLLIE_UPSTREAM_OFFLINE=1 git push` skips only that network check, and CI still runs it. To check the same range manually:

```sh
git fetch origin main
python3 tooling/git-hooks/collie_check_layer_trailers.py range origin/main HEAD
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s tooling/git-hooks -p 'test_*.py'
```

The native [`commit-msg` hook](https://git-scm.com/docs/githooks#_commit_msg) runs after the message is available; `pre-commit` runs earlier and cannot validate the final message. A pre-commit framework could run a commit-message check, but these two hooks need only Git and Python 3.11 or later (to read `tooling/layers.toml`), which CI already uses. Hooks are local and can be bypassed, so the required **Layer trailers** PR check remains the final guard. CI extracts the validator from the trusted PR base and reads the catalog from the PR head; existing layer kinds come from the base so a PR cannot relabel a feature to evade the one-feature rule. The local commands above exercise the worktree validator, including proposed schema changes; CI also runs the hook tests from the head.

## Selected-fork provenance

[`tooling/layers.toml`](../tooling/layers.toml) is the canonical feature catalog. A canonical entry with `kind = "feature"` may contain one optional `provenance` table for its selected fork origin. Aliases refer to their canonical entry and cannot carry provenance. Existing entries without provenance, and historical trees without a catalog, remain valid: missing provenance means **tracking is unknown**, not that the feature is unimplemented.

The engineering planner reads the catalog at the exact app revision being planned. It uses this contract for selective fork maintenance; the engineering source registry still supplies source discovery policy. The table records a reviewed selection, not an additional applied-source baseline or an inventory of every historical contribution.

| Field | Contract |
| --- | --- |
| `repository` | Required GitHub `owner/repo` source identity. Signal and Molly's canonical repositories use verified upstream merges and cannot be recorded here. |
| `revision` | Required full, lowercase, nonzero 40-hex source commit SHA. This is the exact selected revision; it is never the enclosing app commit's own SHA. |
| `revision_kind` | Required `feature-commit` for an isolated original feature commit, or `release-snapshot` for a combined source release containing multiple changes. |
| `relationship` | Required `direct-import` for importing the selected change, `adaptation` for adjusting selected source logic to Collie, or `inspiration` for an independent implementation informed by the source. Inspiration does not claim imported code. |
| `application` | Required `mechanical` or `manual`. Only a `direct-import` from a `feature-commit` may be mechanical. Adaptations, inspiration and combined release snapshots require manual work. |
| `scope` | Required nonblank description of the selected behavior. For a release snapshot, identify the narrow selection rather than claiming the whole snapshot is a feature commit. |
| `discovery_ref` | Optional fully qualified `refs/heads/...` or `refs/tags/...` ref, without wildcards. It is a discovery hint that may move; resolve it when reviewing an update and record the new exact selection in `revision`. Never use it in place of the selected SHA. |
| `prerequisites` | Optional list of distinct canonical feature IDs in this catalog. Each must name another `feature`, not an alias, infrastructure or upstream layer. Self-dependencies and cycles are invalid. Omission or an empty list records no prerequisites. |

Unknown provenance fields are rejected. Keep author attribution in commits, source paths in the code and review, and implementation history in Git and PRs; do not copy those inventories into the catalog. The current selection can be updated in a later feature PR without recording a history list or a self-referential app SHA.

For planning:

- Resolve aliases to their canonical feature and plan it once. Missing provenance is an unknown tracking state, so inspect the implementation and history before deciding whether work is needed.
- Compare the exact selected source revision with a newly resolved discovery candidate. A moved ref alone does not change the recorded selection. `feature-commit` and `release-snapshot` describe the source boundary, not proof that all of its code landed in Collie.
- Treat `mechanical` as eligibility to attempt the selected commit, with conflict checks, review and tests still required. For `manual`, inspect and port the stated scope, or implement it independently for `inspiration`; never cherry-pick a whole combined snapshot as that feature.
- Resolve prerequisites against the same catalog. A prerequisite may have no provenance of its own; that does not make it absent. Prerequisites express ordering, not permission to include another feature in the same PR.

The existing validator checks the table's structure and relationships without fetching selected forks or proving implementation or source-content claims. Source review supplies that evidence. Selected changes still use signed Collie commits, attribution and their canonical feature's `Layer:` trailer. Provenance does not add an `Upstream:` repository, skip signatures or trailers, alter the trusted-base check, or change how commits already landed on main are skipped when bringing main into TrailBlazer Beta. Signal and Molly integration remains represented by the verified merges below.

## Verify an upstream merge

Signal upgrades are `git merge` of a Signal tag and Molly updates are `git merge` of a Molly branch, made with `tooling/upstream/merge-upstream.py`. The merge commit is signed and carries `Layer: upgrade` and either `Upstream: signalapp/Signal-Android <tag>` or `Upstream: mollyim/mollyim-android <branch>@<full-sha>`. The **Layer trailers** check requires its second parent to be the commit that Signal's tag points to, or the declared Molly commit on that branch, checked with `git ls-remote` or a fetch from the upstream repository. To check one yourself:

```sh
MERGE=<full-merge-commit-sha>
TAG=<signal-tag>
git verify-commit "$MERGE"
git show -s --format=%B "$MERGE"
git fetch --no-tags https://github.com/signalapp/Signal-Android.git "refs/tags/$TAG"
test "$(git rev-parse 'FETCH_HEAD^{commit}')" = "$(git rev-parse "$MERGE^2")"
git diff "$MERGE^2" "$MERGE"   # Collie's differences from the upstream release after the merge
```

Peeling `FETCH_HEAD` handles both annotated and lightweight Signal tags. For Molly, the trailer's full SHA must equal the second parent, and the declared branch fetched from Molly must contain that SHA; a moving branch head need not still equal it.

Review the merge's own changes (`git show --remerge-diff "$MERGE"`) for conflict resolutions, kept donation deletions and Collie adaptations.

## Verify a release tag

Release tags are annotated and signed with the publication key listed in `.github/release-signers`. Check the tag and the exact source commit it names:

```sh
TAG=<release-tag>
git fetch --depth=1 origin "refs/tags/$TAG:refs/tags/$TAG"
test "$(git cat-file -t "$TAG")" = tag
git -c gpg.ssh.allowedSignersFile=.github/release-signers verify-tag "$TAG"
git show -s --format=fuller "$TAG^{commit}"
git merge-base --is-ancestor "$TAG^{commit}" origin/main
```

Artifact signing, release checks, and independent rebuild requirements are in [RELEASING.md](RELEASING.md). A source signature or successful source build alone does not establish artifact reproducibility or runtime acceptance.
