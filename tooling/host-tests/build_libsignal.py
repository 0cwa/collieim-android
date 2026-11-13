#!/usr/bin/env python3
"""Build Collie's pinned libsignal JNI for Linux host tests, never for an APK."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import platform
import re
import shutil
import subprocess
import sys
import tomllib
import urllib.request


LIBSIGNAL_ORIGIN = "https://github.com/signalapp/libsignal.git"
RESOURCE = "libsignal_jni_testing_amd64.so"
TARGET = "x86_64-unknown-linux-gnu"


class Failure(Exception):
    pass


def require(condition, message):
    if not condition:
        raise Failure(message)


def run(command, *, cwd=None, env=None, capture=False):
    print("+ " + " ".join(map(str, command)), flush=True)
    result = subprocess.run(command, cwd=cwd, env=env, check=True, text=True,
                            stdout=subprocess.PIPE if capture else None)
    return result.stdout.strip() if capture else None


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def load_inputs(root):
    lock = json.loads((root / "build-inputs/native-lock.json").read_text())
    engineering = json.loads((root / "build-inputs/engineering.json").read_text())
    host = json.loads((root / "build-inputs/host-test-inputs.json").read_text())
    patches = [patch for patch in lock["source_patches"]
               if PurePosixPath(patch["path"]).name.startswith("libsignal-")]
    require(len(patches) == 1, "Expected exactly one pinned libsignal patch")
    patch = patches[0]
    origin = engineering["origin"]
    require(re.fullmatch(r"https://github\.com/[^/]+/[^/]+\.git", origin),
            "Engineering origin must be an HTTPS GitHub repository")
    patch_path = PurePosixPath(engineering["native_recipe_path"]) / patch["path"]
    require(not patch_path.is_absolute() and ".." not in patch_path.parts,
            "Native patch path must stay inside the engineering repository")
    clients = [module for module in lock["modules"] if module["name"] == "libsignal-client"]
    require(len(clients) == 1, "Expected one libsignal-client input")
    inputs = {
        "source_revision": lock["source_provenance"]["libsignal"],
        "prepared_tree": host["prepared_libsignal_tree"],
        "patch_revision": engineering["recipe_revision"],
        "patch_sha256": patch["sha256"],
        "rust_version": lock["toolchain_reference"]["rust_version"],
        "client_version": clients[0]["input_version"],
        "resource": RESOURCE,
    }
    for key in ("source_revision", "prepared_tree", "patch_revision"):
        require(re.fullmatch(r"[0-9a-f]{40}", inputs[key]), f"Invalid pinned {key}")
    require(re.fullmatch(r"[0-9a-f]{64}", inputs["patch_sha256"]), "Invalid patch SHA-256")
    require(re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", inputs["rust_version"]), "Invalid Rust version")
    repository = origin.removeprefix("https://github.com/").removesuffix(".git")
    inputs["patch_url"] = f"https://raw.githubusercontent.com/{repository}/{inputs['patch_revision']}/{patch_path}"
    return inputs


def fetch_patch(path, inputs):
    if path.exists():
        content = path.read_bytes()
    else:
        with urllib.request.urlopen(inputs["patch_url"], timeout=60) as response:
            content = response.read(2 * 1024 * 1024)
    require(hashlib.sha256(content).hexdigest() == inputs["patch_sha256"],
            "Libsignal patch SHA-256 differs from native-lock.json")
    path.write_bytes(content)


def verify_source(source, inputs):
    require(run(["git", "rev-parse", "HEAD"], cwd=source, capture=True) == inputs["source_revision"],
            "Cached libsignal source revision differs; remove its generated source directory and retry")
    run(["git", "diff", "--exit-code", "--"], cwd=source)
    require(run(["git", "write-tree"], cwd=source, capture=True) == inputs["prepared_tree"],
            "Patched libsignal source tree differs from host-test-inputs.json")
    require(not run(["git", "ls-files", "--others", "--exclude-standard"], cwd=source, capture=True),
            "Unexpected untracked files in libsignal source")
    require((source / "rust-toolchain").read_text().strip() == inputs["rust_version"],
            "Source Rust version differs from native-lock.json")
    cargo = tomllib.loads((source / "Cargo.toml").read_text())
    require(cargo["workspace"]["package"]["version"] == inputs["client_version"],
            "Source version differs from the application's libsignal-client input")


def prepare_source(work, inputs, origin=LIBSIGNAL_ORIGIN):
    work.mkdir(parents=True, exist_ok=True)
    patch = work / "libsignal.mbox"
    fetch_patch(patch, inputs)
    source = work / "libsignal"
    if not source.exists():
        run(["git", "init", str(source)])
        run(["git", "remote", "add", "origin", origin], cwd=source)
        run(["git", "fetch", "--depth=1", "origin", inputs["source_revision"]], cwd=source)
        run(["git", "checkout", "--detach", "FETCH_HEAD"], cwd=source)
        # Apply the attributed mailbox without creating commits or inventing an author.
        run(["git", "apply", "--index", str(patch.resolve())], cwd=source)
    verify_source(source, inputs)
    return source


def validate_library(path):
    require(path.is_file() and not path.is_symlink(), "Host JNI output is missing or is a symlink")
    with path.open("rb") as stream:
        header = stream.read(20)
    require(len(header) == 20 and header[:6] == b"\x7fELF\x02\x01"
            and int.from_bytes(header[16:18], "little") == 3
            and int.from_bytes(header[18:20], "little") == 62,
            "Host JNI must be an x86-64 Linux ELF shared library")
    dependencies = run(["ldd", str(path)], capture=True)
    require("not found" not in dependencies, "Host JNI has unresolved shared-library dependencies")
    symbols = {line.split()[-1] for line in run(["nm", "-D", "--defined-only", str(path)], capture=True).splitlines() if line.split()}
    require("Java_org_signal_libsignal_internal_Native_initializeLibrary" in symbols,
            "Host JNI lacks production JNI exports; build with upstream desktop thin LTO")
    require(any(symbol.startswith("Java_org_signal_libsignal_internal_NativeTesting_") for symbol in symbols),
            "Host JNI lacks the testing JNI exports")


def stage_library(library, work, inputs):
    validate_library(library)
    resources = work / "resources"
    require(not resources.is_symlink(), "Host resource directory must not be a symlink")
    resources.mkdir(parents=True, exist_ok=True)
    require(all(path.name == RESOURCE for path in resources.iterdir()), "Unexpected host JNI resources")
    temporary = resources / (RESOURCE + ".tmp")
    try:
        shutil.copyfile(library, temporary)
        require(digest(temporary) == digest(library), "Host JNI changed while staging")
        run(["strip", "--strip-debug", str(temporary)])
        validate_library(temporary)
        temporary.replace(resources / RESOURCE)
    finally:
        temporary.unlink(missing_ok=True)
    receipt = {**inputs, "platform": "Linux x86-64", "test_only": True, "strip_debug": True,
               "sha256": digest(resources / RESOURCE)}
    (work / "receipt.json").write_text(json.dumps(receipt, indent=2, sort_keys=True) + "\n")
    return resources


def verify_staged(work, inputs):
    resources = work / "resources"
    require(resources.is_dir() and not resources.is_symlink(), "Cached host resources are missing")
    require({path.name for path in resources.iterdir()} == {RESOURCE}, "Unexpected cached host JNI resources")
    library = resources / RESOURCE
    validate_library(library)
    receipt = json.loads((work / "receipt.json").read_text())
    expected = {**inputs, "platform": "Linux x86-64", "test_only": True, "strip_debug": True,
                "sha256": digest(library)}
    require(receipt == expected, "Cached host JNI receipt or checksum differs from the current inputs")
    return resources


def build(root, work, prepare_only=False, verify_only=False):
    require(platform.system() == "Linux" and platform.machine() in ("x86_64", "amd64"),
            "This host-test recipe requires Linux x86-64")
    inputs = load_inputs(root)
    if verify_only:
        return verify_staged(work, inputs)
    source = prepare_source(work, inputs)
    if prepare_only:
        return source
    environment = os.environ.copy()
    environment.update(RUSTUP_TOOLCHAIN=inputs["rust_version"], CARGO_BUILD_TARGET=TARGET,
                       CARGO_TARGET_DIR=str(source / "target"))
    environment.setdefault("CARGO_BUILD_JOBS", "2")
    version = run(["rustc", "--version"], env=environment, capture=True)
    require(version.split()[1] == inputs["rust_version"], "Active Rust compiler differs from the pinned version")
    # Fetch exactly Cargo.lock first. The upstream recipe then builds offline and
    # retains its Linux LTO, JNI features, and resource names.
    run(["cargo", "fetch", "--locked"], cwd=source, env=environment)
    environment["CARGO_NET_OFFLINE"] = "true"
    run(["bash", "java/build_jni.sh", "desktop"], cwd=source, env=environment)
    verify_source(source, inputs)
    library = source / "java/client/src/main/resources" / RESOURCE
    return stage_library(library, work, inputs)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--work-dir", type=Path, help="Generated source, Cargo cache, and output directory")
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--prepare-only", action="store_true", help="Verify pinned source and patches without compiling")
    mode.add_argument("--verify-only", action="store_true", help="Verify cached resources and receipt without Rust or network access")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    work = (args.work_dir or root / "build/host-tests").resolve()
    try:
        output = build(root, work, args.prepare_only, args.verify_only)
        print(f"Verified host-test {'source' if args.prepare_only else 'resources'}: {output}")
    except (Failure, OSError, KeyError, ValueError, subprocess.CalledProcessError) as error:
        print(f"Host JNI build failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
