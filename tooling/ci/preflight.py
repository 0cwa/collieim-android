#!/usr/bin/env python3
"""Run fast local checks before pushing a feature branch."""

import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys


def run_check(label, command):
    result = subprocess.run(command, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    if result.stdout:
        print(result.stdout, end="" if result.stdout.endswith("\n") else "\n")
    passed = result.returncode == 0
    print(f"{'PASS' if passed else 'FAIL'}: {label}")
    return passed


PAIRING_CONTRACT_ROOT = Path(__file__).resolve().parents[2] / "app/src/test/resources/colliesocket-pairing-v1"
PAIRING_MANIFEST_SHA256 = "353fd3876887eef4e88421a1bfd71d634b3a37ddb212623e1674b11edc060089"
PAIRING_ARTIFACTS = {
    "README.md",
    "offer.schema.json",
    "request.schema.json",
    "response.schema.json",
    "fixtures/sealed-vectors.json",
}
V4_CONTRACT_ROOT = Path(__file__).resolve().parents[2] / "app/src/test/resources/colliesocket-encrypted-push-v4"
V4_MANIFEST_SHA256 = "5d493f3db2fcb0f54e07ab2ca88a57977883ed0ce532df1fafb6334c2223bd78"


def _unique_json_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"duplicate manifest key: {key}")
        result[key] = value
    return result


def check_pairing_manifest():
    root = PAIRING_CONTRACT_ROOT.resolve()
    manifest_path = root / "copy-manifest.json"
    try:
        if manifest_path.is_symlink() or not manifest_path.is_file():
            raise ValueError("copy-manifest.json must be a regular file")
        raw = manifest_path.read_bytes()
        if hashlib.sha256(raw).hexdigest() != PAIRING_MANIFEST_SHA256:
            raise ValueError("copy-manifest.json does not match the pinned manifest")
        manifest = json.loads(raw, object_pairs_hook=_unique_json_object)
        if not isinstance(manifest, dict) or (
            manifest.get("manifest_version") != 1
            or manifest.get("contract_version") != "pairing/v1"
            or manifest.get("source_repository") != "0cwa/colliesocket"
            or manifest.get("source_revision") != "9e69698761aaa3a17d668790c9493fe7ce49a212"
        ):
            raise ValueError("unexpected pairing contract pin")
        entries = manifest.get("files")
        if not isinstance(entries, list):
            raise ValueError("manifest files must be a list")
        seen = set()
        for entry in entries:
            if not isinstance(entry, dict) or set(entry) != {"path", "sha256"}:
                raise ValueError("malformed manifest file entry")
            path, digest = entry["path"], entry["sha256"]
            if not isinstance(path, str) or "\\" in path:
                raise ValueError(f"unsafe manifest path: {path!r}")
            parts = Path(path).parts
            if Path(path).is_absolute() or ".." in parts or path in seen:
                raise ValueError(f"unsafe or duplicate manifest path: {path}")
            if path not in PAIRING_ARTIFACTS:
                raise ValueError(f"unexpected manifest path: {path}")
            if not isinstance(digest, str) or len(digest) != 64 or any(c not in "0123456789abcdef" for c in digest):
                raise ValueError(f"invalid SHA-256 for {path}")
            candidate = root.joinpath(*parts)
            current = root
            for part in parts:
                current = current / part
                if current.is_symlink():
                    raise ValueError(f"manifest path traverses a symlink: {path}")
            resolved = candidate.resolve(strict=True)
            if not resolved.is_relative_to(root) or not resolved.is_file():
                raise ValueError(f"unsafe manifest path: {path}")
            if hashlib.sha256(resolved.read_bytes()).hexdigest() != digest:
                raise ValueError(f"hash mismatch: {path}")
            seen.add(path)
        if seen != PAIRING_ARTIFACTS:
            raise ValueError("manifest file list does not match the pinned artifact set")
    except (OSError, TypeError, ValueError, json.JSONDecodeError) as error:
        print(f"Pairing v1 manifest: {error}")
        print("FAIL: CollieSocket pairing v1 manifest")
        return False
    print("PASS: CollieSocket pairing v1 manifest")
    return True


def check_v4_contract_manifest():
    root = V4_CONTRACT_ROOT.resolve()
    manifest_path = root / "copy-manifest.json"
    try:
        if manifest_path.is_symlink() or not manifest_path.is_file():
            raise ValueError("copy-manifest.json must be a regular file")
        raw = manifest_path.read_bytes()
        if hashlib.sha256(raw).hexdigest() != V4_MANIFEST_SHA256:
            raise ValueError("copy-manifest.json does not match the pinned manifest")
        manifest = json.loads(raw, object_pairs_hook=_unique_json_object)
        if not isinstance(manifest, dict) or (
            manifest.get("manifest_version") != 1
            or manifest.get("contract_version") != "encrypted-push/v4"
            or manifest.get("source_repository") != "0cwa/colliesocket"
            or manifest.get("source_revision") != "f8db2525d9ea25661efd4903037cc3f2ecdbe918"
        ):
            raise ValueError("unexpected encrypted-push v4 contract pin")
        entries = manifest.get("files")
        if not isinstance(entries, list):
            raise ValueError("manifest files must be a list")
        seen = set()
        for entry in entries:
            if not isinstance(entry, dict) or set(entry) != {"path", "sha256"}:
                raise ValueError("malformed manifest file entry")
            path, digest = entry["path"], entry["sha256"]
            if not isinstance(path, str) or "\\" in path:
                raise ValueError(f"unsafe manifest path: {path!r}")
            parts = Path(path).parts
            if Path(path).is_absolute() or ".." in parts or path in seen:
                raise ValueError(f"unsafe or duplicate manifest path: {path}")
            if not isinstance(digest, str) or len(digest) != 64 or any(c not in "0123456789abcdef" for c in digest):
                raise ValueError(f"invalid SHA-256 for {path}")
            candidate = root.joinpath(*parts)
            current = root
            for part in parts:
                current = current / part
                if current.is_symlink():
                    raise ValueError(f"manifest path traverses a symlink: {path}")
            resolved = candidate.resolve(strict=True)
            if not resolved.is_relative_to(root) or not resolved.is_file():
                raise ValueError(f"unsafe manifest path: {path}")
            if hashlib.sha256(resolved.read_bytes()).hexdigest() != digest:
                raise ValueError(f"hash mismatch: {path}")
            seen.add(path)
        actual = {p.relative_to(root).as_posix() for p in root.rglob("*") if p.is_file() and p.name != "copy-manifest.json"}
        if seen != actual:
            raise ValueError("manifest file list does not match the copied contract tree")
    except (OSError, TypeError, ValueError, json.JSONDecodeError) as error:
        print(f"Encrypted push v4 manifest: {error}")
        print("FAIL: CollieSocket encrypted push v4 manifest")
        return False
    print("PASS: CollieSocket encrypted push v4 manifest")
    return True


CANONICAL_REMOTE = re.compile(
    r"(?:(?:https?|ssh|git)://(?:[^/@]+@)?github\.com/|git@github\.com:)"
    r"0cwa/collieim-android(?:\.git)?/?",
    re.IGNORECASE,
)


def default_base():
    remotes = subprocess.check_output(["git", "remote", "-v"], text=True)
    remote_refs = set(subprocess.check_output(
        ["git", "for-each-ref", "--format=%(refname:strip=2)", "refs/remotes/"],
        text=True,
    ).splitlines())
    for line in remotes.splitlines():
        remote, _, direction = line.rpartition(" ")
        name, _, url = remote.partition("\t")
        ref = f"{name}/main"
        if direction == "(fetch)" and ref in remote_refs and CANONICAL_REMOTE.fullmatch(url):
            return ref
    return "upstream/main" if "upstream/main" in remote_refs else "origin/main"


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", help="base commit or ref (default: fetched Collie main, upstream/main, or origin/main)")
    parser.add_argument("--allow-unsigned", action="store_true", help="do not fail for unsigned commits")
    args = parser.parse_args(argv)
    try:
        base = args.base if args.base is not None else default_base()
    except subprocess.CalledProcessError:
        print("Cannot detect the preflight base. Run preflight from the repository root.", file=sys.stderr)
        return 1

    checks = [
        check_pairing_manifest(),
        check_v4_contract_manifest(),
        run_check("Layer trailers", [sys.executable, "tooling/git-hooks/collie_check_layer_trailers.py",
                                      "range", base, "HEAD"]),
        run_check("Fastlane metadata", [sys.executable, "tooling/fastlane/check_metadata.py"]),
    ]

    log = subprocess.run(
        ["git", "log", "--no-merges", "--format=%H %G?", f"{base}..HEAD"],
        text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
    )
    signatures_ok = log.returncode == 0
    if not signatures_ok:
        print(f"Commit signatures: git log failed: {log.stderr.strip()}")
    else:
        unsigned = [line for line in log.stdout.splitlines()
                    if len(line.split()) == 2 and line.split()[1] in {"N", "B"}]
        if unsigned and not args.allow_unsigned:
            print("Unsigned or bad-signature commits:")
            for line in unsigned:
                print(f"  {line}")
            signatures_ok = False
        else:
            signatures_ok = True
    checks.append(signatures_ok)
    print(f"{'PASS' if signatures_ok else 'FAIL'}: commit signatures"
          f"{' (unsigned commits allowed)' if args.allow_unsigned else ''}")

    passed = all(checks)
    print(f"Preflight {'PASSED' if passed else 'FAILED'} ({sum(checks)}/{len(checks)} checks)")
    return 0 if passed else 1


if __name__ == "__main__":
    sys.exit(main())
