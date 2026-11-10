#!/usr/bin/env bash
# Builds the unsigned Collie release APK from the checked-out commit inside the pinned
# builder image. GitHub CI and local releases both run this script, so their outputs must match.
#
# Usage: tooling/reproducible-builds/build-release.sh <output-dir>
# Env:   CONTAINER=docker|podman (default: docker)
set -euo pipefail

out=$(realpath -m "${1:?usage: build-release.sh <output-dir>}")
root=$(git rev-parse --show-toplevel)
cd "$root"

if [ -n "$(git status --porcelain --untracked-files=no)" ]; then
  echo "build-release: tracked files have uncommitted changes; build a clean commit" >&2
  exit 1
fi

engine=${CONTAINER:-docker}
commit=$(git rev-parse HEAD)
image="collie-builder:${commit:0:12}"
ignore=()
[ "$engine" = podman ] && ignore=(--ignorefile tooling/reproducible-builds/Dockerfile.dockerignore)

"$engine" build "${ignore[@]}" -f tooling/reproducible-builds/Dockerfile --target builder -t "$image" .

mkdir -p "$out"
"$engine" run --rm -v "$out:/out" --entrypoint sh "$image" -c '
  set -eu
  ./gradlew --no-daemon :app:assembleProdWebsiteRelease
  cp app/build/outputs/apk/prodWebsite/release/*.apk /out/
'
echo "commit $commit"
sha256sum "$out"/*.apk
