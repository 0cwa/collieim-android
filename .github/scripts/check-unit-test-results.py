#!/usr/bin/env python3
"""Require the configured unit-test tasks and executed security regression suites."""

import argparse
from collections import defaultdict
import json
import os
from pathlib import Path
import sys
import xml.etree.ElementTree as ET


FIELDS = ("tests", "failures", "errors", "skipped")


def load_policy(path):
    policy = json.loads(path.read_text())
    if policy.get("schema") != 1:
        raise ValueError("Unsupported unit-test policy schema")
    tasks = policy.get("required_tasks")
    if not isinstance(tasks, list) or not tasks or len(set(tasks)) != len(tasks):
        raise ValueError("Unit-test policy must name distinct required tasks")
    if any(not isinstance(task, str) or not task for task in tasks):
        raise ValueError("Invalid required task")
    suites = policy.get("security_suites")
    if not isinstance(suites, list) or not suites:
        raise ValueError("Unit-test policy must name security suites")
    seen = set()
    for suite in suites:
        key = (suite.get("task"), suite.get("class"))
        minimum = suite.get("min_executed")
        if key in seen or key[0] not in tasks or not isinstance(key[1], str) or not key[1]:
            raise ValueError("Invalid or duplicate security suite")
        if type(minimum) is not int or minimum < 1:
            raise ValueError("Security suite minima must be positive integers")
        seen.add(key)
    return policy


def check_results(root, policy):
    counts = defaultdict(lambda: [0, 0, 0, 0])
    suites = {}
    skipped_cases = []
    for group in ("app", "core", "feature", "lib", "tooling"):
        for path in sorted((root / group).glob("**/build/test-results/**/TEST-*.xml")):
            suite = ET.parse(path).getroot()
            if suite.tag != "testsuite":
                raise ValueError(f"Unexpected JUnit report format: {path}")
            values = [int(suite.attrib[name]) for name in FIELDS]
            tests, failures, errors, skipped = values
            if min(values) < 0 or failures + errors + skipped > tests:
                raise ValueError(f"Invalid JUnit report counts: {path}")
            cases = suite.findall("testcase")
            actual = [len(cases), *[sum(case.find(kind) is not None for case in cases)
                                    for kind in ("failure", "error", "skipped")]]
            if values != actual:
                raise ValueError(f"JUnit totals do not match testcase results: {path}")
            parts = path.relative_to(root).parts
            build_index = parts.index("build")
            task = ":".join((*parts[:build_index], parts[build_index + 2]))
            key = (task, suite.attrib["name"])
            if key in suites:
                raise ValueError(f"Duplicate JUnit suite: {task} {key[1]}")
            suites[key] = values
            counts[task] = [a + b for a, b in zip(counts[task], values)]
            skipped_cases.extend({"task": task, "class": key[1], "name": case.get("name", "")}
                                 for case in cases if case.find("skipped") is not None)

    total = [sum(values[index] for values in counts.values()) for index in range(4)]
    lines = ["## Unit test results", "", "| Task | Executed | Failed | Errors | Skipped |",
             "| --- | ---: | ---: | ---: | ---: |"]
    for task, (tests, failures, errors, skipped) in sorted(counts.items()):
        lines.append(f"| `{task}` | {tests - skipped} | {failures} | {errors} | {skipped} |")
    tests, failures, errors, skipped = total
    lines.append(f"| **Total** | **{tests - skipped}** | **{failures}** | **{errors}** | **{skipped}** |")
    problems = []
    for task in policy["required_tasks"]:
        values = counts.get(task, [0, 0, 0, 0])
        if values[0] - values[3] <= 0:
            problems.append(f"Required task has no executed tests: {task}")
    lines.extend(["", "## Security regression coverage", "",
                  "| Suite | Executed | Required minimum | Skipped |",
                  "| --- | ---: | ---: | ---: |"])
    for requirement in policy["security_suites"]:
        key = (requirement["task"], requirement["class"])
        values = suites.get(key, [0, 0, 0, 0])
        executed = values[0] - values[3]
        minimum = requirement["min_executed"]
        lines.append(f"| `{key[1]}` | {executed} | {minimum} | {values[3]} |")
        if executed < minimum or values[3]:
            problems.append(f"Security suite {key[1]} needs at least {minimum} executed tests and zero skips")
    if failures or errors:
        problems.append(f"Unit tests have {failures} failures and {errors} errors")
    if skipped_cases:
        lines.extend(["", "Skipped tests outside the required security suites remain visible in the retained JSON.",
                      "They are not counted as passing tests."])
    if problems:
        lines.extend(["", "## Gate failures", "", *[f"- {problem}" for problem in problems]])
    summary = "\n".join(lines) + "\n"
    print(summary)
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a") as output:
            output.write(summary)

    return {"schema": 1, "passed": not problems, "problems": problems,
            "tasks": {task: dict(zip(FIELDS, values)) for task, values in sorted(counts.items())},
            "total": dict(zip(FIELDS, total)), "skipped_cases": skipped_cases}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root", nargs="?", type=Path, default=Path.cwd())
    parser.add_argument("--policy", type=Path)
    parser.add_argument("--json-output", type=Path)
    args = parser.parse_args()
    try:
        policy = load_policy(args.policy or args.root / ".github/ci-required-tests.json")
        result = check_results(args.root, policy)
        if args.json_output:
            args.json_output.parent.mkdir(parents=True, exist_ok=True)
            args.json_output.write_text(json.dumps(result, indent=2, sort_keys=True) + "\n")
        if not result["passed"]:
            return 1
    except (OSError, KeyError, TypeError, ValueError, ET.ParseError) as error:
        print(f"Unit test verification failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
