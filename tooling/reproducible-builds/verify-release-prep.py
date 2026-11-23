#!/usr/bin/env python3
"""Validate release-preparation provenance from the current Git checkout."""
import os
import re
import subprocess
import sys
from pathlib import Path

KEYS = {"collieBuildRevision", "collieBuildTimestamp", "releaseVersionName", "releaseVersionCode"}
ASSIGNMENT = re.compile(r"^\s*([^\s:=]+)\s*(?:[=:]\s*|\s+)(.*?)\s*$")


class Invalid(ValueError):
    pass


def git(*args, binary=False):
    result = subprocess.run(["git", *args], check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    return result.stdout if binary else result.stdout.decode("utf-8", "strict").strip()


def properties(content):
    values, retained = {}, []
    for line in content.splitlines(keepends=True):
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
        if key in KEYS:
            if (key in values or not re.fullmatch(r"[A-Za-z0-9._-]+", value)
                    or line.rstrip("\r\n") != key + "=" + value):
                raise Invalid("duplicate or invalid release property")
            values[key] = value
        else:
            retained.append(line)
    return values, "".join(retained)


def verify(development_base=None):
    # Replacement refs must never alter the identity, tree, ancestry or timestamp we inspect.
    os.environ["GIT_NO_REPLACE_OBJECTS"] = "1"
    head = git("rev-parse", "--verify", "HEAD^{commit}")
    tree = git("rev-parse", "--verify", "HEAD^{tree}")
    path = Path("gradle.properties")
    if path.is_symlink() or not path.is_file():
        raise Invalid("Gradle properties must be a regular file")
    values, current_other = properties(path.read_text(encoding="utf-8"))
    docker_properties = Path("tooling/gradle.properties.docker")
    if docker_properties.is_symlink() or not docker_properties.is_file():
        raise Invalid("builder Gradle properties must be a regular file")
    docker_values, _ = properties(docker_properties.read_text(encoding="utf-8"))
    if docker_values:
        raise Invalid("release properties in builder Gradle configuration are unsupported")
    if any("ORG_GRADLE_PROJECT_" + key in os.environ for key in KEYS):
        raise Invalid("release property environment overrides are unsupported")
    if any("org.gradle.project." in os.environ.get(key, "") for key in
           ("GRADLE_OPTS", "JAVA_OPTS", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS")):
        raise Invalid("Gradle project property JVM overrides are unsupported")
    present = {key for key in ("releaseVersionName", "releaseVersionCode") if key in values}
    if not present:
        return None
    if set(values) != KEYS:
        raise Invalid("release requires exactly one of each release property")
    grafts = Path(git("rev-parse", "--git-path", "info/grafts"))
    if grafts.is_symlink() or (grafts.exists() and grafts.read_bytes().strip()):
        raise Invalid("grafted Git history is unsupported")
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
    if git("status", "--porcelain=v1", "--untracked-files=all"):
        raise Invalid("release checkout is not clean")
    if any(not entry.startswith(b"H ") for entry in git("ls-files", "-v", "-z", binary=True).split(b"\0") if entry):
        raise Invalid("release checkout has hidden or sparse tracked files")
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
    allowed_additions = {
        f"app/src/main/assets/collie-log/releases/{version}.txt",
        f"fastlane/metadata/android/en-US/changelogs/{code}.txt",
        f"fastlane/metadata/trailblazer-beta/en-US/changelogs/{code}.txt",
    }
    entries = git("diff", "--no-renames", "--name-status", "-z", source, head, binary=True).split(b"\0")
    if entries[-1:] == [b""]:
        entries.pop()
    if len(entries) % 2:
        raise Invalid("invalid preparation diff")
    saw_properties = False
    for index in range(0, len(entries), 2):
        status, path = (part.decode("utf-8", "strict") for part in entries[index:index + 2])
        if path == "gradle.properties" and status == "M":
            saw_properties = True
        elif status == "A" and path in allowed_additions:
            if Path(path).is_symlink() or not Path(path).is_file():
                raise Invalid("release notes must be regular files")
            if "/changelogs/" in path:
                content = Path(path).read_bytes()
                if len(content) > 500 or any(byte > 127 for byte in content):
                    raise Invalid("release changelog must be ASCII and at most 500 bytes")
            continue
        elif status == "D" and re.fullmatch(r"app/src/main/assets/collie-log/unreleased/[0-9]+\.txt", path):
            continue
        else:
            raise Invalid(f"unapproved preparation change: {path}")
    if not saw_properties:
        raise Invalid("release properties did not change during preparation")
    return {"preparation_source_commit": source, "preparation_source_tree": git("rev-parse", source + "^{tree}"),
            "preparation_source_time": values["collieBuildTimestamp"], "built_commit": head, "built_tree": tree}


def main():
    base = None
    if sys.argv[1:2] == ["--development-base"] and len(sys.argv) == 3:
        base = sys.argv[2]
    elif sys.argv[1:]:
        print("usage: verify-release-prep.py [--development-base <commit>]", file=sys.stderr)
        return 2
    try:
        result = verify(base)
        if result is None:
            print("Release preparation check: development build")
        elif "development_base" in result:
            print("Release preparation check: development change; release properties match base "
                  + result["development_base"] + ", so this build is not a preparation")
        else:
            import json
            print(json.dumps(result, sort_keys=True))
    except (Invalid, OSError, UnicodeError, subprocess.CalledProcessError) as error:
        print(f"Release preparation check failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
