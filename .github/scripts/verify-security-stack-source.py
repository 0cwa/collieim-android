#!/usr/bin/env python3
"""Verify that validation support leaves the pinned app's production sources unchanged."""

import argparse
import json
from pathlib import Path
import re
import subprocess
import sys


SUPPORT_FILES = {
    ".github/scripts/check-security-device-results.py",
    ".github/scripts/check-unit-test-results.py",
    ".github/scripts/verify-security-stack-source.py",
    ".github/workflows/pr.yml",
    ".github/workflows/debug-apk.yml",
    "app/src/test/java/org/thoughtcrime/securesms/testutil/MockAppDependenciesRule.kt",
    "app/src/test/java/org/thoughtcrime/securesms/testutil/SignalStoreRule.kt",
    "build-inputs/host-test-inputs.json",
    "build.gradle.kts",
    "tooling/host-tests/README.md",
    "tooling/host-tests/build_libsignal.py",
    "tooling/host-tests/test_build_libsignal.py",
    "tooling/host-tests/test_security_validation.py",
}

# These are the complete permitted root Gradle additions. Removing exactly these
# blocks must reproduce the pinned app's build script, with no production edit.
TEST_JNI_BLOCKS = (
    '// The production client JAR has no desktop JNI. Keep the separately built Linux\n'
    '// library on host test classpaths only, so it can never become an APK input.\n'
    'val hostJniDirectory = providers.gradleProperty("collieHostJniDir").orNull?.let { file(it) }\n\n',
    '    hostJniDirectory?.let { directory ->\n'
    '      val library = directory.resolve("libsignal_jni_testing_amd64.so")\n'
    '      tasks.withType<Test>().configureEach {\n'
    '        classpath = files(directory) + classpath\n'
    '        doFirst {\n'
    '          check(library.isFile) {\n'
    '            "Missing host libsignal JNI. Run python3 tooling/host-tests/build_libsignal.py first."\n'
    '          }\n'
    '        }\n'
    '      }\n'
    '    }\n\n',
)


def git(root, *arguments, strip=True):
    output = subprocess.check_output(["git", *arguments], cwd=root).decode("utf-8")
    return output.strip() if strip else output


def verify_source(root, app_sha):
    if not re.fullmatch(r"[0-9a-f]{40}", app_sha):
        raise ValueError("FINAL_APP_SHA must be the full reviewed app commit")
    if git(root, "diff", "--name-only", "HEAD", "--"):
        raise ValueError("Tracked working tree differs from the validation commit")
    if git(root, "ls-files", "--others", "--exclude-standard"):
        raise ValueError("Unexpected untracked files in the validation checkout")

    changed = set(filter(None, git(root, "diff", "--no-renames", "--name-only", app_sha, "HEAD", "--").splitlines()))
    unexpected = changed - SUPPORT_FILES
    if unexpected:
        raise ValueError("Validation changed pinned app files: " + ", ".join(sorted(unexpected)))

    baseline = git(root, "show", f"{app_sha}:build.gradle.kts", strip=False)
    current = git(root, "show", "HEAD:build.gradle.kts", strip=False)
    if current != baseline:
        for block in TEST_JNI_BLOCKS:
            if current.count(block) != 1:
                raise ValueError("The test-only JNI Gradle configuration does not match the reviewed support code")
            current = current.replace(block, "", 1)
        if current != baseline:
            raise ValueError("Root Gradle differs from pinned app beyond the test-only JNI configuration")

    return {
        "app_sha": app_sha,
        "app_tree": git(root, "rev-parse", f"{app_sha}^{{tree}}"),
        "validation_sha": git(root, "rev-parse", "HEAD"),
        "validation_tree": git(root, "rev-parse", "HEAD^{tree}"),
        "production_sources_match": True,
        "support_files_changed": sorted(changed),
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("app_sha")
    parser.add_argument("--root", type=Path, default=Path.cwd())
    parser.add_argument("--receipt", type=Path, default=Path("build/security-validation/source-receipt.json"))
    args = parser.parse_args()
    try:
        receipt = verify_source(args.root, args.app_sha)
        args.receipt.parent.mkdir(parents=True, exist_ok=True)
        args.receipt.write_text(json.dumps(receipt, indent=2, sort_keys=True) + "\n")
        print(json.dumps(receipt, indent=2, sort_keys=True))
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        print(f"Pinned app source verification failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
