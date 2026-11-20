#!/usr/bin/env python3
"""Check Fastlane metadata against the IzzyOnDroid and F-Droid limits.

Run from the repository root. Every locale of both editions is size-checked, and the Stable
en-US listing must be complete. The release changelog for gradle.properties' releaseVersionCode
is required only under the selected edition (--edition stable|beta, default stable), because
clients show the changelog from the same commit the release is tagged on, and the Beta release
route accepts only its own edition's changelog.
"""
import argparse
import re
import struct
import sys
from pathlib import Path

ROOT = Path("fastlane/metadata/android")
EDITION_ROOTS = {"stable": ROOT, "beta": Path("fastlane/metadata/trailblazer-beta")}
TEXT_LIMITS = {"title.txt": 30, "short_description.txt": 80, "full_description.txt": 3700}
FEATURE_SIZES = {(512, 250), (1024, 500)}
errors = []


def png_size(path):
    head = path.read_bytes()[:24]
    if head[:8] != b"\x89PNG\r\n\x1a\n":
        errors.append(f"{path}: not a PNG")
        return 0, 0
    return struct.unpack(">II", head[16:24])


def check_locale(locale):
    for name, limit in TEXT_LIMITS.items():
        path = locale / name
        if path.exists() and len(path.read_text(encoding="utf-8").strip()) > limit:
            errors.append(f"{path}: over {limit} characters")
    description = locale / "full_description.txt"
    if description.exists():
        text = description.read_text(encoding="utf-8").strip()
        if "<" in text and "\n" in text:
            errors.append(f"{description}: keep HTML on one line, or F-Droid inserts line breaks")
    for path in (locale / "changelogs").glob("*"):
        data = path.read_bytes()
        if not re.fullmatch(r"[1-9][0-9]*\.txt", path.name):
            errors.append(f"{path}: must be named <versionCode>.txt")
        if len(data) > 500 or not data.isascii():
            errors.append(f"{path}: must be plain ASCII, at most 500 bytes")
    icon = locale / "images/icon.png"
    if icon.exists():
        width, height = png_size(icon)
        if width != height or not 48 <= width <= 512:
            errors.append(f"{icon}: must be square, 48 to 512 px, is {width}x{height}")
    feature = locale / "images/featureGraphic.png"
    if feature.exists() and png_size(feature) not in FEATURE_SIZES:
        errors.append(f"{feature}: must be 512x250 or 1024x500")
    for shot in (locale / "images/phoneScreenshots").glob("*"):
        if shot.suffix not in {".png", ".jpg"}:
            errors.append(f"{shot}: screenshots must be PNG or JPG")
        elif shot.suffix == ".png":
            width, height = png_size(shot)
            if max(width, height) > 2 * min(width, height):
                errors.append(f"{shot}: long edge must be at most twice the short edge")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--edition", choices=sorted(EDITION_ROOTS), default="stable")
    edition = parser.parse_args(argv).edition
    for root in EDITION_ROOTS.values():
        if root.is_dir():
            for locale in sorted(p for p in root.iterdir() if p.is_dir()):
                check_locale(locale)
    english = ROOT / "en-US"
    required = ["title.txt", "short_description.txt", "full_description.txt", "images/icon.png"]
    errors.extend(f"{english / name}: missing" for name in required if not (english / name).is_file())
    code = re.search(r"^releaseVersionCode=(\d+)$", Path("gradle.properties").read_text(), re.M)
    if code:
        changelog = EDITION_ROOTS[edition] / "en-US" / "changelogs" / f"{code[1]}.txt"
        if not changelog.is_file():
            errors.append(f"{changelog}: missing for the {edition} edition")
    for error in errors:
        print(f"::error::{error}")
    if not errors:
        print("Fastlane metadata is within limits.")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
