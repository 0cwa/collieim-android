#!/usr/bin/env python3
"""Require every selected security-stack JVM class to execute and pass without skips."""

import argparse
from collections import defaultdict
import os
from pathlib import Path
import sys
import xml.etree.ElementTree as ET


EXPECTED_CLASSES = {
    "app:testProdWebsiteDebugUnitTest": {
        "org.thoughtcrime.securesms.backup.MollyBackupPreferencesTest",
        "org.thoughtcrime.securesms.jobs.UnifiedPushRefreshJobTest",
        "org.thoughtcrime.securesms.apkupdate.CollieUpdateIndexTest",
        "org.thoughtcrime.securesms.jobs.ApkUpdateJobTest",
        "org.thoughtcrime.securesms.components.settings.app.updates.AppUpdatesSettingsViewModelTest",
    },
    "feature:app-settings:testDebugUnitTest": {
        "org.signal.appsettings.updates.AppUpdatesSettingsScreenTest",
    },
    "core:util:testDebugUnitTest": {
        "org.signal.core.util.money.FiatMoneyTest",
    },
}


def check_results(root):
    counts = defaultdict(lambda: [0, 0, 0, 0])
    seen = set()
    problems = []
    for task, expected in EXPECTED_CLASSES.items():
        *module, task_name = task.split(":")
        directory = root.joinpath(*module, "build", "test-results", task_name)
        for path in sorted(directory.glob("TEST-*.xml")):
            suite = ET.parse(path).getroot()
            if suite.tag != "testsuite":
                raise ValueError(f"Unexpected JUnit report format: {path}")
            name = suite.get("name")
            if name not in expected:
                problems.append(f"Unexpected test class in {task}: {name}")
            identity = (task, name)
            if identity in seen:
                problems.append(f"Duplicate report for {task}: {name}")
            seen.add(identity)
            values = [int(suite.get(key, "0")) for key in ("tests", "failures", "errors", "skipped")]
            tests, failures, errors, skipped = values
            cases = suite.findall("testcase")
            if min(values) < 0 or failures + errors + skipped > tests or len(cases) != tests:
                problems.append(f"Invalid JUnit counts in {path}")
            if tests <= 0 or not cases:
                problems.append(f"Test class executed no tests: {name}")
            if failures or errors or skipped:
                problems.append(f"{name}: {failures} failures, {errors} errors, {skipped} skipped")
            for case in cases:
                if case.get("classname") != name:
                    problems.append(f"Unexpected testcase class in {path}")
                if any(case.find(tag) is not None for tag in ("failure", "error", "skipped")):
                    problems.append(f"Testcase did not pass: {name}.{case.get('name')}")
            counts[task] = [left + right for left, right in zip(counts[task], values)]

        for name in sorted(expected):
            if (task, name) not in seen:
                problems.append(f"Missing expected test class in {task}: {name}")

    total = [sum(values[index] for values in counts.values()) for index in range(4)]
    lines = ["## Focused security-stack JVM results", "",
             "| Task | Executed | Failed | Errors | Skipped |",
             "| --- | ---: | ---: | ---: | ---: |"]
    for task in sorted(EXPECTED_CLASSES):
        tests, failures, errors, skipped = counts[task]
        lines.append(f"| `{task}` | {tests - skipped} | {failures} | {errors} | {skipped} |")
    tests, failures, errors, skipped = total
    lines.append(f"| **Total** | **{tests - skipped}** | **{failures}** | **{errors}** | **{skipped}** |")
    summary = "\n".join(lines) + "\n"
    print(summary)
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a") as output:
            output.write(summary)

    if problems:
        raise ValueError("\n".join(problems))
    return total


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root", nargs="?", type=Path, default=Path.cwd())
    args = parser.parse_args()
    try:
        check_results(args.root)
    except (OSError, ValueError, ET.ParseError) as error:
        print(f"Security-stack test verification failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
