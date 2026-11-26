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
COLLIE_REMOTE = re.compile(
    r"(?:https://github\.com/|ssh://git@github\.com/|git@github\.com:)"
    r"0cwa/collieim-android(?:\.git)?/?", re.IGNORECASE,
)
CATALOG = "tooling/layers.toml"
KINDS = {"feature", "infra", "upstream"}
# Upstream merges join unsigned upstream history without Layer trailers. Only
# these repositories qualify, and the declared commit is checked against them.
UPSTREAM_URLS = {
    "signalapp/Signal-Android": "https://github.com/signalapp/Signal-Android.git",
    "mollyim/mollyim-android": "https://github.com/mollyim/mollyim-android.git",
}
SIGNAL, MOLLY = UPSTREAM_URLS
UPSTREAM_REF = re.compile(r"[A-Za-z0-9][A-Za-z0-9._/-]*")
FULL_SHA = re.compile(r"[0-9a-f]{40}")
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

    def problem(self, layer):
        entry = self.layers.get(layer)
        if entry is None:
            return f"layer '{layer}' is not in {CATALOG}."
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


def check_layer(message, label, catalog=None, *, required=False):
    """Return the layer, an empty string when absent, or None for a rejected trailer."""
    layers = trailer_values(message, "layer")
    # Git can ignore the entire footer when a trailer uses a malformed separator.
    # Check the final paragraph for Layer syntax Git could not parse.
    paragraphs = re.split(r"\n[^\S\n]*\n", message.rstrip())
    footer = paragraphs[-1] if len(paragraphs) > 1 else ""
    layer_lines = re.findall(r"^Layer[^\S\n]*[:=]", footer, re.IGNORECASE | re.MULTILINE)
    if len(layer_lines) <= len(layers):
        if not layers and not required:
            return ""
        if len(layers) == 1 and LAYER.fullmatch(layers[0]):
            problem = catalog and catalog.problem(layers[0])
            if not problem:
                return layers[0]
            print(f"{label}: {'Error' if required else 'Note'}: {problem}", file=sys.stderr)
            return None if required else layers[0]
    print(
        f"{label}: {'needs exactly one' if required else 'allows at most one'} "
        "'Layer: <name>' trailer (lowercase letters, digits and hyphens). "
        "Keep trailers together in the final paragraph.",
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
    return check_layer(message, str(path), catalog_in_worktree()) is not None


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
    layer = check_layer(message, label, catalog, required=True)
    if layer is None:
        return False
    if layer != "upgrade" and kind(layer) != "upstream":
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


def main_branch_exists(ref):
    probe = ["git", "rev-parse", "--verify", "--quiet", "--end-of-options", f"{ref}^{{commit}}"]
    return subprocess.run(probe, stdout=subprocess.DEVNULL).returncode == 0


def main_remote():
    for remote in git("remote").splitlines():
        url = git("remote", "get-url", "--", remote).strip()
        if COLLIE_REMOTE.fullmatch(url):
            return remote
    if main_branch_exists("refs/remotes/upstream/main"):
        return "upstream"
    return "origin"


def landed_on_main(base, main_ref):
    """Commits on main that the base lacks, or an empty set when main is unknown."""
    if not main_branch_exists(main_ref):
        return set()
    return set(git("rev-list", main_ref, f"^{base}").splitlines())


def check_range(base, head, *, upstream=None, main_ref="refs/remotes/origin/main"):
    base, head = resolve_commit(base), resolve_commit(head)
    catalog = catalog_at(head)
    # Use the base kind for existing layers so a PR cannot grant its own layer
    # upstream status by changing the catalog.
    base_catalog = catalog_at(base)

    def kind(layer):
        if base_catalog and base_catalog.kind(layer):
            return base_catalog.kind(layer)
        return catalog.kind(layer) if catalog else None

    valid = True
    # Commits reachable only through a verified upstream parent are upstream
    # history and do not need Collie trailer checks.
    # Children come first in topological order, so upstream's own merges are
    # already excluded by the time they are reached.
    upstream_commits = set()
    skipped = 0
    # Commits already on main passed main's own PR check when they landed, so a
    # PR into another branch (the TrailBlazer Beta branch) that brings main in
    # skips them. For a PR into main this set is empty.
    landed = landed_on_main(base, main_ref)
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
    for commit in commits:
        message = git("show", "-s", "--format=%B", commit)
        label = git("show", "-s", "--format=%h %s", commit).strip()
        if check_layer(message, label, catalog) is None:
            valid = False
    if valid:
        print(f"Layer trailers: checked {len(commits)} non-merge commits"
              + (f", skipped {skipped} upstream commits." if upstream_commits else "."))
    return valid


def check_push():
    valid = True
    main_ref = None
    for line in sys.stdin:
        _local_ref, local_oid, remote_ref, _remote_oid = line.split()
        if set(local_oid) == {"0"} or not remote_ref.startswith("refs/heads/"):
            continue  # Branch deletion or a tag, neither of which opens an app PR.
        # Check the whole PR range, including bad commits already on the remote
        # branch. Checking only remote_oid..local_oid would miss those commits.
        if main_ref is None:
            remote = main_remote()
            main_ref = f"refs/remotes/{remote}/main"
            if not main_branch_exists(main_ref):
                raise ValueError(f"Fetch the PR base first: git fetch {remote} main ({main_ref}).")
        if not check_range(
            main_ref, local_oid, main_ref=main_ref, upstream=Upstream(offline=os.environ.get(OFFLINE) == "1")
        ):
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
            valid = check_range(args.base, args.head)
        else:
            valid = check_push()
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        print(f"Cannot check Layer trailers: {error}", file=sys.stderr)
        return 1
    return 0 if valid else 1


if __name__ == "__main__":
    sys.exit(main())
