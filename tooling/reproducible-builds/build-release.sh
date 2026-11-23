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
#                                    ~/.cache/collie-build/gradle). Each build gets a fresh
#                                    Gradle user home; dependencies are seeded read-only from
#                                    ~/.gradle/caches.
#        COLLIE_BUILD_EDITION=stable|beta   stable (default), or the TrailBlazer Beta package.
#        COLLIE_DEVELOPMENT_BASE=<commit>   PR CI only: the PR base. A change that keeps the base's
#                                    release properties is checked as development, not as a
#                                    preparation. Release builds never set it.
set -euo pipefail
export GIT_NO_REPLACE_OBJECTS=1

case "${COLLIE_BUILD_EDITION:-stable}" in
  stable) task=:app:assembleProdWebsiteRelease; apk_dir=app/build/outputs/apk/prodWebsite/release ;;
  beta)   task=:app:assembleProdWebsiteBeta; apk_dir=app/build/outputs/apk/prodWebsite/beta ;;
  *) echo "build-release: COLLIE_BUILD_EDITION must be stable or beta" >&2; exit 1 ;;
esac

out=$(realpath -m "${1:?usage: build-release.sh <output-dir>}")
root=$(git rev-parse --show-toplevel)
cd "$root"

if [ -n "$(git status --porcelain --untracked-files=no)" ]; then
  echo "build-release: tracked files have uncommitted changes; build a clean commit" >&2
  exit 1
fi
commit=$(git rev-parse HEAD)
python3 tooling/reproducible-builds/verify-release-prep.py ${COLLIE_DEVELOPMENT_BASE:+--development-base "$COLLIE_DEVELOPMENT_BASE"}
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
  home=$(realpath "$home")
  build_home=$(mktemp -d "$home/release-home.XXXXXXXX")
  trap 'rm -rf -- "$build_home"' EXIT
  cp tooling/gradle.properties.docker "$build_home/gradle.properties"
  ln -s "$HOME/.gradle/wrapper" "$build_home/wrapper"
  cache_flag=--no-build-cache
  if [ "${COLLIE_BUILD_CACHE:-0}" = 1 ]; then
    mkdir -p "$home/caches/build-cache-1" "$build_home/caches"
    ln -s "$home/caches/build-cache-1" "$build_home/caches/build-cache-1"
    cache_flag=--build-cache
  fi
  env JAVA_HOME="$jdk" ANDROID_HOME="$sdk" ANDROID_SDK_ROOT="$sdk" ANDROID_NDK_HOME="$sdk/ndk/$ndk" \
      GRADLE_USER_HOME="$build_home" GRADLE_RO_DEP_CACHE="$HOME/.gradle/caches" \
      ./gradlew --no-daemon --gradle-user-home "$build_home" "$cache_flag" "$task"
  cp "$apk_dir"/*.apk "$out/"
else
  engine=${CONTAINER:-docker}
  image="collie-builder:${commit:0:12}"
  ignore=()
  [ "$engine" = podman ] && ignore=(--ignorefile tooling/reproducible-builds/Dockerfile.dockerignore)
  "$engine" build "${ignore[@]}" -f "$dockerfile" --target builder -t "$image" .
  "$engine" run --rm -e GIT_NO_REPLACE_OBJECTS=1 -v "$out:/out" --entrypoint sh "$image" -c '
    set -eu
    ./gradlew --no-daemon "$1"
    cp "$2"/*.apk /out/
  ' sh "$task" "$apk_dir"
fi
echo "commit $commit"
sha256sum "$out"/*.apk
