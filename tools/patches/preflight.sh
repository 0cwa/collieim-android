#!/usr/bin/env bash
# Run the CI gates locally before pushing a patch-series change.
#
#   tools/patches/preflight.sh            # proof: patch-tool tests + source reconstruction (~2 min)
#   tools/patches/preflight.sh resources  # proof + Android resource linking (catches missing @color/@string refs)
#   tools/patches/preflight.sh full       # proof + the profile's assemble and regression tests (as CI)
#
# The reconstructed source lands in $MOLLY_PREFLIGHT_DIR (default: /tmp/molly-preflight)
# and is replaced on each run. Android stages need JDK 21 and ANDROID_HOME.
set -euo pipefail

stage=${1:-proof}
case "$stage" in proof|resources|full) ;; *) echo "usage: $0 [proof|resources|full]" >&2; exit 2 ;; esac

repo=$(git rev-parse --show-toplevel)
cd "$repo"
base=${MOLLY_PREFLIGHT_DIR:-/tmp/molly-preflight}
worktree="$base/release"
reports="$base/reports"
mkdir -p "$base"

molly_target=$(python3 -c 'import json; print(json.load(open("molly-build.json"))["molly"]["target"])')

echo '== patch-tool tests'
python3 -m unittest discover -s tools/patches -p 'test_*.py'

if ! git cat-file -e "$molly_target^{commit}" 2>/dev/null; then
  echo "== fetching Molly oracle $molly_target"
  git fetch --no-tags --filter=blob:none https://github.com/mollyim/mollyim-android.git "$molly_target"
fi

# release.py refuses an existing destination; drop the previous run's worktree.
if [[ -e "$worktree" ]]; then
  git worktree remove --force "$worktree" 2>/dev/null || {
    echo "$worktree exists and is not a worktree of this repository; remove it or set MOLLY_PREFLIGHT_DIR" >&2
    exit 1
  }
fi
git worktree prune

echo '== source reconstruction and tree proof'
python3 tools/patches/release.py --repo . --config molly-build.json \
  --worktree "$worktree" --report "$reports/source.json" >/dev/null
python3 - "$reports/source.json" <<'PY'
import json, sys
report = json.load(open(sys.argv[1]))
if report.get('status') != 'PASS':
    sys.exit(f"source proof failed: {report.get('error')}")
print(f"source proof PASS, tree {report['tree']}")
PY

[[ $stage == proof ]] && exit 0

: "${ANDROID_HOME:=${ANDROID_SDK_ROOT:-}}"
if [[ -z $ANDROID_HOME ]]; then echo 'ANDROID_HOME must point at the Android SDK' >&2; exit 1; fi
export ANDROID_HOME
java_major=$(java -XshowSettings:properties -version 2>&1 | sed -n 's/.*java.specification.version = //p')
if [[ $java_major != 21 ]]; then echo "JDK 21 required (found ${java_major:-none}); set JAVA_HOME/PATH" >&2; exit 1; fi

build() { python3 tools/patches/build.py "$1" --worktree "$worktree" --report-dir "$reports"; }

echo '== pinned Android SDK components'
yes 2>/dev/null | "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --licenses >/dev/null || true
build sdk >/dev/null

if [[ $stage == resources ]]; then
  echo '== Android resource linking'
  (cd "$worktree" && ./gradlew --no-configuration-cache --dependency-verification=strict \
     :app:processProdWebsiteDebugResources)
  exit 0
fi

echo '== assemble'
build assemble
echo '== regression tests'
build test
