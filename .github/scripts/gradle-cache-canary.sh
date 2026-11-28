#!/usr/bin/env bash
set -euo pipefail

workload=$1
mode=$2
case "$mode" in
  seed|baseline|online-miss) resolution=(--info) ;;
  candidate) resolution=(--offline) ;;
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
test "$(git rev-parse HEAD^2)" = "$SOURCE_SHA"
test "$GRADLE_USER_HOME" = /home/runner/.gradle
test -z "${GRADLE_RO_DEP_CACHE:-}"
export LC_ALL=C
robolectric_option=-Drobolectric.dependency.repo.url=https://maven-central.storage-download.googleapis.com/maven2
if [[ "$mode" == candidate && "$workload" =~ ^(app-[01]|modules)$ ]]; then
    fixture_dir="$RUNNER_TEMP/robolectric-local"
    mkdir "$fixture_dir"
    while IFS= read -r version; do
        jar="android-all-instrumented-$version.jar"
        source="$HOME/.m2/repository/org/robolectric/android-all-instrumented/$version/$jar"
        test -s "$source"
        ln -s "$source" "$fixture_dir/$jar"
    done < tooling/ci/robolectric-jars.txt
    robolectric_option="-Drobolectric.dependency.dir=$fixture_dir"
fi
export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:+$JAVA_TOOL_OPTIONS }$robolectric_option"
cp tooling/gradle.properties.docker "$GRADLE_USER_HOME/gradle.properties"

evidence="$RUNNER_TEMP/gradle-trial"
transform_dir="$GRADLE_USER_HOME/caches/9.6.0/transforms"
mkdir -p "$evidence" "$transform_dir"
snapshot() {
    find "$transform_dir" -mindepth 2 -maxdepth 2 -name results.bin -type f \
        -printf '%P\t%i\t%s\t%T@\n' | sort
}

phase="$evidence/$workload-$mode"
snapshot > "$phase-before.tsv"
start=$SECONDS
set +e
# Gradle test reports use Unicode test names in filesystem paths.
LC_ALL=C.UTF-8 ./gradlew --no-daemon --no-build-cache --no-configuration-cache --console=plain \
    "${resolution[@]}" "${args[@]}" "${tasks[@]}" 2>&1 | tee "$phase-gradle.log"
phase_status=("${PIPESTATUS[@]}")
set -e
status=${phase_status[0]}
test "${phase_status[1]}" -eq 0
if [[ "$mode" != candidate ]]; then
    downloads=$(grep -Ec '^[[:space:]]*Downloading ' "$phase-gradle.log" || true)
    misses=$(grep -c 'Resource missing\. \[HTTP' "$phase-gradle.log" || true)
    printf '%s %s: %s Gradle download lines, %s missing HTTP resources\n' \
        "$workload" "$mode" "$downloads" "$misses" | tee -a "$GITHUB_STEP_SUMMARY"
fi
elapsed=$((SECONDS - start))
snapshot > "$phase-after.tsv"
comm -13 "$phase-before.tsv" "$phase-after.tsv" \
    | cut -f1 | sort -u > "$phase-rebuilt.txt"
count=$(wc -l < "$phase-rebuilt.txt")
printf '%s %s: Gradle exit %d, %ds, %d new or replaced transform results\n' \
    "$workload" "$mode" "$status" "$elapsed" "$count" | tee -a "$GITHUB_STEP_SUMMARY"
cat "$phase-rebuilt.txt"
if [[ "$status" != 0 ]]; then
    exit "$status"
fi
if [[ "$mode" == online-miss && "$downloads" == 0 ]]; then
    echo '::error::The intentional cache miss did not observe an online Gradle download'
    exit 1
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
