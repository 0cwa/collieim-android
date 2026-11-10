#!/usr/bin/env bash
# Builds the unsigned Collie release APK from the checked-out commit. GitHub CI and local
# releases run this same script, so their outputs must match.
#
# Usage: tooling/reproducible-builds/build-release.sh <output-dir>
# Env:   BUILD_MODE=container|host   container (default): the pinned builder image, via docker
#                                    or podman ($CONTAINER). host: this machine's pinned JDK/SDK.
#        COLLIE_BUILD_CACHE=1        host only: also reuse Gradle task outputs. Fine for your own
#                                    release build; never for a build that verifies another.
#        COLLIE_BUILD_HOME           host only: persistent Gradle home (default
#                                    ~/.cache/collie-build/gradle). Dependencies are seeded
#                                    read-only from ~/.gradle/caches, so nothing is re-downloaded.
set -euo pipefail

out=$(realpath -m "${1:?usage: build-release.sh <output-dir>}")
root=$(git rev-parse --show-toplevel)
cd "$root"

if [ -n "$(git status --porcelain --untracked-files=no)" ]; then
  echo "build-release: tracked files have uncommitted changes; build a clean commit" >&2
  exit 1
fi
commit=$(git rev-parse HEAD)
dockerfile=tooling/reproducible-builds/Dockerfile
mkdir -p "$out"

if [ "${BUILD_MODE:-container}" = host ]; then
  # The build records the Git revision in the APK only if it finds a regular repository.
  [ -d .git ] || { echo "build-release: host builds need a regular clone, not a git worktree (.git must be a directory)" >&2; exit 1; }
  arg() { sed -n "s/^ARG $1=\"\(.*\)\"/\1/p" "$dockerfile"; }
  ndk=$(arg NDK_VERSION); tools=$(arg BUILD_TOOLS_VERSION); platform=$(arg COMPILE_SDK)
  jdk=$HOME/.local/opt/jdk-$(sed -n 's/^java temurin-//p' .tool-versions)
  sdk=${ANDROID_HOME:-$HOME/Android/Sdk}
  for need in "$jdk/bin/java" "$sdk/ndk/$ndk" "$sdk/build-tools/$tools" "$sdk/platforms/$platform"; do
    [ -e "$need" ] || { echo "build-release: missing $need (pinned by Dockerfile/.tool-versions)" >&2; exit 1; }
  done
  home=${COLLIE_BUILD_HOME:-$HOME/.cache/collie-build/gradle}
  mkdir -p "$home"
  cp tooling/gradle.properties.docker "$home/gradle.properties"   # same flags the image uses
  [ -e "$home/wrapper" ] || ln -s "$HOME/.gradle/wrapper" "$home/wrapper"
  cache_flag=--no-build-cache; [ "${COLLIE_BUILD_CACHE:-0}" = 1 ] && cache_flag=--build-cache
  env JAVA_HOME="$jdk" ANDROID_HOME="$sdk" ANDROID_SDK_ROOT="$sdk" ANDROID_NDK_HOME="$sdk/ndk/$ndk" \
      GRADLE_USER_HOME="$home" GRADLE_RO_DEP_CACHE="$HOME/.gradle/caches" \
      ./gradlew --no-daemon "$cache_flag" :app:assembleProdWebsiteRelease
  cp app/build/outputs/apk/prodWebsite/release/*.apk "$out/"
else
  engine=${CONTAINER:-docker}
  image="collie-builder:${commit:0:12}"
  ignore=()
  [ "$engine" = podman ] && ignore=(--ignorefile tooling/reproducible-builds/Dockerfile.dockerignore)
  "$engine" build "${ignore[@]}" -f "$dockerfile" --target builder -t "$image" .
  "$engine" run --rm -v "$out:/out" --entrypoint sh "$image" -c '
    set -eu
    ./gradlew --no-daemon :app:assembleProdWebsiteRelease
    cp app/build/outputs/apk/prodWebsite/release/*.apk /out/
  '
fi
echo "commit $commit"
sha256sum "$out"/*.apk
