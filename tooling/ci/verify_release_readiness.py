#!/usr/bin/env python3
"""Require completed CI for the exact release commit before publishing its APK.

This checks GitHub's current run and job records. It does not attest native
source-to-binary provenance or replace the signed-tag/APK checks.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import re
import subprocess
import sys

WORKFLOW_PATH = ".github/workflows/pr.yml"
WORKFLOW_ID = 413670842
MAINTENANCE_BRANCH = "release/stable/8.35"
RELEASE_SOURCE_BRANCHES = ("main", MAINTENANCE_BRANCH)
TRUSTED_CI_PATHS = (
    WORKFLOW_PATH,
    ".github/actions/disk-cleanup",
    ".github/ci-required-tests.json",
    ".github/scripts/check-unit-test-results.py",
    "tooling/ci",
    "tooling/git-hooks",
    "tooling/host-tests",
    "tooling/gradle.properties.docker",
    "tooling/reproducible-builds",
    "build.gradle.kts",
    "settings.gradle.kts",
    "buildSrc",
    "gradlew",
    "gradle/wrapper",
)
REQUIRED_JOBS = ("Unit tests", "Unsigned production build", "Security readiness")


class Failure(Exception):
    pass


def require(condition, message):
    if not condition:
        raise Failure(message)


def api(endpoint, *, paginate=False):
    command = ["gh", "api", "--method", "GET", "-H", "Accept: application/vnd.github+json"]
    if paginate:
        command += ["--paginate", "--slurp"]
    command.append(endpoint)
    result = subprocess.run(command, capture_output=True, text=True, check=False)
    require(result.returncode == 0, "Cannot read CI evidence from GitHub: " + result.stderr.strip())
    try:
        return json.loads(result.stdout)
    except ValueError as exc:
        raise Failure("GitHub returned invalid CI evidence JSON") from exc


def flatten_pages(pages, key):
    require(isinstance(pages, list) and pages, "Missing paginated GitHub response")
    values = []
    totals = set()
    for page in pages:
        require(isinstance(page, dict) and isinstance(page.get(key), list), "Malformed GitHub response page")
        total = page.get("total_count")
        require(type(total) is int and total >= 0, "Missing GitHub result count")
        totals.add(total)
        require(all(isinstance(value, dict) for value in page[key]), "Malformed GitHub response item")
        values.extend(page[key])
    require(len(totals) == 1 and len(values) == next(iter(totals)), "Incomplete or changing GitHub result pages; retry")
    ids = [value.get("id") for value in values]
    require(all(type(value) is int and value > 0 for value in ids), "Invalid GitHub result ID")
    require(len(set(ids)) == len(ids), "Duplicate GitHub result IDs; retry")
    return values


def select_run(runs, repository, sha, branch):
    require(branch in RELEASE_SOURCE_BRANCHES, "Unsupported release source branch")
    eligible = [run for run in runs if
                run.get("head_sha") == sha
                and run.get("path") == WORKFLOW_PATH
                and run.get("workflow_id") == WORKFLOW_ID
                and run.get("event") in ("push", "workflow_dispatch")
                and run.get("head_branch") == branch
                and isinstance(run.get("repository"), dict)
                and run["repository"].get("full_name", "").lower() == repository.lower()
                and isinstance(run.get("head_repository"), dict)
                and run["head_repository"].get("full_name", "").lower() == repository.lower()]
    require(eligible, f"No trusted {WORKFLOW_PATH} run on {branch} exists for release commit {sha}")
    require(all(type(run.get("id")) is int and run["id"] > 0 for run in eligible), "Invalid workflow run ID")
    # An older passing attempt/run must not hide a newer failed or pending one.
    latest = max(eligible, key=lambda run: run["id"])
    require(latest.get("status") == "completed" and latest.get("conclusion") == "success",
            f"Latest CI run {latest['id']} for {sha} has not succeeded")
    require(type(latest.get("run_attempt")) is int and latest["run_attempt"] > 0, "Missing workflow run attempt")
    return latest


def verify_jobs(run, jobs, sha):
    verified = []
    for name in REQUIRED_JOBS:
        matches = [job for job in jobs if job.get("name") == name]
        require(len(matches) == 1, f"Expected exactly one '{name}' job in this run attempt")
        job = matches[0]
        require(job.get("run_id") == run["id"] and job.get("head_sha") == sha,
                f"'{name}' belongs to a different run or source commit")
        require(job.get("run_attempt") == run["run_attempt"],
                f"'{name}' belongs to a different run attempt")
        require(job.get("status") == "completed" and job.get("conclusion") == "success",
                f"'{name}' did not execute successfully; skipped and neutral jobs do not pass")
        verified.append({"id": job["id"], "name": name, "conclusion": job["conclusion"]})
    return verified


def bind_main_policy(repository, sha, trusted_main, reader):
    require(repository.lower() == "0cwa/collieim-android", "Maintenance route requires the owning repository")
    require(isinstance(trusted_main, str) and re.fullmatch(r"[0-9a-f]{40}", trusted_main),
            "Maintenance route requires an immutable trusted main revision")
    prefix = f"repos/{repository}"
    main = reader(f"{prefix}/git/ref/heads/main")["object"]["sha"]
    require(re.fullmatch(r"[0-9a-f]{40}", main), "Invalid main ref identity")
    if main != trusted_main:
        relation = reader(f"{prefix}/compare/{trusted_main}...{main}")
        require(relation.get("status") == "ahead" and relation["merge_base_commit"]["sha"] == trusted_main,
                "Trusted policy revision is outside protected main history")

    trees = {}

    def entries(oid):
        if oid not in trees:
            tree = reader(f"{prefix}/git/trees/{oid}")
            require(tree["sha"] == oid and tree.get("truncated") is False, "Incomplete Git tree for CI inputs")
            trees[oid] = {entry["path"]: entry for entry in tree["tree"]}
        return trees[oid]

    def path_objects(commit):
        identity = reader(f"{prefix}/git/commits/{commit}")
        require(identity["sha"] == commit, "CI input commit identity changed")
        root_tree = identity["tree"]["sha"]
        objects = {}
        for path in TRUSTED_CI_PATHS:
            parts = path.split("/")
            parent = root_tree
            for part in parts[:-1]:
                directory = entries(parent).get(part)
                require(directory and directory["type"] == "tree", f"Missing trusted CI directory for {path}")
                parent = directory["sha"]
            entry = entries(parent).get(parts[-1])
            require(entry and entry["mode"] in ("040000", "100644", "100755"), f"Missing or invalid CI input {path}")
            objects[path] = (entry["mode"], entry["type"], entry["sha"])
        return objects

    policy = path_objects(trusted_main)
    candidate = path_objects(sha)
    for path in TRUSTED_CI_PATHS:
        require(policy[path] == candidate[path], f"Maintenance CI input differs from trusted main: {path}")


def verify(repository, sha, branch="main", reader=api, *, trusted_main=None):
    require(re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository) is not None, "Invalid repository")
    require(re.fullmatch(r"[0-9a-f]{40}", sha) is not None, "Use the exact lowercase release commit SHA")
    require(branch in RELEASE_SOURCE_BRANCHES, "Unsupported release source branch")
    if branch == MAINTENANCE_BRANCH:
        bind_main_policy(repository, sha, trusted_main, reader)
    endpoint = f"repos/{repository}/actions/runs?head_sha={sha}&per_page=100"
    runs = flatten_pages(reader(endpoint, paginate=True), "workflow_runs")
    run = select_run(runs, repository, sha, branch)
    run_id, attempt = run["id"], run["run_attempt"]
    jobs = flatten_pages(reader(f"repos/{repository}/actions/runs/{run_id}/attempts/{attempt}/jobs?per_page=100",
                                paginate=True), "jobs")
    verified_jobs = verify_jobs(run, jobs, sha)
    # A rerun can start while the job pages are being read. Read current state
    # again, including newer runs, before creating a successful evidence record.
    current = select_run(flatten_pages(reader(endpoint, paginate=True), "workflow_runs"), repository, sha, branch)
    require((current["id"], current["run_attempt"], current["event"]) == (run_id, attempt, run["event"]), "CI changed during verification; retry")
    return {"schema": 1, "status": "EXACT_COMMIT_CI_PASSED", "repository": repository,
            "source_commit": sha, "source_branch": branch, "workflow_path": WORKFLOW_PATH,
            "workflow_event": run["event"], "workflow_id": WORKFLOW_ID,
            "trusted_main": trusted_main if branch == MAINTENANCE_BRANCH else None,
            "run_id": run_id, "run_attempt": attempt,
            "run_url": f"https://github.com/{repository}/actions/runs/{run_id}",
            "jobs": verified_jobs,
            "scope": "unit tests and unsigned production build; native reproduction and APK identity require separate evidence"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", required=True)
    parser.add_argument("--sha", required=True)
    parser.add_argument("--source-branch", choices=RELEASE_SOURCE_BRANCHES, default="main")
    parser.add_argument("--trusted-main", help="Immutable main revision executing the release policy")
    parser.add_argument("--report", required=True, type=Path)
    args = parser.parse_args()
    try:
        require(not args.report.exists() and not args.report.is_symlink(), "Use a fresh CI evidence report path")
        evidence = verify(args.repository, args.sha, args.source_branch, trusted_main=args.trusted_main)
        args.report.parent.mkdir(parents=True, exist_ok=True)
        with args.report.open("x", encoding="utf-8") as output:
            json.dump(evidence, output, indent=2, sort_keys=True)
            output.write("\n")
        print(f"Release commit {args.sha} passed CI: {evidence['run_url']}")
    except (Failure, OSError, KeyError, TypeError, ValueError) as exc:
        print(f"Release CI gate failed: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
