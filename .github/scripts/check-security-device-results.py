#!/usr/bin/env python3
"""Require both updater device tests, their screenshots, and identical app/test APKs."""

import hashlib
import io
import json
import os
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

from PIL import Image


TEST_CLASS = "org.thoughtcrime.securesms.apkupdate.CollieUpdateSettingsTest"
METHODS = {
    "androidVerifiesThePinnedRepositorySigner",
    "updateSettingsShowTheReleaseAndOpenOnlyTheOfficialPage",
}
SCREENSHOTS = {"collie-updates-available.png", "collie-updates-failed.png"}


def apk_hashes(path):
    hashes = {}
    for line in path.read_text().splitlines():
        digest, filename = line.split(maxsplit=1)
        if not re.fullmatch(r"[0-9a-f]{64}", digest) or filename in hashes:
            raise ValueError("Invalid or duplicate APK digest")
        hashes[filename] = digest
    if len(hashes) != 2 or sum("/androidTest/" in name for name in hashes) != 1:
        raise ValueError("Require exactly one app and one instrumentation APK hash")
    return hashes


def check_results(root, build_outcome, device_outcome):
    evidence = root / "build/security-validation/device"
    evidence.mkdir(parents=True, exist_ok=True)
    problems = []
    methods = []
    reports = root / "app/build/outputs/androidTest-results"
    for path in sorted(reports.rglob("TEST-*.xml")):
        report = ET.parse(path).getroot()
        for suite in report.iter("testsuite"):
            if any(int(suite.get(key, "0")) != 0 for key in ("failures", "errors", "skipped")):
                problems.append(f"Test report contains a failure, error, or skip: {path}")
        for case in report.iter("testcase"):
            name = case.get("name")
            if case.get("classname") != TEST_CLASS or name not in METHODS:
                problems.append(f"Unexpected device testcase: {case.get('classname')}#{name}")
            if any(case.find(tag) is not None for tag in ("failure", "error", "skipped")):
                problems.append(f"Device testcase failed or skipped: {name}")
            methods.append(name)
    if len(methods) != 2 or set(methods) != METHODS:
        problems.append("Exactly both named device methods must execute")

    screenshots = {}
    screenshot_roots = [evidence / "screenshots", root / "app/build/outputs/connected_android_test_additional_output"]
    for directory in screenshot_roots:
        for path in sorted(directory.rglob("*.png")):
            if path.name not in SCREENSHOTS:
                continue
            data = path.read_bytes()
            try:
                with Image.open(io.BytesIO(data), formats=["PNG"]) as image:
                    image.verify()
                with Image.open(io.BytesIO(data), formats=["PNG"]) as image:
                    image.load()
            except (OSError, ValueError, SyntaxError):
                problems.append(f"Invalid screenshot: {path}")
                continue
            screenshots[str(path.relative_to(root))] = hashlib.sha256(data).hexdigest()
    missing_screenshots = SCREENSHOTS - {Path(path).name for path in screenshots}
    if missing_screenshots:
        problems.append("Missing screenshots: " + ", ".join(sorted(missing_screenshots)))

    before = apk_hashes(evidence / "apk-sha256.before.txt")
    after = apk_hashes(evidence / "apk-sha256.after.txt")
    if before != after:
        problems.append("App or instrumentation APK changed between assembly and device execution")
    if (evidence / "exit-code.txt").read_text().strip() != "0":
        problems.append("Connected test command did not succeed")
    if (evidence / "device-api.txt").read_text().strip() != "35":
        problems.append("Device was not API 35")
    if not (evidence / "device-fingerprint.txt").read_text().strip():
        problems.append("Missing device fingerprint")
    if build_outcome != "success" or device_outcome != "success":
        problems.append(f"Build outcome: {build_outcome}; device outcome: {device_outcome}")

    result = {
        "class": TEST_CLASS,
        "executed_methods": sorted(methods),
        "apk_sha256": before,
        "screenshots_sha256": screenshots,
        "problems": problems,
    }
    (evidence / "test-summary.json").write_text(json.dumps(result, indent=2, sort_keys=True) + "\n")
    print(json.dumps(result, indent=2, sort_keys=True))
    if problems:
        raise ValueError("\n".join(problems))
    return result


def main():
    try:
        check_results(Path.cwd(), os.environ.get("BUILD_OUTCOME"), os.environ.get("DEVICE_OUTCOME"))
    except (OSError, ValueError, ET.ParseError) as error:
        print(f"Updater device verification failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
