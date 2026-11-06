#!/usr/bin/env python3
"""Build and check the libsignal portion of a prepared native source set.

RingRTC/WebRTC dependency closure is not yet verified or built here. This tool can
only emit a partial diagnostic receipt and cannot authorize a release.
"""
from __future__ import annotations
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import sys

import native_driver
import prepare_sources
import stage_artifacts
import verify_gradle

class Failure(Exception):
    pass

def require(condition, message):
    if not condition:
        raise Failure(message)

def run(args, cwd, env):
    print('+ ' + ' '.join(args), flush=True)
    process = subprocess.Popen(args, cwd=cwd, env=env, stdout=subprocess.PIPE,
                               stderr=subprocess.STDOUT, text=True, errors='replace')
    output = []
    assert process.stdout is not None
    for line in process.stdout:
        print(line, end='', flush=True)
        stripped = line.strip()
        if 'test result:' in stripped or stripped.startswith(('rustc ', 'openjdk version ', 'java version ')) or stripped.endswith('-android') or stripped.endswith('-androideabi'):
            output.append(stripped)
    require(process.wait() == 0, 'Native diagnostic command failed')
    return output

def properties(path):
    require(path.is_file() and not path.is_symlink(), 'Required toolchain properties missing')
    return {key.strip(): value.strip() for key, value in (line.split('=', 1) for line in path.read_text().splitlines() if '=' in line and not line.lstrip().startswith('#'))}

def preflight(lock_path, source_root, sdk_root, java_home, rust_bin, runner=run, java_major=21):
    native_driver.audit_gradle_environment()
    lock = stage_artifacts.load_lock(lock_path.resolve(strict=True))
    proof = prepare_sources.verify_prepared(lock_path, source_root)
    toolchain = lock['toolchain_reference']
    require(toolchain.get('rust_version') == '1.98.1', 'Unexpected Rust version reference')
    require(toolchain.get('ringrtc_aar_android_ndk_revision') == '28.0.13004108', 'Unexpected Android NDK reference')
    sdk_root = sdk_root.resolve(strict=True)
    java_home = java_home.resolve(strict=True)
    ndk = properties(sdk_root / 'ndk' / '28.0.13004108' / 'source.properties')
    require(ndk.get('Pkg.Revision', '').strip() == '28.0.13004108', 'Android NDK version does not match the recorded build')
    rust_bin = rust_bin.resolve(strict=True)
    cargo, rustc, rustup = (rust_bin / name for name in ('cargo', 'rustc', 'rustup'))
    java = java_home / 'bin/java'
    require(all(path.is_file() and os.access(path, os.X_OK) for path in (cargo, rustc, java)), 'Required Rust or Java toolchain missing')
    env = os.environ.copy()
    temp = source_root.parent / '.native-tmp'
    temp.mkdir(exist_ok=True)
    require(temp.is_dir() and not temp.is_symlink(), 'Native temporary directory must be a real directory')
    env['TMPDIR'] = str(temp.resolve())
    env['RUSTUP_TOOLCHAIN'] = '1.98.1'
    # Keep existing SDK/Rust cache selections but ensure Gradle receives explicit tool locations.
    env.update(PATH=str(rust_bin) + os.pathsep + env.get('PATH', ''),
               ANDROID_HOME=str(sdk_root), ANDROID_SDK_ROOT=str(sdk_root), JAVA_HOME=str(java_home),
               ANDROID_NDK_HOME=str(sdk_root / 'ndk/28.0.13004108'),
               ANDROID_NDK_ROOT=str(sdk_root / 'ndk/28.0.13004108'))
    libsignal = source_root / 'libsignal'
    java_versions = runner([str(java), '-version'], libsignal, env)
    require(any(re.match(r'^(?:openjdk|java) version "' + str(java_major) + r'(?:[."+]|$)', line) for line in java_versions), f'Java {java_major} is required for this diagnostic build')
    versions = runner([str(rustc), '--version'], libsignal, env)
    require(any(re.match(r'^rustc 1[.]98[.]1(?:\s|$)', line) for line in versions), 'Rust toolchain version differs')
    required_targets = {'aarch64-linux-android', 'armv7-linux-androideabi', 'i686-linux-android', 'x86_64-linux-android'}
    if rustup.is_file() and os.access(rustup, os.X_OK):
        targets = set(runner([str(rustup), 'target', 'list', '--installed'], libsignal, env))
        require(required_targets.issubset(targets), 'One or more Android Rust targets are missing')
    else:
        # A direct installed toolchain has no rustup shim; validate its standard-library
        # files locally. This is a preflight check, not toolchain authentication.
        rustlib = rust_bin.parent / 'lib/rustlib'
        require(all(any(p.is_file() and p.stat().st_size > 0 for p in (rustlib / target / 'lib').glob('libstd-*.rlib')) for target in required_targets),
                'One or more Android Rust target libraries are missing')
    return lock, proof, env, str(cargo)

def diagnostic_build(lock_path, source_root, sdk_root, java_home, rust_bin, report, gradle_zip, gradle_distribution, runner=run):
    require(not report.exists(), 'Native diagnostic report already exists')
    gradle = verify_gradle.verify(gradle_zip, gradle_distribution, '87a2216cc1f9122192d4e0fe905ffdf1b4c72cff797e9f733b174e157cadd396', top='gradle-9.6.0')
    lock, proof, env, cargo = preflight(lock_path, source_root, sdk_root, java_home, rust_bin, runner)
    libsignal = source_root / 'libsignal'
    result = runner([cargo, 'test', '--locked', '-p', 'libsignal-net', 'configured_proxy_never_falls_back_to_direct'], libsignal, env)
    require(any(re.search(r'test result: ok[.]\s+4 passed;', line) for line in result), 'Expected four proxy-mode tests did not pass')
    runner([str(gradle), '--project-dir', str(libsignal / 'java'), '--no-daemon', ':android:assembleRelease', '-x', ':makeJniLibrariesDesktop'], libsignal / 'java', env)
    require(verify_gradle.verify(gradle_zip, gradle_distribution, '87a2216cc1f9122192d4e0fe905ffdf1b4c72cff797e9f733b174e157cadd396', top='gradle-9.6.0') == gradle, 'Gradle distribution changed during diagnostic build')
    # Source contents must remain the pinned prepared trees after the build.
    after = prepare_sources.verify_prepared(lock_path, source_root)
    require(after['sources'] == proof['sources'], 'Native source changed during diagnostic build')
    aar = sorted((libsignal / 'java/android/build/outputs/aar').glob('*.aar'))
    jars = sorted(p for p in (libsignal / 'java/client/build/libs').glob('*.jar') if not p.name.endswith(('-sources.jar', '-javadoc.jar')))
    require(len(aar) == 1 and len(jars) == 1, 'Cannot identify exactly one built libsignal AAR and client JAR')
    modules = {m['name']: m for m in lock['modules']}
    hashes = {'libsignal-android': stage_artifacts.digest(aar[0]), 'libsignal-client': stage_artifacts.digest(jars[0])}
    require(all(hashes[name] == modules[name]['binary_sha256'] for name in hashes),
            'Local libsignal artifacts differ from locked candidate bytes; reproducibility is unproved')
    receipt = {'schema': 1, 'status': 'PARTIAL_LIBSIGNAL_ONLY', 'release_allowed': False,
               'scope': 'libsignal diagnostic build only; RingRTC, WebRTC closure and runtime unverified',
               'lock_sha256': stage_artifacts.digest(lock_path), 'sources': proof['sources'], 'artifacts': hashes}
    report.parent.mkdir(parents=True, exist_ok=True)
    report.write_text(json.dumps(receipt, indent=2, sort_keys=True) + '\n')
    return receipt

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--lock', required=True, type=Path)
    parser.add_argument('--sources', required=True, type=Path)
    parser.add_argument('--sdk', required=True, type=Path)
    parser.add_argument('--java-home', required=True, type=Path)
    parser.add_argument('--rust-bin', required=True, type=Path)
    parser.add_argument('--report', required=True, type=Path)
    parser.add_argument('--gradle-zip', required=True, type=Path)
    parser.add_argument('--gradle-distribution', required=True, type=Path)
    args = parser.parse_args()
    try:
        diagnostic_build(args.lock.resolve(), args.sources.resolve(), args.sdk, args.java_home, args.rust_bin, args.report, args.gradle_zip, args.gradle_distribution)
    except (Failure, prepare_sources.Failure, stage_artifacts.Failure, verify_gradle.Failure, OSError, ValueError) as exc:
        print(f'native diagnostic build failed: {exc}', file=sys.stderr)
        return 1
    return 0

if __name__ == '__main__':
    sys.exit(main())
