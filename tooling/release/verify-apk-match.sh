#!/usr/bin/env bash
set -euo pipefail

if (( $# != 2 )); then
  echo "Usage: bash verify-apk-match.sh UNSIGNED-REBUILD.apk PUBLISHED.apk" >&2
  exit 2
fi

script_dir=$(dirname -- "$0")
work=$(mktemp -d)
trap 'rm -rf -- "$work"' EXIT

curl -fsSL https://raw.githubusercontent.com/obfusk/reproducible-apk-tools/9401d827a2404827ec3de6cb90a879d9fbd3f60d/zipalign.py -o "$work/zipalign.py"
curl -fsSL https://raw.githubusercontent.com/obfusk/apksigcopier/bc08f220f8c4b5e429b99f3263a56c68f1cda4e9/apksigcopier/__init__.py -o "$work/apksigcopier.py"

python3 "$work/zipalign.py" --page-size 16 --pad-like-apksigner --replace 4 "$1" "$work/aligned.apk"
PYTHONPATH="$work" python3 "$script_dir/copy-apk-signatures.py" \
  "$2" "$work/aligned.apk" "$work/reconstructed.apk"

if cmp -s "$work/reconstructed.apk" "$2"; then
  echo "Byte-for-byte match"
  read -r published_digest _ < <(sha256sum "$2")
  read -r rebuilt_digest _ < <(sha256sum "$work/reconstructed.apk")
  printf 'Published APK         %s\nReconstructed APK     %s\n' "$published_digest" "$rebuilt_digest"
else
  echo "APK bytes differ" >&2
  exit 1
fi
