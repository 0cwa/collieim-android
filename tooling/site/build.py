#!/usr/bin/env python3
"""Build the collie.im website into an output directory.

The page source is docs/site/. This script copies it, fills in the latest
release tag and the APK link, and fails if a placeholder is left behind.

    python3 tooling/site/build.py --out _site            # asks GitHub for the latest release
    python3 tooling/site/build.py --out _site --tag v1.2  # offline, fixed tag
"""
from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import sys
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "docs" / "site"
REPO = "0cwa/collieim-android"
APK_NAME = "collie.apk"
TAG_RE = re.compile(r"^v[0-9][0-9A-Za-z.\-]*$")


def latest_release(repo: str) -> str:
    req = urllib.request.Request(
        f"https://api.github.com/repos/{repo}/releases/latest",
        headers={"Accept": "application/vnd.github+json", "User-Agent": "collie-site-build"},
    )
    token = os.environ.get("GITHUB_TOKEN")
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    with urllib.request.urlopen(req, timeout=30) as resp:
        release = json.load(resp)
    names = {a["name"] for a in release.get("assets", [])}
    if APK_NAME not in names:
        raise SystemExit(f"latest release {release.get('tag_name')} has no {APK_NAME} asset")
    return release["tag_name"]


def build(out: Path, tag: str, repo: str) -> None:
    if not TAG_RE.match(tag):
        raise SystemExit(f"unexpected release tag: {tag!r}")
    if out.exists():
        shutil.rmtree(out)
    shutil.copytree(SOURCE, out, ignore=shutil.ignore_patterns("*.md"))
    values = {
        "RELEASE_TAG": tag,
        "APK_URL": f"https://github.com/{repo}/releases/latest/download/{APK_NAME}",
    }
    page = out / "index.html"
    html = page.read_text(encoding="utf-8")
    for key, value in values.items():
        html = html.replace("{{" + key + "}}", value)
    left = re.findall(r"\{\{[A-Z_]+\}\}", html)
    if left:
        raise SystemExit(f"unfilled placeholders: {sorted(set(left))}")
    page.write_text(html, encoding="utf-8")

    # Every local file the page or stylesheet points at must exist.
    css = (out / "assets" / "site.css").read_text(encoding="utf-8")
    refs = re.findall(r'(?:href|src)="((?!https?:|#|mailto:)[^"]+)"', html)
    refs += ["assets/" + r for r in re.findall(r"url\(([^)\"']+)\)", css)]
    missing = [r for r in refs if r not in ("./",) and not (out / r).is_file()]
    if missing:
        raise SystemExit(f"missing local files: {missing}")
    print(f"built {out} for {tag}")


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--tag", help="release tag to show; default: ask GitHub for the latest release")
    ap.add_argument("--repo", default=REPO)
    args = ap.parse_args()
    build(args.out.resolve(), args.tag or latest_release(args.repo), args.repo)
    return 0


if __name__ == "__main__":
    sys.exit(main())
