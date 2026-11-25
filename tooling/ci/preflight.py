#!/usr/bin/env python3
"""Run fast local checks before pushing a feature branch."""

import argparse
import subprocess
import sys


def run_check(label, command):
    result = subprocess.run(command, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    if result.stdout:
        print(result.stdout, end="" if result.stdout.endswith("\n") else "\n")
    passed = result.returncode == 0
    print(f"{'PASS' if passed else 'FAIL'}: {label}")
    return passed


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", default="origin/main", help="base commit or ref (default: origin/main)")
    parser.add_argument("--allow-unsigned", action="store_true", help="do not fail for unsigned commits")
    args = parser.parse_args(argv)

    checks = [
        run_check("Layer trailers", [sys.executable, "tooling/git-hooks/collie_check_layer_trailers.py",
                                      "range", args.base, "HEAD"]),
        run_check("Fastlane metadata", [sys.executable, "tooling/fastlane/check_metadata.py"]),
    ]

    log = subprocess.run(
        ["git", "log", "--no-merges", "--format=%H %G?", f"{args.base}..HEAD"],
        text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
    )
    signatures_ok = log.returncode == 0
    if not signatures_ok:
        print(f"Commit signatures: git log failed: {log.stderr.strip()}")
    else:
        unsigned = [line for line in log.stdout.splitlines()
                    if len(line.split()) == 2 and line.split()[1] in {"N", "B"}]
        if unsigned and not args.allow_unsigned:
            print("Unsigned or bad-signature commits:")
            for line in unsigned:
                print(f"  {line}")
            signatures_ok = False
        else:
            signatures_ok = True
    checks.append(signatures_ok)
    print(f"{'PASS' if signatures_ok else 'FAIL'}: commit signatures"
          f"{' (unsigned commits allowed)' if args.allow_unsigned else ''}")

    passed = all(checks)
    print(f"Preflight {'PASSED' if passed else 'FAILED'} ({sum(checks)}/{len(checks)} checks)")
    return 0 if passed else 1


if __name__ == "__main__":
    sys.exit(main())
