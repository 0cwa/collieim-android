#!/usr/bin/env python3
"""Summarize the CI JVM suite and reject failures or an empty test run."""

import argparse
from collections import defaultdict
import os
from pathlib import Path
import sys
import xml.etree.ElementTree as ET


def check_results(root):
    counts = defaultdict(lambda: [0, 0, 0, 0])
    for group in ("app", "core", "feature", "lib", "tooling"):
        for path in sorted((root / group).glob("**/build/test-results/**/TEST-*.xml")):
            suite = ET.parse(path).getroot()
            if suite.tag != "testsuite":
                raise ValueError(f"Unexpected JUnit report format: {path}")
            values = [int(suite.get(name, "0")) for name in ("tests", "failures", "errors", "skipped")]
            tests, failures, errors, skipped = values
            if min(values) < 0 or failures + errors + skipped > tests:
                raise ValueError(f"Invalid JUnit report counts: {path}")
            parts = path.relative_to(root).parts
            build_index = parts.index("build")
            task = ":".join((*parts[:build_index], parts[build_index + 2]))
            counts[task] = [a + b for a, b in zip(counts[task], values)]

    total = [sum(values[index] for values in counts.values()) for index in range(4)]
    lines = ["## Unit test results", "", "| Task | Executed | Failed | Errors | Skipped |",
             "| --- | ---: | ---: | ---: | ---: |"]
    for task, (tests, failures, errors, skipped) in sorted(counts.items()):
        lines.append(f"| `{task}` | {tests - skipped} | {failures} | {errors} | {skipped} |")
    tests, failures, errors, skipped = total
    lines.append(f"| **Total** | **{tests - skipped}** | **{failures}** | **{errors}** | **{skipped}** |")
    summary = "\n".join(lines) + "\n"
    print(summary)
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a") as output:
            output.write(summary)

    app_tests = counts["app:testProdWebsiteDebugUnitTest"]
    if tests - skipped <= 0 or app_tests[0] - app_tests[3] <= 0:
        raise ValueError("Unit tests must execute a non-zero test count, including the app suite")
    if failures or errors:
        raise ValueError(f"Unit tests have {failures} failures and {errors} errors")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root", nargs="?", type=Path, default=Path.cwd())
    args = parser.parse_args()
    try:
        check_results(args.root)
    except (OSError, ValueError, ET.ParseError) as error:
        print(f"Unit test verification failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
