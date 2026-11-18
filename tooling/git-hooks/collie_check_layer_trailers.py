#!/usr/bin/env python3
"""Collie Layer trailer hooks: use Git's parser locally and in CI."""

import argparse
from pathlib import Path
import re
import subprocess
import sys


LAYER = re.compile(r"[a-z0-9][a-z0-9-]*")
BASE = "refs/remotes/origin/main"


def git(*args, **kwargs):
    return subprocess.check_output(
        ["git", *args], text=True, encoding="utf-8", errors="replace", **kwargs
    )


def check_message(message, label):
    trailers = git("interpret-trailers", "--parse", input=message)
    layers = [
        value.strip()
        for line in trailers.splitlines()
        for key, separator, value in [line.partition(":")]
        if separator and key.lower() == "layer"
    ]
    if len(layers) == 1 and LAYER.fullmatch(layers[0]):
        return True
    print(
        f"{label}: needs exactly one 'Layer: <feature>' trailer "
        "(lowercase letters, digits and hyphens).\n"
        "Keep trailers together in the final paragraph, with no blank line "
        "between Layer and Co-authored-by. For example:\n\n"
        "Layer: website\n"
        "Co-authored-by: Name <name@example.com>\n",
        file=sys.stderr,
    )
    return False


def check_commit_message(path):
    # git merge passes MERGE_MSG before MERGE_HEAD necessarily exists;
    # git commit completing a stopped merge passes COMMIT_EDITMSG instead.
    merge_head = Path(git("rev-parse", "--git-path", "MERGE_HEAD").strip())
    merge_message = Path(git("rev-parse", "--git-path", "MERGE_MSG").strip())
    if merge_head.exists() or path.resolve() == merge_message.resolve():
        return True
    return check_message(path.read_text(encoding="utf-8", errors="replace"), str(path))


def resolve_commit(ref):
    return git("rev-parse", "--verify", "--end-of-options", f"{ref}^{{commit}}").strip()


def check_range(base, head):
    commits = git(
        "rev-list", "--no-merges", f"{resolve_commit(base)}..{resolve_commit(head)}"
    ).splitlines()
    valid = True
    for commit in commits:
        message = git("show", "-s", "--format=%B", commit)
        label = git("show", "-s", "--format=%h %s", commit).strip()
        if not check_message(message, label):
            valid = False
    if valid:
        print(f"Layer trailers: checked {len(commits)} non-merge commits.")
    return valid


def check_push():
    valid = True
    for line in sys.stdin:
        _local_ref, local_oid, remote_ref, _remote_oid = line.split()
        if set(local_oid) == {"0"} or not remote_ref.startswith("refs/heads/"):
            continue  # Branch deletion or a tag, neither of which opens an app PR.
        # Check the whole PR range, including bad commits already on the remote
        # branch. Checking only remote_oid..local_oid would miss those commits.
        if not check_range(BASE, local_oid):
            valid = False
    return valid


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    message = commands.add_parser("message", help="check a pending commit message")
    message.add_argument("path", type=Path)
    revisions = commands.add_parser("range", help="check every non-merge commit in base..head")
    revisions.add_argument("base")
    revisions.add_argument("head", nargs="?", default="HEAD")
    commands.add_parser("pre-push", help="check branch updates from Git's pre-push stdin")
    args = parser.parse_args()
    try:
        if args.command == "message":
            valid = check_commit_message(args.path)
        elif args.command == "range":
            valid = check_range(args.base, args.head)
        else:
            valid = check_push()
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        print(f"Cannot check Layer trailers: {error}", file=sys.stderr)
        if args.command == "pre-push":
            print(f"Fetch the PR base first: git fetch origin main ({BASE}).", file=sys.stderr)
        return 1
    return 0 if valid else 1


if __name__ == "__main__":
    sys.exit(main())
