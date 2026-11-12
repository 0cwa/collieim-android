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

The PR check requires one lowercase `Layer: <feature>` trailer on every non-merge commit; fast lint and that trailer check are required, while unit tests and a debug APK build run but are not required checks.

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
