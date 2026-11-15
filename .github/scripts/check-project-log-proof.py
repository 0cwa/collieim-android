#!/usr/bin/env python3
"""Gate the hosted API 35 Project log screenshots and external PR156 handoff."""

import os
from pathlib import Path
import re
import struct
import sys
from urllib.parse import urlsplit
import xml.etree.ElementTree as ET
import zlib


CLASS = "org.thoughtcrime.securesms.main.setup.ProjectLogScreenshotTest"
METHODS = {
    "whatsNewShowsThisUpdateAndTheProjectLog",
    "helpOpensTheProjectLogAndOlderReleasesExpand",
    "projectLogDisclosureOpensPr156InTheExternalBrowser",
}
SCREENSHOTS = {
    "01-light-this-update.png",
    "02-light-project-log.png",
    "03-dark-help.png",
    "04-dark-project-log-from-help.png",
    "05-light-pr156-disclosure.png",
    "06-light-pr156-entry.png",
    "07-light-pr156-browser.png",
}
TARGET_URL = "https://github.com/0cwa/collieim-android/pull/156"
TARGET_TITLE = "Add a project log to What's new, and What's new to Settings"
ANDROID = "{http://schemas.android.com/apk/res/android}"


def apk_hashes(path):
    hashes = {}
    for line in path.read_text().splitlines():
        digest, filename = line.split(maxsplit=1)
        if not re.fullmatch(r"[0-9a-f]{64}", digest) or filename in hashes:
            raise ValueError("invalid or duplicate APK digest")
        hashes[filename] = digest
    if len(hashes) != 2 or sum("/androidTest/" in name for name in hashes) != 1:
        raise ValueError("expected exactly one app APK and one instrumentation APK")
    if not any("/prodWebsite/debug/" in name and "/androidTest/" not in name for name in hashes):
        raise ValueError("missing prodWebsite debug app APK hash")
    return hashes


def decode_png(path, title_bounds=None):
    """Decode the RGB/RGBA PNGs produced by Android and reject blank captures."""
    data = path.read_bytes()
    if not data.startswith(b"\x89PNG\r\n\x1a\n"):
        raise ValueError(f"not a PNG: {path.name}")
    offset = 8
    image_data = bytearray()
    dimensions = None
    saw_end = False
    while offset < len(data):
        if offset + 12 > len(data):
            raise ValueError(f"truncated PNG chunk: {path.name}")
        size = struct.unpack_from(">I", data, offset)[0]
        kind = data[offset + 4:offset + 8]
        end = offset + 12 + size
        if end > len(data):
            raise ValueError(f"truncated PNG payload: {path.name}")
        payload = data[offset + 8:offset + 8 + size]
        crc = struct.unpack_from(">I", data, offset + 8 + size)[0]
        if zlib.crc32(kind + payload) & 0xFFFFFFFF != crc:
            raise ValueError(f"invalid PNG CRC: {path.name}")
        if kind == b"IHDR":
            if dimensions is not None or size != 13:
                raise ValueError(f"invalid PNG header: {path.name}")
            width, height, depth, color, compression, filtering, interlace = struct.unpack(">IIBBBBB", payload)
            if depth != 8 or color not in (2, 6) or compression or filtering or interlace:
                raise ValueError(f"unsupported Android screenshot encoding: {path.name}")
            dimensions = (width, height, 3 if color == 2 else 4)
        elif kind == b"IDAT":
            image_data.extend(payload)
        elif kind == b"IEND":
            saw_end = True
            if end != len(data):
                raise ValueError(f"trailing PNG data: {path.name}")
            break
        offset = end

    if dimensions is None or not saw_end:
        raise ValueError(f"incomplete PNG: {path.name}")
    width, height, channels = dimensions
    if min(width, height) < 50:
        raise ValueError(f"undersized screenshot: {path.name}")
    sample_left, top, sample_right, bottom = title_bounds or (0, 0, width, height)
    if not (0 <= sample_left < sample_right <= width and 0 <= top < bottom <= height) or (title_bounds and min(sample_right - sample_left, bottom - top) < 8):
        raise ValueError("invalid browser title bounds")
    x_step = max(1, (sample_right - sample_left) // 80) if title_bounds else 8
    y_step = max(1, (bottom - top) // 20) if title_bounds else 8
    stride = width * channels
    try:
        raw = zlib.decompress(image_data)
    except zlib.error as error:
        raise ValueError(f"invalid PNG image data: {path.name}") from error
    if len(raw) != height * (stride + 1):
        raise ValueError(f"invalid PNG scanline size: {path.name}")

    previous = bytearray(stride)
    sums = [0, 0, 0]
    squares = [0, 0, 0]
    samples = 0
    position = 0
    for y in range(height):
        filter_type = raw[position]
        scanline = raw[position + 1:position + 1 + stride]
        position += stride + 1
        current = bytearray(stride)
        for index, value in enumerate(scanline):
            left = current[index - channels] if index >= channels else 0
            above = previous[index]
            upper_left = previous[index - channels] if index >= channels else 0
            if filter_type == 0:
                predictor = 0
            elif filter_type == 1:
                predictor = left
            elif filter_type == 2:
                predictor = above
            elif filter_type == 3:
                predictor = (left + above) // 2
            elif filter_type == 4:
                base = left + above - upper_left
                distances = (abs(base - left), abs(base - above), abs(base - upper_left))
                predictor = (left, above, upper_left)[distances.index(min(distances))]
            else:
                raise ValueError(f"invalid PNG filter: {path.name}")
            current[index] = (value + predictor) & 0xFF
        if top <= y < bottom and (y - top) % y_step == 0:
            for x in range(sample_left, sample_right, x_step):
                pixel = x * channels
                for channel in range(3):
                    component = current[pixel + channel]
                    sums[channel] += component
                    squares[channel] += component * component
                samples += 1
        previous = current

    if not samples or max(squares[i] / samples - (sums[i] / samples) ** 2 for i in range(3)) < 1:
        raise ValueError(f"blank {'browser title' if title_bounds else 'screenshot'}: {path.name}")


def check(root, build_outcome, device_outcome, expected_sha):
    evidence = root / "build/project-log-proof"
    if (build_outcome, device_outcome) != ("success", "success"):
        raise ValueError("build or API 35 device step failed")

    source = (evidence / "source.txt").read_text().splitlines()
    if len(source) != 2 or any(not re.fullmatch(r"[0-9a-f]{40}", value) for value in source):
        raise ValueError("missing or malformed source SHA/tree identity")
    if source[0] != expected_sha:
        raise ValueError("checked-out source SHA does not match the selected workflow SHA")
    if (evidence / "exit-code.txt").read_text().strip() != "0":
        raise ValueError("connected instrumentation command failed")
    if (evidence / "device-api.txt").read_text().strip() != "35":
        raise ValueError("device was not API 35")
    if not (evidence / "device-fingerprint.txt").read_text().strip():
        raise ValueError("missing API 35 device fingerprint")

    app = ET.parse(evidence / "app-manifest.xml").getroot()
    test = ET.parse(evidence / "test-manifest.xml").getroot()
    runners = test.findall("instrumentation")
    if app.get("package") != "im.collie.app" or test.get("package") != "im.collie.app.test":
        raise ValueError("unexpected app or instrumentation APK package")
    if len(runners) != 1 or runners[0].get(ANDROID + "name") != "org.thoughtcrime.securesms.testing.SignalTestRunner" or runners[0].get(ANDROID + "targetPackage") != "im.collie.app":
        raise ValueError("instrumentation APK does not use the normal Signal test runner")

    before = apk_hashes(evidence / "apk-before.txt")
    after = apk_hashes(evidence / "apk-after.txt")
    if before != after:
        raise ValueError("app or instrumentation APK changed during the run")

    reports = list((root / "app/build/outputs/androidTest-results").rglob("TEST-*.xml"))
    cases = []
    total_tests = 0
    for report_path in reports:
        report = ET.parse(report_path).getroot()
        suites = [report] if report.tag == "testsuite" else list(report.iter("testsuite"))
        if not suites:
            raise ValueError(f"unexpected instrumentation report format: {report_path}")
        for suite in suites:
            tests = int(suite.get("tests", "0"))
            total_tests += tests
            if tests < 0 or any(int(suite.get(name, "0")) != 0 for name in ("failures", "errors", "skipped")):
                raise ValueError("instrumentation report contains failures, errors, or skips")
        for case in report.iter("testcase"):
            if case.get("classname") != CLASS or any(case.find(tag) is not None for tag in ("failure", "error", "skipped")):
                raise ValueError("unexpected test class or testcase failure, error, or skip")
            cases.append(case.get("name"))
    if total_tests != len(METHODS) or len(cases) != len(METHODS) or set(cases) != METHODS:
        raise ValueError(f"expected exactly three ProjectLogScreenshotTest methods; found {cases}")

    screenshot_paths = list((evidence / "screenshots").rglob("*.png"))
    if len(screenshot_paths) != len(SCREENSHOTS) or {path.name for path in screenshot_paths} != SCREENSHOTS:
        raise ValueError("missing, duplicate, or unexpected Project log screenshots")
    handoff = {}
    for line in (evidence / "screenshots/browser-handoff.txt").read_text().splitlines():
        key, value = line.split("=", 1)
        if key in handoff:
            raise ValueError("duplicate browser handoff evidence field")
        handoff[key] = value
    bounds = handoff.get("title_bounds", "")
    if not re.fullmatch(r"[0-9]+,[0-9]+,[0-9]+,[0-9]+", bounds):
        raise ValueError("missing or malformed browser title bounds")
    title_bounds = tuple(int(value) for value in bounds.split(","))
    for path in screenshot_paths:
        decode_png(path, title_bounds=title_bounds if path.name == "07-light-pr156-browser.png" else None)

    address = handoff.get("address", "")
    observed_url = urlsplit(address if "://" in address else "https://" + address)
    expected_url = urlsplit(TARGET_URL)
    if handoff.get("url") != TARGET_URL or observed_url.scheme != expected_url.scheme or observed_url.hostname != expected_url.hostname or observed_url.path.rstrip("/") != expected_url.path:
        raise ValueError("external browser did not show the PR156 URL")
    if not handoff.get("browser") or handoff["browser"] == "im.collie.app":
        raise ValueError("handoff did not reach an external browser")
    if handoff.get("rendered_title") != TARGET_TITLE or handoff.get("rendered_pr") != "#156":
        raise ValueError("external browser did not render the public PR156 page")
    print("Passed three ProjectLogScreenshotTest methods with matching API 35 APKs, seven decoded captures, and the external PR156 page")


if __name__ == "__main__":
    try:
        check(Path.cwd(), os.environ.get("BUILD_OUTCOME"), os.environ.get("DEVICE_OUTCOME"), os.environ.get("EXPECTED_SHA", ""))
    except (OSError, ValueError, ET.ParseError, KeyError) as error:
        print(f"Project log hosted proof failed: {error}", file=sys.stderr)
        sys.exit(1)
