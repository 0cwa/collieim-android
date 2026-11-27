#!/usr/bin/env bash
set -euo pipefail

workload=$1
mode=$2
case "$mode" in
  seed) offline=() ;;
  baseline|candidate) offline=(--offline) ;;
  *) echo "Unknown trial phase: $mode" >&2; exit 1 ;;
esac

args=()
case "$workload" in
  app-0|app-1)
    tasks=(:app:testProdWebsiteDebugUnitTest)
    args=(--continue -PcollieHostJniDir=build/host-tests/resources "-PcollieAppTestShard=${workload#app-}")
    ;;
  modules)
    tasks=(testDebugUnitTest :core:util-jvm:test :lib:libsignal-service:test :core:network:test
           :lib:network:test :fast-lint:test :lintchecks:test)
    args=(--continue -PcollieHostJniDir=build/host-tests/resources)
    ;;
  stable)
    tasks=(:app:assembleProdWebsiteDebug :app:assembleProdWebsiteDebugAndroidTest)
    ;;
  beta)
    tasks=(:app:assembleProdWebsiteBeta :app:assembleProdWebsiteBetaAndroidTest)
    args=(-PcollieBetaInstrumentation=true)
    ;;
  *) echo "Unknown trial workload: $workload" >&2; exit 1 ;;
esac

test "$(git rev-parse HEAD)" = "$GITHUB_SHA"
test "$GRADLE_USER_HOME" = /home/runner/.gradle
test -z "${GRADLE_RO_DEP_CACHE:-}"
export LC_ALL=C
export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:+$JAVA_TOOL_OPTIONS }-Drobolectric.dependency.repo.url=https://maven-central.storage-download.googleapis.com/maven2"
cp tooling/gradle.properties.docker "$GRADLE_USER_HOME/gradle.properties"

evidence="$RUNNER_TEMP/gradle-trial"
transform_dir="$GRADLE_USER_HOME/caches/9.6.0/transforms"
mkdir -p "$evidence" "$transform_dir"
snapshot() {
    find "$transform_dir" -mindepth 2 -maxdepth 2 -name results.bin -type f \
        -printf '%P\t%i\t%s\t%T@\n' | sort
}

snapshot > "$evidence/$mode-before.tsv"
start=$SECONDS
status=0
./gradlew --no-daemon --no-build-cache --no-configuration-cache --console=plain \
    "${offline[@]}" "${args[@]}" "${tasks[@]}" || status=$?
elapsed=$((SECONDS - start))
snapshot > "$evidence/$mode-after.tsv"
comm -13 "$evidence/$mode-before.tsv" "$evidence/$mode-after.tsv" \
    | cut -f1 | sort -u > "$evidence/$mode-rebuilt.txt"
count=$(wc -l < "$evidence/$mode-rebuilt.txt")
printf '%s %s: Gradle exit %d, %ds, %d new or replaced transform results\n' \
    "$workload" "$mode" "$status" "$elapsed" "$count" | tee -a "$GITHUB_STEP_SUMMARY"
cat "$evidence/$mode-rebuilt.txt"
if [[ "$status" != 0 ]]; then
    exit "$status"
fi

case "$workload" in
    stable)
        test -s app/build/outputs/apk/prodWebsite/debug/app-prod-website-debug.apk
        test -s app/build/outputs/apk/androidTest/prodWebsite/debug/app-prod-website-debug-androidTest.apk
        ;;
    beta)
        test -s app/build/outputs/apk/prodWebsite/beta/app-prod-website-beta.apk
        test -s app/build/outputs/apk/androidTest/prodWebsite/beta/app-prod-website-beta-androidTest.apk
        ;;
    *)
        python3 - <<'PY'
from pathlib import Path
from xml.etree import ElementTree

root_dirs = ("app", "core", "feature", "lib", "tooling")
reports = (report for root in root_dirs for report in Path(root).glob("**/build/test-results/*/TEST-*.xml"))
tests = sum(int(ElementTree.parse(report).getroot().attrib["tests"]) for report in reports)
if tests == 0:
    raise SystemExit("No unit tests ran")
print(f"Recorded {tests} unit tests")
PY
        ;;
esac
