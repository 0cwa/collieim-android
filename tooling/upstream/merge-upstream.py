#!/usr/bin/env python3
"""Start a Git merge of a Signal tag or Molly branch, keeping reviewed donation deletions.

The merge stops before committing. Resolve what it lists, then run `git commit`:
the prepared message carries the `Layer: upgrade` and `Upstream:` trailers that
tooling/git-hooks/collie_check_layer_trailers.py verifies.
"""

import argparse
import json
import os
from pathlib import Path
import re
import subprocess


MANIFEST = Path("tooling/upstream/signal-donation-removals.json")
LITERAL_PATH = re.compile(r"[A-Za-z0-9_.+@$/-]+")
UPSTREAM_REF = re.compile(r"[A-Za-z0-9][A-Za-z0-9._/-]*")
FULL_SHA = re.compile(r"[0-9a-f]{40}")
UPSTREAMS = {
    "signal": ("signalapp/Signal-Android", "https://github.com/signalapp/Signal-Android.git", "Signal"),
    "molly": ("mollyim/mollyim-android", "https://github.com/mollyim/mollyim-android.git", "Molly"),
}
CONFLICTS = {
    (1, 2, 3): "both modified", (1, 2): "deleted by upstream", (1, 3): "deleted by Collie",
    (2, 3): "both added", (2,): "added by Collie", (3,): "added by upstream",
}


def git(root, *args):
    return subprocess.run(["git", *args], cwd=root, stdout=subprocess.PIPE, check=True).stdout


def git_path(root, name):
    return Path(os.fsdecode(git(root, "rev-parse", "--path-format=absolute", "--git-path", name).strip()))


def paths(root, *args):
    return {os.fsdecode(path) for path in git(root, *args, "-z").split(b"\0")[:-1]}


def removal_paths(root):
    document = json.loads((root / MANIFEST).read_text(encoding="utf-8"))
    if document["schema"] != 1:
        raise ValueError("unknown Signal donation removal manifest schema")
    paths = set()
    for entry in document["removed"]:
        name, reason = entry["path"], entry["reason"]
        if (
            not LITERAL_PATH.fullmatch(name)
            or "/" not in name
            or any(part in {"", ".", "..", ".git"} for part in name.split("/"))
            or name in paths
            or not reason.strip()
        ):
            raise ValueError(f"invalid or duplicate removal path: {name!r}")
        paths.add(name)
    if not paths:
        raise ValueError("empty Signal donation removal manifest")
    return paths


def unmerged(root):
    """Map each unmerged path to the index stages it has."""
    stages = {}
    for record in git(root, "ls-files", "-u", "-z").split(b"\0")[:-1]:
        info, _, name = record.partition(b"\t")
        stages.setdefault(os.fsdecode(name), set()).add(int(info.split()[2]))
    return stages


def fetch(root, upstream, name, url):
    if upstream == "signal":
        local = f"refs/tags/signal-{name}"
        # No forced update: a local tag that differs from Signal's stops here.
        spec = f"refs/tags/{name}:{local}"
    else:
        local = f"refs/remotes/molly/{name}"
        spec = f"+refs/heads/{name}:{local}"
    if url is not None:
        subprocess.run(["git", "fetch", "--no-tags", "--end-of-options", url, spec], cwd=root, check=True)
    return local


def merge(root, upstream, name, url, join, expected=None):
    repository, default_url, label = UPSTREAMS[upstream]
    if (not UPSTREAM_REF.fullmatch(name) or (upstream == "signal" and "/" in name)
            or subprocess.run(["git", "check-ref-format", f"refs/x/{name}"], cwd=root).returncode):
        raise ValueError(f"invalid {label} {'tag' if upstream == 'signal' else 'branch'}: {name!r}")
    if expected is not None and (not FULL_SHA.fullmatch(expected) or expected == "0" * 40):
        raise ValueError(f"--expected-commit must be a full, lowercase, nonzero 40-hex commit SHA: {expected!r}")
    if git(root, "status", "--porcelain=v1", "-z", "--untracked-files=all"):
        raise ValueError("start the upstream merge on a clean topic branch")
    if git_path(root, "MERGE_HEAD").exists():
        raise ValueError("a merge is already in progress")
    removed = removal_paths(root)
    local = fetch(root, upstream, name, default_url if url == "" else url)
    commit = git(root, "rev-parse", "--verify", "--end-of-options", f"{local}^{{commit}}").decode().strip()
    if expected is not None and commit != expected:
        # Fetching moved only the local ref; the index and worktree are untouched.
        raise ValueError(f"{label} {name} resolves to {commit}, not the expected {expected}; nothing was merged")
    trailer = f"{repository} {name}" if upstream == "signal" else f"{repository} {name}@{commit}"
    strategy = ["--strategy=ours"] if join else []
    # A conflicted merge exits non-zero but still leaves MERGE_HEAD to finish.
    subprocess.run(["git", "merge", "--no-commit", "--no-ff", *strategy, commit], cwd=root)
    if not git_path(root, "MERGE_HEAD").exists():
        raise ValueError(f"git merge did not start; is {label} {name} already merged?")

    # Collie deleted the reviewed donation files; upstream edits to them do not
    # bring them back. Other conflicts, including renames, stay for review.
    kept = sorted(path for path, stages in unmerged(root).items() if stages == {1, 3} and path in removed)
    if kept:
        git(root, "rm", "-q", "-f", "--", *(f":(top,literal){path}" for path in kept))
    readded = sorted(removed & paths(root, "ls-files") - paths(root, "ls-tree", "-r", "--name-only", "HEAD"))
    conflicts = unmerged(root)

    body = []
    if kept:
        body.append(f"Kept {len(kept)} files deleted by the Signal donation removal.")
    if readded:
        body.append("Upstream adds files on the donation removal list; review them:")
        body.extend(f"- {path}" for path in readded)
    if conflicts:
        body.append("Conflicts at merge time:")
        body.extend(f"- {path} ({CONFLICTS.get(tuple(sorted(stages)), 'unmerged')})"
                    for path, stages in sorted(conflicts.items()))
    message = f"Merge {label} {name}\n\n"
    if body:
        message += "\n".join(body) + "\n\n"
    message += f"Layer: upgrade\nUpstream: {trailer}\n"
    git_path(root, "MERGE_MSG").write_text(message, encoding="utf-8")

    print(f"Merging {label} {name} ({commit}); kept {len(kept)} donation files deleted.")
    for path in readded:
        print(f"Review: upstream adds {path}, which is on the donation removal list.")
    if conflicts:
        print(f"Resolve {len(conflicts)} conflicts, then run git commit; the message is prepared.")
    else:
        print("No conflicts remain. Review the merge, then run git commit; the message is prepared.")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("upstream", choices=sorted(UPSTREAMS))
    parser.add_argument("name", help="Signal tag (for example v8.36.3) or Molly branch")
    parser.add_argument("--url", default="", help="fetch from this repository instead of upstream")
    parser.add_argument("--no-fetch", dest="url", action="store_const", const=None,
                        help="use the ref fetched earlier (refs/tags/signal-<tag> or refs/remotes/molly/<branch>)")
    parser.add_argument("--join", action="store_true",
                        help="record the upstream commit without taking its changes (-s ours), "
                             "for a release Collie already ported")
    parser.add_argument("--expected-commit", metavar="SHA",
                        help="stop before merging unless the resolved upstream commit (a Signal tag's peeled "
                             "target) is this full 40-hex SHA")
    args = parser.parse_args()
    # Remove the record terminator only, preserving the actual filesystem name.
    root = Path(os.fsdecode(git(Path.cwd(), "rev-parse", "--show-toplevel").removesuffix(b"\n")))
    try:
        merge(root, args.upstream, args.name, args.url, args.join, args.expected_commit)
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        parser.exit(1, f"Upstream merge stopped: {error}\n")


if __name__ == "__main__":
    main()
