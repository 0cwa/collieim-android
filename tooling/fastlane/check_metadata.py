#!/usr/bin/env python3
"""Check fastlane/metadata/android against the IzzyOnDroid and F-Droid limits.

Run from the repository root. Every locale is size-checked; en-US must be complete and must
have a changelog for gradle.properties' releaseVersionCode, because clients show the changelog
from the same commit the release is tagged on.
"""
import re
import struct
import sys
from pathlib import Path

ROOT = Path("fastlane/metadata/android")
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


def main():
    for locale in sorted(p for p in ROOT.iterdir() if p.is_dir()):
        check_locale(locale)
    english = ROOT / "en-US"
    code = re.search(r"^releaseVersionCode=(\d+)$", Path("gradle.properties").read_text(), re.M)
    required = ["title.txt", "short_description.txt", "full_description.txt", "images/icon.png"]
    if code:
        required.append(f"changelogs/{code[1]}.txt")
    errors.extend(f"{english / name}: missing" for name in required if not (english / name).is_file())
    for error in errors:
        print(f"::error::{error}")
    if not errors:
        print("Fastlane metadata is within limits.")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
