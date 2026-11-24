#!/usr/bin/env python3
"""Require completed CI for the exact release commit before publishing its APK.

This checks GitHub's current run and job records. It does not attest native
source-to-binary provenance or replace the signed-tag/APK checks.
"""
from __future__ import annotations

import argparse
import hashlib
import io
import json
from pathlib import Path
import re
import subprocess
import sys
from zipfile import BadZipFile, ZipFile

WORKFLOW_PATH = ".github/workflows/pr.yml"
WORKFLOW_ID = 413670842
MAINTENANCE_BRANCH = "release/stable/8.35"
MAINTENANCE_POLICY = ".github/ci-required-tests-stable-8.35.json"
# The published Classic candidate retains the four-screen WebView evidence
# contract after removing its donation surfaces. Bind that reviewed script
# separately from the six-screen checker on main.
CLASSIC_WEBVIEW_CHECKER = ".github/scripts/check-webview-acceptance.py"
CLASSIC_WEBVIEW_CHECKER_BLOB = "e448152de272f7403b329ad8822c52df4142f7de"
RELEASE_SOURCE_BRANCHES = ("main", MAINTENANCE_BRANCH)
TRUSTED_CI_PATHS = (
    WORKFLOW_PATH,
    ".github/actions/disk-cleanup",
    ".github/ci-required-tests.json",
    MAINTENANCE_POLICY,
    ".github/scripts/check-unit-test-results.py",
    ".github/scripts/check-archive-device-results.py",
    CLASSIC_WEBVIEW_CHECKER,
    "tooling/fastlane/check_metadata.py",
    "tooling/collie-log/stamp.py",
    "tooling/ci",
    "tooling/git-hooks",
    "tooling/host-tests",
    "tooling/gradle.properties.docker",
    "tooling/reproducible-builds",
    "build.gradle.kts",
    "settings.gradle.kts",
    "buildSrc",
    "build-logic",
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


def select_run(runs, repository, sha, branch, confirmed_tag=None):
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
    latest = next((run for run in sorted(eligible, key=lambda run: run["id"], reverse=True)
                   if confirmed_tag is None or run["event"] != "workflow_dispatch" or not confirmed_tag(run)), None)
    require(latest is not None, f"No trusted branch run on {branch} exists for release commit {sha}")
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
        paths = TRUSTED_CI_PATHS
        objects = {}
        for path in paths:
            parts = path.split("/")
            parent = root_tree
            missing = False
            for part in parts[:-1]:
                directory = entries(parent).get(part)
                if not directory or directory["type"] != "tree":
                    if path == "tooling/collie-log/stamp.py":
                        missing = True
                        break
                    require(False, f"Missing trusted CI directory for {path}")
                parent = directory["sha"]
            if missing:
                continue
            entry = entries(parent).get(parts[-1])
            if path == "tooling/collie-log/stamp.py" and not entry:
                continue
            require(entry and entry["mode"] in ("040000", "100644", "100755"), f"Missing or invalid CI input {path}")
            objects[path] = (entry["mode"], entry["type"], entry["sha"])
        return objects

    policy = path_objects(trusted_main)
    candidate = path_objects(sha)
    for path in policy:
        expected = (policy[path][0], "blob", CLASSIC_WEBVIEW_CHECKER_BLOB) if path == CLASSIC_WEBVIEW_CHECKER else policy[path]
        require(expected == candidate.get(path), f"Maintenance CI input differs from trusted main: {path}")
    return policy[MAINTENANCE_POLICY][2]


def read_route_archive(endpoint):
    result = subprocess.run(["gh", "api", "--hostname", "github.com", "--method", "GET", endpoint],
                            capture_output=True, timeout=30, check=False)
    require(result.returncode == 0, "Cannot read the Stable CI route artifact")
    return result.stdout


def unique_object(pairs):
    value = dict(pairs)
    require(len(value) == len(pairs), "Duplicate Stable route record keys")
    return value


def verify_maintenance_route(repository, run, unit_job, policy_blob, reader, archive_reader):
    run_id, attempt = run["id"], run["run_attempt"]
    name = f"stable-ci-route-{run_id}-{attempt}"
    endpoint = f"repos/{repository}/actions/runs/{run_id}/artifacts?name={name}&per_page=100"
    matches = flatten_pages(reader(endpoint, paginate=True), "artifacts")
    require(len(matches) == 1 and matches[0]["name"] == name, "Missing or ambiguous Stable CI route artifact")
    artifact = matches[0]
    require(artifact.get("expired") is False and type(artifact.get("size_in_bytes")) is int and
            0 < artifact["size_in_bytes"] <= 65536, "Invalid Stable CI route artifact")
    require(unit_job["started_at"] <= artifact["created_at"] <= unit_job["completed_at"],
            "Stable CI route artifact does not belong to the exact Unit tests attempt")
    data = archive_reader(f"repos/{repository}/actions/artifacts/{artifact['id']}/zip")
    require(len(data) == artifact["size_in_bytes"] and
            artifact.get("digest") == "sha256:" + hashlib.sha256(data).hexdigest(),
            "Stable CI route artifact digest or size differs")
    with ZipFile(io.BytesIO(data)) as archive:
        members = archive.infolist()
        require(len(members) == 1 and members[0].filename == "stable-source-route.json" and
                members[0].file_size <= 4096, "Unexpected Stable CI route archive")
        route = json.loads(archive.read(members[0]), object_pairs_hook=unique_object)
    require(route == {
        "schema": 1, "source_commit": run["head_sha"], "source_ref": f"refs/heads/{MAINTENANCE_BRANCH}",
        "event": run["event"], "run_id": run_id, "run_attempt": attempt,
        "policy_path": MAINTENANCE_POLICY, "policy_blob": policy_blob,
    }, "Stable CI route does not match the exact branch ref, run attempt and test policy")
    return route


def is_confirmed_tag_dispatch(run, jobs):
    markers = [job for job in jobs if job.get("name") == "Stable tag ref marker"]
    if len(markers) != 1:
        return False
    job = markers[0]
    return (job.get("head_sha") == run["head_sha"] and job.get("run_id") == run["id"] and
            job.get("run_attempt") == run["run_attempt"] and job.get("status") == "completed" and
            job.get("conclusion") == "success")


def verify(repository, sha, branch="main", reader=api, *, trusted_main=None, archive_reader=read_route_archive):
    require(re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository) is not None, "Invalid repository")
    require(re.fullmatch(r"[0-9a-f]{40}", sha) is not None, "Use the exact lowercase release commit SHA")
    require(branch in RELEASE_SOURCE_BRANCHES, "Unsupported release source branch")
    policy_blob = bind_main_policy(repository, sha, trusted_main, reader) if branch == MAINTENANCE_BRANCH else None
    endpoint = f"repos/{repository}/actions/runs?head_sha={sha}&per_page=100"
    attempts = {}

    def read_attempt(run):
        run_id, attempt = run["id"], run["run_attempt"]
        key = (run_id, attempt)
        if key not in attempts:
            attempts[key] = flatten_pages(reader(f"repos/{repository}/actions/runs/{run_id}/attempts/{attempt}/jobs?per_page=100",
                                                 paginate=True), "jobs")
        return attempts[key]

    def confirmed_tag(run):
        # An unclassified pending, cancelled or failed dispatch does not justify a fallback.
        return (run.get("status") == "completed" and type(run.get("run_attempt")) is int and
                run["run_attempt"] > 0 and is_confirmed_tag_dispatch(run, read_attempt(run)))

    reject_tag_dispatch = confirmed_tag if branch == MAINTENANCE_BRANCH else None

    def latest_run():
        runs = flatten_pages(reader(endpoint, paginate=True), "workflow_runs")
        return select_run(runs, repository, sha, branch, reject_tag_dispatch)

    run = latest_run()
    run_id, attempt = run["id"], run["run_attempt"]
    jobs = read_attempt(run)
    verified_jobs = verify_jobs(run, jobs, sha)
    route = None
    if policy_blob is not None:
        unit_job = next(job for job in jobs if job["name"] == "Unit tests")
        route = verify_maintenance_route(repository, run, unit_job, policy_blob, reader, archive_reader)
    # A rerun can start while the job pages are being read. Read current state
    # again, including newer runs, before creating a successful evidence record.
    current = latest_run()
    require((current["id"], current["run_attempt"], current["event"]) == (run_id, attempt, run["event"]), "CI changed during verification; retry")
    return {"schema": 1, "status": "EXACT_COMMIT_CI_PASSED", "repository": repository,
            "source_commit": sha, "source_branch": branch, "workflow_path": WORKFLOW_PATH,
            "workflow_event": run["event"], "workflow_id": WORKFLOW_ID,
            "trusted_main": trusted_main if branch == MAINTENANCE_BRANCH else None,
            **({"stable_route": route} if route is not None else {}),
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
    except (Failure, OSError, KeyError, TypeError, ValueError, BadZipFile, subprocess.TimeoutExpired) as exc:
        print(f"Release CI gate failed: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
