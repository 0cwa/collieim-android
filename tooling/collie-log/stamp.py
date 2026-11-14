#!/usr/bin/env python3
"""Moves the project log's waiting entries into a release file, and drafts the F-Droid changelog from them.

Usage, during release prep (see docs/PROJECT-LOG.md):

    python3 tooling/collie-log/stamp.py 8.35.2-1 --version-code 177801 [--date 2026-11-20]

- Writes app/src/main/assets/collie-log/releases/<version>.txt from every file in .../unreleased/ and deletes them.
- Writes fastlane/metadata/android/en-US/changelogs/<versionCode>.txt (plain ASCII, at most 500 bytes) unless it
  already exists. It is a draft: trim it by hand before the release PR is merged.
"""

import argparse
import datetime
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
LOG = ROOT / "app/src/main/assets/collie-log"
CHANGELOGS = ROOT / "fastlane/metadata/android/en-US/changelogs"
FIELD = re.compile(r"^([a-z]+):\s*(.*)$")
KEYS = {"version", "date", "topic", "pr"}
# Order of topics in the release file and the changelog: what matters most to people first.
TOPIC_ORDER = ["setup", "push", "notifications", "backups", "privacy", "security", "updates", "devices", "settings", "look", "fixes", "signal"]
MAX_CHANGELOG = 500
ASCII = {"’": "'", "‘": "'", "“": '"', "”": '"', "–": "-", "—": "-", "…": "...", " ": " "}


def entries(text):
    """Yields (fields, sentence) for each entry block, the same way ProjectLog.parse reads them."""
    for block in re.split(r"\n\s*\n", text.replace("\r\n", "\n")):
        fields, sentence = {}, []
        for line in (l.strip() for l in block.splitlines()):
            if not line:
                continue
            match = FIELD.match(line)
            if not sentence and match and match.group(1) in KEYS:
                fields[match.group(1)] = match.group(2).strip()
            else:
                sentence.append(line)
        if sentence:
            yield fields, " ".join(sentence)


def sort_key(item):
    fields, _ = item
    topic = fields.get("topic", "").lower()
    rank = TOPIC_ORDER.index(topic) if topic in TOPIC_ORDER else len(TOPIC_ORDER)
    pr = fields.get("pr", "").lstrip("#")
    return rank, -(int(pr) if pr.isdigit() else 0)


def to_ascii(text):
    for a, b in ASCII.items():
        text = text.replace(a, b)
    return text.encode("ascii", "ignore").decode()


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("version")
    parser.add_argument("--version-code", type=int)
    parser.add_argument("--date", default=datetime.date.today().isoformat())
    args = parser.parse_args()

    datetime.date.fromisoformat(args.date)
    waiting = sorted((LOG / "unreleased").glob("*.txt"))
    target = LOG / "releases" / f"{args.version}.txt"
    if not waiting:
        sys.exit("No waiting entries in unreleased/.")
    if target.exists():
        sys.exit(f"{target.relative_to(ROOT)} already exists.")

    items = sorted((item for path in waiting for item in entries(path.read_text())), key=sort_key)
    blocks = [f"version: {args.version}\ndate: {args.date}"]
    for fields, sentence in items:
        head = "".join(f"{key}: {fields[key]}\n" for key in ("topic", "pr") if key in fields)
        blocks.append(head + sentence)
    target.write_text("\n\n".join(blocks) + "\n")
    for path in waiting:
        path.unlink()
    print(f"Wrote {target.relative_to(ROOT)} with {len(items)} entries and removed {len(waiting)} waiting files.")

    if args.version_code is not None:
        changelog = CHANGELOGS / f"{args.version_code}.txt"
        if changelog.exists():
            print(f"{changelog.relative_to(ROOT)} already exists; left unchanged.")
            return
        lines, size = [], 0
        for _, sentence in items:
            line = "- " + to_ascii(sentence)
            if size + len(line) + 1 > MAX_CHANGELOG:
                break
            lines.append(line)
            size += len(line) + 1
        changelog.write_text("\n".join(lines) + "\n")
        left = len(items) - len(lines)
        print(f"Drafted {changelog.relative_to(ROOT)} ({size} bytes, {left} entries did not fit). Trim it by hand.")


if __name__ == "__main__":
    main()
