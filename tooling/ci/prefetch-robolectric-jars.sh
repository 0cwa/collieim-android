#!/usr/bin/env bash
set -euo pipefail

central=https://repo1.maven.org/maven2
mirror=https://maven-central.storage-download.googleapis.com/maven2
dir=org/robolectric/android-all-instrumented
get() {
  timeout 600 curl -fsSL --connect-timeout 20 --max-time 300 --retry 3 --retry-all-errors \
    --retry-delay 5 --retry-max-time 120 -o "$2" "$1"
}

while IFS= read -r version || [[ -n "$version" ]]; do
  artifact="$dir/$version/android-all-instrumented-$version.jar"
  jar="$HOME/.m2/repository/$artifact"
  [[ -s "$jar" ]] && continue
  mkdir -p "$(dirname "$jar")"

  # Prefer Central for the published checksum and the mirror for the large jar.
  if ! get "$central/$artifact.sha1" "$jar.sha1" && ! get "$mirror/$artifact.sha1" "$jar.sha1"; then
    echo "::error::Could not fetch the Robolectric checksum for $version" >&2
    rm -f "$jar.sha1"
    exit 1
  fi
  checksum=$(cut -d' ' -f1 "$jar.sha1")
  rm -f "$jar.sha1"

  for repo in "$mirror" "$central"; do
    get "$repo/$artifact" "$jar.part" || continue
    if [[ $(sha1sum "$jar.part" | cut -d' ' -f1) == "$checksum" ]]; then
      mv "$jar.part" "$jar"
      echo "Fetched Robolectric $version"
      break
    fi
    echo "Checksum mismatch from $repo for $version" >&2
  done
  if [[ ! -s "$jar" ]]; then
    rm -f "$jar.part"
    echo "::error::Could not fetch and verify the Robolectric jar for $version" >&2
    exit 1
  fi
done < tooling/ci/robolectric-jars.txt
