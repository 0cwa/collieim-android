#!/usr/bin/env python3
"""Compile the repository's JNI module with its pinned Android NDK for CodeQL."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tomllib


KNOWN_ABIS = {"armeabi-v7a", "arm64-v8a", "x86", "x86_64"}


def configuration(repo: Path) -> tuple[str, int, list[str]]:
    with (repo / "gradle/libs.versions.toml").open("rb") as stream:
        versions = tomllib.load(stream)["versions"]
    ndk = versions["ndk"]
    if not isinstance(ndk, str) or not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", ndk):
        raise ValueError("Invalid pinned NDK revision")
    release = json.loads((repo / "molly-build.json").read_text(encoding="utf-8"))
    if release["android"]["ndk"] != ndk:
        raise ValueError("Release and Gradle NDK pins differ")
    minimum = versions["minSdk"]
    if not isinstance(minimum, str) or not re.fullmatch(r"[1-9][0-9]", minimum) or int(minimum) < 21:
        raise ValueError("The pinned NDK requires a supported Android minimum API")
    application = (repo / "app/jni/Application.mk").read_text(encoding="utf-8")
    declarations = re.findall(r"^APP_ABI\s*:?=\s*([^#\r\n]+)", application, re.MULTILINE)
    if len(declarations) != 1:
        raise ValueError("Expected one explicit APP_ABI declaration in Application.mk")
    abis = declarations[0].split()
    if not abis or len(set(abis)) != len(abis) or not set(abis) <= KNOWN_ABIS:
        raise ValueError("Review the JNI ABI list before updating this analysis build")
    return ndk, int(minimum), abis


def build(repo: Path, ndk: Path, output: Path) -> dict:
    repo, ndk, output = repo.resolve(), ndk.resolve(), output.resolve()
    revision, minimum, abis = configuration(repo)
    properties = dict(re.findall(r"^\s*([^#=\s]+)\s*=\s*(.*?)\s*$",
                                (ndk / "source.properties").read_text(encoding="utf-8"), re.MULTILINE))
    if properties.get("Pkg.Revision") != revision:
        raise ValueError(f"Install Android NDK {revision}; the provided NDK has a different revision")
    executable = ndk / "ndk-build"
    jni_header = ndk / "toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/include/jni.h"
    if not executable.is_file() or not os.access(executable, os.X_OK) or not jni_header.is_file():
        raise ValueError("A complete Linux Android NDK, including its JNI headers, is required")
    make = shutil.which("make")
    if not make:
        raise ValueError("GNU Make is required for the JNI analysis build")
    make_version = subprocess.run([make, "--version"], check=True, capture_output=True, text=True).stdout.splitlines()[0]
    if not make_version.startswith("GNU Make "):
        raise ValueError("The selected make executable is not GNU Make")
    if output.is_relative_to(repo) or output.is_relative_to(ndk):
        raise ValueError("Keep analysis build output outside the source tree and NDK")
    # A fresh directory plus -B and disabled ccache ensures actual compiler
    # invocations occur under CodeQL; no compiled outputs are restored.
    output.mkdir(parents=True, exist_ok=False)
    command = [
        str(executable), "-B", "-j2", "V=1", f"NDK_PROJECT_PATH={repo}",
        f"APP_BUILD_SCRIPT={repo / 'app/jni/Android.mk'}",
        f"NDK_APPLICATION_MK={repo / 'app/jni/Application.mk'}",
        # The historical makefile still says android-19. Use the current app's
        # minSdk (23) with its current NDK; do not silently rely on NDK fallback.
        f"APP_PLATFORM=android-{minimum}", "APP_ABI=" + " ".join(abis),
        f"NDK_OUT={output / 'obj'}", f"NDK_LIBS_OUT={output / 'lib'}", "NDK_CCACHE=",
    ]
    # ndk-build supports GNUMAKE explicitly. Use the runner's GNU Make rather
    # than the NDK's older bundled executable, which can crash on modern hosts.
    # Preserve CodeQL's tracing environment when selecting the build driver.
    # EDG's documented USR_INCLUDE replaces its /usr/include fallback. Without
    # it, bionic's limits.h include_next can pull host glibc into the CodeQL
    # parse, even though Android Clang correctly uses only the NDK sysroot.
    # Point the fallback at the same real Android headers; no headers are stubbed
    # and no compiler or extraction error is ignored.
    include_root = jni_header.parent
    subprocess.run(command, cwd=repo, env={**os.environ, "GNUMAKE": make,
                                          "USR_INCLUDE": str(include_root)}, check=True)
    artifacts = []
    for abi in abis:
        binary = output / "lib" / abi / "libnative-utils.so"
        if not binary.is_file() or binary.stat().st_size == 0:
            raise ValueError(f"JNI build did not produce libnative-utils.so for {abi}")
        artifacts.append({"abi": abi, "path": str(binary),
                          "sha256": hashlib.sha256(binary.read_bytes()).hexdigest()})
    result = {"schema": 1, "purpose": "CodeQL extraction build, not a release artifact",
              "ndk_revision": revision, "android_api": minimum, "abis": abis,
              "build_script": "app/jni/Android.mk", "gnu_make": make_version,
              "extractor_default_include": str(include_root),
              "command": command, "artifacts": artifacts}
    (output / "build.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--ndk", type=Path, required=True, help="Installed, pinned Linux Android NDK directory")
    parser.add_argument("--output", type=Path, required=True, help="New directory outside the source tree")
    args = parser.parse_args()
    try:
        result = build(args.repo, args.ndk, args.output)
    except (OSError, ValueError, KeyError, subprocess.CalledProcessError) as error:
        parser.exit(1, f"JNI analysis build failed: {error}\n")
    print(f"Built JNI module with Android NDK {result['ndk_revision']}, "
          f"API {result['android_api']}, ABIs: {', '.join(result['abis'])}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
