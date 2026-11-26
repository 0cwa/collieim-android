#!/usr/bin/env python3
"""Show Collie's current tree differences by feature using tooling/features.toml."""

import argparse
from dataclasses import asdict, dataclass
from fnmatch import fnmatchcase
from graphlib import CycleError, TopologicalSorter
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tomllib


MOLLY = "mollyim/mollyim-android"
MAP_PATH = Path("tooling/features.toml")
RESERVED_OWNERS = ("shared", "unmapped", "total")


@dataclass(frozen=True)
class Feature:
    about: str
    paths: tuple[str, ...]
    shared_paths: tuple[str, ...]
    depends_on: tuple[str, ...]


@dataclass(frozen=True)
class Change:
    path: str
    status: str
    origin: str
    owner: str
    shared_with: tuple[str, ...]
    added: int | None
    deleted: int | None


def git(*args):
    result = subprocess.run(["git", *args], check=True, stdout=subprocess.PIPE,
                            stderr=subprocess.PIPE)
    return result.stdout


def resolve_commit(revision):
    return git("rev-parse", "--verify", "--end-of-options", f"{revision}^{{commit}}").decode().strip()


def molly_merge(head):
    # Molly upgrades can be merged through a PR, off the first-parent chain.
    # Check the pinned parent locally because upstream branches can disappear.
    merges = git(
        "log", "--merges", "--date-order", f"--grep=^Upstream: {MOLLY} ",
        "--format=%H%x00%P%x00%(trailers:key=Layer,valueonly)%x00"
        "%(trailers:key=Upstream,valueonly)", "-z", head,
    ).decode("utf-8", errors="replace").split("\0")
    for oid, parents, layers, upstream in zip(merges[0::4], merges[1::4], merges[2::4], merges[3::4]):
        parents = parents.split()
        if len(parents) != 2 or layers.splitlines() != ["upgrade"]:
            continue
        match = re.fullmatch(
            rf"{re.escape(MOLLY)} ([^\s@]+)@{parents[1]}\n?", upstream,
        )
        if not match:
            continue
        try:
            git("check-ref-format", f"refs/heads/{match[1]}")
            git("verify-commit", oid)
        except subprocess.CalledProcessError:
            continue
        return oid, parents[1]
    return None


def string_list(table, key, context, *, required=False):
    values = table.get(key, [] if not required else None)
    if not isinstance(values, list) or any(not isinstance(value, str) or not value.strip() for value in values):
        raise ValueError(f"{context}.{key} must be an array of non-empty strings")
    if len(set(values)) != len(values):
        raise ValueError(f"{context}.{key} contains a duplicate entry")
    return tuple(values)


def path_list(table, key, context, *, required=False):
    patterns = string_list(table, key, context, required=required)
    for pattern in patterns:
        parts = pattern.split("/")
        if any(part in ("", ".", "..") for part in parts) or "\0" in pattern:
            raise ValueError(f"{context}.{key} needs repository-relative paths: {pattern!r}")
        if any("**" in part and part != "**" for part in parts):
            raise ValueError(f"{context}.{key} needs '**' as a whole path component: {pattern!r}")
    return patterns


def load_map(path):
    with path.open("rb") as stream:
        catalog = tomllib.load(stream)
    if catalog.keys() - {"shared", "features"}:
        raise ValueError("expected only [shared] and [features] tables")
    shared = catalog.get("shared", {})
    entries = catalog.get("features")
    if not isinstance(shared, dict) or shared.keys() - {"paths"}:
        raise ValueError("[shared] accepts only a paths array")
    if not isinstance(entries, dict) or not entries:
        raise ValueError("expected at least one [features.<id>] table")

    features = {}
    declared = {}
    for name, table in entries.items():
        context = f"features.{name}"
        if name in RESERVED_OWNERS or not re.fullmatch(r"[a-z0-9][a-z0-9-]*", name):
            raise ValueError(f"invalid feature ID: {name!r}")
        if not isinstance(table, dict) or table.keys() - {"about", "paths", "shared_paths", "depends_on"}:
            raise ValueError(f"{context} must be a table with about, paths, and optional shared_paths and depends_on")
        about = table.get("about")
        if not isinstance(about, str) or not about.strip():
            raise ValueError(f"{context}.about must be a non-empty string")
        paths = path_list(table, "paths", context, required=True)
        for pattern in paths:
            if pattern in declared:
                raise ValueError(f"duplicate exclusive ownership of {pattern!r}: {declared[pattern]}, {name}")
            declared[pattern] = name
        features[name] = Feature(
            about, paths, path_list(table, "shared_paths", context),
            string_list(table, "depends_on", context),
        )

    graph = {name: feature.depends_on for name, feature in features.items()}
    for name, dependencies in graph.items():
        unknown = sorted(set(dependencies) - features.keys())
        if unknown:
            raise ValueError(f"{name} depends on unknown features: {', '.join(unknown)}")
    try:
        TopologicalSorter(graph).prepare()
    except CycleError as exc:
        raise ValueError(f"dependency cycle: {' -> '.join(exc.args[1])}") from exc
    return path_list(shared, "paths", "shared"), features


def matches(parts, pattern):
    """Match one path component at a time, with ** matching zero or more."""
    for i, segment in enumerate(pattern):
        if segment == "**":
            if i == len(pattern) - 1:
                return True
            return any(matches(parts[start:], pattern[i + 1:]) for start in range(i, len(parts) + 1))
        if i >= len(parts) or not fnmatchcase(parts[i], segment):
            return False
    return len(parts) == len(pattern)


def classify(paths, shared, features):
    exclusive_patterns = [(name, pattern.split("/")) for name, feature in features.items()
                          for pattern in feature.paths]
    shared_patterns = [(name, pattern.split("/")) for name, feature in features.items()
                       for pattern in feature.shared_paths]
    global_patterns = [pattern.split("/") for pattern in shared]
    for path in paths:
        parts = path.split("/")
        exclusive = {name for name, pattern in exclusive_patterns if matches(parts, pattern)}
        if len(exclusive) > 1:
            raise ValueError(f"duplicate exclusive ownership of {path!r}: {', '.join(sorted(exclusive))}")
        shared_with = tuple(sorted({name for name, pattern in shared_patterns if matches(parts, pattern)}))
        if shared_with or any(matches(parts, pattern) for pattern in global_patterns):
            yield path, ("shared", shared_with)
        else:
            yield path, (next(iter(exclusive), "unmapped"), ())


def changed_paths(base, head):
    # Use individual paths as the accounting unit. A move between features
    # becomes one deletion and one addition, each assigned to its own owner.
    options = ("--no-renames", "--no-ext-diff", "--no-relative", "-z", f"{base}..{head}", "--")
    names = git("diff", "--name-status", *options).split(b"\0")
    stats = git("diff", "--numstat", *options).split(b"\0")
    counts = {}
    for entry in stats[:-1]:
        added, deleted, path = entry.split(b"\t", 2)
        counts[path] = (None, None) if added == b"-" else (int(added), int(deleted))
    return {os.fsdecode(path): (status.decode("ascii"), *counts[path])
            for status, path in zip(names[0:-1:2], names[1:-1:2])}


def totals(changes):
    return {
        "files": len(changes),
        "collie_only": sum(change.origin == "collie-only" for change in changes),
        "modified_upstream": sum(change.origin == "modified-upstream" for change in changes),
        "added": sum(change.added or 0 for change in changes),
        "deleted": sum(change.deleted or 0 for change in changes),
        "binary": sum(change.added is None for change in changes),
    }


def summary(report):
    base_label = "Molly base" if report["molly_merge"] else "Base"
    print(f"{base_label + ':':<12}{report['base']}\n{'Head:':<12}{report['head']}\n")
    print(f"{'Owner':<27} {'Files':>5} {'Collie-only':>11} {'Upstream':>8} {'Added':>8} {'Deleted':>8} {'Binary':>6}")
    for name, counts in [*report["owners"].items(), ("total", report["total"])]:
        print(f"{name:<27} " + " ".join(f"{value:>{width}}" for value, width in zip(
            counts.values(), (5, 11, 8, 8, 8, 6))))
    print("\nUse --json for the full path list, including unmapped paths.")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("summary", "diff"), nargs="?", default="summary")
    parser.add_argument("--base", help="base revision (defaults to the latest locally verified Molly merge's second parent)")
    parser.add_argument("--head", default="HEAD", help="head revision (default: HEAD)")
    parser.add_argument("--feature", help="feature ID for diff mode")
    parser.add_argument("--include-shared", action="store_true", help="include this feature's shared_paths in diff mode")
    output = parser.add_mutually_exclusive_group()
    output.add_argument("--json", action="store_true", help="write the summary and file details as JSON")
    output.add_argument("--check", action="store_true", help="validate the map and check for overlap on tracked paths")
    args = parser.parse_args(argv)
    if args.mode == "diff":
        if not args.feature or args.json or args.check:
            parser.error("diff needs --feature and cannot be combined with --json or --check")
    elif args.feature or args.include_shared:
        parser.error("--feature and --include-shared require diff mode")

    try:
        root = Path(os.fsdecode(git("rev-parse", "--show-toplevel").removesuffix(b"\n")))
        shared, features = load_map(root / MAP_PATH)
        if args.feature and args.feature not in features:
            raise ValueError(f"unknown feature: {args.feature}")
        head = resolve_commit(args.head)
        merge = None if args.base else molly_merge(head)
        base = resolve_commit(args.base) if args.base else merge[1] if merge else None
        if args.check:
            paths = {os.fsdecode(path) for revision in {head, base} - {None}
                     for path in git("ls-tree", "-rz", "--name-only", "--full-tree", revision).split(b"\0")[:-1]}
            paths.update(pattern for feature in features.values() for pattern in feature.paths
                         if not any(char in pattern for char in "*?["))
            dict(classify(sorted(paths), shared, features))
            print(f"{MAP_PATH}: OK ({len(features)} features)")
            return 0
        if base is None:
            raise ValueError("no locally verifiable Molly upgrade merge found. Configure Git signature trust "
                             "or supply --base <rev> (shallow clones may need more history).")

        paths = changed_paths(base, head)
        owners = dict(classify(paths, shared, features))
        if args.mode == "diff":
            selected = [path for path, (owner, shared_with) in owners.items()
                        if owner == args.feature or (args.include_shared and args.feature in shared_with)]
            if not selected:
                return 0
            command = ["git", "--literal-pathspecs", "diff", f"{base}..{head}", "--", *selected]
            return subprocess.run(command, cwd=root).returncode

        by_owner = {name: [] for name in (*sorted(features), "shared", "unmapped")}
        changes = []
        for path, (status, added, deleted) in paths.items():
            owner, shared_with = owners[path]
            origin = "collie-only" if status == "A" else "modified-upstream"
            change = Change(path, status, origin, owner, shared_with, added, deleted)
            changes.append(change)
            by_owner[owner].append(change)

        report = {
            "base": base, "head": head, "molly_merge": merge[0] if merge else None,
            "owners": {owner: totals(files) for owner, files in by_owner.items()},
            "total": totals(changes),
        }
        if args.json:
            report["features"] = {name: {"about": feature.about, "depends_on": feature.depends_on}
                                  for name, feature in features.items()}
            report["files"] = [asdict(change) for change in changes]
            print(json.dumps(report, indent=2))
        else:
            summary(report)
        return 0
    except subprocess.CalledProcessError as exc:
        print(f"feature_diff: {os.fsdecode(exc.stderr).strip()}", file=sys.stderr)
    except (ValueError, OSError) as exc:
        print(f"feature_diff: {exc}", file=sys.stderr)
    return 1


if __name__ == "__main__":
    sys.exit(main())
