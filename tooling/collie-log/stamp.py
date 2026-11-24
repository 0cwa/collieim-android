#!/usr/bin/env python3
"""Prepare one product's release notes and tracked build identity from a clean source commit.

Usage (see docs/PROJECT-LOG.md):

    python3 tooling/collie-log/stamp.py <version> --edition stable|beta \
        --version-code <code> --date YYYY-MM-DD [--highlights <content-id>] [--tag <tag>]

The complete record consumes tooling/collie-log/unreleased/*.txt. The selected product's
Fastlane changelog is a 500-byte ASCII draft; trim that draft before committing preparation.
"""

import argparse
from collections import Counter
import datetime
import os
from pathlib import Path
import re
import runpy
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[2]
PENDING = Path("tooling/collie-log/unreleased")
RELEASES = Path("app/src/main/assets/collie-log/releases")
CHANGELOGS = {
    "stable": Path("fastlane/metadata/android/en-US/changelogs"),
    "beta": Path("fastlane/metadata/trailblazer-beta/en-US/changelogs"),
}
TAG_PREFIX = {"stable": "v", "beta": "trailblazer-v"}
FIELD = re.compile(r"^([a-z]+):\s*(.*)$")
IDENTIFIER = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]*\Z")
FRAGMENT = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]*\.txt\Z")
VERSION = re.compile(r"[0-9]+\.[0-9]+\.[0-9]+(?:-[0-9]+)?(?:-(?:alpha|beta|rc)[0-9]+)?\Z")
HEADER_KEYS = {"version", "date", "highlights", "tag"}
ENTRY_KEYS = {"topic", "pr"}
KEYS = HEADER_KEYS | ENTRY_KEYS
# What matters most to people first, then the newest PR within each topic.
TOPIC_ORDER = ["setup", "push", "notifications", "backups", "privacy", "security", "updates", "devices", "settings", "look", "fixes", "signal"]
MAX_LENGTH = 120
MAX_CHANGELOG = 500
ASCII = {"’": "'", "‘": "'", "“": '"', "”": '"', "–": "-", "—": "-", "…": "...", " ": " "}


def parse(text, *, release=False):
    """Read the app's block format, rejecting malformed authoring input before it can be consumed."""
    header, items = {}, []
    for block in re.split(r"\n\s*\n", text.replace("\r\n", "\n").replace("\r", "\n")):
        fields, sentence = {}, []
        for line in (line.strip() for line in block.splitlines()):
            if not line:
                continue
            match = FIELD.fullmatch(line)
            if not sentence and match and match[1] in KEYS:
                if match[1] in fields:
                    raise ValueError(f"duplicate {match[1]} field")
                fields[match[1]] = match[2].strip()
            else:
                sentence.append(line)
        if not fields and not sentence:
            continue
        if not sentence:
            if not release or header or items or not fields.keys() <= HEADER_KEYS:
                raise ValueError("release metadata must be one header before the entries")
            header = fields
            continue
        if not fields.keys() <= ENTRY_KEYS:
            raise ValueError("release metadata cannot be part of a change entry")
        sentence = " ".join(sentence)
        # Kotlin String.length counts UTF-16 code units, including both halves of an emoji.
        if len(sentence.encode("utf-16-le")) // 2 > MAX_LENGTH:
            raise ValueError(f"change exceeds {MAX_LENGTH} characters")
        if "topic" in fields:
            fields["topic"] = fields["topic"].lower()
            if fields["topic"] not in TOPIC_ORDER:
                raise ValueError(f"unknown topic: {fields['topic']}")
        if "pr" in fields:
            number = fields["pr"].removeprefix("#")
            if not re.fullmatch(r"[1-9][0-9]*", number) or int(number) > 2_147_483_647:
                raise ValueError("PR number must be a positive integer")
            fields["pr"] = str(int(number))
        items.append((fields, sentence))
    if not release and not items:
        raise ValueError("pending fragment has no change entries")
    return header, items


def release_metadata(version, date, edition, *, highlights=None, tag=None):
    if edition not in CHANGELOGS or not VERSION.fullmatch(version):
        raise ValueError("valid product and release version required")
    if not re.fullmatch(r"[0-9]{4}-[0-9]{2}-[0-9]{2}", date):
        raise ValueError("release date must be YYYY-MM-DD")
    datetime.date.fromisoformat(date)
    if highlights is not None and not IDENTIFIER.fullmatch(highlights):
        raise ValueError("invalid highlights content ID")
    if tag is not None and tag != TAG_PREFIX[edition] + version:
        raise ValueError("release tag must match the selected product and version")
    header = {"version": version, "date": date}
    if tag is not None:
        header["tag"] = tag
    if highlights is not None:
        header["highlights"] = highlights
    return header


def release_entries(text, version, edition):
    header, items = parse(text, release=True)
    if header.get("version") != version:
        raise ValueError("release record version does not match the build")
    release_metadata(version, header.get("date", ""), edition,
                     highlights=header.get("highlights"), tag=header.get("tag"))
    return items


def entry_counts(items):
    """Compare preserved changes without making an editor's ordering part of provenance."""
    return Counter((fields.get("topic"), fields.get("pr"), sentence) for fields, sentence in items)


def sort_key(item):
    fields, _ = item
    topic = fields.get("topic", "")
    rank = TOPIC_ORDER.index(topic) if topic in TOPIC_ORDER else len(TOPIC_ORDER)
    return rank, -int(fields.get("pr", "0"))


def render(header, items):
    blocks = ["\n".join(f"{key}: {value}" for key, value in header.items())]
    for fields, sentence in items:
        head = "".join(f"{key}: {fields[key]}\n" for key in ("topic", "pr") if key in fields)
        blocks.append(head + sentence)
    return "\n\n".join(blocks) + "\n"


def changelog(items):
    lines, size = [], 0
    for _, sentence in items:
        for before, after in ASCII.items():
            sentence = sentence.replace(before, after)
        line = "- " + sentence.encode("ascii", "ignore").decode("ascii") + "\n"
        if size + len(line) > MAX_CHANGELOG:
            break
        lines.append(line)
        size += len(line)
    return "".join(lines)


def regular_path(path, *, new=False):
    """All inputs and outputs stay in ordinary directories inside the checked-out repository."""
    for parent in (path, *path.parents):
        if parent.is_symlink():
            raise ValueError(f"symlinked preparation path: {path}")
    if new:
        if path.exists():
            raise ValueError(f"{path} already exists")
    elif not path.is_file():
        raise ValueError(f"regular file required: {path}")
    if any(parent.exists() and not parent.is_dir() for parent in path.parents):
        raise ValueError(f"preparation parent is not a directory: {path}")


def current_records(version, edition):
    """Find the selected version by its header, including a wrongly named duplicate."""
    directory = RELEASES / edition
    if directory.is_symlink() or (directory.exists() and not directory.is_dir()):
        raise ValueError("release records must be in a regular product directory")
    matches = []
    for path in sorted(directory.iterdir()) if directory.exists() else []:
        regular_path(path)
        if not FRAGMENT.fullmatch(path.name):
            raise ValueError(f"invalid release record filename: {path}")
        header, _ = parse(path.read_text(encoding="utf-8"), release=True)
        if header.get("version") == version:
            matches.append(path)
    return matches


def prepare(version, code, date, edition, *, highlights=None, tag=None):
    """Validate the entire plan first, then write only its metadata and consume its fragments."""
    header = release_metadata(version, date, edition, highlights=highlights, tag=tag)
    if not 0 < code <= 2_100_000_000:
        raise ValueError("version code must be between 1 and 2100000000")
    os.environ["GIT_NO_REPLACE_OBJECTS"] = "1"
    provenance = runpy.run_path(str(ROOT / "tooling/reproducible-builds/verify-release-prep.py"))
    _, other_properties = provenance["configuration"]()
    provenance["check_history"]()
    provenance["check_clean_checkout"]()
    git = provenance["git"]
    source = git("rev-parse", "--verify", "HEAD^{commit}")
    timestamp = git("show", "-s", "--format=%ct", source)

    target = RELEASES / edition / f"{version}.txt"
    draft = CHANGELOGS[edition] / f"{code}.txt"
    regular_path(target, new=True)
    regular_path(draft, new=True)
    if current_records(version, edition):
        raise ValueError("selected product already has a record for this release version")
    legacy = RELEASES.parent / "unreleased"
    if legacy.is_symlink() or (legacy.exists() and (not legacy.is_dir() or any(legacy.iterdir()))):
        raise ValueError("legacy pending assets must be moved outside the APK before preparation")
    if PENDING.is_symlink() or (PENDING.exists() and not PENDING.is_dir()):
        raise ValueError("pending fragments must be in a regular directory")
    waiting = sorted(PENDING.iterdir()) if PENDING.exists() else []
    tracked = set(git("ls-files", "-z", "--", str(PENDING), binary=True).decode("utf-8").split("\0")) - {""}
    if tracked != {str(path) for path in waiting}:
        raise ValueError("pending fragments must be tracked files in the clean source")
    items = []
    for path in waiting:
        if not FRAGMENT.fullmatch(path.name):
            raise ValueError(f"invalid pending fragment filename: {path}")
        regular_path(path)
        try:
            items.extend(parse(path.read_text(encoding="utf-8"))[1])
        except ValueError as error:
            raise ValueError(f"{path}: {error}") from error
    items.sort(key=sort_key)
    record = render(header, items)
    short_notes = changelog(items)
    if other_properties and not other_properties.endswith(("\n", "\r")):
        raise ValueError("Gradle properties must end with a newline before preparation")
    properties = other_properties + (
        f"collieBuildRevision={source}\ncollieBuildTimestamp={timestamp}\n"
        f"releaseVersionName={version}\nreleaseVersionCode={code}\n"
    )

    # No validation or metadata reads occur after this point. Keep generated files
    # recoverable until all writes and pending-fragment consumption have succeeded.
    target.parent.mkdir(parents=True, exist_ok=True)
    draft.parent.mkdir(parents=True, exist_ok=True)
    created = []
    removed = []
    properties_path = Path("gradle.properties")
    temporary = None
    replacement = None
    try:
        with target.open("x", encoding="utf-8", newline="\n") as output:
            created.append(target)
            output.write(record)
        with draft.open("x", encoding="ascii", newline="\n") as output:
            created.append(draft)
            output.write(short_notes)
        fd, temporary_name = tempfile.mkstemp(prefix=".gradle.properties.", dir=properties_path.parent)
        temporary = Path(temporary_name)
        with os.fdopen(fd, "w", encoding="utf-8", newline="\n") as output:
            output.write(properties)
        os.chmod(temporary, properties_path.stat().st_mode)
        replacement = temporary.stat()
        for path in waiting:
            content = path.read_bytes()
            mode = path.stat().st_mode
            removed.append((path, content, mode))
            path.unlink()
        os.replace(temporary, properties_path)
    except BaseException as error:
        if replacement is not None:
            try:
                committed = os.path.samestat(replacement, properties_path.stat())
            except OSError as check_error:
                error.add_note(f"Cannot determine whether release properties were installed: {check_error}")
                raise error from check_error
            if committed:
                # An interrupt may arrive after the rename succeeded.
                raise
        for path, content, mode in reversed(removed):
            try:
                with path.open("xb") as output:
                    output.write(content)
                os.chmod(path, mode)
            except FileExistsError:
                pass
            except OSError as rollback_error:
                error.add_note(f"Could not restore {path}: {rollback_error}")
        for path in [*reversed(created), temporary]:
            if path is None:
                continue
            try:
                path.unlink(missing_ok=True)
            except OSError as rollback_error:
                error.add_note(f"Could not remove {path}: {rollback_error}")
        raise
    print(f"Prepared {edition} {version} from {source}: {len(items)} changes.")
    print(f"Wrote {target} and {draft}; review the Fastlane draft before committing.")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("version")
    parser.add_argument("--edition", required=True, choices=sorted(CHANGELOGS))
    parser.add_argument("--version-code", type=int, required=True)
    parser.add_argument("--date", required=True)
    parser.add_argument("--highlights")
    parser.add_argument("--tag")
    args = parser.parse_args()
    os.chdir(ROOT)
    try:
        prepare(args.version, args.version_code, args.date, args.edition,
                highlights=args.highlights, tag=args.tag)
    except (ValueError, OSError, UnicodeError, subprocess.CalledProcessError) as error:
        print(f"Release preparation failed: {error}", file=sys.stderr)
        for note in getattr(error, "__notes__", ()):
            print(note, file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
