#!/usr/bin/env python3
"""Validate release-preparation provenance from the current Git checkout."""
import argparse
import os
import re
import runpy
import subprocess
import sys
from pathlib import Path

KEYS = {"collieBuildRevision", "collieBuildTimestamp", "releaseVersionName", "releaseVersionCode"}
PREPARED_PROPERTY = "collieReleasePrepared"
ASSIGNMENT = re.compile(r"^\s*([^\s:=]+)\s*(?:[=:]\s*|\s+)(.*?)\s*$")
NATURAL_LINE = re.compile(r"[^\r\n]*(?:\r\n|\r|\n)|[^\r\n]+\Z")


class Invalid(ValueError):
    pass


def git(*args, binary=False):
    result = subprocess.run(["git", *args], check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    return result.stdout if binary else result.stdout.decode("utf-8", "strict").strip()


def properties(content):
    values, retained = {}, []
    # Java properties use CR, LF and CRLF as line endings. A form feed can occur inside a comment.
    for line in NATURAL_LINE.findall(content):
        stripped = line.lstrip()
        if not stripped.strip() or stripped.startswith(("#", "!")):
            retained.append(line)
            continue
        match = ASSIGNMENT.fullmatch(line.rstrip("\r\n"))
        if not match:
            raise Invalid("unsupported property syntax")
        key, value = match.groups()
        if "\\" in key:
            raise Invalid("escaped property keys are unsupported")
        if (len(value) - len(value.rstrip("\\"))) % 2:
            raise Invalid("continued property values are unsupported")
        if key.startswith("systemProp.org.gradle.project."):
            raise Invalid("tracked Gradle project property aliases are unsupported")
        if key == "org.gradle.jvmargs" and ("org.gradle.project." in value or "\\" in value):
            raise Invalid("tracked Gradle JVM project property overrides are unsupported")
        if key == PREPARED_PROPERTY:
            raise Invalid("prepared release state is supplied only by the build recipe")
        if key in KEYS:
            if (key in values or not re.fullmatch(r"[A-Za-z0-9._-]+", value)
                    or line.rstrip("\r\n") != key + "=" + value):
                raise Invalid("duplicate or invalid release property")
            values[key] = value
        else:
            retained.append(line)
    return values, "".join(retained)


def configuration():
    """Read release properties without allowing local build configuration to replace them."""
    path = Path("gradle.properties")
    if path.is_symlink() or not path.is_file():
        raise Invalid("Gradle properties must be a regular file")
    values, current_other = properties(path.read_bytes().decode("utf-8"))
    docker_properties = Path("tooling/gradle.properties.docker")
    if docker_properties.is_symlink() or not docker_properties.is_file():
        raise Invalid("builder Gradle properties must be a regular file")
    docker_values, _ = properties(docker_properties.read_bytes().decode("utf-8"))
    if docker_values:
        raise Invalid("release properties in builder Gradle configuration are unsupported")
    if any("ORG_GRADLE_PROJECT_" + key in os.environ for key in KEYS | {PREPARED_PROPERTY}):
        raise Invalid("release property environment overrides are unsupported")
    if any("org.gradle.project." in os.environ.get(key, "") for key in
           ("GRADLE_OPTS", "JAVA_OPTS", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS")):
        raise Invalid("Gradle project property JVM overrides are unsupported")
    return values, current_other


def check_history():
    grafts = Path(git("rev-parse", "--git-path", "info/grafts"))
    if grafts.is_symlink() or (grafts.exists() and grafts.read_bytes().strip()):
        raise Invalid("grafted Git history is unsupported")


def check_clean_checkout():
    if git("status", "--porcelain=v1", "--untracked-files=all"):
        raise Invalid("release checkout is not clean")
    if any(not entry.startswith(b"H ") for entry in git("ls-files", "-v", "-z", binary=True).split(b"\0") if entry):
        raise Invalid("release checkout has hidden or sparse tracked files")


def verify(development_base=None, edition="stable"):
    # Replacement refs must never alter the identity, tree, ancestry or timestamp we inspect.
    os.environ["GIT_NO_REPLACE_OBJECTS"] = "1"
    if edition not in {"stable", "beta"}:
        raise Invalid("release edition must be stable or beta")
    head = git("rev-parse", "--verify", "HEAD^{commit}")
    tree = git("rev-parse", "--verify", "HEAD^{tree}")
    values, current_other = configuration()
    present = {key for key in ("releaseVersionName", "releaseVersionCode") if key in values}
    if not present:
        return None
    if set(values) != KEYS:
        raise Invalid("release requires exactly one of each release property")
    check_history()
    if development_base:
        # A PR that keeps its base's release properties is development on top of the last
        # preparation (a feature, or main merged into the Beta branch), not a preparation. Its
        # build keeps that release identity, as main's do. Release builds never pass a base.
        if not re.fullmatch(r"[0-9a-f]{40}", development_base):
            raise Invalid("development base must be a full commit ID")
        base = git("rev-parse", "--verify", "--end-of-options", development_base + "^{commit}")
        if base != development_base or base == head:
            raise Invalid("development base must be a different commit")
        subprocess.run(["git", "merge-base", "--is-ancestor", base, head], check=True,
                       stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
        base_values, _ = properties(git("show", base + ":gradle.properties", binary=True).decode("utf-8"))
        if base_values == values:
            return {"development_base": base}
    check_clean_checkout()
    source = values["collieBuildRevision"]
    if not re.fullmatch(r"[0-9a-f]{40}", source):
        raise Invalid("release source revision must be a full lowercase commit ID")
    if git("cat-file", "-t", source) != "commit" or git("rev-parse", "--verify", source + "^{commit}") != source:
        raise Invalid("release source revision does not resolve to that commit")
    if source == head:
        raise Invalid("release source must precede preparation")
    subprocess.run(["git", "merge-base", "--is-ancestor", source, head], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
    if values["collieBuildTimestamp"] != git("show", "-s", "--format=%ct", source):
        raise Invalid("release timestamp does not match source commit time")
    if not re.fullmatch(r"[1-9][0-9]*", values["releaseVersionCode"]):
        raise Invalid("invalid release version code")
    _, source_other = properties(git("show", source + ":gradle.properties", binary=True).decode("utf-8"))
    if current_other != source_other:
        raise Invalid("non-release Gradle properties changed during preparation")
    version, code = values["releaseVersionName"], values["releaseVersionCode"]
    notes = runpy.run_path(str(Path(__file__).resolve().parents[1] / "collie-log/stamp.py"))
    release_path = notes["RELEASES"] / edition / f"{version}.txt"
    changelog_path = notes["CHANGELOGS"][edition] / f"{code}.txt"
    allowed_additions = {str(release_path), str(changelog_path)}
    entries = git("diff", "--no-renames", "--name-status", "-z", source, head, binary=True).split(b"\0")
    if entries[-1:] == [b""]:
        entries.pop()
    if len(entries) % 2:
        raise Invalid("invalid preparation diff")
    saw_properties = False
    additions = set()
    for index in range(0, len(entries), 2):
        status, path = (part.decode("utf-8", "strict") for part in entries[index:index + 2])
        if path == "gradle.properties" and status == "M":
            saw_properties = True
        elif status == "A" and path in allowed_additions:
            notes["regular_path"](Path(path))
            additions.add(path)
            if "/changelogs/" in path:
                content = Path(path).read_bytes()
                if len(content) > 500 or any(byte > 127 for byte in content):
                    raise Invalid("release changelog must be ASCII and at most 500 bytes")
            continue
        elif (status == "D" and Path(path).parent == notes["PENDING"]
              and notes["FRAGMENT"].fullmatch(Path(path).name)):
            continue
        else:
            raise Invalid(f"unapproved preparation change: {path}")
    if not saw_properties:
        raise Invalid("release properties did not change during preparation")
    if additions != allowed_additions:
        raise Invalid("preparation must add the selected product's release record and changelog")
    if notes["current_records"](version, edition) != [release_path]:
        raise Invalid("selected product must have exactly one current release record at its version filename")
    pending = notes["PENDING"]
    if pending.is_symlink() or (pending.exists() and (not pending.is_dir() or any(pending.iterdir()))):
        raise Invalid("release has unconsumed pending fragments")
    legacy = notes["RELEASES"].parent / "unreleased"
    if legacy.is_symlink() or (legacy.exists() and (not legacy.is_dir() or any(legacy.iterdir()))):
        raise Invalid("legacy pending assets must be moved outside the APK before preparation")
    source_items = []
    for entry in git("ls-tree", "-r", "-z", source, "--", str(pending), binary=True).split(b"\0"):
        if not entry:
            continue
        info, name = entry.decode("utf-8").split("\t", 1)
        mode, kind, _ = info.split()
        if (kind != "blob" or mode not in {"100644", "100755"} or Path(name).parent != pending
                or not notes["FRAGMENT"].fullmatch(Path(name).name)):
            raise Invalid("source pending fragments must be named regular files")
        source_items.extend(notes["parse"](git("show", source + ":" + name, binary=True).decode("utf-8"))[1])
    release_items = notes["release_entries"](release_path.read_text(encoding="utf-8"), version, edition)
    if notes["entry_counts"](source_items) != notes["entry_counts"](release_items):
        raise Invalid("release record must preserve every pending source entry exactly once")
    return {"preparation_source_commit": source, "preparation_source_tree": git("rev-parse", source + "^{tree}"),
            "preparation_source_time": values["collieBuildTimestamp"], "built_commit": head, "built_tree": tree}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--development-base")
    parser.add_argument("--edition", choices=("stable", "beta"), default="stable")
    parser.add_argument("--build-state", action="store_true", help="print the verified prepared boolean for the build recipe")
    args = parser.parse_args()
    try:
        result = verify(args.development_base, args.edition)
        output = sys.stderr if args.build_state else sys.stdout
        if result is None:
            print("Release preparation check: development build", file=output)
        elif "development_base" in result:
            print("Release preparation check: development change; release properties match base "
                  + result["development_base"] + ", so this build is not a preparation", file=output)
        else:
            import json
            print(json.dumps(result, sort_keys=True), file=output)
        if args.build_state:
            print("true" if result is not None and "preparation_source_commit" in result else "false")
    except (ValueError, OSError, UnicodeError, subprocess.CalledProcessError) as error:
        print(f"Release preparation check failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
