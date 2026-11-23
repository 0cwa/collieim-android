#!/usr/bin/env python3
"""Collie Layer trailer hooks: use Git's parser locally and in CI."""

import argparse
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile


LAYER = re.compile(r"[a-z0-9][a-z0-9-]*")
BASE = "refs/remotes/origin/main"
CATALOG = "tooling/layers.toml"
KINDS = {"feature", "infra", "upstream"}
# PRs numbered from here on may change only one feature layer. This lives in the
# checker, which CI takes from the PR base, so a PR cannot move its own cutoff.
ONE_FEATURE_PER_PR_FROM = 202
# Upstream merges join unsigned upstream history without Layer trailers. Only
# these repositories qualify, and the declared commit is checked against them.
UPSTREAM_URLS = {
    "signalapp/Signal-Android": "https://github.com/signalapp/Signal-Android.git",
    "mollyim/mollyim-android": "https://github.com/mollyim/mollyim-android.git",
}
SIGNAL, MOLLY = UPSTREAM_URLS
UPSTREAM_REF = re.compile(r"[A-Za-z0-9][A-Za-z0-9._/-]*")
FULL_SHA = re.compile(r"[0-9a-f]{40}")
REPOSITORY = re.compile(r"[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?/[A-Za-z0-9_.-]+")
PROVENANCE_REQUIRED = {"repository", "revision", "revision_kind", "relationship", "application", "scope"}
PROVENANCE_OPTIONAL = {"discovery_ref", "prerequisites"}
# The pre-push hook may skip only the network identity check, for offline work.
OFFLINE = "COLLIE_UPSTREAM_OFFLINE"


def git(*args, **kwargs):
    return subprocess.check_output(
        ["git", *args], text=True, encoding="utf-8", errors="replace", **kwargs
    )


class Catalog:
    """The layer names in tooling/layers.toml, or None where a tree predates it."""

    def __init__(self, text):
        try:
            import tomllib  # Python 3.11+; only trees with a catalog need it.
        except ModuleNotFoundError:
            raise ValueError(f"{CATALOG} needs Python 3.11 or later to read") from None
        self.layers = tomllib.loads(text).get("layers", {})
        if not isinstance(self.layers, dict):
            raise ValueError(f"{CATALOG}: layers must be a table")
        for name, entry in self.layers.items():
            if not LAYER.fullmatch(name):
                raise ValueError(f"{CATALOG}: invalid layer name {name!r}")
            if not isinstance(entry, dict):
                raise ValueError(f"{CATALOG}: {name} must be a table")
            if "alias_of" in entry:
                alias = entry["alias_of"]
                target = self.layers.get(alias) if isinstance(alias, str) else None
                if not isinstance(target, dict) or "alias_of" in target:
                    raise ValueError(f"{CATALOG}: {name} is an alias of an unknown layer")
            elif not isinstance(entry.get("kind"), str) or entry["kind"] not in KINDS or not entry.get("about"):
                raise ValueError(f"{CATALOG}: {name} needs a kind ({', '.join(sorted(KINDS))}) and about")
        for name, entry in self.layers.items():
            if "provenance" in entry:
                self.validate_provenance(name, entry)
        self.validate_prerequisites()

    def validate_provenance(self, name, entry):
        """Validate selective-feature metadata without granting upstream trust."""
        label = f"{CATALOG}: {name}.provenance"
        def fail(reason):
            raise ValueError(f"{label}: {reason}")
        if "alias_of" in entry or entry.get("kind") != "feature":
            fail("only canonical feature layers may have provenance")
        provenance = entry["provenance"]
        if not isinstance(provenance, dict):
            fail("must be a table")
        missing = PROVENANCE_REQUIRED - provenance.keys()
        unknown = provenance.keys() - PROVENANCE_REQUIRED - PROVENANCE_OPTIONAL
        if missing:
            fail(f"missing fields: {', '.join(sorted(missing))}")
        if unknown:
            fail(f"unknown fields: {', '.join(sorted(unknown))}")
        for field in PROVENANCE_REQUIRED:
            if not isinstance(provenance[field], str) or not provenance[field].strip():
                fail(f"{field} must be a nonblank string")
        repository = provenance["repository"]
        if not REPOSITORY.fullmatch(repository) or repository.split("/")[-1] in {".", ".."} or repository.lower().endswith(".git"):
            fail("repository must be a GitHub owner/repo name")
        if repository.lower() in {upstream.lower() for upstream in UPSTREAM_URLS}:
            fail("Signal and Molly integration is recorded by verified upstream merges")
        revision = provenance["revision"]
        if not FULL_SHA.fullmatch(revision) or revision == "0" * 40:
            fail("revision must be an exact, nonzero, lowercase 40-hex commit SHA")
        for field, choices in (
            ("revision_kind", {"feature-commit", "release-snapshot"}),
            ("relationship", {"direct-import", "adaptation", "inspiration"}),
            ("application", {"mechanical", "manual"}),
        ):
            if provenance[field] not in choices:
                fail(f"{field} must be one of {', '.join(sorted(choices))}")
        if provenance["application"] == "mechanical" and (
            provenance["relationship"] != "direct-import" or provenance["revision_kind"] != "feature-commit"
        ):
            fail("mechanical application requires a direct-import of a feature-commit")
        if "discovery_ref" in provenance:
            ref = provenance["discovery_ref"]
            if not isinstance(ref, str) or not ref.startswith(("refs/heads/", "refs/tags/")) or subprocess.run(
                ["git", "check-ref-format", ref], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL
            ).returncode:
                fail("discovery_ref must be a full refs/heads/ or refs/tags/ ref, not a revision or pattern")
        prerequisites = provenance.get("prerequisites", [])
        if not isinstance(prerequisites, list) or any(not isinstance(item, str) for item in prerequisites):
            fail("prerequisites must be a list of canonical feature IDs")
        if len(set(prerequisites)) != len(prerequisites):
            fail("prerequisites must not contain duplicate feature IDs")
        for prerequisite in prerequisites:
            target = self.layers.get(prerequisite, {})
            if prerequisite == name or "alias_of" in target or target.get("kind") != "feature":
                fail(f"prerequisite {prerequisite!r} must name another canonical feature")

    def validate_prerequisites(self):
        """Prerequisites order maintenance work; they cannot depend on themselves."""
        visiting, visited = set(), set()
        def visit(name):
            if name in visiting:
                raise ValueError(f"{CATALOG}: cyclic provenance prerequisites at {name}")
            if name in visited:
                return
            visiting.add(name)
            for prerequisite in self.layers[name].get("provenance", {}).get("prerequisites", []):
                visit(prerequisite)
            visiting.remove(name)
            visited.add(name)
        for name in self.layers:
            visit(name)

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


def trailer_values(message, name):
    trailers = git("interpret-trailers", "--parse", input=message)
    return [
        value.strip()
        for line in trailers.splitlines()
        for key, separator, value in [line.partition(":")]
        if separator and key.lower() == name
    ]


def check_message(message, label, catalog=None):
    """Return the commit's layer name, or None after printing why it is invalid."""
    layers = trailer_values(message, "layer")
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


class Upstream:
    """Confirms an upstream commit against the upstream repository itself."""

    def __init__(self, urls=None, offline=False):
        self.urls = urls or UPSTREAM_URLS
        self.offline = offline

    def signal_tag(self, tag):
        """The commit Signal's tag points to, peeled, or None if it has no such tag."""
        ref = f"refs/tags/{tag}"
        found = {}
        for line in git("ls-remote", "--tags", "--end-of-options", self.urls[SIGNAL], ref, f"{ref}^{{}}").splitlines():
            oid, _, name = line.partition("\t")
            found[name] = oid
        return found.get(f"{ref}^{{}}", found.get(ref))

    def molly_contains(self, branch, commit):
        """Whether Molly's branch head is, or descends from, commit."""
        common = Path(git("rev-parse", "--path-format=absolute", "--git-common-dir").strip())
        objects = git("rev-parse", "--path-format=absolute", "--git-path", "objects").strip()
        # A throwaway repository borrowing this one's objects keeps the
        # blob-less fetch's partial-clone settings out of this repository.
        scratch = Path(tempfile.mkdtemp(prefix="collie-upstream-check-", dir=common))
        try:
            # Hooks may set GIT_DIR and similar; they must not reach the scratch repository.
            local = set(git("rev-parse", "--local-env-vars").split())
            env = {key: value for key, value in os.environ.items() if key not in local}
            def run(*args):
                return subprocess.run(["git", "-C", str(scratch), *args], text=True, env=env,
                                      stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            if run("init", "-q", "--bare").returncode:
                raise ValueError(f"cannot prepare a check of {MOLLY} {branch}")
            (scratch / "objects/info/alternates").write_text(objects + "\n", encoding="utf-8")
            # Offer the merged commit, so the fetch sends only what follows it.
            if run("update-ref", "refs/have", commit).returncode:
                raise ValueError(f"cannot prepare a check of {MOLLY} {branch}")
            fetched = run("fetch", "-q", "--no-tags", "--filter=blob:none", "--end-of-options",
                          self.urls[MOLLY], f"+refs/heads/{branch}:refs/upstream")
            if fetched.returncode:
                raise ValueError(f"cannot fetch {MOLLY} {branch}: {fetched.stderr.strip()}")
            return run("merge-base", "--is-ancestor", commit, "refs/upstream").returncode == 0
        finally:
            shutil.rmtree(scratch, ignore_errors=True)


def upstream_parent(merge, catalog, kind, upstream):
    """For a merge declaring Upstream:, its verified upstream parent and trailer.

    Returns False after printing why an upstream merge is invalid, and None for
    an ordinary merge, which keeps the ordinary merge rules.
    """
    message = git("show", "-s", "--format=%B", merge)
    declared = trailer_values(message, "upstream")
    if not declared:
        return None
    label = git("show", "-s", "--format=%h %s", merge).strip()
    def fail(reason):
        print(f"{label}: upstream merge {reason}", file=sys.stderr)
        return False
    if len(declared) != 1:
        return fail("needs exactly one 'Upstream:' trailer.")
    repository, _, name = declared[0].partition(" ")
    if repository not in UPSTREAM_URLS:
        return fail(f"names {repository!r}; only {' and '.join(UPSTREAM_URLS)} are accepted.")
    layer = check_message(message, label, catalog)
    if layer is None:
        return False
    if kind(layer) != "upstream":
        return fail(f"needs a Layer of kind 'upstream' in {CATALOG}, not '{layer}'.")
    parents = git("show", "-s", "--format=%P", merge).split()
    if len(parents) != 2:
        return fail("needs exactly two parents: Collie first, then the upstream commit.")
    parent = parents[1]
    if repository == SIGNAL:
        if not UPSTREAM_REF.fullmatch(name) or "/" in name:
            return fail(f"needs 'Upstream: {SIGNAL} <tag>', not {declared[0]!r}.")
        expected = name
    else:
        branch, _, expected = name.rpartition("@")
        if not UPSTREAM_REF.fullmatch(branch) or not FULL_SHA.fullmatch(expected):
            return fail(f"needs 'Upstream: {MOLLY} <branch>@<full-sha>', not {declared[0]!r}.")
        if parent != expected:
            return fail(f"second parent {parent} is not the declared {MOLLY} commit {expected}.")
        name = branch
    if subprocess.run(["git", "check-ref-format", f"refs/x/{name}"]).returncode:
        return fail(f"names an invalid upstream ref {name!r}.")
    if upstream.offline:
        print(f"{label}: offline, so {declared[0]} is not checked against {repository}; "
              "CI checks it.", file=sys.stderr)
    elif repository == SIGNAL:
        expected = upstream.signal_tag(name)
        if expected is None:
            return fail(f"names tag {name}, which {SIGNAL} does not have.")
        if parent != expected:
            return fail(f"second parent {parent} is not {SIGNAL} {name} ({expected}).")
    elif not upstream.molly_contains(name, expected):
        return fail(f"declares {expected}, which is not on {MOLLY} {name}.")
    return parent, declared[0]


def landed_on_main(base):
    """Commits on main that the base lacks, or an empty set when main is unknown."""
    probe = ["git", "rev-parse", "--verify", "--quiet", "--end-of-options", f"{BASE}^{{commit}}"]
    if subprocess.run(probe, stdout=subprocess.DEVNULL).returncode:
        return set()
    return set(git("rev-list", BASE, f"^{base}").splitlines())


def check_range(base, head, pr=None, upstream=None):
    base, head = resolve_commit(base), resolve_commit(head)
    catalog = catalog_at(head)  # The PR's own catalog, so a new feature adds its entry.
    # Kinds of layers that already exist come from the base, so a PR cannot
    # relabel a feature as infra to pass the one-feature rule.
    base_catalog = catalog_at(base)
    def kind(layer):
        if base_catalog and base_catalog.kind(layer):
            return base_catalog.kind(layer)
        return catalog.kind(layer) if catalog else None

    valid = True
    # Commits reachable only through a verified upstream parent are upstream
    # history: unsigned, without trailers, and outside the one-feature rule.
    # Children come first in topological order, so upstream's own merges are
    # already excluded by the time they are reached.
    upstream_commits = set()
    skipped = 0
    # Commits already on main passed main's own PR check when they landed, so a
    # PR into another branch (the TrailBlazer Beta branch) that brings main in
    # skips them. For a PR into main this set is empty.
    landed = landed_on_main(base)
    for merge in git("rev-list", "--merges", "--topo-order", f"{base}..{head}").splitlines():
        if merge in upstream_commits or merge in landed:
            continue
        joined = upstream_parent(merge, catalog, kind, upstream or Upstream())
        if joined is False:
            valid = False
        elif joined:
            parent, declared = joined
            plain = set(git("rev-list", "--no-merges", parent, f"^{base}").splitlines())
            count = len(plain - upstream_commits)
            upstream_commits |= set(git("rev-list", parent, f"^{base}").splitlines())
            skipped += count
            print(f"Upstream merge {merge[:10]} ({declared}): skipped {count} upstream commits.")
    commits = [
        commit for commit in git("rev-list", "--no-merges", f"{base}..{head}").splitlines()
        if commit not in upstream_commits and commit not in landed
    ]
    if landed:
        print(f"Skipped {len(landed)} commits already on main.")
    layers = set()
    for commit in commits:
        message = git("show", "-s", "--format=%B", commit)
        label = git("show", "-s", "--format=%h %s", commit).strip()
        layer = check_message(message, label, catalog)
        if layer is None:
            valid = False
        else:
            layers.add(layer)
    features = sorted(layer for layer in layers if kind(layer) == "feature")
    if len(features) > 1:
        enforced = pr is not None and pr >= ONE_FEATURE_PER_PR_FROM
        print(
            f"{'Error' if enforced else 'Warning'}: this range changes {len(features)} feature "
            f"layers ({', '.join(features)}). A PR changes one feature; open one PR per "
            "feature. Tests and docs for a feature use that feature's layer.",
            file=sys.stderr,
        )
        valid = valid and not enforced
    if valid:
        print(f"Layer trailers: checked {len(commits)} non-merge commits"
              + (f", skipped {skipped} upstream commits" if upstream_commits else "")
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
        if not check_range(BASE, local_oid, upstream=Upstream(offline=os.environ.get(OFFLINE) == "1")):
            valid = False
    return valid


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    message = commands.add_parser("message", help="check a pending commit message")
    message.add_argument("path", type=Path)
    revisions = commands.add_parser("range", help="check every non-merge commit in base..head outside verified upstream merges")
    revisions.add_argument("base")
    revisions.add_argument("head", nargs="?", default="HEAD")
    commands.add_parser("pre-push", help=(
        "check branch updates from Git's pre-push stdin; "
        f"{OFFLINE}=1 skips only the network check of upstream merges"))
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
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        print(f"Cannot check Layer trailers: {error}", file=sys.stderr)
        if args.command == "pre-push":
            print(f"Fetch the PR base first: git fetch origin main ({BASE}).", file=sys.stderr)
        return 1
    return 0 if valid else 1


if __name__ == "__main__":
    sys.exit(main())
