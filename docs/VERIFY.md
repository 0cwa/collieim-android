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

The source commit must be signed and reachable from `origin/main`. For an open pull request, verify every non-merge commit in its exact base-to-head range and inspect the diff against that base:

```sh
BASE=<full-pr-base-sha>
HEAD=<full-pr-head-sha>
for commit in $(git rev-list --no-merges "$BASE..$HEAD"); do
  git verify-commit "$commit"
done
git diff --stat "$BASE...$HEAD"
```

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

Layer: website
Co-authored-by: Name <name@example.com>
```

A blank line between `Layer:` and `Co-authored-by:` makes Git ignore the layer. The check uses Git's trailer parser, including its handling of editor comments. Ordinary merge commits are exempt, as in CI.

`pre-push` checks every non-merge commit in `origin/main..HEAD` for each branch being pushed, including commits already published on that branch. Fetch `origin/main` before pushing; a missing base blocks the push with a fetch instruction. Branch deletions and tags are left to their existing workflows. To check the same range manually:

```sh
git fetch origin main
python3 tooling/git-hooks/collie_check_layer_trailers.py range origin/main HEAD
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s tooling/git-hooks -p 'test_*.py'
```

The native [`commit-msg` hook](https://git-scm.com/docs/githooks#_commit_msg) runs after the message is available; `pre-commit` runs earlier and cannot validate the final message. A pre-commit framework could run a commit-message check, but these two hooks need only Git and Python 3.11 or later (to read `tooling/layers.toml`), which CI already uses. Hooks are local and can be bypassed, so the required **Layer trailers** PR check remains the final guard and runs the same validator.

## Verify a Signal upgrade

Take `OLD` from `canonicalVersionName` in `app/build.gradle.kts` and `NEW` from the Signal tag under review. Fetch both tags and inspect the complete upstream change:

```sh
git fetch --no-tags https://github.com/signalapp/Signal-Android.git \
  "refs/tags/$OLD:refs/tags/signal-$OLD" \
  "refs/tags/$NEW:refs/tags/signal-$NEW"
git diff --binary -M "signal-$OLD" "signal-$NEW"
```

Compare that tag-to-tag diff with the app PR diff. Keep applicable changes, adapt or remove changes that do not belong in Collie, and check the PR's `Upstream:` and `Co-authored-by:` trailers. Do not merge an upstream tag into app `main`.

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
