#!/usr/bin/env python3
"""Collie Layer trailer hooks: use Git's parser locally and in CI."""

import argparse
import os
from pathlib import Path
import re
import subprocess
import sys
import tomllib


LAYER = re.compile(r"[a-z0-9][a-z0-9-]*")
BASE = "refs/remotes/origin/main"
CATALOG = "tooling/layers.toml"
KINDS = {"feature", "infra", "upstream"}


def git(*args, **kwargs):
    return subprocess.check_output(
        ["git", *args], text=True, encoding="utf-8", errors="replace", **kwargs
    )


class Catalog:
    """The layer names in tooling/layers.toml, or None where a tree predates it."""

    def __init__(self, text):
        data = tomllib.loads(text)
        self.layers = data.get("layers", {})
        self.one_feature_from = data.get("rules", {}).get("one_feature_per_pr_from")
        for name, entry in self.layers.items():
            if not LAYER.fullmatch(name):
                raise ValueError(f"{CATALOG}: invalid layer name {name!r}")
            if "alias_of" in entry:
                target = self.layers.get(entry["alias_of"])
                if target is None or "alias_of" in target:
                    raise ValueError(f"{CATALOG}: {name} is an alias of an unknown layer")
            elif entry.get("kind") not in KINDS or not entry.get("about"):
                raise ValueError(f"{CATALOG}: {name} needs a kind ({', '.join(sorted(KINDS))}) and about")

    def problem(self, layer):
        entry = self.layers.get(layer)
        if entry is None:
            return (f"layer '{layer}' is not in {CATALOG}. Use an existing layer, or add "
                    "an entry for a new feature in the same PR.")
        if "alias_of" in entry:
            return f"layer '{layer}' is an old spelling; use 'Layer: {entry['alias_of']}'."
        return None

    def kind(self, layer):
        return self.layers.get(layer, {}).get("kind")


def catalog_at(commit):
    spec = f"{commit}:{CATALOG}"
    if subprocess.run(["git", "cat-file", "-e", spec], stderr=subprocess.DEVNULL).returncode:
        return None
    return Catalog(git("show", spec))


def catalog_in_worktree():
    path = Path(git("rev-parse", "--show-toplevel").strip()) / CATALOG
    return Catalog(path.read_text(encoding="utf-8")) if path.is_file() else None


def check_message(message, label, catalog=None):
    """Return the commit's layer name, or None after printing why it is invalid."""
    trailers = git("interpret-trailers", "--parse", input=message)
    layers = [
        value.strip()
        for line in trailers.splitlines()
        for key, separator, value in [line.partition(":")]
        if separator and key.lower() == "layer"
    ]
    if len(layers) == 1 and LAYER.fullmatch(layers[0]):
        problem = catalog and catalog.problem(layers[0])
        if not problem:
            return layers[0]
        print(f"{label}: {problem}", file=sys.stderr)
        return None
    print(
        f"{label}: needs exactly one 'Layer: <feature>' trailer "
        "(lowercase letters, digits and hyphens).\n"
        "Keep trailers together in the final paragraph, with no blank line "
        "between Layer and Co-authored-by. For example:\n\n"
        "Layer: website\n"
        "Co-authored-by: Name <name@example.com>\n",
        file=sys.stderr,
    )
    return None


def check_commit_message(path):
    # git merge passes MERGE_MSG before MERGE_HEAD necessarily exists;
    # git commit completing a stopped merge passes COMMIT_EDITMSG instead.
    merge_head = Path(git("rev-parse", "--git-path", "MERGE_HEAD").strip())
    merge_message = Path(git("rev-parse", "--git-path", "MERGE_MSG").strip())
    if merge_head.exists() or path.resolve() == merge_message.resolve():
        return True
    message = path.read_text(encoding="utf-8", errors="replace")
    return check_message(message, str(path), catalog_in_worktree()) is not None


def resolve_commit(ref):
    return git("rev-parse", "--verify", "--end-of-options", f"{ref}^{{commit}}").strip()


def check_range(base, head, pr=None):
    head = resolve_commit(head)
    catalog = catalog_at(head)  # The PR's own catalog, so a new feature adds its entry.
    commits = git("rev-list", "--no-merges", f"{resolve_commit(base)}..{head}").splitlines()
    valid = True
    layers = set()
    for commit in commits:
        message = git("show", "-s", "--format=%B", commit)
        label = git("show", "-s", "--format=%h %s", commit).strip()
        layer = check_message(message, label, catalog)
        if layer is None:
            valid = False
        else:
            layers.add(layer)
    features = sorted(layer for layer in layers if catalog and catalog.kind(layer) == "feature")
    if len(features) > 1:
        enforced = (pr is not None and catalog.one_feature_from is not None
                    and pr >= catalog.one_feature_from)
        print(
            f"{'Error' if enforced else 'Warning'}: this range changes {len(features)} feature "
            f"layers ({', '.join(features)}). A PR changes one feature; open one PR per "
            "feature. Tests and docs for a feature use that feature's layer.",
            file=sys.stderr,
        )
        valid = valid and not enforced
    if valid:
        print(f"Layer trailers: checked {len(commits)} non-merge commits"
              + (f"; feature layer: {', '.join(features)}." if features else "."))
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
            # CI passes the PR number in the environment: an older trusted base
            # checker ignores it instead of rejecting an unknown option.
            pr = os.environ.get("COLLIE_PR_NUMBER")
            valid = check_range(args.base, args.head, int(pr) if pr else None)
        else:
            valid = check_push()
    except (OSError, ValueError, tomllib.TOMLDecodeError, subprocess.CalledProcessError) as error:
        print(f"Cannot check Layer trailers: {error}", file=sys.stderr)
        if args.command == "pre-push":
            print(f"Fetch the PR base first: git fetch origin main ({BASE}).", file=sys.stderr)
        return 1
    return 0 if valid else 1


if __name__ == "__main__":
    sys.exit(main())
