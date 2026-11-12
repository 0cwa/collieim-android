#!/usr/bin/env python3
"""Build the collie.im website into an output directory.

The page source is docs/site/. This script copies it, fills in the latest
release tag and the APK link, and fails if a placeholder is left behind.

    python3 tooling/site/build.py --out _site            # asks GitHub for the latest release
    python3 tooling/site/build.py --out _site --tag v1.2  # offline, fixed tag
"""
from __future__ import annotations

import argparse
import html
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
ROADMAP_CATEGORY = "upcoming-planned-features"


def fetch_roadmap(repo: str) -> list[dict]:
    token = os.environ.get("GITHUB_TOKEN") or os.environ.get("GH_TOKEN")
    if not token:
        raise SystemExit("GitHub Discussions needs GITHUB_TOKEN; use --roadmap-json for an offline build")
    owner, name = repo.split("/", 1)
    query = """query($owner:String!,$name:String!,$after:String) {
      repository(owner:$owner,name:$name) {
        discussions(first:100,after:$after,orderBy:{field:UPDATED_AT,direction:DESC}) {
          pageInfo { hasNextPage endCursor }
          nodes { number title category { slug } labels(first:100) { nodes { name } } }
        }
      }
    }"""
    nodes = []
    cursor = None
    while True:
        req = urllib.request.Request(
            "https://api.github.com/graphql",
            data=json.dumps({"query": query, "variables": {"owner": owner, "name": name, "after": cursor}}).encode(),
            headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json", "User-Agent": "collie-site-build"},
        )
        with urllib.request.urlopen(req, timeout=30) as response:
            result = json.load(response)
        if result.get("errors"):
            raise SystemExit("Could not read the upcoming-features discussions; check discussions:read permission")
        connection = result["data"]["repository"]["discussions"]
        nodes.extend(connection["nodes"])
        page = connection["pageInfo"]
        if not page["hasNextPage"]:
            return nodes
        if not page["endCursor"] or page["endCursor"] == cursor:
            raise SystemExit("GitHub Discussions pagination did not advance")
        cursor = page["endCursor"]


def render_roadmap(nodes: list[dict], repo: str) -> str:
    planned = [node for node in nodes if node["category"]["slug"] == ROADMAP_CATEGORY]
    planned.sort(key=lambda node: (
        not any(label["name"].lower() == "priority" for label in node.get("labels", {}).get("nodes", [])),
        -int(node["number"]),
    ))
    if not planned:
        return "<li>No upcoming features are listed right now. Share an idea in our forum.</li>"
    return "\n      ".join(
        f'<li><a href="https://github.com/{repo}/discussions/{int(node["number"])}">{html.escape(node["title"])}</a></li>'
        for node in planned
    )


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


def build(out: Path, tag: str, repo: str, roadmap: list[dict] | None = None) -> None:
    out = out.resolve()
    if out == SOURCE.resolve() or out in SOURCE.resolve().parents:
        raise SystemExit("output directory must not contain the website source")
    if not TAG_RE.match(tag):
        raise SystemExit(f"unexpected release tag: {tag!r}")
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repo):
        raise SystemExit("unexpected repository name")
    upcoming = render_roadmap(fetch_roadmap(repo) if roadmap is None else roadmap, repo)
    if out.exists():
        shutil.rmtree(out)
    shutil.copytree(SOURCE, out, ignore=shutil.ignore_patterns("*.md"))
    values = {
        "RELEASE_TAG": tag,
        "APK_URL": f"https://github.com/{repo}/releases/latest/download/{APK_NAME}",
        "UPCOMING_FEATURES": upcoming,

    }
    for page in out.rglob("*.html"):
        rendered = page.read_text(encoding="utf-8")
        for key, value in values.items():
            rendered = rendered.replace("{{" + key + "}}", value)
        left = re.findall(r"\{\{[A-Z_]+\}\}", rendered)
        if left:
            raise SystemExit(f"unfilled placeholders in {page.name}: {sorted(set(left))}")
        page.write_text(rendered, encoding="utf-8")

    # Every local file the page or stylesheet points at must exist.
    css = (out / "assets" / "site.css").read_text(encoding="utf-8")
    refs = [(out / "assets" / ref) for ref in re.findall(r"url\(([^)\"']+)\)", css)]
    for page in out.rglob("*.html"):
        refs += [page.parent / ref for ref in re.findall(r'(?:href|src)="((?![a-zA-Z][a-zA-Z0-9+.-]*:|#)[^"]+)"', page.read_text(encoding="utf-8"))]
    missing = [str(ref.relative_to(out)) for ref in refs if not ref.is_file() and not (ref / "index.html").is_file()]
    if missing:
        raise SystemExit(f"missing local files: {missing}")
    print(f"built {out} for {tag}")


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--tag", help="release tag to show; default: ask GitHub for the latest release")
    ap.add_argument("--repo", default=REPO)
    ap.add_argument("--roadmap-json", type=Path, help="offline JSON array of discussion nodes (number, title, category.slug, labels.nodes)")
    args = ap.parse_args()
    roadmap = json.loads(args.roadmap_json.read_text(encoding="utf-8")) if args.roadmap_json else None
    build(args.out.resolve(), args.tag or latest_release(args.repo), args.repo, roadmap)
    return 0


if __name__ == "__main__":
    sys.exit(main())
