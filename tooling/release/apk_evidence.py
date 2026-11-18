#!/usr/bin/env python3
"""Select an existing source build and the exact published Collie APK to compare."""

import argparse
import json
from pathlib import Path
import sys
from urllib.parse import urlencode

from publication import (
    Failure, GitHub, PACKAGE, Publication, RELEASE_SOURCE_BRANCHES, REPOSITORY, SHA256, positive, release_state,
    read_json, require, version_properties,
)


def build_artifact(github, source, run_id, *, legacy, approved=None, source_branch="main"):
    require(source_branch in RELEASE_SOURCE_BRANCHES, "Unsupported release source branch")
    require(not legacy or source_branch == "main", "Legacy build evidence must come from main")
    if approved:
        require(approved["source_branch"] == source_branch and
                approved["workflow_path"] == ".github/workflows/pr.yml" and
                approved["workflow_id"] == 413670842,
                "Approved CI has a different source branch or workflow")
    require(run_id is None or positive(run_id), "Build run ID must be positive")
    workflow = "build-release.yml" if legacy else "pr.yml"
    query = urlencode({"head_sha": source, "branch": source_branch, "per_page": 100})
    runs = github.api(f"actions/workflows/{workflow}/runs?{query}")["workflow_runs"]
    allowed_events = {"workflow_dispatch"} if legacy else {"push", "workflow_dispatch"}
    candidates = [
        run for run in runs
        if (run_id is None or run["id"] == run_id)
        and run["path"] == f".github/workflows/{workflow}"
        and run["head_sha"] == source and run["head_branch"] == source_branch
        and run["head_repository"]["full_name"] == REPOSITORY
        and run["event"] in allowed_events
    ]
    require(candidates, f"No trusted {workflow} run for {source}")
    run = max(candidates, key=lambda candidate: candidate["id"])
    require(run["status"] == "completed" and run["conclusion"] == "success",
            f"Source build run {run['id']} has not passed")
    if approved:
        require(run["workflow_id"] == approved["workflow_id"], "Approved CI workflow identity changed")
        require(run["run_attempt"] == approved["run_attempt"], "Approved CI attempt changed before artifact selection")
        require(run["event"] == approved["workflow_event"], "Approved CI event changed before artifact selection")

    job_name = f"Build {source} (0)" if legacy else "Unsigned production build"
    jobs = github.api(f"actions/runs/{run['id']}/attempts/{run['run_attempt']}/jobs?per_page=100")["jobs"]
    jobs = [job for job in jobs if job["name"] == job_name and job["head_sha"] == source
            and job["run_id"] == run["id"] and job["conclusion"] == "success"]
    require(len(jobs) == 1, f"Run {run['id']} did not build the exact commit")
    job = jobs[0]

    artifact_name = "unsigned-release-apk" if legacy else f"production-build-{run['id']}"
    artifacts = github.api(f"actions/runs/{run['id']}/artifacts?name={artifact_name}")["artifacts"]
    artifacts = [asset for asset in artifacts if asset["name"] == artifact_name and not asset["expired"]]
    require(len(artifacts) == 1, f"Run {run['id']} needs one unexpired {artifact_name} artifact")
    artifact = artifacts[0]
    if approved:
        require(job.get("run_attempt") == run["run_attempt"], "Build job belongs to a different attempt")
        expected_job = next(entry for entry in approved["jobs"] if entry["name"] == job_name)
        require(job["id"] == expected_job["id"] and job["started_at"] <= artifact["created_at"] <= job["completed_at"],
                "Build artifact does not belong to the approved CI job")
    require(positive(run["id"]) and positive(artifact["id"]), "Invalid build run or artifact ID")
    require(artifact["digest"].startswith("sha256:") and SHA256.fullmatch(artifact["digest"][7:]),
            "Build artifact is missing its SHA-256 digest")
    return run["id"], artifact["id"]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tag")
    parser.add_argument("--scratch", type=Path)
    parser.add_argument("--build-tools", type=Path)
    parser.add_argument("--github-output", type=Path, required=True)
    parser.add_argument("--ci-report", type=Path)
    parser.add_argument("--source-branch", choices=RELEASE_SOURCE_BRANCHES)
    parser.add_argument("--bound-build")
    parser.add_argument("--legacy-build-run", type=int)
    parser.add_argument("--published-sha256")
    args = parser.parse_args()

    try:
        github = GitHub()
        if args.ci_report:
            approved = read_json(args.ci_report)
            source = approved["source_commit"]
            source_branch = approved["source_branch"]
            require(args.source_branch is None or args.source_branch == source_branch,
                    "Requested branch differs from the approved CI report")
            run_id, artifact_id = build_artifact(
                github, source, approved["run_id"], legacy=False, approved=approved, source_branch=source_branch,
            )
            build = {"source_commit": source, "source_branch": source_branch, "run_attempt": approved["run_attempt"],
                     "build_run_id": run_id, "artifact_id": artifact_id}
            with args.github_output.open("a", encoding="utf-8") as output:
                print(f"bound_build={json.dumps(build, separators=(',', ':'))}", file=output)
            return 0

        require(args.tag and args.scratch and args.build_tools, "Verification requires a tag and paths")
        release = Publication(Path(__file__).resolve().parents[2], args.scratch, args.build_tools, github)
        state = release_state(github.release(args.tag))
        require(state["tag_name"] == args.tag and not state["draft"], "Expected a published release for this tag")
        source = release.signed_tag_identity(args.tag)["source_commit"]
        source_branch = args.source_branch or "main"
        if args.bound_build:
            build = json.loads(args.bound_build)
            require(build["source_branch"] == source_branch, "Approved CI differs from the requested source branch")
            require(positive(build["run_attempt"]), "Invalid approved build run attempt")
            require(build["source_commit"] == source, "Approved CI source differs from the signed tag")
            run_id, artifact_id = build["build_run_id"], build["artifact_id"]
            require(positive(run_id) and positive(artifact_id), "Invalid approved build run or artifact ID")
        else:
            require(source_branch == "main", "Maintenance verification requires the approved build binding")
            run_id, artifact_id = build_artifact(
                github, source, args.legacy_build_run, legacy=args.legacy_build_run is not None, source_branch=source_branch,
            )
        canonical = [asset for asset in release.asset_list(state["id"]) if asset["name"] == "collie.apk"]
        require(len(canonical) == 1, "Expected one published collie.apk")
        asset = canonical[0]
        require(not args.published_sha256 or asset["sha256"] == args.published_sha256,
                "Published APK differs from the APK recorded by the release workflow")
        apk = release.downloaded(asset)
        version = version_properties(release.run(["git", "show", f"{source}:gradle.properties"]), args.tag)
        require(release.apk_metadata(apk) == {"package": PACKAGE, **version},
                "Published APK identity differs from the signed source tag")

        with args.github_output.open("a", encoding="utf-8") as output:
            for key, value in {
                "source_commit": source, "build_run_id": run_id, "artifact_id": artifact_id,
                "published_apk": apk,
            }.items():
                print(f"{key}={value}", file=output)
    except (Failure, OSError, KeyError, StopIteration, TypeError, ValueError) as exc:
        print(f"APK evidence failed: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
