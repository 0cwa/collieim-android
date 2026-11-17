#!/usr/bin/env python3
"""Build the collie.im website into an output directory.

The page source is docs/site/. This script copies it, fills in the latest
release tag and the APK link, and generates unlinked patch/release pages from
reviewed source evidence. It fails if a placeholder or stale source record remains.

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
import struct
import urllib.request
import zlib
from urllib.parse import quote
from pathlib import Path

from qrcodegen import QrCode
from receipt import pipeline

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "docs" / "site"
REPO = "0cwa/collieim-android"
APK_NAME = "collie.apk"
TAG_RE = re.compile(r"^v[0-9][0-9A-Za-z.\-]*$")
ROADMAP_CATEGORY = "upcoming-planned-features"
ROADMAP_TITLES = {
    55: "UnifiedPush on linked devices",
    57: "Silent notifications for reactions",
    53: "Shorter disappearing timers per chat and device",
    52: "Security hardening settings",
    97: "Custom themes",
    56: "On-device translation",
    54: "On-device voice message transcription",
    106: "Send a text with its own timer",
}


DONATION_JSON = Path(__file__).with_name("donation.json")
DONATION_QR = Path("assets") / "donate-qr.png"
# Format checks only; the wallet the address came from is the checksum authority.
DONATION_ADDRESS_RE = {
    "monero": re.compile(r"^[48][1-9A-HJ-NP-Za-km-z]{94}$"),
}
DONATION_MARKER_RE = re.compile(r"[ \t]*<!-- donation:(?:start|end) -->\n")
DONATION_BLOCK_RE = re.compile(r"[ \t]*<!-- donation:start -->.*?<!-- donation:end -->\n?", re.DOTALL)
QR_SCALE = 12  # pixels per module in the PNG; the page scales it down for the thumbnail
QR_BORDER = 4  # quiet zone in modules, part of the image so page colours never touch the code


def load_donation(path: Path) -> dict | None:
    """Return the checked donation settings, or None when no address is set."""
    donation = json.loads(path.read_text(encoding="utf-8"))
    address = donation.get("address", "").strip()
    if not address:
        return None
    scheme = donation.get("uri_scheme", "")
    pattern = DONATION_ADDRESS_RE.get(scheme)
    if pattern is None:
        raise SystemExit(f"no address check for donation URI scheme {scheme!r}; add one to DONATION_ADDRESS_RE")
    if not pattern.match(address):
        raise SystemExit(f"donation address does not look like a {donation.get('name', scheme)} address")
    for key in ("name", "ticker"):
        if not re.fullmatch(r"[A-Za-z0-9 ]{1,24}", donation.get(key, "")):
            raise SystemExit(f"donation {key} must be 1-24 letters, digits or spaces")
    uri = f"{scheme}:{address}"
    # Optional label the sender's wallet shows; it is not part of the transaction.
    recipient = donation.get("recipient_name", "")
    if recipient:
        if not re.fullmatch(r"[A-Za-z0-9 ]{1,32}", recipient):
            raise SystemExit("donation recipient_name must be 1-32 letters, digits or spaces")
        uri += "?recipient_name=" + quote(recipient)
    return {"name": donation["name"], "ticker": donation["ticker"], "address": address, "uri": uri}


def qr_png(text: str) -> tuple[bytes, int]:
    """Encode text as an opaque black-on-white PNG; returns (bytes, width in modules)."""
    qr = QrCode.encode_text(text, QrCode.Ecc.MEDIUM)
    modules = qr.get_size() + 2 * QR_BORDER
    rows = []
    for y in range(modules):
        row = bytes(
            0 if qr.get_module(x - QR_BORDER, y - QR_BORDER) else 255
            for x in range(modules)
            for _ in range(QR_SCALE)
        )
        rows.extend([b"\x00" + row] * QR_SCALE)
    size = modules * QR_SCALE

    def chunk(kind: bytes, data: bytes) -> bytes:
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))

    png = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", size, size, 8, 0, 0, 0, 0))
    png += chunk(b"IDAT", zlib.compress(b"".join(rows), 9)) + chunk(b"IEND", b"")
    return png, modules


def render_address(address: str) -> str:
    """Mark the first and last six characters, the part people compare after pasting."""
    head, middle, tail = address[:6], address[6:-6], address[-6:]
    return f'<span class="address-edge">{head}</span>{middle}<span class="address-edge">{tail}</span>'


def is_priority(node: dict) -> bool:
    return any(label["name"].lower() == "priority" for label in node.get("labels", {}).get("nodes", []))


def display_roadmap_title(node: dict) -> str:
    if int(node["number"]) in ROADMAP_TITLES:
        return ROADMAP_TITLES[int(node["number"])]
    title = node["title"].strip()
    prefix = r"^(?:high\s+priority\s*[-:–—]\s*|\[[^\]]+\]\s*[-:–—]?\s*)"
    while cleaned := re.sub(prefix, "", title, count=1, flags=re.IGNORECASE):
        if cleaned == title:
            break
        title = cleaned.strip(" \t:-–—")
    return title or node["title"]


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
        not is_priority(node),
        -int(node["number"]),
    ))
    if not planned:
        return "<li>No upcoming features are listed right now. Share an idea in our forum.</li>"
    items = []
    for node in planned:
        priority = is_priority(node)
        item_class = ' class="priority-plan"' if priority else ""
        badge = '<span class="plan-badge">Priority</span>' if priority else ""
        title = html.escape(display_roadmap_title(node))
        items.append(f'<li{item_class}>{badge}<a href="https://github.com/{repo}/discussions/{int(node["number"])}">{title}</a></li>')
    return "\n      ".join(items)


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



def published_tags(releases):
    """Only published non-draft identities; summaries remain editorial."""
    from datetime import datetime
    tags = {}
    if not isinstance(releases, list):
        raise ValueError('Release index must be an array')
    for release in releases:
        if release.get('draft') is not False or not release.get('published_at'):
            continue
        tag = release.get('tag_name', '')
        if not TAG_RE.fullmatch(tag):
            raise ValueError('Invalid published release tag')
        when = datetime.fromisoformat(release['published_at'].replace('Z', '+00:00'))
        if when.tzinfo is None:
            raise ValueError('Published release timestamp must include timezone')
        tags[tag] = max(tags.get(tag, when), when)
    return sorted(tags, key=lambda tag: (tags[tag], tag), reverse=True)


def fetch_releases(repo, max_pages=5):
    """Bound discovery to 500 entries; fail instead of silently truncating."""
    releases = []
    for page in range(1, max_pages + 1):
        req = urllib.request.Request(
            f'https://api.github.com/repos/{repo}/releases?per_page=100&page={page}',
            headers={'Accept': 'application/vnd.github+json', 'User-Agent': 'collie-site-build'})
        token = os.environ.get('GITHUB_TOKEN')
        if token:
            req.add_header('Authorization', f'Bearer {token}')
        with urllib.request.urlopen(req, timeout=30) as response:
            batch = json.load(response)
        if not isinstance(batch, list):
            raise ValueError('GitHub release index must be an array')
        releases.extend(batch)
        if len(batch) < 100:
            return published_tags(releases)
    raise ValueError('Release index exceeded 500 entries; review discovery bound before publishing')

def validate_output(out: Path) -> Path:
    """Only replace disposable directories, never source or checkout files."""
    requested = Path(os.path.abspath(out))
    resolved = out.resolve()
    root = ROOT.resolve()
    sources = (Path(os.path.abspath(SOURCE)), SOURCE.resolve())
    # Keep these lexical: resolving an allowlisted symlink could bless its target.
    disposable = (root / "_site", root / "build")
    for destination in (requested, resolved):
        for source in sources:
            if destination == source or destination in source.parents or source in destination.parents:
                raise SystemExit("output directory must not contain or be inside the website source")
        if destination == root or destination in root.parents:
            raise SystemExit("output directory must not contain the repository")
        if root in destination.parents and not any(
            destination == directory or directory in destination.parents for directory in disposable
        ):
            raise SystemExit("output inside the repository must be in _site or build")
    return resolved


def build(out: Path, tag: str, repo: str, roadmap: list[dict] | None = None, donation_json: Path = DONATION_JSON, published: list[str] | None = None) -> None:
    out = validate_output(out)
    if not TAG_RE.match(tag):
        raise SystemExit(f"unexpected release tag: {tag!r}")
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repo):
        raise SystemExit("unexpected repository name")
    upcoming = render_roadmap(fetch_roadmap(repo) if roadmap is None else roadmap, repo)
    donation = load_donation(donation_json)
    if out.exists():
        shutil.rmtree(out)
    shutil.copytree(SOURCE, out, ignore=shutil.ignore_patterns("*.md"))
    values = {
        "RELEASE_TAG": tag,
        "APK_URL": f"https://github.com/{repo}/releases/latest/download/{APK_NAME}",
        "UPCOMING_FEATURES": upcoming,
        "OBTAINIUM_URL": "obtainium://app/" + quote(
            json.dumps(json.loads(Path(__file__).with_name("obtainium.json").read_text(encoding="utf-8")), separators=(",", ":")),
            safe="",
        ),
    }
    if donation:
        png, modules = qr_png(donation["uri"])
        (out / DONATION_QR).write_bytes(png)
        values.update({
            "DONATION_NAME": donation["name"],
            "DONATION_TICKER": donation["ticker"],
            "DONATION_ADDRESS": donation["address"],
            "DONATION_ADDRESS_HTML": render_address(donation["address"]),
            "DONATION_URI": html.escape(donation["uri"]),
            "DONATION_QR": DONATION_QR.as_posix(),
            "DONATION_QR_THUMB": str(modules * 3),
            "DONATION_QR_LARGE": str(modules * 8),
        })
    for page in out.rglob("*.html"):
        rendered = page.read_text(encoding="utf-8")
        if donation:
            rendered = DONATION_MARKER_RE.sub("", rendered)
        else:
            rendered = DONATION_BLOCK_RE.sub("", rendered)
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
    pipeline.build(out, published=published)
    print(f"built {out} for {tag}")


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--tag", help="release tag to show; default: ask GitHub for the latest release")
    ap.add_argument("--repo", default=REPO)
    ap.add_argument("--roadmap-json", type=Path, help="offline JSON array of discussion nodes (number, title, category.slug, labels.nodes)")
    ap.add_argument("--donation-json", type=Path, default=DONATION_JSON, help="donation settings; default: tooling/site/donation.json")
    ap.add_argument("--receipt-preview", type=Path, help="opt in to nonpublished pilot pages from a frozen receipt; never selects a release")
    ap.add_argument('--releases-json', type=Path, help='offline GitHub release array; --tag alone uses the reviewed release index')
    args = ap.parse_args()
    roadmap = json.loads(args.roadmap_json.read_text(encoding="utf-8")) if args.roadmap_json else None
    published = published_tags(json.loads(args.releases_json.read_text())) if args.releases_json else (
        None if args.tag else fetch_releases(args.repo))
    build(args.out, args.tag or latest_release(args.repo), args.repo, roadmap, args.donation_json, published)
    if args.receipt_preview:
        build_receipt_preview(args.out, args.receipt_preview)
    return 0


def build_receipt_preview(out: Path, frozen: Path) -> None:
    """Compatibility route set for local draft previews."""
    pipeline.build(out, receipt=json.loads(frozen.read_text(encoding='utf-8')), routes='pilot')


if __name__ == "__main__":
    sys.exit(main())
