#!/usr/bin/env python3
"""Apply a Signal tag diff while preserving reviewed donation deletions."""

import argparse
import json
import os
from pathlib import Path
import re
import subprocess


MANIFEST = Path("tooling/upstream/signal-donation-removals.json")
LITERAL_PATH = re.compile(r"[A-Za-z0-9_.+@$/-]+")


def git(root, *args, data=None):
    return subprocess.run(["git", *args], cwd=root, input=data, stdout=subprocess.PIPE, check=True).stdout


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


def verify_removed_path_changes(root, old, new, removed):
    fields = iter(git(root, "diff", "--no-relative", "--name-status", "-z", "-M", "-l0", old, new, "--").split(b"\0")[:-1])
    for status, first in zip(fields, fields, strict=True):
        names = [os.fsdecode(first)]
        if status.startswith((b"R", b"C")):
            names.append(os.fsdecode(next(fields)))
        for name in names:
            overlaps = any(name.startswith(f"{path}/") or path.startswith(f"{name}/") for path in removed)
            if overlaps or (name in removed and status not in (b"M", b"D")):
                raise ValueError(f"upstream {os.fsdecode(status)} overlaps reviewed removal path {name!r}; review the removal list")


def apply_upgrade(root, old, new):
    if git(root, "status", "--porcelain=v1", "-z", "--untracked-files=all"):
        raise ValueError("start the upstream upgrade on a clean topic branch")
    if git(root, "ls-files", "-u", "-z"):
        raise ValueError("unmerged paths already exist")
    removed = removal_paths(root)
    old, new = (
        git(root, "rev-parse", "--verify", "--end-of-options", f"{ref}^{{commit}}").decode().strip()
        for ref in (old, new)
    )
    verify_removed_path_changes(root, old, new, removed)
    patch = git(root, "diff", "--no-relative", "--no-ext-diff", "--no-textconv", "--binary", "--full-index", "-M", "-l0", old, new, "--")
    if patch:
        # Exclude only the literal reviewed paths. Git cannot apply a patch to a
        # path already absent from the index even when the incoming change is a
        # modification. Renames, additions and type changes require review above.
        excludes = [f"--exclude={path}" for path in sorted(removed)]
        subprocess.run(["git", "apply", "--3way", "--index", *excludes], cwd=root, input=patch, check=True)
    if git(root, "ls-files", "-u", "-z"):
        raise ValueError("unlisted upstream conflicts remain; resolve them before copying any further changes")
    pathspecs = [f":(top,literal){path}" for path in sorted(removed)]
    subprocess.run(["git", "rm", "--ignore-unmatch", "--", *pathspecs], cwd=root, check=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("old", help="resolved previous Signal tag")
    parser.add_argument("new", help="resolved target Signal tag")
    args = parser.parse_args()
    # Remove the record terminator only, preserving the actual filesystem name.
    root = Path(os.fsdecode(git(Path.cwd(), "rev-parse", "--show-toplevel").removesuffix(b"\n")))
    try:
        apply_upgrade(root, args.old, args.new)
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        parser.exit(1, f"Signal port stopped: {error}\n")


if __name__ == "__main__":
    main()
